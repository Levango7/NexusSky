package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.telemetry.PendingAcks;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.Heartbeat;
import io.aerofleet.mavlink.transport.UdpMavlinkTransport;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

import java.util.function.BiConsumer;

/**
 * Binds the MAVLink UDP transport (default port 14550) and pumps every valid
 * frame into {@link TelemetryIngestService}.
 *
 * <p>Routing: every drone's sysid is mapped to the socket address its frames
 * arrive from, so COMMAND/MISSION traffic is directed per-drone even when
 * several drones (or several link-sim proxies, one per "network segment")
 * share this gateway. lastPeer remains only as a discovery bootstrap.
 *
 * <p>Security (P1-3):
 * <ul>
 *   <li><b>Device whitelist</b>: when {@code aerofleet.udp.device-whitelist-enabled=true}
 *       (prod default), only frames from sysids registered in {@link DeviceRegistry}
 *       are accepted. Dev mode ({@code false}) accepts all sysids.</li>
 *   <li><b>Rate limiting</b>: per-sysid sliding-window rate limit
 *       ({@code aerofleet.udp.max-frame-rate-per-sysid}, default 100 fps).
 *       Excess frames are dropped with a WARN log.</li>
 *   <li><b>Bind address</b>: {@code aerofleet.udp.bind-address} (default 0.0.0.0;
 *       prod should bind to an internal NIC).</li>
 * </ul>
 */
@Component
public class UdpGateway {

    private static final Logger log = LoggerFactory.getLogger(UdpGateway.class);

    /** MAVLink system id of the ground station (cloud) itself. */
    public static final int GCS_SYSID = 255;
    public static final int GCS_COMPID = 190;   // MAV_COMP_ID_MISSIONPLANNER

    private final UdpMavlinkTransport transport;
    private final TelemetryIngestService ingest;
    private final PendingAcks pendings;

    /** sysid routes with aging (D3): see {@link RouteTable}. */
    private final RouteTable droneRoutes = new RouteTable();

    /** 设备白名单是否启用（prod=true, dev=false）。 */
    private final boolean deviceWhitelistEnabled;

    /** 单 sysid 每秒最大帧数（默认 100）。 */
    private final int maxFrameRatePerSysid;

    /** 设备注册表，用于白名单校验；可选注入（dev 模式下可能为 null）。 */
    @Autowired(required = false)
    private DeviceRegistry deviceRegistry;

    /** 每 sysid 滑动窗口频率限制器：key=sysid, value=最近1秒内的时间戳队列。 */
    private final ConcurrentHashMap<Integer, Deque<Long>> rateLimitWindows = new ConcurrentHashMap<>();

    public UdpGateway(@Value("${aerofleet.udp-port:14550}") int udpPort,
                      @Value("${aerofleet.drone-host:127.0.0.1}") String droneHost,
                      @Value("${aerofleet.drone-port:14540}") int dronePort,
                      @Value("${aerofleet.drone-extra-ports:}") String extraPorts,
                      @Value("${aerofleet.udp.bind-address:0.0.0.0}") String bindAddress,
                      @Value("${aerofleet.udp.device-whitelist-enabled:false}") boolean deviceWhitelistEnabled,
                      @Value("${aerofleet.udp.max-frame-rate-per-sysid:100}") int maxFrameRatePerSysid,
                      TelemetryIngestService ingest,
                      PendingAcks pendings) throws IOException {
        this.ingest = ingest;
        this.pendings = pendings;
        this.deviceWhitelistEnabled = deviceWhitelistEnabled;
        this.maxFrameRatePerSysid = maxFrameRatePerSysid;
        this.transport = new UdpMavlinkTransport(bindAddress, udpPort);
        // 带源地址的监听器：路由表的学习与分发都靠它
        transport.addFrameListener((BiConsumer<MavlinkFrame, SocketAddress>) this::onFrame);

        // QGroundControl-style active handshake toward each configured drone port.
        java.net.InetSocketAddress droneAddr = new java.net.InetSocketAddress(droneHost, dronePort);
        transport.enablePeerDiscovery(droneAddr, () -> gcsHeartbeat(0));
        if (extraPorts != null && !extraPorts.isBlank()) {
            for (String p : extraPorts.split(",")) {
                try {
                    int port = Integer.parseInt(p.trim());
                    transport.enablePeerDiscovery(
                            new java.net.InetSocketAddress(droneHost, port),
                            () -> gcsHeartbeat(0));
                } catch (NumberFormatException e) {
                    log.warn("Ignoring invalid extra drone port: {}", p);
                }
            }
        }
        log.info("MAVLink UDP gateway listening on {}:{}, discovery -> {} (+{}), whitelist={}, maxRate={}/s",
                bindAddress, transport.getLocalPort(), droneAddr, extraPorts,
                deviceWhitelistEnabled, maxFrameRatePerSysid);
    }

    /** Frame + source address entry: whitelist check, rate limit, ingest, and route learning. */
    private void onFrame(MavlinkFrame frame, SocketAddress source) {
        int sysid = frame.getSystemId();

        // 白名单校验：prod 模式下只接受已注册 sysid（GCS_SYSID 始终放行）
        if (deviceWhitelistEnabled && sysid > 0 && sysid != GCS_SYSID) {
            if (deviceRegistry == null || deviceRegistry.get(sysid) == null) {
                log.warn("Rejected frame from unregistered sysid={} (device whitelist enabled)", sysid);
                return;
            }
        }

        // 频率限制：滑动窗口算法（GCS_SYSID 不受限）
        if (sysid > 0 && sysid != GCS_SYSID && !rateLimitAllow(sysid)) {
            log.warn("Rate limit exceeded for sysid={}, dropping frame (max {}/s)",
                    sysid, maxFrameRatePerSysid);
            return;
        }

        if (sysid > 0 && sysid != GCS_SYSID) {
            SocketAddress prev = droneRoutes.get(sysid);
            droneRoutes.learn(sysid, source);
            if (prev == null || !prev.equals(source)) {
                log.debug("Route sysid={} -> {}", sysid, source);
            }
        }
        ingest.handle(frame);
    }

    /**
     * 滑动窗口频率限制：检查 sysid 在最近 1 秒内的帧数是否超限。
     *
     * @return true=允许通过, false=超限需丢弃
     */
    private boolean rateLimitAllow(int sysid) {
        long now = System.currentTimeMillis();
        Deque<Long> window = rateLimitWindows.computeIfAbsent(sysid, k -> new ArrayDeque<>());
        synchronized (window) {
            // 移除超过 1 秒的旧时间戳
            while (!window.isEmpty() && now - window.peekFirst() > 1000) {
                window.pollFirst();
            }
            if (window.size() >= maxFrameRatePerSysid) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    /**
     * Route aging (D3): drop routes silent for 5 min - a replaced/rebound
     * drone otherwise leaves a stale address that silently eats commands.
     * Runs every 60 s.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void pruneStaleRoutes() {
        for (Integer sysid : droneRoutes.prune()) {
            log.info("pruning stale route sysid={} (silent >5min)", sysid);
        }
    }

    /**
     * 频率限制窗口清理：移除长时间（2 分钟）无活动的 sysid 窗口，防止内存泄漏。
     * 每 5 分钟运行一次。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void pruneStaleRateLimitWindows() {
        long cutoff = System.currentTimeMillis() - 120_000;
        int before = rateLimitWindows.size();
        rateLimitWindows.entrySet().removeIf(entry -> {
            Deque<Long> window = entry.getValue();
            synchronized (window) {
                return window.isEmpty() || window.peekLast() < cutoff;
            }
        });
        int removed = before - rateLimitWindows.size();
        if (removed > 0) {
            log.debug("Pruned {} stale rate-limit windows", removed);
        }
    }

    public int getLocalPort() {
        return transport.getLocalPort();
    }

    /** Send one frame to the drone with the given sysid (route learned per-drone). */
    public void send(int sysid, MavlinkFrame frame) throws IOException {
        SocketAddress addr = sysid > 0 ? droneRoutes.get(sysid) : null;
        if (addr != null) {
            transport.send(frame, addr);
        } else {
            transport.sendToLastPeer(frame); // route unknown yet: best effort
        }
    }

    /** Send one frame to the drone with the given sysid, or learn-and-wait fallback. */
    public void send(MavlinkFrame frame) throws IOException {
        // 兼容旧签名：无 sysid 时用 lastPeer（discovery bootstrap 场景）
        transport.sendToLastPeer(frame);
    }

    /** Direct send used by the heartbeat watchdog re-discovery path. */
    public void sendToLastPeer(MavlinkFrame frame) throws IOException {
        transport.sendToLastPeer(frame);
    }

    public SocketAddress routeOf(int sysid) {
        return droneRoutes.get(sysid);
    }

    public SocketAddress getLastPeer() {
        return transport.getLastPeer();
    }

    /** GCS heartbeat frame (MAV_TYPE_GCS=6, AUTOPILOT_INVALID=8). */
    MavlinkFrame gcsHeartbeat(int seq) {
        Heartbeat hb = new Heartbeat(0, 6, 8, 0, MavEnums.MAV_STATE_ACTIVE);
        return hb.toFrame(GCS_SYSID, GCS_COMPID, seq);
    }

    /** Sequence counter for the periodic GCS heartbeat below. */
    private final java.util.concurrent.atomic.AtomicInteger hbSeq =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * QGroundControl-style 1 Hz GCS heartbeat to every known drone route.
     * A real GCS always keeps chattering; without this the vehicle's datalink
     * failsafe (PX4 NAV_DLLC_ACT) correctly fires ~15s into a silent mission
     * flight and RTLs on its own. Discovery heartbeats stop once a drone
     * answers, so this loop is what keeps the link "alive" afterwards.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 1000)
    public void heartbeatLoop() {
        if (droneRoutes.isEmpty()) {
            return;
        }
        MavlinkFrame hb = gcsHeartbeat(hbSeq.getAndIncrement() & 0xFF);
        for (SocketAddress addr : droneRoutes.addresses()) {
            try {
                transport.send(hb, addr);
            } catch (IOException e) {
                // route went dark: the heartbeat watchdog will age it out
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        transport.close();
        log.info("MAVLink UDP gateway closed");
    }
}

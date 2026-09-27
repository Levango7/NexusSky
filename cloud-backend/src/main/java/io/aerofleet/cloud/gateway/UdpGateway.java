package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.rid.RidIngestService;
import io.aerofleet.cloud.telemetry.PendingAcks;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkMessageInfo;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.Heartbeat;
import io.aerofleet.mavlink.security.MavlinkSignatureConfig;
import io.aerofleet.mavlink.security.MavlinkSigner;
import io.aerofleet.mavlink.security.SigningKeyManager;
import io.aerofleet.mavlink.security.TimestampTracker;
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

    // ==================== MAVLink v2 签名组件（可选注入） ====================

    /** 签名器：提供 HMAC-SHA256 签名与验证功能。 */
    @Autowired(required = false)
    private MavlinkSigner mavlinkSigner;

    /** 密钥管理器：提供 sysid→密钥与 linkId 映射。 */
    @Autowired(required = false)
    private SigningKeyManager signingKeyManager;

    /** 时间戳跟踪器：重放攻击防护。 */
    @Autowired(required = false)
    private TimestampTracker timestampTracker;

    /** 签名配置：控制签名开关与策略。 */
    @Autowired(required = false)
    private MavlinkSignatureConfig signatureConfig;

    /** RID 接入服务：可选注入，用于处理 OPEN_DRONE_ID_* 消息。 */
    @Autowired(required = false)
    private RidIngestService ridIngestService;

    /** 签名统计计数器（60s 周期报告）。 */
    private final SigningStats signingStats = new SigningStats();

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
        transport.enablePeerDiscovery(droneAddr, () -> signedGcsHeartbeat(0));
        if (extraPorts != null && !extraPorts.isBlank()) {
            for (String p : extraPorts.split(",")) {
                try {
                    int port = Integer.parseInt(p.trim());
                    transport.enablePeerDiscovery(
                            new java.net.InetSocketAddress(droneHost, port),
                            () -> signedGcsHeartbeat(0));
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

        // 签名验证（签名启用时）
        if (isSigningEnabled() && !verifyFrame(frame)) {
            return; // 验证失败，丢弃帧
        }

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

        // RID 消息接入：当 ridIngestService 可用时，将帧传递给 RID 处理链
        if (ridIngestService != null) {
            ridIngestService.onFrame(frame);
        }
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
        MavlinkFrame frameToSend = frame;

        // 签名启用时，对帧附加签名
        if (isSigningEnabled()) {
            MavlinkFrame signed = signFrame(sysid, frame);
            if (signed != null) {
                frameToSend = signed;
            }
            // signed==null 表示降级为未签名帧（signFrame 已记录 WARN 日志）
        }

        if (addr != null) {
            transport.send(frameToSend, addr);
        } else {
            transport.sendToLastPeer(frameToSend); // route unknown yet: best effort
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

        // 签名启用时，对 GCS 心跳帧签名（使用 GCS_SYSID 密钥）
        if (isSigningEnabled()) {
            MavlinkFrame signed = signFrame(GCS_SYSID, hb);
            if (signed != null) {
                hb = signed;
            }
        }

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

    // ==================== MAVLink v2 签名集成 ====================

    /**
     * 判断签名是否启用。
     * 签名组件未注入或配置未启用时返回 false，所有路径与现有实现一致。
     */
    private boolean isSigningEnabled() {
        return signatureConfig != null && signatureConfig.isEnabled()
                && mavlinkSigner != null && signingKeyManager != null;
    }

    /**
     * 发送路径签名：对帧附加 HMAC-SHA256 签名。
     * <p>
     * 流程：从 keyManager 获取 linkId → 计算 timestamp → 构造临时签名帧获取 frameBytes →
     * signer.sign() 计算签名 → 构造最终签名帧。
     * <p>
     * 降级条件（返回 null，调用方发送未签名帧）：
     * <ul>
     *   <li>密钥查找失败（keyFor 返回 null）</li>
     *   <li>签名计算返回 null（signer 未启用）</li>
     *   <li>签名计算异常</li>
     * </ul>
     *
     * @param sysid 目标系统 ID
     * @param frame 原始未签名帧
     * @return 签名帧，或 null（降级为未签名帧）
     */
    private MavlinkFrame signFrame(int sysid, MavlinkFrame frame) {
        try {
            SigningKeyManager.KeyEntry keyEntry = signingKeyManager.keyFor(sysid);
            if (keyEntry == null) {
                log.warn("签名降级：sysid={} 密钥查找失败，发送未签名帧", sysid);
                return null;
            }

            int linkId = keyEntry.linkId();
            long timestamp = System.currentTimeMillis() / 10;

            // 获取 CRC_EXTRA（通过 msgId 查找消息定义）
            int crcExtra = MavlinkMessageInfo.crcExtraOf(frame.getMessageId());

            // 构造临时签名帧（signature=null）获取 frameBytes（帧头至 CRC，不含签名数据）
            MavlinkFrame tempFrame = MavlinkFrame.ofSigned(
                    frame.getSystemId(), frame.getComponentId(), frame.getSequence(),
                    frame.getMessageId(), crcExtra, frame.getPayload(),
                    linkId, timestamp, null);
            byte[] frameBytes = tempFrame.encodeV2();

            // 计算 HMAC-SHA256 签名
            byte[] signature = mavlinkSigner.sign(frameBytes, linkId, timestamp);
            if (signature == null) {
                log.warn("签名降级：sysid={} 签名计算返回 null，发送未签名帧", sysid);
                return null;
            }

            // 构造最终签名帧
            return MavlinkFrame.ofSigned(
                    frame.getSystemId(), frame.getComponentId(), frame.getSequence(),
                    frame.getMessageId(), crcExtra, frame.getPayload(),
                    linkId, timestamp, signature);
        } catch (Exception e) {
            log.warn("签名降级：sysid={} 签名计算异常：{}，发送未签名帧", sysid, e.getMessage());
            return null;
        }
    }

    /**
     * 接收路径签名验证：验证帧的签名与 timestamp。
     * <p>
     * 验证流程：
     * <ol>
     *   <li>检测 INC bit 0：未签名帧按 rejectUnsigned 配置处理</li>
     *   <li>签名帧：验证 signature + timestamp</li>
     *   <li>验证失败：丢弃帧 + WARN 日志</li>
     * </ol>
     *
     * @param frame 接收到的帧
     * @return true=通过验证（或未签名帧被允许），false=验证失败需丢弃
     */
    private boolean verifyFrame(MavlinkFrame frame) {
        boolean isSigned = frame.isSigned();

        if (!isSigned) {
            // 未签名帧：根据 rejectUnsigned 配置处理
            if (signatureConfig.isRejectUnsigned()) {
                signingStats.rejected++;
                log.warn("拒绝未签名帧：sysid={} msgId={} (rejectUnsigned=true)",
                        frame.getSystemId(), frame.getMessageId());
                return false;
            } else {
                signingStats.unsigned++;
                return true; // 允许未签名帧通过
            }
        }

        // 签名帧：验证签名 + timestamp
        try {
            // 获取 frameBytes（帧头至 CRC 的全部字节，不含签名数据）
            byte[] fullBytes = frame.encodeV2();
            int frameBytesLen = 12 + frame.getPayloadLength();
            byte[] frameBytes = java.util.Arrays.copyOf(fullBytes, frameBytesLen);

            int linkId = frame.getLinkId();
            long timestamp = frame.getTimestamp();
            byte[] signature = frame.getSignature();

            // 验证签名
            if (!mavlinkSigner.verify(frameBytes, linkId, timestamp, signature)) {
                signingStats.rejected++;
                log.warn("签名验证失败：sysid={} linkId={} msgId={}",
                        frame.getSystemId(), linkId, frame.getMessageId());
                return false;
            }

            // 验证 timestamp（重放攻击防护）
            if (timestampTracker != null && !timestampTracker.check(linkId, timestamp)) {
                signingStats.rejected++;
                log.warn("Timestamp 校验失败（疑似重放）：sysid={} linkId={} timestamp={}",
                        frame.getSystemId(), linkId, timestamp);
                return false;
            }

            signingStats.verified++;
            return true;
        } catch (Exception e) {
            signingStats.rejected++;
            log.warn("签名验证异常：sysid={} {}", frame.getSystemId(), e.getMessage());
            return false;
        }
    }

    /**
     * 构造签名后的 GCS 心跳帧（用于 discovery 和 heartbeatLoop）。
     * 签名未启用或降级时返回未签名心跳帧。
     */
    private MavlinkFrame signedGcsHeartbeat(int seq) {
        MavlinkFrame hb = gcsHeartbeat(seq);
        if (isSigningEnabled()) {
            MavlinkFrame signed = signFrame(GCS_SYSID, hb);
            if (signed != null) {
                return signed;
            }
        }
        return hb;
    }

    /**
     * 签名统计：每 60s 输出 verified/rejected/unsigned 计数。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void reportSigningStats() {
        if (!isSigningEnabled()) {
            return;
        }
        log.info("signing: verified={} rejected={} unsigned={}",
                signingStats.verified, signingStats.rejected, signingStats.unsigned);
        signingStats.reset();
    }

    /** 签名统计计数器（周期性重置）。 */
    private static class SigningStats {
        volatile long verified;
        volatile long rejected;
        volatile long unsigned;

        void reset() {
            verified = 0;
            rejected = 0;
            unsigned = 0;
        }
    }
}

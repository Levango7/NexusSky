package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.telemetry.PendingAcks;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.Heartbeat;
import io.aerofleet.mavlink.transport.UdpMavlinkTransport;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketAddress;

import java.util.function.BiConsumer;

/**
 * Binds the MAVLink UDP transport (default port 14550) and pumps every valid
 * frame into {@link TelemetryIngestService}.
 *
 * <p>Routing: every drone's sysid is mapped to the socket address its frames
 * arrive from, so COMMAND/MISSION traffic is directed per-drone even when
 * several drones (or several link-sim proxies, one per "network segment")
 * share this gateway. lastPeer remains only as a discovery bootstrap.
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

    public UdpGateway(@Value("${aerofleet.udp-port:14550}") int udpPort,
                      @Value("${aerofleet.drone-host:127.0.0.1}") String droneHost,
                      @Value("${aerofleet.drone-port:14540}") int dronePort,
                      @Value("${aerofleet.drone-extra-ports:}") String extraPorts,
                      TelemetryIngestService ingest,
                      PendingAcks pendings) throws IOException {
        this.ingest = ingest;
        this.pendings = pendings;
        this.transport = new UdpMavlinkTransport(udpPort);
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
        log.info("MAVLink UDP gateway listening on port {}, discovery -> {} (+{})",
                transport.getLocalPort(), droneAddr, extraPorts);
    }

    /** Frame + source address entry: ingest, and remember the sysid route. */
    private void onFrame(MavlinkFrame frame, SocketAddress source) {
        if (frame.getSystemId() > 0 && frame.getSystemId() != GCS_SYSID) {
            SocketAddress prev = droneRoutes.get(frame.getSystemId());
            droneRoutes.learn(frame.getSystemId(), source);
            if (prev == null || !prev.equals(source)) {
                log.debug("Route sysid={} -> {}", frame.getSystemId(), source);
            }
        }
        ingest.handle(frame);
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

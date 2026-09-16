package io.aerofleet.sim.satrelay;

import io.aerofleet.mavlink.messages.HierarchicalRouteDecisionMsg;
import io.aerofleet.mavlink.messages.SatLinkStatusMsg;
import io.aerofleet.mavlink.messages.SatPassScheduleMsg;
import io.aerofleet.mavlink.transport.UdpMavlinkTransport;
import io.aerofleet.sim.SimLog;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 星-空-地多层级中继引擎（M7，运行时封装）。
 * <p>
 * 持有 LEO 星座、HAPS 节点列表、层级路由器，由 {@code VirtualDrone.tickOnce} 周期驱动。
 * 产出 SatLinkStatus(459) / SatPassSchedule(460) / HierarchicalRouteDecision(461) 消息并 UDP 上报。
 * <p>
 * 生命周期：{@link #start()} → {@link #tick(long)} 周期驱动 → {@link #close()} 主动退出。
 */
public final class SatRelayEngine implements AutoCloseable {

    private final int selfSysid;
    private final SatRelayConfig config;
    private final UdpMavlinkTransport transport;
    private final InetSocketAddress cloudBackendAddress;
    private final LeoConstellation constellation;
    private final List<HapsRelayNode> hapsNodes;
    private final HierarchicalRouter router;

    // 周期驱动时间戳
    private volatile long lastLinkReportMs = 0;
    private volatile long lastPassScheduleMs = 0;
    private volatile long lastRouteDecisionMs = 0;

    // 地面点位置（供可见性计算，由 VirtualDrone 更新）
    private volatile double groundLatDeg = 0;
    private volatile double groundLonDeg = 0;

    private volatile boolean closed = false;

    /** 默认 cloud-backend 上报地址。 */
    private static final InetSocketAddress DEFAULT_CLOUD_ADDR =
            new InetSocketAddress("127.0.0.1", 14550);

    /**
     * 构造器。
     *
     * @param selfSysid          本节点 sysid
     * @param config             中继配置
     * @param transport          UDP 传输（可为 null，测试用）
     * @param cloudBackendAddress cloud-backend 上报地址（可为 null）
     */
    public SatRelayEngine(int selfSysid, SatRelayConfig config,
                          UdpMavlinkTransport transport,
                          InetSocketAddress cloudBackendAddress) {
        this.selfSysid = selfSysid;
        this.config = config;
        this.transport = transport;
        this.cloudBackendAddress = cloudBackendAddress != null ? cloudBackendAddress : DEFAULT_CLOUD_ADDR;
        // 创建 LEO 星座（Walker 壳均匀分布）
        this.constellation = LeoConstellation.walkerShell(
                config.constellationSize, config.orbitAltitudeKm, config.inclinationDeg);
        // 创建 HAPS 节点（默认 1 个，覆盖本地区域）
        this.hapsNodes = new ArrayList<>();
        this.hapsNodes.add(HapsRelayNode.atDefault(200, 22.5907, 113.9345));
        // 创建层级路由器
        this.router = new HierarchicalRouter(config, hapsNodes, constellation);
    }

    /** 启动。 */
    public void start() {
        long now = System.currentTimeMillis();
        lastLinkReportMs = now;
        lastPassScheduleMs = now;
        lastRouteDecisionMs = now;
        SimLog.info("[sat-relay] engine started: sysid=" + selfSysid
                + " constellation=" + constellation.size()
                + " config=" + config);
    }

    /**
     * 更新地面点位置（由 VirtualDrone tick 调用，供可见性计算）。
     *
     * @param latDeg 纬度
     * @param lonDeg 经度
     */
    public void updateGroundPosition(double latDeg, double lonDeg) {
        this.groundLatDeg = latDeg;
        this.groundLonDeg = lonDeg;
    }

    /**
     * 周期驱动（由 VirtualDrone.tickOnce 调用）。
     * <ol>
     *   <li>推进星座轨道位置</li>
     *   <li>检测过境切换，产出 SatLinkStatus</li>
     *   <li>按周期产出 SatPassSchedule</li>
     *   <li>按周期执行路由决策，产出 HierarchicalRouteDecision</li>
     * </ol>
     *
     * @param nowMs 当前仿真时钟（ms）
     */
    public void tick(long nowMs) {
        if (closed) return;

        // 1) 推进星座轨道位置
        constellation.tick(nowMs);

        // 2) 检测过境切换 + 链路状态上报
        if (nowMs - lastLinkReportMs >= config.satLinkReportIntervalMs) {
            reportSatLinkStatus(nowMs);
            lastLinkReportMs = nowMs;
        }

        // 3) 过境计划预告（较低频率，默认 10s）
        if (nowMs - lastPassScheduleMs >= 10_000L) {
            reportPassSchedules(nowMs);
            lastPassScheduleMs = nowMs;
        }

        // 4) 路由决策（默认 2s）
        if (nowMs - lastRouteDecisionMs >= config.satLinkReportIntervalMs) {
            reportRouteDecision(nowMs);
            lastRouteDecisionMs = nowMs;
        }
    }

    /** 产出所有可见卫星的 SatLinkStatus 消息。 */
    private void reportSatLinkStatus(long nowMs) {
        List<SatelliteNode> visible = constellation.findVisible(
                groundLatDeg, groundLonDeg, config.elevationThresholdDeg);
        for (SatelliteNode sat : visible) {
            int delayMs = HierarchicalRouter.computeDelayMs(sat.orbitAltitudeKm());
            int bandwidthMbps = HierarchicalRouter.computeBandwidthMbps(1);
            long windowEnd = nowMs + LinkWindowCalculator.windowRemainingMs(
                    sat, groundLatDeg, groundLonDeg, config.elevationThresholdDeg,
                    nowMs, 1_200_000L, config.linkWindowScanStepMs);
            SatLinkStatusMsg msg = SatLinkStatusMsg.simulated(
                    sat.satId(), 1, (int) sat.elevationDeg(), (int) sat.azimuthDeg(),
                    delayMs, bandwidthMbps, windowEnd, 1, nowMs);
            sendFrame(msg);
        }
    }

    /** 产出过境计划预告消息。 */
    private void reportPassSchedules(long nowMs) {
        for (SatelliteNode sat : constellation.satellites()) {
            List<LinkWindowCalculator.VisibilityWindow> windows =
                    LinkWindowCalculator.computeWindows(sat, selfSysid,
                            groundLatDeg, groundLonDeg, config.elevationThresholdDeg,
                            nowMs, nowMs + config.passScheduleHorizonMs,
                            config.linkWindowScanStepMs);
            for (LinkWindowCalculator.VisibilityWindow w : windows) {
                SatPassScheduleMsg msg = new SatPassScheduleMsg(
                        w.satId(), w.startMs(), w.endMs(),
                        (int) w.maxElevationDeg(), w.groundPointId(), nowMs);
                sendFrame(msg);
            }
        }
    }

    /** 执行路由决策并产出 HierarchicalRouteDecision 消息。 */
    private void reportRouteDecision(long nowMs) {
        // 简化：以本节点为源，目标节点固定为 GCS(255)
        HierarchicalRouter.RouteDecision decision = router.decide(
                selfSysid, 255, groundLatDeg, groundLonDeg,
                22.5907, 113.9345, false, nowMs);
        int chosenLayer = decision.isNoPath()
                ? HierarchicalRouteDecisionMsg.NO_PATH_LAYER
                : decision.chosenLayer().layerNumber();
        int delayMs = decision.isNoPath()
                ? HierarchicalRouteDecisionMsg.NO_PATH_DELAY
                : decision.estimatedDelayMs();
        HierarchicalRouteDecisionMsg msg = new HierarchicalRouteDecisionMsg(
                decision.sourceLayer().layerNumber(),
                decision.targetLayer().layerNumber(),
                chosenLayer, delayMs, decision.pathNodes(),
                decision.strategy().code(), decision.decisionReason(), nowMs);
        sendFrame(msg);
    }

    /** 发送 MAVLink 消息帧到 cloud-backend。 */
    private void sendFrame(io.aerofleet.mavlink.messages.MavlinkMessage msg) {
        if (transport == null) return;
        try {
            byte[] payload = msg.encode();
            io.aerofleet.mavlink.MavlinkFrame frame = io.aerofleet.mavlink.MavlinkFrame.of(
                    selfSysid, 1, 0, msg.messageId(),
                    io.aerofleet.mavlink.MavlinkMessageInfo.crcExtraOf(msg.messageId()), payload);
            transport.send(frame, cloudBackendAddress);
        } catch (Exception e) {
            SimLog.warn("[sat-relay] send failed: " + e.getMessage());
        }
    }

    /** 获取层级路由器（供外部查询/策略切换）。 */
    public HierarchicalRouter router() {
        return router;
    }

    /** 获取星座。 */
    public LeoConstellation constellation() {
        return constellation;
    }

    /** 获取 HAPS 节点列表。 */
    public List<HapsRelayNode> hapsNodes() {
        return hapsNodes;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        SimLog.info("[sat-relay] engine closed: sysid=" + selfSysid);
    }
}
package io.aerofleet.sim;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkParser;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.enums.ThreatLevel;
import io.aerofleet.mavlink.messages.Heartbeat;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.messages.ObstacleReportMsg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3 避障接线守卫：障碍物场景 → 超声波读数 → ObstacleReportMsg(30010) 的真链路。
 *
 * <p><b>这个缺陷是怎么漏掉的</b>：{@code UltrasonicSource.measure(double[])} 在整个
 * {@code src/main} 里<b>零调用方</b>，{@code lastReadings} 因此永远是 {@code null}，
 * {@code ObstacleDetector.detect()} 便恒走 {@code nearestObstacle()} 的 null 早退分支，
 * 返回 {@code (Double.MAX_VALUE, 0)} ⇒ threat 恒为 NONE。
 *
 * <p>与此同时 {@code VirtualDroneBudgetIntegrationTest.toyMode_ultrasonicObstacleDetection_works}
 * 这条用例<b>自己</b>调了 {@code ultrasonic.measure(new double[]{0.3, ...})}，
 * 人为造出了生产流程永远不产生的状态，于是断言 CRITICAL 成立、测试全绿。
 * 换句话说：13 条超声波用例测的是一个在产品中不可达的缓存字段。
 *
 * <p>更彻底地说，修复前模拟器里<b>根本没有"障碍物"这个概念</b>：
 * {@code ScenarioController.Kind} 八种里没有 OBSTACLE，{@code SimulatedDepthSource}
 * 在 {@code src/main} 里从未被构造，注入它的 {@code setDepthSource} 也零生产调用方。
 * 所以"接线"必须先造一个障碍物来源，本类同时守住这两半。
 *
 * <p>本类走真实 UDP 往返而非反射私有字段：要钉的正是"线上那帧 30010 有没有真实威胁等级"，
 * 与演示时看到的是同一件事。
 */
@DisplayName("M3 避障接线：obstacle 场景 → 超声波 → 30010 真链路")
class ObstacleSourceWiringTest {

    /** 独立端口段，避开 Gripper(24790+) / Budget(24590+) / Dispatch(24750+) / Lifecycle(24700+)。 */
    private static final int PORT_WITH = 24820;
    private static final int PORT_WITHOUT = 24821;
    private static final int GCS_COMP = 190;
    private static final int REPORT_TIMEOUT_MS = 4000;

    /**
     * 跑一台带 obstacle 场景（或不带）的 toy 模式无人机，等它上报一帧 30010。
     *
     * @return 收到的 ObstacleReportMsg；超时返回 null
     */
    private ObstacleReportMsg awaitObstacleReport(int port, String scenarioSpec) throws Exception {
        List<String> args = new ArrayList<>(List.of(
                "--budget=toy", "--port=" + port, "--failsafe=off"));
        if (scenarioSpec != null) {
            args.add("--scenario=" + scenarioSpec);
        }
        SimConfig config = SimConfig.parse(args.toArray(new String[0]));
        try (VirtualDrone drone = new VirtualDrone(config);
             DatagramSocket gcs = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {

            drone.start();
            drone.setObstacleEnabled(true);

            // send() 只发往 transport.getLastPeer()，所以必须先让无人机记住 GCS 地址。
            byte[] hb = new Heartbeat(0, 2, 6, 0, MavEnums.MAV_STATE_ACTIVE)
                    .toFrame(VirtualDrone.GCS_SYSID, GCS_COMP, 0).encodeV2();
            gcs.send(new DatagramPacket(hb, hb.length,
                    InetAddress.getLoopbackAddress(), port));

            // 30010 走 5Hz 分频，最多等 REPORT_TIMEOUT_MS
            long deadline = System.currentTimeMillis() + REPORT_TIMEOUT_MS;
            gcs.setSoTimeout(400);
            MavlinkParser parser = new MavlinkParser();
            while (System.currentTimeMillis() < deadline) {
                byte[] buf = new byte[2048];
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                try {
                    gcs.receive(pkt);
                } catch (java.net.SocketTimeoutException ignored) {
                    continue;
                }
                ByteBuffer bb = ByteBuffer.wrap(buf, 0, pkt.getLength());
                MavlinkParser.ParseResult parsed;
                while ((parsed = parser.parse(bb)) != null) {
                    MavlinkFrame f = parsed.frame;
                    if (f.getMessageId() != ObstacleReportMsg.ID) {
                        continue;
                    }
                    MavlinkMessage msg = MavlinkMessage.decode(f);
                    if (msg instanceof ObstacleReportMsg r) {
                        return r;
                    }
                }
            }
            return null;
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("有障碍场景：30010 必须报真实威胁等级与距离（修复前恒为 NONE/MAX_VALUE）")
    void obstacleScenarioProducesRealThreatOnTheWire() throws Exception {
        // 机头正前方 0.3m —— toy 模式阈值 safety=2.0 / emergency=0.5 ⇒ CRITICAL
        ObstacleReportMsg report = awaitObstacleReport(PORT_WITH, "obstacle:0:9999:0.3:0");

        assertThat(report).as("应在超时前收到一帧 ObstacleReportMsg(30010)").isNotNull();
        assertThat(report.threat)
                .as("0.3m 正前方障碍应报 threat=%s，实际 ordinal=%d。"
                        + "修复前 UltrasonicSource.lastReadings 恒为 null，"
                        + "detect() 恒走 null 早退分支 ⇒ 恒为 NONE(0)",
                        ThreatLevel.CRITICAL, report.threat)
                .isEqualTo(ThreatLevel.CRITICAL.ordinal());
        assertThat(report.distance)
                .as("距离应约 0.3m，而不是 Float.MAX_VALUE")
                .isBetween(0.0f, 0.5f);
    }

    @Test
    @Timeout(30)
    @DisplayName("对照组：无障碍场景时 30010 必须是 NONE —— 必须能与'接了线但没障碍'区分")
    void noObstacleScenarioStillReportsNone() throws Exception {
        ObstacleReportMsg report = awaitObstacleReport(PORT_WITHOUT, null);

        assertThat(report).as("即使无障碍事件也必须上报 30010（此时读数是满量程）").isNotNull();
        assertThat(report.threat)
                .as("无障碍时应报 NONE —— 必须能与'链路没接上导致的恒 NONE'区分，"
                        + "否则排查时分不清是没障碍还是没接线")
                .isEqualTo(ThreatLevel.NONE.ordinal());
    }

    @Test
    @DisplayName("故障排除前提：有障碍时 CRITICAL 与无障碍时 NONE 必须是不同值")
    void criticalAndNoneAreDistinct() {
        assertThat(ThreatLevel.CRITICAL.ordinal()).isNotEqualTo(ThreatLevel.NONE.ordinal());
    }
}
package io.aerofleet.sim;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.CommandAck;
import io.aerofleet.mavlink.messages.CommandLong;
import io.aerofleet.mavlink.messages.GlobalPositionInt;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MAV_CMD_DO_REPOSITION(192) 机载派发 + 真飞到位守卫。
 * <p>
 * 背景：编队后端把「成员该去哪」全部用 DO_REPOSITION 下发（FR-04 队形变换逐点
 * 引导、FR-08 偏差超容差修正）。此前 drone-sim 的 switch 没有这条 case ⇒
 * "unsupported command 192"、真机侧一步都不动，e2e-formation 的 STABLE 永远
 * 等不到。本测试走<b>真实 UDP 协议路径</b>（编码 CommandLong → 发给运行中的
 * VirtualDrone → 收 COMMAND_ACK / 遥测），钉死三件事：
 * <ol>
 *   <li>未 armed → DENIED（与 takeoff / mission start 同口径）</li>
 *   <li>armed 后 → ACCEPTED，且飞机<b>真的飞到目标经纬高</b>（到位容差取
 *       FormationKeeper.KEEP_TOLERANCE_M 同级的 1m）</li>
 *   <li>lat/lon 传 0,0 → 只改高度、水平位置不动（PX4 同口径的"只改高度"用法）</li>
 * </ol>
 * 场景取默认 none：GPS 噪声 0、气压漂移 0，遥测位置即真值，断言才能收到 1m。
 * <p>
 * 模式来源：NexusCommandDispatchTest（独立高端口 + try-with-resources + ACK 扫描）。
 */
@DisplayName("MAV_CMD_DO_REPOSITION(192)：编队引导命令的机载派发 + 真飞到位守卫")
class RepositionCommandDispatchTest {

    /** 独立端口：Lifecycle 24700+ / Budget 24590+ / Nexus 私有区命令 24750。 */
    private static final int DRONE_PORT = 24760;
    private static final int AUTOPILOT_COMP = 1;
    private static final long ACK_TIMEOUT_MS = 5_000;
    /** 到位轮询上限：44m 斜线 + 20m 爬升（2m/s）≈ 15s，留足余量。 */
    private static final long ARRIVE_TIMEOUT_MS = 25_000;
    /** 到位容差（m）：与 FormationKeeper.KEEP_TOLERANCE_M 同级。 */
    private static final double ARRIVE_TOL_M = 1.0;
    /** 高度到位容差（m）：遥测 1Hz，比水平放宽一档。 */
    private static final double ALT_TOL_M = 0.5;
    private static final double HOME_LAT = 22.5431;
    private static final double HOME_LON = 113.9578;

    @Test
    @DisplayName("未解锁拒绝 → 解锁后接受 → 飞机飞到目标经纬高并保持")
    void repositionGuidesVehicleToTarget() throws Exception {
        runOnPort(DRONE_PORT, gcs -> {
            // 1) 未 armed：必须拒绝（否则停在地上的飞机会被一条命令"召唤"走）
            send(gcs, reposition(HOME_LAT + 0.0004, HOME_LON, 20f));
            assertThat(awaitAck(gcs, MavEnums.MAV_CMD_DO_REPOSITION))
                    .as("未 armed 时 DO_REPOSITION 应回 DENIED")
                    .isEqualTo(MavEnums.MAV_RESULT_DENIED);

            // 2) 解锁
            arm(gcs);
            // 3) 引导到 slot 位（≈44m 北 / 46m 东 / 20m 高）
            double targetLat = GeoUtil.latOf(HOME_LAT, HOME_LON, 44.0, 0);
            double targetLon = GeoUtil.lonOf(HOME_LAT, HOME_LON, 0, 46.0);
            send(gcs, reposition(targetLat, targetLon, 20f));
            assertThat(awaitAck(gcs, MavEnums.MAV_CMD_DO_REPOSITION))
                    .as("armed 后 DO_REPOSITION 应回 ACCEPTED")
                    .isEqualTo(MavEnums.MAV_RESULT_ACCEPTED);

            assertThat(awaitArrival(gcs, targetLat, targetLon, 20.0))
                    .as("飞机应在 1m 容差内飞到目标位并保持（Keeper 到位判定同口径）")
                    .isTrue();
        });
    }

    @Test
    @DisplayName("lat/lon 传 0,0 只改高度：水平位置保持")
    void repositionZeroLatLonOnlyChangesAltitude() throws Exception {
        runOnPort(DRONE_PORT + 1, gcs -> {
            arm(gcs);
            send(gcs, reposition(0.0, 0.0, 18f));
            assertThat(awaitAck(gcs, MavEnums.MAV_CMD_DO_REPOSITION))
                    .as("lat/lon=0,0 应被接受（PX4 同口径：只改高度）")
                    .isEqualTo(MavEnums.MAV_RESULT_ACCEPTED);
            assertThat(awaitArrival(gcs, HOME_LAT, HOME_LON, 18.0))
                    .as("只改高度时水平位置必须保持")
                    .isTrue();
        });
    }

    @Test
    @DisplayName("经纬度越界：拒绝而不是飞向 2500km 外的解析点")
    void repositionOutOfRangeRejected() throws Exception {
        runOnPort(DRONE_PORT + 2, gcs -> {
            arm(gcs);
            send(gcs, reposition(95.0, 0.0, 20f));
            assertThat(awaitAck(gcs, MavEnums.MAV_CMD_DO_REPOSITION))
                    .as("lat=95 越界应回 DENIED")
                    .isEqualTo(MavEnums.MAV_RESULT_DENIED);
        });
    }

    /** 起一架与参考点同 home 的 drone-sim（--failsafe=off：不让链路失败保护干扰断言）。 */
    private void runOnPort(int port, DroneCase body) throws Exception {
        SimConfig config = SimConfig.parse(new String[]{
                "--port=" + port, "--failsafe=off",
                "--lat=" + HOME_LAT, "--lon=" + HOME_LON});
        try (VirtualDrone drone = new VirtualDrone(config);
             DatagramSocket gcs = new DatagramSocket()) {
            drone.start();
            gcs.connect(InetAddress.getLoopbackAddress(), port);
            body.run(gcs);
        }
    }

    private interface DroneCase {
        void run(DatagramSocket gcs) throws Exception;
    }

    private void arm(DatagramSocket gcs) throws Exception {
        send(gcs, new CommandLong(1, AUTOPILOT_COMP,
                MavEnums.MAV_CMD_COMPONENT_ARM_DISARM, 0,
                1, 0, 0, 0, 0, 0, 0));
        assertThat(awaitAck(gcs, MavEnums.MAV_CMD_COMPONENT_ARM_DISARM))
                .as("ARM 应被接受")
                .isEqualTo(MavEnums.MAV_RESULT_ACCEPTED);
    }

    /** DO_REPOSITION：param4=yaw=NaN（不变），param5=lat，param6=lon，param7=alt。 */
    private static CommandLong reposition(double lat, double lon, float alt) {
        return new CommandLong(1, AUTOPILOT_COMP, MavEnums.MAV_CMD_DO_REPOSITION, 0,
                0, 0, 0, Float.NaN, (float) lat, (float) lon, alt);
    }

    private final int[] seq = {0};

    private void send(DatagramSocket gcs, MavlinkMessage msg) throws Exception {
        byte[] frame = msg.toFrame(VirtualDrone.GCS_SYSID, AUTOPILOT_COMP, seq[0]++)
                .encodeV2();
        gcs.send(new DatagramPacket(frame, frame.length));
    }

    /**
     * 等待回显指定命令 ID 的 COMMAND_ACK，返回其 result；超时返回 null。
     * 帧损坏（截断/CRC 不符）按丢弃处理——心跳等常规广播与 ACK 混在同一条 socket 上。
     */
    private Integer awaitAck(DatagramSocket gcs, int cmdId) throws Exception {
        long deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS;
        byte[] buf = new byte[2048];
        while (System.currentTimeMillis() < deadline) {
            MavlinkFrame frame = nextFrame(gcs, buf);
            if (frame == null || frame.getMessageId() != CommandAck.ID) {
                continue;
            }
            CommandAck ack = CommandAck.decode(frame);
            if (ack.command == cmdId) {
                return ack.result;
            }
        }
        return null;
    }

    /** 轮询遥测直到水平位置与高度都进入容差；超时返回 false。 */
    private boolean awaitArrival(DatagramSocket gcs, double targetLat, double targetLon,
                                 double targetAlt) throws Exception {
        long deadline = System.currentTimeMillis() + ARRIVE_TIMEOUT_MS;
        byte[] buf = new byte[2048];
        while (System.currentTimeMillis() < deadline) {
            MavlinkFrame frame = nextFrame(gcs, buf);
            if (frame == null || frame.getMessageId() != GlobalPositionInt.ID) {
                continue;
            }
            GlobalPositionInt p = GlobalPositionInt.decode(frame);
            double lat = p.latE7 / 1e7;
            double lon = p.lonE7 / 1e7;
            double alt = p.relativeAltMm / 1000.0;
            double north = GeoUtil.north(HOME_LAT, HOME_LON, lat, lon);
            double east = GeoUtil.east(HOME_LAT, HOME_LON, lat, lon);
            double tNorth = GeoUtil.north(HOME_LAT, HOME_LON, targetLat, targetLon);
            double tEast = GeoUtil.east(HOME_LAT, HOME_LON, targetLat, targetLon);
            if (Math.hypot(north - tNorth, east - tEast) < ARRIVE_TOL_M
                    && Math.abs(alt - targetAlt) < ALT_TOL_M) {
                return true;
            }
        }
        return false;
    }

    /** 收一帧并解码；超时/损坏返回 null。 */
    private static MavlinkFrame nextFrame(DatagramSocket gcs, byte[] buf) throws Exception {
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        try {
            gcs.setSoTimeout(200);
            gcs.receive(packet);
        } catch (java.net.SocketTimeoutException ste) {
            return null;
        }
        byte[] raw = Arrays.copyOf(packet.getData(), packet.getLength());
        try {
            return MavlinkFrame.decodeV2(raw);
        } catch (Exception ignored) {
            return null;
        }
    }
}

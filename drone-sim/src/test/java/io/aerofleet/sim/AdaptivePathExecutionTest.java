package io.aerofleet.sim;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.AdaptivePathMsg;
import io.aerofleet.mavlink.messages.CommandAck;
import io.aerofleet.mavlink.messages.CommandLong;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.messages.MissionAckMsg;
import io.aerofleet.mavlink.messages.MissionCountMsg;
import io.aerofleet.mavlink.messages.MissionCurrent;
import io.aerofleet.mavlink.messages.MissionItemInt;
import io.aerofleet.mavlink.messages.MissionRequestInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M11 执行级 ADAPT_PATH 自适应航线改写的端到端守卫（2026-10-04 接线）。
 *
 * <p>走<b>真实 UDP 协议路径</b>（模式来源 {@link NexusCommandDispatchTest}）：
 * 作为 GCS 客户端完成 Mission Protocol 上传握手（MISSION_COUNT →
 * MISSION_REQUEST_INT → MISSION_ITEM_INT × N → MISSION_ACK）→ ARM →
 * MISSION_START，然后直调 {@code executeAdaptivePath}（包内可见，生产入口是
 * {@code AutonomyExecutor} 门控回调）并从同一条 socket 断言两件事：
 * <ol>
 *   <li><b>ADAPTIVE_PATH(30052) 真实下发</b>——该消息的第一个生产者接线，
 *       断言公告的是真实几何（新航点在本场附近、originalWaypointSeq 是
 *       当前 seq、原因码正确）；</li>
 *   <li><b>任务尾部真实改写</b>——MISSION_CURRENT 广播的 total 从 3 变大
 *       （Z 字形任务的两个 90° 尖角被 Dubins 30m 圆弧替换，每角 9 个采样点）。</li>
 * </ol>
 * 负向契约（非 NAV_WAYPOINT 尾不改写、无尖角不发 30052）由
 * {@code MissionStoreTest}（replaceTail 守卫）与策略单测覆盖，不做基于
 * 「等一段时间没收到帧」的脆弱断言。
 *
 * <p><b>复合任务尾</b>（2026-10-05）：任务含非航点指令（拍照/悬停/RTL）时，
 * 只平滑剩余头部连续 NAV_WAYPOINT 段，段终点之后的项原样拼接（仅重编 seq）。
 * 此前含任何非航点指令的尾整体跳过——一条拍照指令就废掉全部适配。
 */
@DisplayName("M11 ADAPT_PATH 执行级：任务改写 + ADAPTIVE_PATH(30052) 真实下发")
class AdaptivePathExecutionTest {

    /** 独立端口，避开 Lifecycle(24700+)/Dispatch(24750+) 测试段。 */
    private static final int DRONE_PORT = 24780;
    private static final int AUTOPILOT_COMP = 1;
    private static final long FRAME_TIMEOUT_MS = 8_000;

    /** 本场默认坐标（SimConfig.defaults：深圳大学城）。 */
    private static final double HOME_LAT = 22.5907;
    private static final double HOME_LON = 113.9345;

    @Test
    @DisplayName("Z 字形任务执行 ADAPT_PATH：尾部插点改写（total 3→更大）且 30052 公告真实新航点")
    void adaptPathRewritesMissionTailAndAnnounces() throws Exception {
        // 裸写布尔开关在 SimConfig 加固（2026-10-04）后安全：不吞 token、可作末参数
        SimConfig config = SimConfig.parse(new String[]{"--port=" + DRONE_PORT, "--autonomy-exec"});
        try (VirtualDrone drone = new VirtualDrone(config);
             DatagramSocket gcs = new DatagramSocket()) {
            drone.start();
            InetAddress addr = InetAddress.getLoopbackAddress();
            int[] seq = {0};

            // 1. 任务上传握手：Z 字形 = 北 120m → 东 120m → 南 120m（两个 90° 尖角）
            uploadMission(gcs, addr, seq, new double[][]{
                    {HOME_LAT + 120.0 / 111320.0, HOME_LON, 30},
                    {HOME_LAT + 120.0 / 111320.0, HOME_LON + 120.0 / (111320.0 * Math.cos(Math.toRadians(HOME_LAT))), 30},
                    {HOME_LAT, HOME_LON + 120.0 / (111320.0 * Math.cos(Math.toRadians(HOME_LAT))), 30},
            });
            sendCommand(gcs, addr, seq, MavEnums.MAV_CMD_COMPONENT_ARM_DISARM, 1, 0, 0, 0, 0, 0, 0);
            sendCommand(gcs, addr, seq, MavEnums.MAV_CMD_MISSION_START, 0, 0, 0, 0, 0, 0, 0);

            // 2. 直调执行级（生产入口是 AutonomyExecutor 的 ADAPT_PATH 门控回调）
            drone.executeAdaptivePath("strong wind");

            // 3. 30052 公告：真实生产者接线断言
            AdaptivePathMsg announced = awaitFrame(gcs, AdaptivePathMsg.ID, AdaptivePathMsg::decode);
            assertThat(announced.originalWaypointSeq).isEqualTo(0);
            assertThat(announced.adjustmentReason)
                    .isEqualTo(io.aerofleet.mavlink.enums.AdjustmentReason.WIND.ordinal());
            double newLat = announced.newLat / 1e7;
            double newLon = announced.newLon / 1e7;
            // 新航点必须在本场附近（≤500m）：Z 字形全场 120m 步长 + 30m 圆弧不会跑出本场
            assertThat(Math.abs(newLat - HOME_LAT) * 111320.0).isLessThan(500.0);
            assertThat(Math.abs(newLon - HOME_LON) * 111320.0).isLessThan(500.0);

            // 4. 任务尾部改写：MISSION_CURRENT 的 total 从 3 变大（2Hz 广播，等一拍）
            long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS;
            Integer total = null;
            while (System.currentTimeMillis() < deadline) {
                MissionCurrent mc = pollFrame(gcs, MissionCurrent.ID, MissionCurrent::decode);
                if (mc != null) {
                    total = mc.total;
                    if (total != null && total > 3) {
                        break;
                    }
                }
            }
            assertThat(total).as("MISSION_CURRENT.total 应从 3 增大（Dubins 尖角插点）").isGreaterThan(3);
        }
    }

    @Test
    @DisplayName("复合任务尾（Z 字形 + 拍照 + RTL）：头部航点段照常平滑，拍照/RTL 项原样保留仅重编 seq")
    void compoundTailAdaptsPrefixAndPreservesNonWaypoints() throws Exception {
        SimConfig config = SimConfig.parse(new String[]{"--port=" + DRONE_PORT, "--autonomy-exec"});
        try (VirtualDrone drone = new VirtualDrone(config);
             DatagramSocket gcs = new DatagramSocket()) {
            drone.start();
            InetAddress addr = InetAddress.getLoopbackAddress();
            int[] seq = {0};

            // Z 字形两航点（一个 90° 尖角）+ 拍照(IMAGE_START_CAPTURE) + RTL：
            // 旧守卫下这条任务整体跳过（尾含非航点指令），新语义只平滑前缀段
            uploadCompoundMission(gcs, addr, seq);
            sendCommand(gcs, addr, seq, MavEnums.MAV_CMD_COMPONENT_ARM_DISARM, 1, 0, 0, 0, 0, 0, 0);
            sendCommand(gcs, addr, seq, MavEnums.MAV_CMD_MISSION_START, 0, 0, 0, 0, 0, 0, 0);

            drone.executeAdaptivePath("strong wind");

            // 30052 照常公告（前缀段 2 航点含 1 尖角，Dubins 必插点）
            AdaptivePathMsg announced = awaitFrame(gcs, AdaptivePathMsg.ID, AdaptivePathMsg::decode);
            assertThat(announced.originalWaypointSeq).isEqualTo(0);

            // 复合尾断言（直读快照，不经 UDP 二次猜测）：
            java.util.List<MissionItemInt> items = drone.missionItemsSnapshot();
            assertThat(items.size()).as("尖角插点后任务应变大（4 → >4）").isGreaterThan(4);
            // seq 从 0 连续编号到 size-1（拼接后无空洞、无重复）
            for (int i = 0; i < items.size(); i++) {
                assertThat(items.get(i).seq).as("seq 应连续，i=" + i).isEqualTo(i);
            }
            // 头部全是 NAV_WAYPOINT（平滑产物）
            for (MissionItemInt it : items.subList(0, items.size() - 2)) {
                assertThat(it.command).isEqualTo(MavEnums.MAV_CMD_NAV_WAYPOINT);
            }
            // 倒数第二项 = 拍照指令原样保留（指令/参数/坐标一字不动）
            MissionItemInt photo = items.get(items.size() - 2);
            assertThat(photo.command).isEqualTo(MavEnums.MAV_CMD_IMAGE_START_CAPTURE);
            assertThat(photo.param1).isEqualTo(7.5f);
            assertThat(photo.param2).isEqualTo(3.25f);
            assertThat(photo.x).isEqualTo((int) Math.round((HOME_LAT + 0.001) * 1e7));
            assertThat(photo.y).isEqualTo((int) Math.round((HOME_LON + 0.001) * 1e7));
            assertThat(photo.z).isEqualTo(30f);
            // 末项 = RTL 原样保留
            MissionItemInt rtl = items.get(items.size() - 1);
            assertThat(rtl.command).isEqualTo(MavEnums.MAV_CMD_NAV_RETURN_TO_LAUNCH);
        }
    }

    // ------------------------------------------------------------------
    // GCS 侧协议助手（模式来源：NexusCommandDispatchTest）
    // ------------------------------------------------------------------

    /** Mission Protocol 上传握手：COUNT → 逐项 ITEM_INT（跟随 REQUEST_INT）→ ACK。 */
    private void uploadMission(DatagramSocket gcs, InetAddress addr, int[] seq, double[][] latLonAlt)
            throws Exception {
        send(gcs, addr, seq, new MissionCountMsg(latLonAlt.length, 1, AUTOPILOT_COMP, 0, 0));
        int sent = 0;
        long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS;
        while (sent < latLonAlt.length && System.currentTimeMillis() < deadline) {
            MissionRequestInt req = pollFrame(gcs, MissionRequestInt.ID, MissionRequestInt::decode);
            if (req == null) {
                continue;
            }
            double[] wp = latLonAlt[req.seq];
            MissionItemInt item = new MissionItemInt(1, AUTOPILOT_COMP, req.seq,
                    MavEnums.MAV_FRAME_GLOBAL, MavEnums.MAV_CMD_NAV_WAYPOINT,
                    0, 1, 0f, 0f, 0f, 0f,
                    (int) Math.round(wp[0] * 1e7), (int) Math.round(wp[1] * 1e7),
                    (float) wp[2], 0);
            send(gcs, addr, seq, item);
            sent++;
        }
        assertThat(sent).as("任务上传应完成（跟随 MISSION_REQUEST_INT 逐项发送）").isEqualTo(latLonAlt.length);
        MissionAckMsg ack = awaitFrame(gcs, MissionAckMsg.ID, MissionAckMsg::decode);
        assertThat(ack.type).isEqualTo(MavEnums.MAV_MISSION_ACCEPTED);
    }

    /** 复合任务上传：WP → WP（90° 尖角）→ DO_DIGICAM_CONTROL → NAV_RETURN_TO_LAUNCH。 */
    private void uploadCompoundMission(DatagramSocket gcs, InetAddress addr, int[] seq)
            throws Exception {
        double northLat = HOME_LAT + 120.0 / 111320.0;
        double eastLon = HOME_LON + 120.0 / (111320.0 * Math.cos(Math.toRadians(HOME_LAT)));
        MissionItemInt[] plan = {
                new MissionItemInt(1, AUTOPILOT_COMP, 0, MavEnums.MAV_FRAME_GLOBAL,
                        MavEnums.MAV_CMD_NAV_WAYPOINT, 0, 1, 0f, 0f, 0f, 0f,
                        (int) Math.round(northLat * 1e7), (int) Math.round(HOME_LON * 1e7), 30f, 0),
                new MissionItemInt(1, AUTOPILOT_COMP, 1, MavEnums.MAV_FRAME_GLOBAL,
                        MavEnums.MAV_CMD_NAV_WAYPOINT, 0, 1, 0f, 0f, 0f, 0f,
                        (int) Math.round(northLat * 1e7), (int) Math.round(eastLon * 1e7), 30f, 0),
                new MissionItemInt(1, AUTOPILOT_COMP, 2, MavEnums.MAV_FRAME_GLOBAL,
                        MavEnums.MAV_CMD_IMAGE_START_CAPTURE, 0, 1, 7.5f, 3.25f, 0f, 0f,
                        (int) Math.round((HOME_LAT + 0.001) * 1e7),
                        (int) Math.round((HOME_LON + 0.001) * 1e7), 30f, 0),
                new MissionItemInt(1, AUTOPILOT_COMP, 3, MavEnums.MAV_FRAME_GLOBAL,
                        MavEnums.MAV_CMD_NAV_RETURN_TO_LAUNCH, 0, 1, 0f, 0f, 0f, 0f, 0, 0, 0f, 0),
        };
        send(gcs, addr, seq, new MissionCountMsg(plan.length, 1, AUTOPILOT_COMP, 0, 0));
        int sent = 0;
        long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS;
        while (sent < plan.length && System.currentTimeMillis() < deadline) {
            MissionRequestInt req = pollFrame(gcs, MissionRequestInt.ID, MissionRequestInt::decode);
            if (req == null) {
                continue;
            }
            send(gcs, addr, seq, plan[req.seq]);
            sent++;
        }
        assertThat(sent).as("复合任务上传应完成").isEqualTo(plan.length);
        MissionAckMsg ack = awaitFrame(gcs, MissionAckMsg.ID, MissionAckMsg::decode);
        assertThat(ack.type).isEqualTo(MavEnums.MAV_MISSION_ACCEPTED);
    }

    /** 发命令并等待 ACCEPTED 的 ACK（ARM/MISSION_START 业务断言）。 */
    private void sendCommand(DatagramSocket gcs, InetAddress addr, int[] seq,
                             int cmd, float... params) throws Exception {
        send(gcs, addr, seq, new CommandLong(1, AUTOPILOT_COMP, cmd, 0,
                params[0], params[1], params[2], params[3], params[4], params[5], params[6]));
        CommandAck ack = awaitFrame(gcs, CommandAck.ID, CommandAck::decode);
        assertThat(ack.result).isEqualTo(MavEnums.MAV_RESULT_ACCEPTED);
    }

    private void send(DatagramSocket gcs, InetAddress addr, int[] seq, MavlinkMessage msg) throws Exception {
        byte[] frame = msg.toFrame(VirtualDrone.GCS_SYSID, AUTOPILOT_COMP, seq[0]++).encodeV2();
        gcs.send(new DatagramPacket(frame, frame.length, addr, DRONE_PORT));
    }

    /** 阻塞等待指定 msgId 的帧并解码；超时抛断言失败。 */
    private <T> T awaitFrame(DatagramSocket gcs, int msgId, java.util.function.Function<MavlinkFrame, T> decoder)
            throws Exception {
        long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            T decoded = pollFrame(gcs, msgId, decoder);
            if (decoded != null) {
                return decoded;
            }
        }
        throw new AssertionError("等待 msgId=" + msgId + " 超时（" + FRAME_TIMEOUT_MS + "ms）");
    }

    /** 非阻塞收一帧：匹配 msgId 则解码返回，否则返回 null（心跳等噪音帧直接跳过）。 */
    private <T> T pollFrame(DatagramSocket gcs, int msgId, java.util.function.Function<MavlinkFrame, T> decoder)
            throws Exception {
        gcs.setSoTimeout(200);
        byte[] buf = new byte[512];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        try {
            gcs.receive(p);
        } catch (java.net.SocketTimeoutException e) {
            return null;
        }
        MavlinkFrame frame;
        try {
            frame = MavlinkFrame.decodeV2(java.util.Arrays.copyOf(p.getData(), p.getLength()));
        } catch (Exception e) {
            return null;   // 坏帧丢弃，继续扫描
        }
        return frame.getMessageId() == msgId ? decoder.apply(frame) : null;
    }
}

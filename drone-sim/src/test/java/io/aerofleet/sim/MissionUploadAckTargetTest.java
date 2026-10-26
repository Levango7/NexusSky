package io.aerofleet.sim;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.messages.MissionAckMsg;
import io.aerofleet.mavlink.messages.MissionCountMsg;
import io.aerofleet.mavlink.messages.MissionItemInt;
import io.aerofleet.mavlink.messages.MissionRequestInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务上传方向的寻址守卫（2026-10-12）。
 *
 * <p>上传方向是"GCS 推、飞机收"：飞机发出的 MISSION_REQUEST（向 GCS 要下一项）与
 * MISSION_ACK（上传结果）的 {@code targetSystem} 必须是<b>请求方 GCS</b>。
 * 旧代码把这两处填成 {@code config.sysid}（飞机自己），帧源虽然是飞机、但载荷
 * 寻址指向自己——严格 GCS（按 target_system 路由的 QGC/PX4 类）会认为这些帧
 * 不是发给自己的，任务上传握手永远等不到最终 ACK。
 *
 * <p>走真实 UDP 路径：本类作为 GCS 客户端完成握手，再从同一条 socket 读取
 * 飞机发出的两种帧并断言其 {@code targetSystem}。
 */
@DisplayName("任务上传寻址：MISSION_REQUEST / MISSION_ACK 的 targetSystem 指向 GCS")
class MissionUploadAckTargetTest {

    private static final int AUTOPILOT_COMP = 1;
    private static final long FRAME_TIMEOUT_MS = 8_000;

    @Test
    @DisplayName("上传握手：MISSION_REQUEST 与 MISSION_ACK 的 targetSystem = GCS sysid（而非飞机自身）")
    void uploadFramesTargetTheGcs() throws Exception {
        SimConfig config = SimConfig.parse(new String[]{"--port=0", "--sysid=2"});
        assertThat(config.sysid).as("本测试刻意让飞机 sysid ≠ GCS sysid，否则断言失去意义")
                .isNotEqualTo(VirtualDrone.GCS_SYSID);

        try (VirtualDrone drone = new VirtualDrone(config);
             DatagramSocket gcs = new DatagramSocket()) {
            drone.start();
            InetAddress addr = InetAddress.getLoopbackAddress();
            int[] seq = {0};

            // 宣告 1 项任务
            send(gcs, addr, drone.getLocalPort(), seq, new MissionCountMsg(1, VirtualDrone.GCS_SYSID, AUTOPILOT_COMP, 0, 0));

            // 飞机应来要第 0 项，且要的对象是 GCS
            MissionRequestInt req = awaitFrame(gcs, MissionRequestInt.ID, MissionRequestInt::decode);
            assertThat(req.seq).isZero();
            assertThat(req.targetSystem)
                    .as("MISSION_REQUEST 的 targetSystem 应是请求方 GCS，不是飞机自己")
                    .isEqualTo(VirtualDrone.GCS_SYSID);

            // 把唯一一项发过去
            send(gcs, addr, drone.getLocalPort(), seq, new MissionItemInt(VirtualDrone.GCS_SYSID, AUTOPILOT_COMP, 0,
                    MavEnums.MAV_FRAME_GLOBAL, MavEnums.MAV_CMD_NAV_WAYPOINT,
                    0, 1, 0f, 0f, 0f, 0f,
                    (int) Math.round(22.5907 * 1e7), (int) Math.round(113.9345 * 1e7), 60f, 0));

            // 最终 ACK 同样应指向 GCS
            MissionAckMsg ack = awaitFrame(gcs, MissionAckMsg.ID, MissionAckMsg::decode);
            assertThat(ack.type).isEqualTo(MavEnums.MAV_MISSION_ACCEPTED);
            assertThat(ack.targetSystem)
                    .as("MISSION_ACK 的 targetSystem 应是请求方 GCS，不是飞机自己")
                    .isEqualTo(VirtualDrone.GCS_SYSID);
        }
    }

    @Test
    @DisplayName("MISSION_COUNT=0 的清空 ACK 同样指向请求方 GCS")
    void clearMissionAckTargetsTheGcs() throws Exception {
        SimConfig config = SimConfig.parse(new String[]{"--port=0", "--sysid=2"});
        try (VirtualDrone drone = new VirtualDrone(config);
             DatagramSocket gcs = new DatagramSocket()) {
            drone.start();
            InetAddress addr = InetAddress.getLoopbackAddress();
            int[] seq = {0};

            send(gcs, addr, drone.getLocalPort(), seq, new MissionCountMsg(0, VirtualDrone.GCS_SYSID, AUTOPILOT_COMP, 0, 0));

            MissionAckMsg ack = awaitFrame(gcs, MissionAckMsg.ID, MissionAckMsg::decode);
            assertThat(ack.type).isEqualTo(MavEnums.MAV_MISSION_ACCEPTED);
            assertThat(ack.targetSystem).isEqualTo(VirtualDrone.GCS_SYSID);
        }
    }

    // ---- GCS 侧协议助手（写法对齐 AdaptivePathExecutionTest）----

    private void send(DatagramSocket gcs, InetAddress addr, int dronePort, int[] seq,
                      MavlinkMessage msg) throws Exception {
        byte[] frame = msg.toFrame(VirtualDrone.GCS_SYSID, AUTOPILOT_COMP, seq[0]++).encodeV2();
        gcs.send(new DatagramPacket(frame, frame.length, addr, dronePort));
    }

    /** 阻塞等待指定 msgId 的帧并解码；超时抛断言失败。 */
    private <T> T awaitFrame(DatagramSocket gcs, int msgId, java.util.function.Function<MavlinkFrame, T> decoder)
            throws Exception {
        long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS;
        do {
            T decoded = pollFrame(gcs, msgId, decoder);
            if (decoded != null) {
                return decoded;
            }
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("等待帧超时: msgId=" + msgId);
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

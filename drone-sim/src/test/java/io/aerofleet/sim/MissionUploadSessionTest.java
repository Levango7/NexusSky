package io.aerofleet.sim;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.messages.MissionAckMsg;
import io.aerofleet.mavlink.messages.MissionCountMsg;
import io.aerofleet.mavlink.messages.MissionItemInt;
import io.aerofleet.mavlink.messages.MissionRequestInt;
import io.aerofleet.mavlink.messages.MissionRequestList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务上传会话的健壮性守卫（2026-10-12）。
 *
 * <p>修前两个缺陷（外部评估报告 P1）：
 * <ol>
 *   <li><b>会话无超时</b>：GCS 宣告 MISSION_COUNT 后中途消失（掉线/被取消），
 *       {@code reRequestIfStalled()} 每 3s 无限重发 MISSION_REQUEST，会话永久挂着——
 *       飞机一直空喊要下一项；</li>
 *   <li><b>下载请求被无条件吞掉</b>：会话挂着时 {@code isUploading()} 恒真，
 *       同一条链路上的 MISSION_REQUEST_LIST 全被忽略，操作员在 GCS 上"点了没反应"。</li>
 * </ol>
 *
 * <p>修后：会话静默超过 {@code UPLOAD_SESSION_TIMEOUT_MS}（60s）主动中止并回
 * MAV_MISSION_OPERATION_CANCELLED；GCS 主动要求下载时先中止死会话再服务下载。
 */
@DisplayName("任务上传会话：超时中止 + 不被下载请求饿死")
class MissionUploadSessionTest {

    private static final int AUTOPILOT_COMP = 1;
    private static final long FRAME_TIMEOUT_MS = 5_000;

    @Test
    @DisplayName("GCS 中途消失：会话超时后中止，并回 CANCELLED 给请求方")
    void stalledUploadSessionTimesOut() throws Exception {
        // 生产默认 60s；这里缩到 300ms，否则这条回归要跑一分钟
        System.setProperty("aerofleet.sim.upload-session-timeout-ms", "300");
        try {
            SimConfig config = SimConfig.parse(new String[]{"--port=0", "--sysid=2"});
        try (VirtualDrone drone = new VirtualDrone(config);
             DatagramSocket gcs = new DatagramSocket()) {
            drone.start();
            InetAddress addr = InetAddress.getLoopbackAddress();
            int[] seq = {0};

            // 宣告 3 项任务，但一项都不发（模拟 GCS 消失）
            send(gcs, addr, drone.getLocalPort(), seq, new MissionCountMsg(3, VirtualDrone.GCS_SYSID, AUTOPILOT_COMP, 0, 0));

            // 等 tick 里的会话超时判定生效（60s 超时 + 3s 重发节奏）
            MissionAckMsg ack = awaitAck(gcs);
            assertThat(ack.targetSystem).isEqualTo(VirtualDrone.GCS_SYSID);
            assertThat(ack.type)
                    .as("会话是我们主动中止的，应答应是 OPERATION_CANCELLED")
                    .isEqualTo(MavEnums.MAV_MISSION_OPERATION_CANCELLED);

            // 中止后不应再继续重发 MISSION_REQUEST（旧行为是无限空转）
            MissionRequestInt noMore = pollFrame(gcs, MissionRequestInt.ID, MissionRequestInt::decode);
            assertThat(noMore)
                    .as("会话已中止，不应再有 MISSION_REQUEST")
                    .isNull();
            }
        } finally {
            System.clearProperty("aerofleet.sim.upload-session-timeout-ms");
        }
    }

    @Test
    @DisplayName("死会话不再吞下载请求：GCS 要求读任务时先中止旧会话再回 COUNT")
    void downloadAbortsStaleUploadSession() throws Exception {
        SimConfig config = SimConfig.parse(new String[]{"--port=0", "--sysid=2"});
        try (VirtualDrone drone = new VirtualDrone(config);
             DatagramSocket gcs = new DatagramSocket()) {
            drone.start();
            InetAddress addr = InetAddress.getLoopbackAddress();
            int[] seq = {0};

            // 先建一个上传会话（宣告 2 项，不发）
            send(gcs, addr, drone.getLocalPort(), seq, new MissionCountMsg(2, VirtualDrone.GCS_SYSID, AUTOPILOT_COMP, 0, 0));
            // 立刻要求下载：旧实现会因为 isUploading() 把这条请求吞掉
            send(gcs, addr, drone.getLocalPort(), seq, new MissionRequestList(VirtualDrone.GCS_SYSID, AUTOPILOT_COMP, 0));

            // 期望：飞机回一个 MISSION_COUNT（0 项 = 没有已存任务），而不是沉默
            MissionCountMsg count = awaitCount(gcs);
            assertThat(count.count).as("下载应答的 COUNT 必须是本次下载的结果（无任务=0）").isZero();
        }
    }

    // ---- GCS 侧协议助手 ----

    private void send(DatagramSocket gcs, InetAddress addr, int dronePort, int[] seq,
                      MavlinkMessage msg) throws Exception {
        byte[] frame = msg.toFrame(VirtualDrone.GCS_SYSID, AUTOPILOT_COMP, seq[0]++).encodeV2();
        gcs.send(new DatagramPacket(frame, frame.length, addr, dronePort));
    }

    /** 等一个 MISSION_ACK（会话超时判定是 60s，所以这里放宽到 12 个周期）。 */
    private MissionAckMsg awaitAck(DatagramSocket gcs) throws Exception {
        long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS * 12;
        do {
            MissionAckMsg ack = pollFrame(gcs, MissionAckMsg.ID, MissionAckMsg::decode);
            if (ack != null) {
                return ack;
            }
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("等待 MISSION_ACK 超时");
    }

    private MissionCountMsg awaitCount(DatagramSocket gcs) throws Exception {
        long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS * 12;
        do {
            MissionCountMsg count = pollFrame(gcs, MissionCountMsg.ID, MissionCountMsg::decode);
            if (count != null) {
                return count;
            }
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("等待 MISSION_COUNT 超时");
    }

    private <T> T pollFrame(DatagramSocket gcs, int msgId, java.util.function.Function<MavlinkFrame, T> decoder)
            throws Exception {
        gcs.setSoTimeout(500);
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
            return null;
        }
        return frame.getMessageId() == msgId ? decoder.apply(frame) : null;
    }
}

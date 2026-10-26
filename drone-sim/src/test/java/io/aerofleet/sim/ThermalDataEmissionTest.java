package io.aerofleet.sim;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.Heartbeat;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.messages.ThermalDataMsg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3 热成像 THERMAL_DATA(30012) 真实下发守卫（P2 #19，2026-10-24 接线）。
 *
 * <p>走<b>真实 UDP 协议路径</b>（模式来源 {@code AdaptivePathExecutionTest}）：
 * 起 VirtualDrone、GCS socket 发心跳建立回程路由，然后等 1Hz 遥测里的
 * THERMAL_DATA 帧。此前 ThermalDataMsg 定义/解码/载荷源三件齐备，唯独
 * <b>全仓零发送点</b>——"壳到 sim 侧"，这条守卫防它再次静默退线。
 *
 * <p>断言口径：帧真到了（1Hz 必达）+ 数值非平凡（tempMax > tempMean > 22℃ 基线，
 * 标准差非零——合成场景有两个热源，恒值场说明分析链路没走通）+ sysid 正确。
 */
@DisplayName("M3 THERMAL_DATA(30012) 真实下发（1Hz 遥测，P2 #19 接线守卫）")
class ThermalDataEmissionTest {

    private static final int AUTOPILOT_COMP = 1;
    private static final long FRAME_TIMEOUT_MS = 8_000;

    @Test
    @DisplayName("1Hz 遥测含 THERMAL_DATA：数值非平凡（热源场景 ≠ 恒值场）且 sysid 正确")
    void thermalDataFlowsOverRealUdp() throws Exception {
        SimConfig config = SimConfig.parse(new String[]{"--port=0"});
        try (VirtualDrone drone = new VirtualDrone(config);
             DatagramSocket gcs = new DatagramSocket()) {
            drone.start();
            InetAddress addr = InetAddress.getLoopbackAddress();
            int[] seq = {0};

            // 心跳建立回程路由（transport.getLastPeer 只认收到的对端；构造沿 Obstacle 先例）
            send(gcs, addr, drone.getLocalPort(), seq, new Heartbeat(0, 2, 6, 0, MavEnums.MAV_STATE_ACTIVE));

            ThermalDataMsg thermal = awaitFrame(gcs, ThermalDataMsg.ID, ThermalDataMsg::decode);

            assertThat(thermal.sysid).isEqualTo(config.sysid);
            assertThat(thermal.tempMax).as("合成场景含热源：tempMax 必须高于 22℃ 基线")
                    .isGreaterThan(22.0f);
            assertThat(thermal.tempMean).isGreaterThan(22.0f).isLessThan(thermal.tempMax);
            assertThat(thermal.tempMin).isLessThan(thermal.tempMean);
            assertThat(thermal.tempStdDev).as("温度场有梯度（热源 + 基线），标准差非零").isPositive();
            assertThat(thermal.timestamp).isPositive();
        }
    }

    @Test
    @DisplayName("合成场景确定性：同 sysid 同时刻同矩阵，不同 sysid 场景不同")
    void sceneSynthesisIsDeterministicPerSysid() {
        long now = 1_700_000_000_000L;
        double[][] a1 = VirtualDrone.synthesizeThermalScene(7, now);
        double[][] a2 = VirtualDrone.synthesizeThermalScene(7, now);
        double[][] b = VirtualDrone.synthesizeThermalScene(8, now);

        assertThat(a1).isDeepEqualTo(a2);
        // 不同 sysid 的热源位置由 sysid 派生：逐元素完全相同反而是 bug（各机看到同一场景）
        boolean anyDifferent = false;
        for (int y = 0; y < a1.length && !anyDifferent; y++) {
            for (int x = 0; x < a1[0].length; x++) {
                if (Math.abs(a1[y][x] - b[y][x]) > 1e-9) {
                    anyDifferent = true;
                    break;
                }
            }
        }
        assertThat(anyDifferent).as("不同 sysid 的场景应有差异").isTrue();
        // 场景基线：所有像元 ≥ 22℃（基线），且存在高于基线的热源像元
        double max = Double.NEGATIVE_INFINITY;
        for (double[] row : b) {
            for (double v : row) {
                assertThat(v).isGreaterThanOrEqualTo(22.0);
                max = Math.max(max, v);
            }
        }
        assertThat(max).as("存在热源像元").isGreaterThan(22.0);
    }

    private void send(DatagramSocket gcs, InetAddress addr, int dronePort, int[] seq, MavlinkMessage msg)
            throws Exception {
        byte[] frame = msg.toFrame(VirtualDrone.GCS_SYSID, AUTOPILOT_COMP, seq[0]++).encodeV2();
        gcs.send(new DatagramPacket(frame, frame.length, addr, dronePort));
    }

    /** 收一个 MAVLink 帧；不是目标 msgId 就丢弃继续（心跳/遥测噪声多）。 */
    private <T> T pollFrame(DatagramSocket gcs, int msgId,
                            java.util.function.Function<MavlinkFrame, T> decoder) throws Exception {
        gcs.setSoTimeout(200);
        byte[] buf = new byte[512];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        try {
            gcs.receive(pkt);
        } catch (java.net.SocketTimeoutException e) {
            return null;
        }
        MavlinkFrame frame;
        try {
            frame = MavlinkFrame.decodeV2(Arrays.copyOf(pkt.getData(), pkt.getLength()));
        } catch (Exception e) {
            return null;   // 坏帧丢弃，继续扫描
        }
        return frame.getMessageId() == msgId ? decoder.apply(frame) : null;
    }

    /** 阻塞等待指定 msgId 的帧并解码；超时抛断言失败。 */
    private <T> T awaitFrame(DatagramSocket gcs, int msgId,
                             java.util.function.Function<MavlinkFrame, T> decoder) throws Exception {
        long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            T decoded = pollFrame(gcs, msgId, decoder);
            if (decoded != null) {
                return decoded;
            }
        }
        throw new AssertionError("等待 msgId=" + msgId + " 超时（" + FRAME_TIMEOUT_MS + "ms）"
                + "——THERMAL_DATA 没有下发（1Hz 挂钩退线？）");
    }
}

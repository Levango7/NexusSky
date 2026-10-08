package io.aerofleet.sim;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.CommandAck;
import io.aerofleet.mavlink.messages.CommandLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 夹爪装配后可用的回归守卫（FR-19/FR-21 投递链路）。
 *
 * <p><b>这个缺陷是怎么漏掉的</b>：{@link NexusCommandDispatchTest} 覆盖了
 * MAV_CMD 30080-30087 的派发，但它**只断言 result != UNSUPPORTED**（文件注释明说
 * "不钉各 handler 的业务结果"）。而未 enable 的夹爪对 GRAB 返回的是
 * {@code MAV_RESULT_DENIED}（2），不是 UNSUPPORTED（3）——于是"抓不动"这件事
 * 在那条守卫里完全不可见，测试全绿而演示时表现为"机载拒收配送指令"。
 *
 * <p>叠加另一处配置缺口：drone-sim 默认不注入执行机构（{@code --actuators} 缺省
 * false ⇒ sprayPump/gripper 为 null ⇒ 30083/30084/30085 恒 UNSUPPORTED）。
 * 两者叠加，最终现象是 e2e-spray 的机载回执恒为 {@code result=-1}。
 *
 * <p>本类沿用 {@link NexusCommandDispatchTest} 的真实 UDP 往返（而非反射私有字段），
 * 因为要钉的正是"命令能否被机载接受"——与演示时看到的是同一件事，
 * 而反射只会测到"我改的那一行"。
 */
@DisplayName("夹爪装配语义：--actuators 后应立即可用（GRAB 不得被拒）")
class GripperActuatorWiringTest {

    /** 独立端口，避开 Lifecycle(24700+) / Budget(24590+) / Dispatch(24750+) 测试段。 */
    private static final int PORT_WITH = 24790;
    private static final int PORT_WITHOUT = 24791;
    private static final int GCS_COMP = 190;
    private static final int ACK_TIMEOUT_MS = 3000;

    /**
     * 下发 GRIPPER_CONTROL(30084) GRAB(subcmd=0) 并等回执。
     *
     * @return MAV_RESULT 值；未收到 ACK 视为 UNSUPPORTED 以便断言失败可读
     */
    private int grabViaUdp(int port) throws Exception {
        boolean withActuators = port == PORT_WITH;
        List<String> args = new ArrayList<>(List.of(
                "--port=" + port, "--failsafe=off"));
        if (withActuators) {
            args.add("--actuators");
        }
        SimConfig config = SimConfig.parse(args.toArray(new String[0]));
        try (VirtualDrone drone = new VirtualDrone(config);
             DatagramSocket gcs = new DatagramSocket()) {
            drone.start();
            CommandLong cmd = new CommandLong(1, GCS_COMP,
                    MavEnums.MAV_CMD_NEXUS_GRIPPER_CONTROL, 0,
                    0.0f,         // subcmd = GRAB
                    1.0f,         // payloadId
                    2.0f,         // weightKg（<= payloadMax）
                    5.0f,         // volumeL
                    0f, 0f, 0f);
            byte[] frame = cmd.toFrame(VirtualDrone.GCS_SYSID, GCS_COMP, 0).encodeV2();
            gcs.send(new DatagramPacket(frame, frame.length,
                    InetAddress.getLoopbackAddress(), port));

            CommandAck ack = awaitAck(gcs, MavEnums.MAV_CMD_NEXUS_GRIPPER_CONTROL);
            return ack == null ? MavEnums.MAV_RESULT_UNSUPPORTED : ack.result;
        }
    }

    @Test
    @DisplayName("装配 actuators 后 GRAB 必须被接受（DENIED=夹爪没 enable，UNSUPPORTED=没装配）")
    void grabIsAcceptedWhenActuatorsAssembled() throws Exception {
        int result = grabViaUdp(PORT_WITH);
        assertThat(result)
                .as("装配 --actuators 后夹爪应已通电，GRAB 必须 MAV_RESULT_ACCEPTED(0)。"
                        + "返回 2(DENIED)=夹爪未 enable（2026-10-07 修复前的缺陷）；"
                        + "返回 3(UNSUPPORTED)=执行机构未装配")
                .isEqualTo(MavEnums.MAV_RESULT_ACCEPTED);
    }

    @Test
    @DisplayName("对照组：未装配 actuators 时必须是 UNSUPPORTED（与 DENIED 区分开）")
    void grabIsUnsupportedWithoutActuators() throws Exception {
        int result = grabViaUdp(PORT_WITHOUT);
        assertThat(result)
                .as("未装配执行机构应回 UNSUPPORTED —— 必须能与'装配了但没 enable'的 DENIED 区分，"
                        + "否则排查时分不清是没装配还是没通电")
                .isEqualTo(MavEnums.MAV_RESULT_UNSUPPORTED);
    }

    @Test
    @DisplayName("MAV_RESULT 语义：DENIED 与 UNSUPPORTED 是不同值（本缺陷的排查前提）")
    void deniedAndUnsupportedAreDistinct() {
        assertThat(MavEnums.MAV_RESULT_DENIED)
                .as("抓取被拒（夹爪未 enable）返回 DENIED")
                .isEqualTo(2);
        assertThat(MavEnums.MAV_RESULT_UNSUPPORTED)
                .as("执行机构未装配返回 UNSUPPORTED")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("夹爪状态机：HOLDING 下再 GRAB 必须被拒（幂等保护不被 enable 修复破坏）")
    void doubleGrabIsRejected() {
        Gripper g = new Gripper(10.0);
        g.enable();
        assertThat(g.grab(new PayloadItem(1, 2.0, 5.0, 0))).isTrue();
        assertThat(g.grab(new PayloadItem(2, 1.0, 3.0, 0)))
                .as("已 HOLDING 时再抓必须被拒 —— e2e-spray 因此在 START 前先 RESET")
                .isFalse();
    }

    @Test
    @DisplayName("超重负载必须被拒（使能修复不得放宽 FR-03 安全校验）")
    void overweightGrabStillRejected() {
        Gripper g = new Gripper(10.0);
        g.enable();
        assertThat(g.grab(new PayloadItem(1, 99.0, 5.0, 0)))
                .as("超过 payloadMax 必须拒绝")
                .isFalse();
    }

    @Test
    @DisplayName("未 enable 的夹爪抓不动 —— 记录该陷阱，避免再次被当成'默认行为'放过")
    void disabledGripperCannotGrab() {
        Gripper fresh = new Gripper(10.0);
        assertThat(fresh.grab(new PayloadItem(1, 2.0, 5.0, 0)))
                .as("Gripper 构造后默认未使能，抓取前置校验因此恒失败 —— "
                        + "这正是本缺陷的机制，装配时必须显式 enable")
                .isFalse();
    }

    private CommandAck awaitAck(DatagramSocket gcs, int cmdId) throws Exception {
        long deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS;
        byte[] buf = new byte[2048];
        while (System.currentTimeMillis() < deadline) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                gcs.setSoTimeout(200);
                gcs.receive(packet);
            } catch (java.net.SocketTimeoutException ste) {
                continue;
            }
            byte[] raw = Arrays.copyOf(packet.getData(), packet.getLength());
            MavlinkFrame frame;
            try {
                frame = MavlinkFrame.decodeV2(raw);
            } catch (Exception ignored) {
                continue;
            }
            if (frame.getMessageId() == CommandAck.ID) {
                CommandAck ack = CommandAck.decode(frame);
                if (ack.command == cmdId) {
                    return ack;
                }
            }
        }
        return null;
    }
}
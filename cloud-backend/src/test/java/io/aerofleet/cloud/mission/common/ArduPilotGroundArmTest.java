package io.aerofleet.cloud.mission.common;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.gateway.UdpGateway;
import io.aerofleet.cloud.telemetry.PendingAcks;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.CommandAck;
import io.aerofleet.mavlink.messages.CommandLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * ArduPilot 地面 arm 的模式约束（P2-F1 方案 A，SITL 首验实录 §11.2-⑥）：
 * AUTO 下 arm 必被拒（"Arm: Auto mode not armable"），切 GUIDED（custom_mode=4）后成功。
 * 本类钉住 arm() 的**命令序列**：AP+AUTO ⇒ 先 DO_SET_MODE(custom=1, guid=4) 再 ARM；
 * 其余目标/模式不变；切换失败不吞错（仍发 ARM，由固件如实拒绝）。
 *
 * <p>用 mock 网关捕获 COMMAND_LONG 并同步回 COMMAND_ACK（真 PendingAcks 驱动等待），
 * 与 {@code ArduPilotMissionUploadTest} 同款夹具。
 */
class ArduPilotGroundArmTest {

    private final List<MavlinkFrame> sent = new CopyOnWriteArrayList<>();

    private PendingAcks pendings;
    private DeviceRegistry registry;
    private DroneCommandService svc;

    /** DO_SET_MODE 的 ACK 结果（默认接受；拒绝场景改写它）。 */
    private volatile int setModeAckResult = MavEnums.MAV_RESULT_ACCEPTED;

    @BeforeEach
    void setUp() throws Exception {
        UdpGateway gateway = mock(UdpGateway.class);
        doAnswer(inv -> {
            MavlinkFrame frame = inv.getArgument(1);
            sent.add(frame);
            if (frame.getMessageId() == CommandLong.ID) {
                CommandLong cmd = CommandLong.decode(frame);
                int result = cmd.command == MavEnums.MAV_CMD_DO_SET_MODE
                        ? setModeAckResult : MavEnums.MAV_RESULT_ACCEPTED;
                pendings.offer(CommandAck.ID, new CommandAck(cmd.command, result, 0, 0, 0, 0), 1);
            }
            return null;
        }).when(gateway).send(anyInt(), any(MavlinkFrame.class));

        pendings = new PendingAcks();
        registry = new DeviceRegistry();
        svc = new DroneCommandService(gateway, pendings, registry, 1);
    }

    private List<CommandLong> commands() {
        return sent.stream()
                .filter(f -> f.getMessageId() == CommandLong.ID)
                .map(CommandLong::decode)
                .toList();
    }

    private DroneSnapshot drone(int autopilot, String mode) {
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.autopilot = autopilot;
        s.mode = mode;
        s.online = true;
        return s;
    }

    @Test
    @DisplayName("AP + AUTO：先切 GUIDED（DO_SET_MODE p1=1,p2=4）再 ARM——两条命令、顺序固定")
    void arm_arduPilotAuto_switchesGuidedFirst() {
        drone(MavEnums.MAV_AUTOPILOT_ARDUPILOTMEGA, "AUTO");

        int result = svc.arm(1);

        assertThat(result).isEqualTo(MavEnums.MAV_RESULT_ACCEPTED);
        List<CommandLong> cmds = commands();
        assertThat(cmds).hasSize(2);
        assertThat(cmds.get(0).command).isEqualTo(MavEnums.MAV_CMD_DO_SET_MODE);
        assertThat(cmds.get(0).param1).isEqualTo(1f);                                   // custom-mode 使能
        assertThat(cmds.get(0).param2).isEqualTo((float) DroneCommandService.ARDUPILOT_GUIDED_MODE_ID);
        assertThat(cmds.get(0).param2).isEqualTo(4f);                                   // ArduCopter GUIDED
        assertThat(cmds.get(1).command).isEqualTo(MavEnums.MAV_CMD_COMPONENT_ARM_DISARM);
        assertThat(cmds.get(1).param1).isEqualTo(1f);                                   // ARM
    }

    @Test
    @DisplayName("AP + GUIDED：直接 ARM，不多发模式切换")
    void arm_arduPilotGuided_armsDirectly() {
        drone(MavEnums.MAV_AUTOPILOT_ARDUPILOTMEGA, "GUIDED");

        svc.arm(1);

        assertThat(commands()).hasSize(1);
        assertThat(commands().get(0).command).isEqualTo(MavEnums.MAV_CMD_COMPONENT_ARM_DISARM);
    }

    @Test
    @DisplayName("PX4（MISSION 模式）：直接 ARM——模式约束是 AP 特有")
    void arm_px4_armsDirectly() {
        drone(MavEnums.MAV_AUTOPILOT_PX4, "MISSION");

        svc.arm(1);

        assertThat(commands()).hasSize(1);
        assertThat(commands().get(0).command).isEqualTo(MavEnums.MAV_CMD_COMPONENT_ARM_DISARM);
    }

    @Test
    @DisplayName("autopilot 未知（-1，还没收到心跳）：直接 ARM，不猜固件")
    void arm_unknownAutopilot_armsDirectly() {
        drone(-1, "AUTO");

        svc.arm(1);

        assertThat(commands()).hasSize(1);
        assertThat(commands().get(0).command).isEqualTo(MavEnums.MAV_CMD_COMPONENT_ARM_DISARM);
    }

    @Test
    @DisplayName("切 GUIDED 被拒：重试一次后仍发 ARM（不吞错、也不替固件做决定）")
    void arm_guidedSwitchRejected_stillAttemptsArm() {
        drone(MavEnums.MAV_AUTOPILOT_ARDUPILOTMEGA, "AUTO");
        setModeAckResult = MavEnums.MAV_RESULT_DENIED;

        int result = svc.arm(1);

        assertThat(result).isEqualTo(MavEnums.MAV_RESULT_ACCEPTED);
        List<CommandLong> cmds = commands();
        // command() 对非 ACCEPTED 重试一次 ⇒ DO_SET_MODE ×2，随后 ARM ×1
        assertThat(cmds).hasSize(3);
        assertThat(cmds.get(0).command).isEqualTo(MavEnums.MAV_CMD_DO_SET_MODE);
        assertThat(cmds.get(1).command).isEqualTo(MavEnums.MAV_CMD_DO_SET_MODE);
        assertThat(cmds.get(2).command).isEqualTo(MavEnums.MAV_CMD_COMPONENT_ARM_DISARM);
    }
}

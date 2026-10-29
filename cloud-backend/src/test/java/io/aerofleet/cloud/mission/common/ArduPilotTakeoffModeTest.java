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
 * 决策钉子：{@code takeoff} **不做模式适配**（与 {@code arm} 刻意不同）。
 *
 * <p>2026-10-29 AP SITL 实测矩阵（docs/sitl-integration.md §11.2-⑧）：
 * <pre>
 *   地面 + AUTO + 未 arm   → NAV_TAKEOFF 拒（MAV_RESULT=4，固件正确语义：未 arm）
 *   arm（自动切 GUIDED）后 → 接受，爬升                          ← 产品流可用
 *   空中 + AUTO            → 拒（非 GUIDED）且切模式只会打断任务
 *   空中 + GUIDED          → 仍拒（已起飞状态 NAV_TAKEOFF 不接受）
 * </pre>
 *
 * <p>故 takeoff 保持"只发一条 NAV_TAKEOFF"：① arm 已保证 AP 起飞前在 GUIDED；
 * ② 空中切 GUIDED 会静默打断任务，副作用大于收益；③ 未 arm 应由 arm 显式完成
 * （走拦截链），不由 takeoff 代做。本类钉住这三条，防止后人"顺手"加模式切换。
 */
class ArduPilotTakeoffModeTest {

    private final List<MavlinkFrame> sent = new CopyOnWriteArrayList<>();

    private PendingAcks pendings;
    private DeviceRegistry registry;
    private DroneCommandService svc;

    @BeforeEach
    void setUp() throws Exception {
        UdpGateway gateway = mock(UdpGateway.class);
        doAnswer(inv -> {
            MavlinkFrame frame = inv.getArgument(1);
            sent.add(frame);
            if (frame.getMessageId() == CommandLong.ID) {
                CommandLong cmd = CommandLong.decode(frame);
                pendings.offer(CommandAck.ID,
                        new CommandAck(cmd.command, MavEnums.MAV_RESULT_ACCEPTED, 0, 0, 0, 0), 1);
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

    private DroneSnapshot drone(int autopilot, String mode, boolean armed, int landedState) {
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.autopilot = autopilot;
        s.mode = mode;
        s.armed = armed;
        s.landedState = landedState;
        s.online = true;
        return s;
    }

    @Test
    @DisplayName("AP + AUTO + 地面：takeoff 只发 1 条 NAV_TAKEOFF，不擅自切模式（切模式会打断任务）")
    void takeoff_arduPilotAuto_doesNotSwitchMode() {
        drone(MavEnums.MAV_AUTOPILOT_ARDUPILOTMEGA, "AUTO", true, 1);

        svc.takeoff(1, 20.0);

        List<CommandLong> cmds = commands();
        assertThat(cmds).hasSize(1);
        assertThat(cmds.get(0).command).isEqualTo(MavEnums.MAV_CMD_NAV_TAKEOFF);
        assertThat(cmds.get(0).param7).isEqualTo(20.0f);
    }

    @Test
    @DisplayName("AP + 空中（IN_AIR）+ AUTO：同样只发 TAKEOFF——不因'非 GUIDED'去切模式")
    void takeoff_arduPilotInAir_doesNotSwitchMode() {
        drone(MavEnums.MAV_AUTOPILOT_ARDUPILOTMEGA, "AUTO", true, 2);

        svc.takeoff(1, 30.0);

        assertThat(commands()).hasSize(1);
    }

    @Test
    @DisplayName("AP + 未 arm：只发 TAKEOFF——不代 arm（arm 走独立入口 + 拦截链）")
    void takeoff_notArmed_doesNotArm() {
        drone(MavEnums.MAV_AUTOPILOT_ARDUPILOTMEGA, "AUTO", false, 1);

        svc.takeoff(1, 20.0);

        List<CommandLong> cmds = commands();
        assertThat(cmds).hasSize(1);
        assertThat(cmds.get(0).command).isEqualTo(MavEnums.MAV_CMD_NAV_TAKEOFF);
        assertThat(cmds).noneMatch(c -> c.command == MavEnums.MAV_CMD_COMPONENT_ARM_DISARM);
        assertThat(cmds).noneMatch(c -> c.command == MavEnums.MAV_CMD_DO_SET_MODE);
    }

    @Test
    @DisplayName("PX4：takeoff 同样只发 1 条（模式约束是 AP 特有，但适配也不该乱加）")
    void takeoff_px4_singleCommand() {
        drone(MavEnums.MAV_AUTOPILOT_PX4, "MISSION", true, 2);

        svc.takeoff(1, 20.0);

        assertThat(commands()).hasSize(1);
    }
}

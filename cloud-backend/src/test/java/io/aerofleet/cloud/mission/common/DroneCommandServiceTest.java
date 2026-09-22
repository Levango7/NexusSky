package io.aerofleet.cloud.mission.common;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.telemetry.PendingAcks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DroneCommandService 单测：未知 sysid 在 resolveTarget 阶段即被拒绝。
 * <p>
 * 使用空 DeviceRegistry，UdpGateway 传 null（resolveTarget 在发送前就抛异常，
 * 永远不会到达 gateway.send）。这些测试验证命令通道的输入校验，不需要 Mock 网关。
 */
@DisplayName("DroneCommandService 未知设备拒绝")
class DroneCommandServiceTest {

    /** 构造一个使用空注册表的 service：UdpGateway 传 null（不会到达发送步骤）。 */
    private DroneCommandService newService() {
        DeviceRegistry registry = new DeviceRegistry();
        PendingAcks pendings = new PendingAcks();
        return new DroneCommandService(null, pendings, registry, 1);
    }

    @Test
    @DisplayName("arm 未知 sysid 抛 CommandException")
    void arm_unknownSysid_throwsCommandException() {
        DroneCommandService service = newService();

        assertThatThrownBy(() -> service.arm(99))
                .isInstanceOf(DroneCommandService.CommandException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("disarm 未知 sysid 抛 CommandException")
    void disarm_unknownSysid_throwsCommandException() {
        DroneCommandService service = newService();

        assertThatThrownBy(() -> service.disarm(99))
                .isInstanceOf(DroneCommandService.CommandException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("rtl 未知 sysid 抛 CommandException")
    void rtl_unknownSysid_throwsCommandException() {
        DroneCommandService service = newService();

        assertThatThrownBy(() -> service.rtl(99))
                .isInstanceOf(DroneCommandService.CommandException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("takeoff 未知 sysid 抛 CommandException")
    void takeoff_unknownSysid_throwsCommandException() {
        DroneCommandService service = newService();

        assertThatThrownBy(() -> service.takeoff(99, 50.0))
                .isInstanceOf(DroneCommandService.CommandException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("manualControl 未知 sysid 抛 CommandException")
    void manualControl_unknownSysid_throwsCommandException() {
        DroneCommandService service = newService();

        assertThatThrownBy(() -> service.manualControl(99, 0, 0, 500, 0))
                .isInstanceOf(DroneCommandService.CommandException.class)
                .hasMessageContaining("99");
    }
}
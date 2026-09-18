package io.aerofleet.cloud.mission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EmergencyCommand} 命令模型单测。
 * <p>
 * 覆盖构造、字段读写、无人机分配、执行日志、阶段历史、防御性拷贝等。
 */
@DisplayName("EmergencyCommand 命令模型")
class EmergencyCommandTest {

    private EmergencyCommand newCommand() {
        return new EmergencyCommand(
                "CMD-001",
                EmergencyCommand.IncidentType.FIRE,
                EmergencyCommand.Severity.CRITICAL,
                new EmergencyCommand.Location(39.9, 116.3, 50.0),
                "某地发生火灾",
                "张三",
                "13800000000",
                System.currentTimeMillis());
    }

    @Test
    @DisplayName("构造命令初始阶段为 RECEIVED")
    void initialPhaseIsReceived() {
        EmergencyCommand cmd = newCommand();
        assertThat(cmd.getCurrentPhase()).isEqualTo(EmergencyCommandPhase.RECEIVED);
        assertThat(cmd.isClosed()).isFalse();
    }

    @Test
    @DisplayName("只读字段正确返回")
    void readOnlyFields() {
        EmergencyCommand cmd = newCommand();
        assertThat(cmd.getId()).isEqualTo("CMD-001");
        assertThat(cmd.getIncidentType()).isEqualTo(EmergencyCommand.IncidentType.FIRE);
        assertThat(cmd.getSeverity()).isEqualTo(EmergencyCommand.Severity.CRITICAL);
        assertThat(cmd.getDescription()).isEqualTo("某地发生火灾");
        assertThat(cmd.getReporterName()).isEqualTo("张三");
        assertThat(cmd.getReporterContact()).isEqualTo("13800000000");
    }

    @Test
    @DisplayName("Location 字段正确返回经纬度海拔")
    void locationFields() {
        EmergencyCommand cmd = newCommand();
        assertThat(cmd.getLocation().getLat()).isEqualTo(39.9);
        assertThat(cmd.getLocation().getLon()).isEqualTo(116.3);
        assertThat(cmd.getLocation().getAlt()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("assignDrone 分配无人机去重")
    void assignDroneDedup() {
        EmergencyCommand cmd = newCommand();
        cmd.assignDrone(1);
        cmd.assignDrone(2);
        cmd.assignDrone(1); // 重复
        assertThat(cmd.getAssignedDrones()).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    @DisplayName("assignDrones 批量分配")
    void assignDronesBatch() {
        EmergencyCommand cmd = newCommand();
        cmd.assignDrones(Set.of(1, 2, 3));
        assertThat(cmd.getAssignedDrones()).containsExactlyInAnyOrder(1, 2, 3);
    }

    @Test
    @DisplayName("getAssignedDrones 返回防御性拷贝")
    void assignedDronesDefensiveCopy() {
        EmergencyCommand cmd = newCommand();
        cmd.assignDrone(1);
        Set<Integer> drones = cmd.getAssignedDrones();
        drones.add(99); // 修改返回值不影响内部
        assertThat(cmd.getAssignedDrones()).doesNotContain(99);
    }

    @Test
    @DisplayName("appendExecutionLog 追加日志")
    void executionLog() {
        EmergencyCommand cmd = newCommand();
        cmd.appendExecutionLog("drone 1 takeoff");
        cmd.appendExecutionLog("drone 2 takeoff");
        List<String> log = cmd.getExecutionLog();
        assertThat(log).hasSize(2);
        assertThat(log.get(0)).isEqualTo("drone 1 takeoff");
    }

    @Test
    @DisplayName("getExecutionLog 返回防御性拷贝")
    void executionLogDefensiveCopy() {
        EmergencyCommand cmd = newCommand();
        cmd.appendExecutionLog("entry1");
        List<String> log = cmd.getExecutionLog();
        log.add("injected"); // 修改返回值不影响内部
        assertThat(cmd.getExecutionLog()).hasSize(1);
    }

    @Test
    @DisplayName("addPhaseTransition 记录阶段历史")
    void phaseHistory() {
        EmergencyCommand cmd = newCommand();
        cmd.addPhaseTransition(new EmergencyCommand.PhaseTransition(
                EmergencyCommandPhase.RECEIVED, EmergencyCommandPhase.ASSESSED,
                System.currentTimeMillis(), "operator1", "assess"));
        cmd.setCurrentPhase(EmergencyCommandPhase.ASSESSED);
        assertThat(cmd.getPhaseHistory()).hasSize(1);
        assertThat(cmd.getPhaseHistory().get(0).getFromPhase()).isEqualTo(EmergencyCommandPhase.RECEIVED);
        assertThat(cmd.getPhaseHistory().get(0).getToPhase()).isEqualTo(EmergencyCommandPhase.ASSESSED);
    }

    @Test
    @DisplayName("IncidentType.fromString 解析中文名和枚举名")
    void incidentTypeFromString() {
        assertThat(EmergencyCommand.IncidentType.fromString("火灾")).isEqualTo(EmergencyCommand.IncidentType.FIRE);
        assertThat(EmergencyCommand.IncidentType.fromString("FIRE")).isEqualTo(EmergencyCommand.IncidentType.FIRE);
        assertThat(EmergencyCommand.IncidentType.fromString("地震")).isEqualTo(EmergencyCommand.IncidentType.EARTHQUAKE);
        assertThat(EmergencyCommand.IncidentType.fromString("unknown")).isEqualTo(EmergencyCommand.IncidentType.OTHER);
        assertThat(EmergencyCommand.IncidentType.fromString(null)).isEqualTo(EmergencyCommand.IncidentType.OTHER);
    }

    @Test
    @DisplayName("Severity.fromString 解析名称和级别数字")
    void severityFromString() {
        assertThat(EmergencyCommand.Severity.fromString("CRITICAL")).isEqualTo(EmergencyCommand.Severity.CRITICAL);
        assertThat(EmergencyCommand.Severity.fromString("3")).isEqualTo(EmergencyCommand.Severity.CRITICAL);
        assertThat(EmergencyCommand.Severity.fromString("WARN")).isEqualTo(EmergencyCommand.Severity.WARN);
        assertThat(EmergencyCommand.Severity.fromString("2")).isEqualTo(EmergencyCommand.Severity.WARN);
        assertThat(EmergencyCommand.Severity.fromString("unknown")).isEqualTo(EmergencyCommand.Severity.INFO);
    }

    @Test
    @DisplayName("isClosed 在 CLOSED 阶段返回 true")
    void isClosed() {
        EmergencyCommand cmd = newCommand();
        assertThat(cmd.isClosed()).isFalse();
        cmd.setCurrentPhase(EmergencyCommandPhase.CLOSED);
        cmd.setClosedTimeMs(System.currentTimeMillis());
        assertThat(cmd.isClosed()).isTrue();
        assertThat(cmd.getClosedTimeMs()).isPositive();
    }
}
package io.aerofleet.cloud.voicecmd;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VoiceCommandExecutor} 单元测试。
 * <p>
 * 测试指令执行流程、高优先级指令确认机制和拒绝逻辑。
 */
@DisplayName("VoiceCommandExecutor 指令执行")
class VoiceCommandExecutorTest {

    private VoiceCommandExecutor newExecutor(DeviceRegistry registry) {
        return new VoiceCommandExecutor(registry);
    }

    private DeviceRegistry newRegistryWithDrone(int sysid) {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(sysid);
        return registry;
    }

    // --- 正常执行 ---

    @Test
    @DisplayName("普通优先级起飞指令直接执行")
    void execute_normalPriority_takeoff_executed() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceCommandExecutor executor = newExecutor(registry);

        ParsedCommand cmd = new ParsedCommand("无人机1号起飞");
        cmd.setAction(ParsedCommand.Action.TAKEOFF);
        cmd.setSysid(1);

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.EXECUTED);
        assertThat(result.getExecutedAction()).contains("takeoff");
        assertThat(result.getExecutedAction()).contains("sysid=1");
        assertThat(result.getCommandId()).isNotBlank();
    }

    @Test
    @DisplayName("普通优先级降落指令直接执行")
    void execute_normalPriority_land_executed() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceCommandExecutor executor = newExecutor(registry);

        ParsedCommand cmd = new ParsedCommand("降落");
        cmd.setAction(ParsedCommand.Action.LAND);
        cmd.setSysid(1);

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.EXECUTED);
        assertThat(result.getExecutedAction()).contains("land");
    }

    @Test
    @DisplayName("普通优先级返航指令直接执行")
    void execute_normalPriority_return_executed() {
        DeviceRegistry registry = newRegistryWithDrone(2);
        VoiceCommandExecutor executor = newExecutor(registry);

        ParsedCommand cmd = new ParsedCommand("返航");
        cmd.setAction(ParsedCommand.Action.RETURN);
        cmd.setSysid(2);

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.EXECUTED);
        assertThat(result.getExecutedAction()).contains("rtl");
        assertThat(result.getExecutedAction()).contains("sysid=2");
    }

    @Test
    @DisplayName("起飞指令带高度参数执行")
    void execute_takeoffWithAltitude_executed() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceCommandExecutor executor = newExecutor(registry);

        ParsedCommand cmd = new ParsedCommand("起飞高度50米");
        cmd.setAction(ParsedCommand.Action.TAKEOFF);
        cmd.setSysid(1);
        cmd.setAltitudeM(50.0);

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.EXECUTED);
        assertThat(result.getExecutedAction()).contains("takeoff");
        assertThat(result.getExecutedAction()).contains("alt=50.0m");
    }

    @Test
    @DisplayName("前往目标指令执行")
    void execute_flyTo_executed() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceCommandExecutor executor = newExecutor(registry);

        ParsedCommand cmd = new ParsedCommand("前往东门");
        cmd.setAction(ParsedCommand.Action.FLY_TO);
        cmd.setSysid(1);
        cmd.setTargetName("东门");

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.EXECUTED);
        assertThat(result.getExecutedAction()).contains("fly_to");
        assertThat(result.getExecutedAction()).contains("target=东门");
    }

    // --- 高优先级指令需确认 ---

    @Test
    @DisplayName("高优先级指令返回 PENDING_CONFIRMATION")
    void execute_highPriority_pendingConfirmation() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceCommandExecutor executor = newExecutor(registry);

        ParsedCommand cmd = new ParsedCommand("紧急起飞");
        cmd.setAction(ParsedCommand.Action.TAKEOFF);
        cmd.setSysid(1);
        cmd.setPriority(ParsedCommand.Priority.HIGH);

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.PENDING_CONFIRMATION);
        assertThat(result.getCommandId()).isNotBlank();
        assertThat(result.getMessage()).contains("确认");
        assertThat(result.getExecutedAction()).isNull();
    }

    @Test
    @DisplayName("高优先级指令确认后执行")
    void confirm_highPriority_executed() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceCommandExecutor executor = newExecutor(registry);

        ParsedCommand cmd = new ParsedCommand("紧急返航");
        cmd.setAction(ParsedCommand.Action.RETURN);
        cmd.setSysid(1);
        cmd.setPriority(ParsedCommand.Priority.HIGH);

        // 先执行，得到 pendingId
        ExecutionResult pendingResult = executor.execute(cmd);
        assertThat(pendingResult.getStatus()).isEqualTo(ExecutionResult.Status.PENDING_CONFIRMATION);
        String pendingId = pendingResult.getCommandId();

        // 确认后应执行
        ExecutionResult confirmed = executor.confirm(pendingId);

        assertThat(confirmed.getStatus()).isEqualTo(ExecutionResult.Status.EXECUTED);
        assertThat(confirmed.getCommandId()).isEqualTo(pendingId);
        assertThat(confirmed.getExecutedAction()).contains("rtl");
    }

    @Test
    @DisplayName("确认不存在的 pendingId 返回 FAILED")
    void confirm_nonExistentId_failed() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceCommandExecutor executor = newExecutor(registry);

        ExecutionResult result = executor.confirm("non-existent-id");

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.FAILED);
        assertThat(result.getMessage()).contains("未找到");
    }

    @Test
    @DisplayName("高优先级指令确认后从 pending 列表移除")
    void confirm_removesFromPending() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceCommandExecutor executor = newExecutor(registry);

        ParsedCommand cmd = new ParsedCommand("紧急降落");
        cmd.setAction(ParsedCommand.Action.LAND);
        cmd.setSysid(1);
        cmd.setPriority(ParsedCommand.Priority.HIGH);

        ExecutionResult pendingResult = executor.execute(cmd);
        String pendingId = pendingResult.getCommandId();

        assertThat(executor.getPending()).containsKey(pendingId);

        executor.confirm(pendingId);

        assertThat(executor.getPending()).doesNotContainKey(pendingId);
    }

    // --- 无人机不存在时拒绝 ---

    @Test
    @DisplayName("未注册无人机指令被拒绝")
    void execute_unknownDrone_rejected() {
        DeviceRegistry registry = new DeviceRegistry(); // 空注册表
        VoiceCommandExecutor executor = newExecutor(registry);

        ParsedCommand cmd = new ParsedCommand("起飞");
        cmd.setAction(ParsedCommand.Action.TAKEOFF);
        cmd.setSysid(99);

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.REJECTED);
        assertThat(result.getMessage()).contains("未注册");
    }

    // --- 指令历史 ---

    @Test
    @DisplayName("执行后的指令记录在历史中")
    void execute_recordedInHistory() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceCommandExecutor executor = newExecutor(registry);

        ParsedCommand cmd = new ParsedCommand("起飞");
        cmd.setAction(ParsedCommand.Action.TAKEOFF);
        cmd.setSysid(1);

        ExecutionResult result = executor.execute(cmd);

        assertThat(executor.getHistory()).containsKey(result.getCommandId());
        assertThat(executor.getHistory().get(result.getCommandId())).isEqualTo(result);
    }

    @Test
    @DisplayName("待确认指令出现在 pending 列表中")
    void execute_highPriority_appearsInPending() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceCommandExecutor executor = newExecutor(registry);

        ParsedCommand cmd = new ParsedCommand("紧急起飞");
        cmd.setAction(ParsedCommand.Action.TAKEOFF);
        cmd.setSysid(1);
        cmd.setPriority(ParsedCommand.Priority.HIGH);

        ExecutionResult result = executor.execute(cmd);

        assertThat(executor.getPending()).containsKey(result.getCommandId());
    }

    // --- 默认 sysid 行为 ---

    @Test
    @DisplayName("sysid 未指定时默认使用 sysid=1")
    void execute_defaultSysid() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceCommandExecutor executor = newExecutor(registry);

        ParsedCommand cmd = new ParsedCommand("起飞");
        cmd.setAction(ParsedCommand.Action.TAKEOFF);
        cmd.setSysid(-1); // 未指定

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.EXECUTED);
        assertThat(result.getExecutedAction()).contains("sysid=1");
    }
}
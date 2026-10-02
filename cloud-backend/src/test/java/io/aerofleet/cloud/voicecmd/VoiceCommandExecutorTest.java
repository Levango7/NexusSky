package io.aerofleet.cloud.voicecmd;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.cloud.mission.common.MissionItemRequest;
import io.aerofleet.cloud.mission.common.MissionUploadResult;
import io.aerofleet.mavlink.enums.MavEnums;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link VoiceCommandExecutor} 单元测试。
 * <p>
 * {@link DroneCommandService} 用 Mockito 桩化（全部 ACK ACCEPTED），
 * 验证指令 → 真实命令通路的映射、高优先级确认机制、拒绝与失败语义。
 */
@DisplayName("VoiceCommandExecutor 指令执行")
class VoiceCommandExecutorTest {

    // --- 夹具 ---

    private DeviceRegistry registryWithDrone(int sysid) {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(sysid);
        return registry;
    }

    /** 全部命令均 ACK ACCEPTED 的 DroneCommandService 桩。 */
    private DroneCommandService stubCommands() {
        DroneCommandService commands = mock(DroneCommandService.class);
        when(commands.takeoff(anyInt(), anyDouble())).thenReturn(MavEnums.MAV_RESULT_ACCEPTED);
        when(commands.rtl(anyInt())).thenReturn(MavEnums.MAV_RESULT_ACCEPTED);
        when(commands.startMission(anyInt())).thenReturn(MavEnums.MAV_RESULT_ACCEPTED);
        when(commands.command(anyInt(), anyInt(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat()))
                .thenReturn(MavEnums.MAV_RESULT_ACCEPTED);
        when(commands.toMissionItems(anyList(), anyInt())).thenReturn(List.of());
        when(commands.uploadMission(anyInt(), anyList())).thenReturn(MissionUploadResult.ok(1));
        return commands;
    }

    // --- 正常执行（真实通路映射） ---

    @Test
    @DisplayName("普通优先级起飞指令经 DroneCommandService.takeoff 下发，未指定高度用默认 10m")
    void execute_normalPriority_takeoff_executed() {
        DeviceRegistry registry = registryWithDrone(1);
        DroneCommandService commands = stubCommands();
        VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, commands);

        ParsedCommand cmd = new ParsedCommand("无人机1号起飞");
        cmd.setAction(ParsedCommand.Action.TAKEOFF);
        cmd.setSysid(1);

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.EXECUTED);
        assertThat(result.getExecutedAction()).contains("takeoff");
        assertThat(result.getExecutedAction()).contains("sysid=1");
        assertThat(result.getCommandId()).isNotBlank();
        verify(commands).takeoff(1, VoiceCommandExecutor.DEFAULT_TAKEOFF_ALT_M);
    }

    @Test
    @DisplayName("起飞指令带高度参数下发指定高度")
    void execute_takeoffWithAltitude_executed() {
        DeviceRegistry registry = registryWithDrone(1);
        DroneCommandService commands = stubCommands();
        VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, commands);

        ParsedCommand cmd = new ParsedCommand("起飞高度50米");
        cmd.setAction(ParsedCommand.Action.TAKEOFF);
        cmd.setSysid(1);
        cmd.setAltitudeM(50.0);

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.EXECUTED);
        assertThat(result.getExecutedAction()).contains("takeoff");
        assertThat(result.getExecutedAction()).contains("alt=50.0m");
        verify(commands).takeoff(1, 50.0);
    }

    @Test
    @DisplayName("降落指令经 MAV_CMD_NAV_LAND 下发")
    void execute_normalPriority_land_executed() {
        DeviceRegistry registry = registryWithDrone(1);
        DroneCommandService commands = stubCommands();
        VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, commands);

        ParsedCommand cmd = new ParsedCommand("降落");
        cmd.setAction(ParsedCommand.Action.LAND);
        cmd.setSysid(1);

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.EXECUTED);
        assertThat(result.getExecutedAction()).contains("land");
        verify(commands).command(1, MavEnums.MAV_CMD_NAV_LAND,
                0f, 0f, 0f, 0f, 0f, 0f, 0f);
    }

    @Test
    @DisplayName("返航指令经 DroneCommandService.rtl 下发")
    void execute_normalPriority_return_executed() {
        DeviceRegistry registry = registryWithDrone(2);
        DroneCommandService commands = stubCommands();
        VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, commands);

        ParsedCommand cmd = new ParsedCommand("返航");
        cmd.setAction(ParsedCommand.Action.RETURN);
        cmd.setSysid(2);

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.EXECUTED);
        assertThat(result.getExecutedAction()).contains("rtl");
        assertThat(result.getExecutedAction()).contains("sysid=2");
        verify(commands).rtl(2);
    }

    @Test
    @DisplayName("拍照指令经 MAV_CMD_IMAGE_START_CAPTURE（单张）下发")
    void execute_photo_executed() {
        DeviceRegistry registry = registryWithDrone(1);
        DroneCommandService commands = stubCommands();
        VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, commands);

        ParsedCommand cmd = new ParsedCommand("拍照");
        cmd.setAction(ParsedCommand.Action.PHOTO);
        cmd.setSysid(1);

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.EXECUTED);
        verify(commands).command(1, MavEnums.MAV_CMD_IMAGE_START_CAPTURE,
                0f, 1f, 0f, 0f, 0f, 0f, 0f);
    }

    @Test
    @DisplayName("前往目标（带经纬度与高度）经单航点任务上传 + MISSION_START 下发")
    void execute_flyTo_withCoordinates_executed() {
        DeviceRegistry registry = registryWithDrone(1);
        DroneCommandService commands = stubCommands();
        VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, commands);

        ParsedCommand cmd = new ParsedCommand("前往北纬22.59东经113.93，高度50米");
        cmd.setAction(ParsedCommand.Action.FLY_TO);
        cmd.setSysid(1);
        cmd.setTargetLat(22.59);
        cmd.setTargetLon(113.93);
        cmd.setAltitudeM(50.0);

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.EXECUTED);
        assertThat(result.getExecutedAction()).contains("fly_to");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MissionItemRequest>> itemsCaptor =
                ArgumentCaptor.forClass((Class<List<MissionItemRequest>>) (Class<?>) List.class);
        verify(commands).toMissionItems(itemsCaptor.capture(), eq(1));
        List<MissionItemRequest> items = itemsCaptor.getValue();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).cmd()).isEqualTo("waypoint");
        assertThat(items.get(0).lat()).isEqualTo(22.59);
        assertThat(items.get(0).lon()).isEqualTo(113.93);
        assertThat(items.get(0).alt()).isEqualTo(50.0);
        verify(commands).uploadMission(eq(1), anyList());
        verify(commands).startMission(1);
    }

    @Test
    @DisplayName("前往目标只有地名（无坐标）时拒绝，不调用任务上传")
    void execute_flyTo_withoutCoordinates_rejected() {
        DeviceRegistry registry = registryWithDrone(1);
        DroneCommandService commands = stubCommands();
        VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, commands);

        ParsedCommand cmd = new ParsedCommand("前往东门");
        cmd.setAction(ParsedCommand.Action.FLY_TO);
        cmd.setSysid(1);
        cmd.setTargetName("东门");

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.REJECTED);
        assertThat(result.getMessage()).contains("地名");
        verify(commands, never()).uploadMission(anyInt(), anyList());
        verify(commands, never()).startMission(anyInt());
    }

    @Test
    @DisplayName("前往目标有坐标但缺高度时拒绝")
    void execute_flyTo_withoutAltitude_rejected() {
        DeviceRegistry registry = registryWithDrone(1);
        DroneCommandService commands = stubCommands();
        VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, commands);

        ParsedCommand cmd = new ParsedCommand("前往目标点");
        cmd.setAction(ParsedCommand.Action.FLY_TO);
        cmd.setSysid(1);
        cmd.setTargetLat(22.59);
        cmd.setTargetLon(113.93);

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.REJECTED);
        assertThat(result.getMessage()).contains("高度");
        verify(commands, never()).uploadMission(anyInt(), anyList());
    }

    // --- 无真实命令通路的动作：明确拒绝 ---

    @Test
    @DisplayName("悬停指令无命令通路，拒绝且不下发任何命令")
    void execute_hover_rejected() {
        DeviceRegistry registry = registryWithDrone(1);
        DroneCommandService commands = stubCommands();
        VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, commands);

        ParsedCommand cmd = new ParsedCommand("悬停");
        cmd.setAction(ParsedCommand.Action.HOVER);
        cmd.setSysid(1);

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.REJECTED);
        assertThat(result.getMessage()).contains("无对应命令通路");
        verifyNoInteractions(commands);
    }

    @Test
    @DisplayName("录像指令无命令通路，拒绝")
    void execute_record_rejected() {
        DeviceRegistry registry = registryWithDrone(1);
        DroneCommandService commands = stubCommands();
        VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, commands);

        ParsedCommand cmd = new ParsedCommand("录像");
        cmd.setAction(ParsedCommand.Action.RECORD);
        cmd.setSysid(1);

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.REJECTED);
        assertThat(result.getMessage()).contains("录像");
        verifyNoInteractions(commands);
    }

    @Test
    @DisplayName("设置高度/速度指令无命令通路，拒绝")
    void execute_setAltitudeSpeed_rejected() {
        DeviceRegistry registry = registryWithDrone(1);
        DroneCommandService commands = stubCommands();
        VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, commands);

        ParsedCommand alt = new ParsedCommand("高度80米");
        alt.setAction(ParsedCommand.Action.SET_ALTITUDE);
        alt.setSysid(1);
        assertThat(executor.execute(alt).getStatus()).isEqualTo(ExecutionResult.Status.REJECTED);

        ParsedCommand speed = new ParsedCommand("速度5米");
        speed.setAction(ParsedCommand.Action.SET_SPEED);
        speed.setSysid(1);
        assertThat(executor.execute(speed).getStatus()).isEqualTo(ExecutionResult.Status.REJECTED);

        verifyNoInteractions(commands);
    }

    // --- 下发失败映射 FAILED ---

    @Test
    @DisplayName("飞控拒绝（非 ACCEPTED ACK）映射为 FAILED")
    void execute_ackDenied_failed() {
        DeviceRegistry registry = registryWithDrone(1);
        DroneCommandService commands = stubCommands();
        when(commands.rtl(1)).thenReturn(MavEnums.MAV_RESULT_DENIED);
        VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, commands);

        ParsedCommand cmd = new ParsedCommand("返航");
        cmd.setAction(ParsedCommand.Action.RETURN);
        cmd.setSysid(1);

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.FAILED);
        assertThat(result.getMessage()).contains("被飞控拒绝");
        assertThat(result.getExecutedAction()).contains("rtl");
    }

    @Test
    @DisplayName("命令超时（CommandException）映射为 FAILED")
    void execute_commandTimeout_failed() {
        DeviceRegistry registry = registryWithDrone(1);
        DroneCommandService commands = stubCommands();
        when(commands.rtl(anyInt())).thenThrow(new DroneCommandService.CommandException(
                "command 20 timed out waiting for COMMAND_ACK", -1));
        VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, commands);

        ParsedCommand cmd = new ParsedCommand("返航");
        cmd.setAction(ParsedCommand.Action.RETURN);
        cmd.setSysid(1);

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.FAILED);
        assertThat(result.getMessage()).contains("下发失败");
    }

    @Test
    @DisplayName("目标点任务上传失败映射为 FAILED")
    void execute_flyTo_uploadFailed() {
        DeviceRegistry registry = registryWithDrone(1);
        DroneCommandService commands = stubCommands();
        when(commands.uploadMission(anyInt(), anyList()))
                .thenReturn(MissionUploadResult.failure("mission upload timed out"));
        VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, commands);

        ParsedCommand cmd = new ParsedCommand("前往目标点");
        cmd.setAction(ParsedCommand.Action.FLY_TO);
        cmd.setSysid(1);
        cmd.setTargetLat(22.59);
        cmd.setTargetLon(113.93);
        cmd.setAltitudeM(50.0);

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.FAILED);
        assertThat(result.getMessage()).contains("任务上传失败");
        verify(commands, never()).startMission(anyInt());
    }

    // --- 高优先级指令需确认 ---

    @Test
    @DisplayName("高优先级指令返回 PENDING_CONFIRMATION，不下发")
    void execute_highPriority_pendingConfirmation() {
        DeviceRegistry registry = registryWithDrone(1);
        DroneCommandService commands = stubCommands();
        VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, commands);

        ParsedCommand cmd = new ParsedCommand("紧急起飞");
        cmd.setAction(ParsedCommand.Action.TAKEOFF);
        cmd.setSysid(1);
        cmd.setPriority(ParsedCommand.Priority.HIGH);

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.PENDING_CONFIRMATION);
        assertThat(result.getCommandId()).isNotBlank();
        assertThat(result.getMessage()).contains("确认");
        assertThat(result.getExecutedAction()).isNull();
        verifyNoInteractions(commands);
    }

    @Test
    @DisplayName("高优先级指令确认后真实下发")
    void confirm_highPriority_executed() {
        DeviceRegistry registry = registryWithDrone(1);
        DroneCommandService commands = stubCommands();
        VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, commands);

        ParsedCommand cmd = new ParsedCommand("紧急返航");
        cmd.setAction(ParsedCommand.Action.RETURN);
        cmd.setSysid(1);
        cmd.setPriority(ParsedCommand.Priority.HIGH);

        ExecutionResult pendingResult = executor.execute(cmd);
        assertThat(pendingResult.getStatus()).isEqualTo(ExecutionResult.Status.PENDING_CONFIRMATION);
        String pendingId = pendingResult.getCommandId();

        ExecutionResult confirmed = executor.confirm(pendingId);

        assertThat(confirmed.getStatus()).isEqualTo(ExecutionResult.Status.EXECUTED);
        assertThat(confirmed.getCommandId()).isEqualTo(pendingId);
        assertThat(confirmed.getExecutedAction()).contains("rtl");
        verify(commands).rtl(1);
    }

    @Test
    @DisplayName("确认不存在的 pendingId 返回 FAILED")
    void confirm_nonExistentId_failed() {
        DeviceRegistry registry = registryWithDrone(1);
        VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, stubCommands());

        ExecutionResult result = executor.confirm("non-existent-id");

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.FAILED);
        assertThat(result.getMessage()).contains("未找到");
    }

    @Test
    @DisplayName("高优先级指令确认后从 pending 列表移除")
    void confirm_removesFromPending() {
        DeviceRegistry registry = registryWithDrone(1);
        DroneCommandService commands = stubCommands();
        VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, commands);

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

    @Test
    @DisplayName("pending 期间无人机注销：确认时重校验并拒绝")
    void confirm_droneUnregistered_rejected() {
        DeviceRegistry registry = registryWithDrone(1);
        DroneCommandService commands = stubCommands();
        VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, commands);

        ParsedCommand cmd = new ParsedCommand("紧急返航");
        cmd.setAction(ParsedCommand.Action.RETURN);
        cmd.setSysid(1);
        cmd.setPriority(ParsedCommand.Priority.HIGH);
        String pendingId = executor.execute(cmd).getCommandId();

        registry.deregister(1);

        ExecutionResult result = executor.confirm(pendingId);
        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.REJECTED);
        verifyNoInteractions(commands);
    }

    // --- 无人机不存在时拒绝 ---

    @Test
    @DisplayName("未注册无人机指令被拒绝")
    void execute_unknownDrone_rejected() {
        DeviceRegistry registry = new DeviceRegistry(); // 空注册表
        DroneCommandService commands = stubCommands();
        VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, commands);

        ParsedCommand cmd = new ParsedCommand("起飞");
        cmd.setAction(ParsedCommand.Action.TAKEOFF);
        cmd.setSysid(99);

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.REJECTED);
        assertThat(result.getMessage()).contains("未注册");
        verifyNoInteractions(commands);
    }

    // --- 指令历史 ---

    @Test
    @DisplayName("执行后的指令记录在历史中")
    void execute_recordedInHistory() {
        DeviceRegistry registry = registryWithDrone(1);
        VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, stubCommands());

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
        DeviceRegistry registry = registryWithDrone(1);
        VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, stubCommands());

        ParsedCommand cmd = new ParsedCommand("紧急起飞");
        cmd.setAction(ParsedCommand.Action.TAKEOFF);
        cmd.setSysid(1);
        cmd.setPriority(ParsedCommand.Priority.HIGH);

        ExecutionResult result = executor.execute(cmd);

        assertThat(executor.getPending()).containsKey(result.getCommandId());
        assertThat(executor.hasPending(result.getCommandId())).isTrue();
    }

    // --- 默认 sysid 行为 ---

    @Test
    @DisplayName("sysid 未指定时默认使用 sysid=1")
    void execute_defaultSysid() {
        DeviceRegistry registry = registryWithDrone(1);
        DroneCommandService commands = stubCommands();
        VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, commands);

        ParsedCommand cmd = new ParsedCommand("起飞");
        cmd.setAction(ParsedCommand.Action.TAKEOFF);
        cmd.setSysid(-1); // 未指定

        ExecutionResult result = executor.execute(cmd);

        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.EXECUTED);
        assertThat(result.getExecutedAction()).contains("sysid=1");
        verify(commands).takeoff(1, VoiceCommandExecutor.DEFAULT_TAKEOFF_ALT_M);
    }
}

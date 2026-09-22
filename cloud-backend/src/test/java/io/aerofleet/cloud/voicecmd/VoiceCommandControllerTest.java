package io.aerofleet.cloud.voicecmd;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.api.ApiExceptionHandler;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link VoiceCommandController} REST 端点测试。
 * <p>
 * 使用 MockMvc standaloneSetup 测试所有端点，无需 Spring 上下文。
 */
@DisplayName("VoiceCommandController REST API")
class VoiceCommandControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private DeviceRegistry registry;
    private VoiceCommandParser parser;
    private VoiceCommandExecutor executor;
    private VoiceBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry();
        DroneSnapshot drone = registry.registerIfAbsent(1);
        drone.mode = "AUTO";
        drone.battery = 75;
        drone.relativeAlt = 100.0;
        drone.online = true;
        drone.lastHeartbeatMs = System.currentTimeMillis();

        parser = new VoiceCommandParser();
        executor = new VoiceCommandExecutor(registry);
        broadcaster = new VoiceBroadcaster(registry);

        VoiceCommandController controller = new VoiceCommandController(
                parser, executor, broadcaster, registry);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    // --- POST /parse ---

    @Test
    @DisplayName("POST /parse 解析中文起飞指令")
    void parse_takeoff_cn() throws Exception {
        mockMvc.perform(post("/api/v1/voice-cmd/parse")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("text", "起飞"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("TAKEOFF"))
                .andExpect(jsonPath("$.rawText").value("起飞"));
    }

    @Test
    @DisplayName("POST /parse 解析复合指令")
    void parse_complex() throws Exception {
        mockMvc.perform(post("/api/v1/voice-cmd/parse")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("text", "无人机1号起飞，高度50米，前往东门侦察"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("TAKEOFF"))
                .andExpect(jsonPath("$.sysid").value(1))
                .andExpect(jsonPath("$.altitudeM").value(50.0));
    }

    @Test
    @DisplayName("POST /parse 空文本返回 400")
    void parse_emptyText_badRequest() throws Exception {
        mockMvc.perform(post("/api/v1/voice-cmd/parse")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("text", ""))))
                .andExpect(status().isBadRequest());
    }

    // --- POST /execute ---

    @Test
    @DisplayName("POST /execute 执行普通指令")
    void execute_normal() throws Exception {
        ParsedCommand cmd = new ParsedCommand("起飞");
        cmd.setAction(ParsedCommand.Action.TAKEOFF);
        cmd.setSysid(1);

        mockMvc.perform(post("/api/v1/voice-cmd/execute")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(cmd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.executedAction").exists());
    }

    @Test
    @DisplayName("POST /execute 高优先级指令返回 PENDING_CONFIRMATION")
    void execute_highPriority() throws Exception {
        ParsedCommand cmd = new ParsedCommand("紧急起飞");
        cmd.setAction(ParsedCommand.Action.TAKEOFF);
        cmd.setSysid(1);
        cmd.setPriority(ParsedCommand.Priority.HIGH);

        mockMvc.perform(post("/api/v1/voice-cmd/execute")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(cmd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.commandId").exists());
    }

    @Test
    @DisplayName("POST /execute 未注册无人机返回 REJECTED")
    void execute_unknownDrone() throws Exception {
        ParsedCommand cmd = new ParsedCommand("起飞");
        cmd.setAction(ParsedCommand.Action.TAKEOFF);
        cmd.setSysid(99);

        mockMvc.perform(post("/api/v1/voice-cmd/execute")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(cmd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    // --- POST /confirm/{pendingId} ---

    @Test
    @DisplayName("POST /confirm/{pendingId} 确认高优先级指令")
    void confirm_valid() throws Exception {
        // 先执行一个高优先级指令获取 pendingId
        ParsedCommand cmd = new ParsedCommand("紧急返航");
        cmd.setAction(ParsedCommand.Action.RETURN);
        cmd.setSysid(1);
        cmd.setPriority(ParsedCommand.Priority.HIGH);
        ExecutionResult pendingResult = executor.execute(cmd);
        String pendingId = pendingResult.getCommandId();

        mockMvc.perform(post("/api/v1/voice-cmd/confirm/" + pendingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.commandId").value(pendingId));
    }

    @Test
    @DisplayName("POST /confirm/{pendingId} 不存在的 pendingId 返回 404")
    void confirm_nonExistent() throws Exception {
        mockMvc.perform(post("/api/v1/voice-cmd/confirm/non-existent-id"))
                .andExpect(status().isNotFound());
    }

    // --- POST /broadcast/{sysid} ---

    @Test
    @DisplayName("POST /broadcast/{sysid} 发送播报")
    void broadcast_sent() throws Exception {
        mockMvc.perform(post("/api/v1/voice-cmd/broadcast/1")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("text", "请注意前方障碍物"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.text").value("请注意前方障碍物"))
                .andExpect(jsonPath("$.sysid").value(1));
    }

    @Test
    @DisplayName("POST /broadcast/{sysid} 未注册无人机返回 404")
    void broadcast_unknownDrone() throws Exception {
        mockMvc.perform(post("/api/v1/voice-cmd/broadcast/99")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("text", "测试"))))
                .andExpect(status().isNotFound());
    }

    // --- GET /broadcast/{sysid}/status ---

    @Test
    @DisplayName("GET /broadcast/{sysid}/status 获取状态播报")
    void statusBroadcast() throws Exception {
        mockMvc.perform(get("/api/v1/voice-cmd/broadcast/1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").exists())
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("无人机1")));
    }

    @Test
    @DisplayName("GET /broadcast/{sysid}/status 未注册无人机返回 404")
    void statusBroadcast_unknownDrone() throws Exception {
        mockMvc.perform(get("/api/v1/voice-cmd/broadcast/99/status"))
                .andExpect(status().isNotFound());
    }

    // --- GET /broadcast/{sysid}/alert ---

    @Test
    @DisplayName("GET /broadcast/{sysid}/alert 获取告警播报")
    void alertBroadcast() throws Exception {
        mockMvc.perform(get("/api/v1/voice-cmd/broadcast/1/alert"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").exists())
                .andExpect(jsonPath("$.alertType").exists());
    }

    @Test
    @DisplayName("GET /broadcast/{sysid}/alert 未注册无人机返回 404")
    void alertBroadcast_unknownDrone() throws Exception {
        mockMvc.perform(get("/api/v1/voice-cmd/broadcast/99/alert"))
                .andExpect(status().isNotFound());
    }

    // --- GET /history ---

    @Test
    @DisplayName("GET /history 查询指令历史")
    void history() throws Exception {
        // 先执行一条指令
        ParsedCommand cmd = new ParsedCommand("起飞");
        cmd.setAction(ParsedCommand.Action.TAKEOFF);
        cmd.setSysid(1);
        executor.execute(cmd);

        mockMvc.perform(get("/api/v1/voice-cmd/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // --- GET /pending ---

    @Test
    @DisplayName("GET /pending 查询待确认指令")
    void pending() throws Exception {
        // 先执行一个高优先级指令
        ParsedCommand cmd = new ParsedCommand("紧急起飞");
        cmd.setAction(ParsedCommand.Action.TAKEOFF);
        cmd.setSysid(1);
        cmd.setPriority(ParsedCommand.Priority.HIGH);
        executor.execute(cmd);

        mockMvc.perform(get("/api/v1/voice-cmd/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].pendingId").exists());
    }

    @Test
    @DisplayName("GET /pending 无待确认指令返回空数组")
    void pending_empty() throws Exception {
        mockMvc.perform(get("/api/v1/voice-cmd/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }
}
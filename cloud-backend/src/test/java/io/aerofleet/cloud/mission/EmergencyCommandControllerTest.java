package io.aerofleet.cloud.mission;

import io.aerofleet.cloud.api.ApiExceptionHandler;
import io.aerofleet.cloud.api.EmergencyOrchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link EmergencyCommandController} REST API 单测（MockMvc standaloneSetup）。
 * <p>
 * 直接实例化依赖链（无 Spring 上下文），使用 MockMvc 验证 HTTP 请求/响应。
 * 依赖链：EmergencyCommandWorkflow + EmergencyOrchService(null pusher)
 *         + OneClickEmergencyResponse + EmergencyCommandController
 */
@DisplayName("EmergencyCommandController REST API (MockMvc)")
class EmergencyCommandControllerTest {

    private MockMvc mockMvc;
    private EmergencyCommandWorkflow workflow;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        workflow = new EmergencyCommandWorkflow();
        EmergencyOrchService orchService = new EmergencyOrchService(null);
        OneClickEmergencyResponse oneClick = new OneClickEmergencyResponse(workflow, orchService);
        EmergencyCommandController controller = new EmergencyCommandController(workflow, oneClick);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private String createBody() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("incidentType", "火灾");
        body.put("severity", "CRITICAL");
        body.put("lat", 39.9);
        body.put("lon", 116.3);
        body.put("alt", 50.0);
        body.put("description", "某地火灾");
        body.put("reporterName", "张三");
        body.put("reporterContact", "13800000000");
        return json.writeValueAsString(body);
    }

    /** 创建命令并返回 ID。 */
    private String createCommand() throws Exception {
        String body = createBody();
        String response = mockMvc.perform(post("/api/emergency-command")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asText();
    }

    // =====================================================================
    // 创建
    // =====================================================================

    @Test
    @DisplayName("POST /api/emergency-command 创建命令返回 200")
    void createReturns200() throws Exception {
        mockMvc.perform(post("/api/emergency-command")
                        .contentType("application/json")
                        .content(createBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.currentPhase").value("RECEIVED"))
                .andExpect(jsonPath("$.incidentType").value("FIRE"))
                .andExpect(jsonPath("$.severity").value("CRITICAL"));
    }

    @Test
    @DisplayName("POST 创建命令包含位置和报告人信息")
    void createContainsLocationAndReporter() throws Exception {
        mockMvc.perform(post("/api/emergency-command")
                        .contentType("application/json")
                        .content(createBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location.lat").value(39.9))
                .andExpect(jsonPath("$.location.lon").value(116.3))
                .andExpect(jsonPath("$.reporterName").value("张三"))
                .andExpect(jsonPath("$.reporterContact").value("13800000000"));
    }

    // =====================================================================
    // 列表
    // =====================================================================

    @Test
    @DisplayName("GET /api/emergency-command 列出所有命令")
    void listAll() throws Exception {
        createCommand();
        createCommand();
        mockMvc.perform(get("/api/emergency-command"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.commands[0].id").exists());
    }

    @Test
    @DisplayName("GET /api/emergency-command?phase=RECEIVED 按阶段筛选")
    void listByPhase() throws Exception {
        String id = createCommand();
        // 转移到 ASSESSED
        Map<String, Object> assessBody = new LinkedHashMap<>();
        assessBody.put("assessmentResult", "需要 6 架");
        assessBody.put("operator", "op1");
        mockMvc.perform(post("/api/emergency-command/" + id + "/assess")
                .contentType("application/json")
                .content(json.writeValueAsString(assessBody)));

        mockMvc.perform(get("/api/emergency-command").param("phase", "RECEIVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get("/api/emergency-command").param("phase", "ASSESSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    // =====================================================================
    // 详情
    // =====================================================================

    @Test
    @DisplayName("GET /api/emergency-command/{id} 返回命令详情")
    void getDetail() throws Exception {
        String id = createCommand();
        mockMvc.perform(get("/api/emergency-command/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.currentPhase").value("RECEIVED"));
    }

    @Test
    @DisplayName("GET /api/emergency-command/{id} 不存在返回 404")
    void getNotFound() throws Exception {
        mockMvc.perform(get("/api/emergency-command/nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    // =====================================================================
    // 研判
    // =====================================================================

    @Test
    @DisplayName("POST /{id}/assess 研判成功")
    void assessSuccess() throws Exception {
        String id = createCommand();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assessmentResult", "研判完成");
        body.put("operator", "op1");
        mockMvc.perform(post("/api/emergency-command/" + id + "/assess")
                        .contentType("application/json")
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPhase").value("ASSESSED"))
                .andExpect(jsonPath("$.assessmentResult").value("研判完成"));
    }

    @Test
    @DisplayName("POST /{id}/assess 非法阶段返回 400")
    void assessIllegalPhase() throws Exception {
        String id = createCommand();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assessmentResult", "r1");
        body.put("operator", "op1");
        // 第一次成功
        mockMvc.perform(post("/api/emergency-command/" + id + "/assess")
                .contentType("application/json")
                .content(json.writeValueAsString(body)))
                .andExpect(status().isOk());
        // 第二次非法
        mockMvc.perform(post("/api/emergency-command/" + id + "/assess")
                        .contentType("application/json")
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    // =====================================================================
    // 部署
    // =====================================================================

    @Test
    @DisplayName("POST /{id}/deploy 部署成功")
    void deploySuccess() throws Exception {
        String id = createCommand();
        Map<String, Object> assessBody = new LinkedHashMap<>();
        assessBody.put("assessmentResult", "r");
        assessBody.put("operator", "op");
        mockMvc.perform(post("/api/emergency-command/" + id + "/assess")
                .contentType("application/json")
                .content(json.writeValueAsString(assessBody)));

        Map<String, Object> deployBody = new LinkedHashMap<>();
        deployBody.put("planName", "fire-plan");
        deployBody.put("strategy", "COMMAND_RELAY");
        deployBody.put("estimatedDurationMin", 120);
        deployBody.put("communicationRelay", "mesh");
        deployBody.put("operator", "op");
        mockMvc.perform(post("/api/emergency-command/" + id + "/deploy")
                        .contentType("application/json")
                        .content(json.writeValueAsString(deployBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPhase").value("DEPLOYED"))
                .andExpect(jsonPath("$.deploymentPlan.planName").value("fire-plan"));
    }

    // =====================================================================
    // 执行
    // =====================================================================

    @Test
    @DisplayName("POST /{id}/execute 开始执行成功")
    void executeSuccess() throws Exception {
        String id = createCommand();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operator", "op");
        mockMvc.perform(post("/api/emergency-command/" + id + "/assess")
                .contentType("application/json").content(json.writeValueAsString(body)));
        mockMvc.perform(post("/api/emergency-command/" + id + "/deploy")
                .contentType("application/json").content(json.writeValueAsString(body)));
        mockMvc.perform(post("/api/emergency-command/" + id + "/execute")
                        .contentType("application/json")
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPhase").value("EXECUTING"));
    }

    // =====================================================================
    // 评估 + 关闭
    // =====================================================================

    @Test
    @DisplayName("POST /{id}/evaluate 评估成功")
    void evaluateSuccess() throws Exception {
        String id = createCommand();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operator", "op");
        mockMvc.perform(post("/api/emergency-command/" + id + "/assess")
                .contentType("application/json").content(json.writeValueAsString(body)));
        mockMvc.perform(post("/api/emergency-command/" + id + "/deploy")
                .contentType("application/json").content(json.writeValueAsString(body)));
        mockMvc.perform(post("/api/emergency-command/" + id + "/execute")
                .contentType("application/json").content(json.writeValueAsString(body)));

        body.put("evaluationResult", "覆盖率 95%");
        mockMvc.perform(post("/api/emergency-command/" + id + "/evaluate")
                        .contentType("application/json")
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPhase").value("EVALUATED"))
                .andExpect(jsonPath("$.evaluationResult").value("覆盖率 95%"));
    }

    @Test
    @DisplayName("POST /{id}/close 关闭成功")
    void closeSuccess() throws Exception {
        String id = createCommand();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operator", "op");
        mockMvc.perform(post("/api/emergency-command/" + id + "/assess")
                .contentType("application/json").content(json.writeValueAsString(body)));
        mockMvc.perform(post("/api/emergency-command/" + id + "/deploy")
                .contentType("application/json").content(json.writeValueAsString(body)));
        mockMvc.perform(post("/api/emergency-command/" + id + "/execute")
                .contentType("application/json").content(json.writeValueAsString(body)));
        body.put("evaluationResult", "good");
        mockMvc.perform(post("/api/emergency-command/" + id + "/evaluate")
                .contentType("application/json").content(json.writeValueAsString(body)));

        body.put("summary", "任务完成");
        mockMvc.perform(post("/api/emergency-command/" + id + "/close")
                        .contentType("application/json")
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPhase").value("CLOSED"))
                .andExpect(jsonPath("$.summary").value("任务完成"))
                .andExpect(jsonPath("$.closedTimeMs").isNumber());
    }

    // =====================================================================
    // 历史
    // =====================================================================

    @Test
    @DisplayName("GET /{id}/history 返回阶段转移历史")
    void history() throws Exception {
        String id = createCommand();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operator", "op");
        mockMvc.perform(post("/api/emergency-command/" + id + "/assess")
                .contentType("application/json").content(json.writeValueAsString(body)));

        mockMvc.perform(get("/api/emergency-command/" + id + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commandId").value(id))
                .andExpect(jsonPath("$.currentPhase").value("ASSESSED"))
                .andExpect(jsonPath("$.history[0].fromPhase").value("RECEIVED"))
                .andExpect(jsonPath("$.history[0].toPhase").value("ASSESSED"))
                .andExpect(jsonPath("$.history[0].operatorName").value("op"));
    }

    @Test
    @DisplayName("GET /{id}/history 不存在返回 404")
    void historyNotFound() throws Exception {
        mockMvc.perform(get("/api/emergency-command/nonexistent/history"))
                .andExpect(status().isNotFound());
    }

    // =====================================================================
    // 一键应急响应
    // =====================================================================

    @Test
    @DisplayName("POST /{id}/one-click 一键应急响应成功")
    void oneClickSuccess() throws Exception {
        String id = createCommand();
        mockMvc.perform(post("/api/emergency-command/" + id + "/one-click"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPhase").value("EXECUTING"))
                .andExpect(jsonPath("$.assessmentResult").exists())
                .andExpect(jsonPath("$.deploymentPlan").exists());
    }

    @Test
    @DisplayName("POST /{id}/one-click 命令不存在返回 400")
    void oneClickNotFound() throws Exception {
        mockMvc.perform(post("/api/emergency-command/nonexistent/one-click"))
                .andExpect(status().isBadRequest());
    }
}
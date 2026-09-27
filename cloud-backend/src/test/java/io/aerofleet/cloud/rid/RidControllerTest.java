package io.aerofleet.cloud.rid;

import io.aerofleet.cloud.rid.model.BasicIdData;
import io.aerofleet.cloud.rid.model.OperatorIdData;
import io.aerofleet.cloud.rid.model.RidComplianceState;
import io.aerofleet.cloud.rid.model.RidSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link RidController} REST API 单测（MockMvc standaloneSetup）。
 * <p>
 * 直接实例化依赖链（无 Spring 上下文），使用 MockMvc 验证 HTTP 请求/响应。
 * 依赖链：RidConfig + RidStateManager + RidController
 * <p>
 * 风格与 {@code GeofenceControllerTest} 一致。
 */
@DisplayName("RidController REST API (MockMvc)")
class RidControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RidConfig config;
    private RidStateManager stateManager;
    private RidController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        config = new RidConfig();
        stateManager = new RidStateManager(config);
        controller = new RidController(stateManager, config);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static String json(Map<String, Object> body) throws Exception {
        return MAPPER.writeValueAsString(body);
    }

    // =====================================================================
    // GET /status/{sysid}
    // =====================================================================

    @Nested
    @DisplayName("GET /status/{sysid} RID 状态详情")
    class GetStatusEndpoint {

        @Test
        @DisplayName("存在的 sysid 返回快照")
        void getStatus_existing() throws Exception {
            stateManager.updateBasicId(1, new BasicIdData(1, 2, "UAS-001"));

            mockMvc.perform(get("/api/v1/rid/status/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sysid").value(1))
                    .andExpect(jsonPath("$.ridStatus").value("BROADCASTING"))
                    .andExpect(jsonPath("$.basicId.uasId").value("UAS-001"));
        }

        @Test
        @DisplayName("不存在的 sysid 返回 404")
        void getStatus_notFound_returns404() throws Exception {
            mockMvc.perform(get("/api/v1/rid/status/99"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").exists());
        }
    }

    // =====================================================================
    // GET /status
    // =====================================================================

    @Nested
    @DisplayName("GET /status RID 状态列表")
    class GetAllStatusEndpoint {

        @Test
        @DisplayName("返回全部状态列表")
        void getAllStatus_returnsList() throws Exception {
            stateManager.updateBasicId(1, new BasicIdData(1, 2, "UAS-001"));
            stateManager.updateBasicId(2, new BasicIdData(1, 2, "UAS-002"));

            mockMvc.perform(get("/api/v1/rid/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].sysid").value(1))
                    .andExpect(jsonPath("$[1].sysid").value(2));
        }

        @Test
        @DisplayName("空列表返回空数组")
        void getAllStatus_empty() throws Exception {
            mockMvc.perform(get("/api/v1/rid/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        }
    }

    // =====================================================================
    // POST /config
    // =====================================================================

    @Nested
    @DisplayName("POST /config 更新配置")
    class UpdateConfigEndpoint {

        @Test
        @DisplayName("更新配置返回脱敏后的 operatorId")
        void updateConfig_returnsDesensitizedOperatorId() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("operatorId", "OPERATOR123456");
            body.put("operatorLat", 39.9);
            body.put("operatorLon", 116.3);
            body.put("defaultSelfId", "My drone");

            mockMvc.perform(post("/api/v1/rid/config")
                            .contentType("application/json")
                            .content(json(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.operatorId").value("OPER**********"))
                    .andExpect(jsonPath("$.operatorLat").value(39.9))
                    .andExpect(jsonPath("$.operatorLon").value(116.3))
                    .andExpect(jsonPath("$.defaultSelfId").value("My drone"));
        }

        @Test
        @DisplayName("更新部分配置（仅 operatorId）")
        void updateConfig_partialUpdate() throws Exception {
            // 先设置初始值
            config.setOperatorId("OLDOPERATOR");
            config.setOperatorLat(10.0);
            config.setOperatorLon(20.0);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("operatorId", "NEWOPERATOR123");

            mockMvc.perform(post("/api/v1/rid/config")
                            .contentType("application/json")
                            .content(json(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.operatorId").value("NEWO**********"))
                    .andExpect(jsonPath("$.operatorLat").value(10.0))
                    .andExpect(jsonPath("$.operatorLon").value(20.0));
        }
    }

    // =====================================================================
    // POST /broadcast/{sysid}/start
    // =====================================================================

    @Nested
    @DisplayName("POST /broadcast/{sysid}/start 启动广播")
    class StartBroadcastEndpoint {

        @Test
        @DisplayName("启动广播返回成功")
        void startBroadcast_success() throws Exception {
            mockMvc.perform(post("/api/v1/rid/broadcast/1/start"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sysid").value(1))
                    .andExpect(jsonPath("$.action").value("start"))
                    .andExpect(jsonPath("$.ridStatus").value("BROADCASTING"));
        }

        @Test
        @DisplayName("已存在的 sysid 启动广播保持 BROADCASTING")
        void startBroadcast_existingSysid() throws Exception {
            stateManager.updateBasicId(1, new BasicIdData(1, 2, "UAS-001"));

            mockMvc.perform(post("/api/v1/rid/broadcast/1/start"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sysid").value(1))
                    .andExpect(jsonPath("$.action").value("start"))
                    .andExpect(jsonPath("$.ridStatus").value("BROADCASTING"));
        }
    }

    // =====================================================================
    // POST /broadcast/{sysid}/stop
    // =====================================================================

    @Nested
    @DisplayName("POST /broadcast/{sysid}/stop 停止广播")
    class StopBroadcastEndpoint {

        @Test
        @DisplayName("停止广播返回成功")
        void stopBroadcast_success() throws Exception {
            stateManager.updateBasicId(1, new BasicIdData(1, 2, "UAS-001"));

            mockMvc.perform(post("/api/v1/rid/broadcast/1/stop"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sysid").value(1))
                    .andExpect(jsonPath("$.action").value("stop"))
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.ridStatus").value("NOT_BROADCASTING"));
        }

        @Test
        @DisplayName("不存在的 sysid 停止广播也返回成功")
        void stopBroadcast_nonExistent_success() throws Exception {
            mockMvc.perform(post("/api/v1/rid/broadcast/99/stop"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sysid").value(99))
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    // =====================================================================
    // operatorId 脱敏
    // =====================================================================

    @Nested
    @DisplayName("operatorId 脱敏")
    class OperatorIdDesensitization {

        @Test
        @DisplayName("响应中 operatorId 脱敏（保留前4位+*）")
        void response_operatorId_desensitized() throws Exception {
            // 设置带有 operatorId 的快照
            stateManager.updateBasicId(1, new BasicIdData(1, 2, "UAS-001"));
            stateManager.updateOperatorId(1, new OperatorIdData(0, "ABCD123456789"));

            mockMvc.perform(get("/api/v1/rid/status/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.operatorId.operatorId").value("ABCD*********"));
        }

        @Test
        @DisplayName("operatorId 长度 <=4 时不脱敏")
        void response_shortOperatorId_notDesensitized() throws Exception {
            stateManager.updateBasicId(1, new BasicIdData(1, 2, "UAS-001"));
            stateManager.updateOperatorId(1, new OperatorIdData(0, "ABCD"));

            mockMvc.perform(get("/api/v1/rid/status/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.operatorId.operatorId").value("ABCD"));
        }
    }
}
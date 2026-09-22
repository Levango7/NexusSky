package io.aerofleet.cloud.drone;

import io.aerofleet.cloud.api.exception.ApiExceptionHandler;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link DroneLockController} REST API 端点测试（MockMvc standaloneSetup）。
 * <p>
 * 不启动完整 Spring 上下文，直接为 Controller 构建 MockMvc，验证：
 * 锁定/解锁/状态查询/已锁定列表/清除记录/错误处理。
 */
@DisplayName("DroneLockController REST API (MockMvc)")
class DroneLockControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DeviceRegistry registry;
    private DroneLockService lockService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry();
        lockService = new DroneLockService(registry);
        DroneLockController controller = new DroneLockController(lockService, registry);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static String json(Map<String, Object> body) throws Exception {
        return MAPPER.writeValueAsString(body);
    }

    private Map<String, Object> lockBody(String reason, String lockedBy, String action) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("reason", reason);
        b.put("lockedBy", lockedBy);
        b.put("action", action);
        return b;
    }

    private Map<String, Object> unlockBody(String unlockedBy) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("unlockedBy", unlockedBy);
        return b;
    }

    // ===== POST /{sysid}/lock 锁定 =====

    @Test
    @DisplayName("testLockEndpoint: POST /lock 锁定已注册无人机返回 200")
    void testLockEndpoint() throws Exception {
        registry.registerIfAbsent(1);

        mockMvc.perform(post("/api/v1/drone-lock/1/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(lockBody("被盗", "admin", "FORCE_LAND"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sysid").value(1))
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.lockReason").value("被盗"))
                .andExpect(jsonPath("$.lockedBy").value("admin"))
                .andExpect(jsonPath("$.action").value("FORCE_LAND"))
                .andExpect(jsonPath("$.lockTimeMs").isNumber())
                .andExpect(jsonPath("$.unlockTimeMs").value(0));
    }

    @Test
    @DisplayName("POST /lock 未注册无人机返回 404")
    void lockUnregistered_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/drone-lock/99/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(lockBody("test", "admin", "DISARM"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /lock 缺 lockedBy 返回 400")
    void lockMissingLockedBy_returns400() throws Exception {
        registry.registerIfAbsent(1);

        mockMvc.perform(post("/api/v1/drone-lock/1/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "test", "action", "DISARM"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /lock 非法 action 返回 400")
    void lockInvalidAction_returns400() throws Exception {
        registry.registerIfAbsent(1);

        mockMvc.perform(post("/api/v1/drone-lock/1/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(lockBody("test", "admin", "EXPLODE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /lock 重复锁定幂等返回当前状态")
    void lockDoubleLock_idempotent() throws Exception {
        registry.registerIfAbsent(1);

        // 第一次锁定
        mockMvc.perform(post("/api/v1/drone-lock/1/lock")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(lockBody("被盗", "admin", "DISARM"))));

        // 第二次锁定（不同参数）— 幂等返回首次状态
        mockMvc.perform(post("/api/v1/drone-lock/1/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(lockBody("遗失", "admin2", "FORCE_LAND"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.lockedBy").value("admin"))
                .andExpect(jsonPath("$.lockReason").value("被盗"))
                .andExpect(jsonPath("$.action").value("DISARM"));
    }

    // ===== POST /{sysid}/unlock 解锁 =====

    @Test
    @DisplayName("testUnlockEndpoint: POST /unlock 解锁已锁定无人机返回 200")
    void testUnlockEndpoint() throws Exception {
        registry.registerIfAbsent(1);
        lockService.lock(1, "test", "admin", LockState.Action.DISARM);

        mockMvc.perform(post("/api/v1/drone-lock/1/unlock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(unlockBody("admin2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sysid").value(1))
                .andExpect(jsonPath("$.locked").value(false))
                .andExpect(jsonPath("$.unlockTimeMs").isNumber());
    }

    @Test
    @DisplayName("POST /unlock 未锁定无人机返回 400")
    void unlockNotLocked_returns400() throws Exception {
        registry.registerIfAbsent(1);

        mockMvc.perform(post("/api/v1/drone-lock/1/unlock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(unlockBody("admin"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /unlock 未注册无人机返回 404")
    void unlockUnregistered_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/drone-lock/99/unlock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(unlockBody("admin"))))
                .andExpect(status().isNotFound());
    }

    // ===== GET /{sysid} 获取锁定状态 =====

    @Test
    @DisplayName("testGetLockStatus: GET /{sysid} 返回锁定状态")
    void testGetLockStatus() throws Exception {
        registry.registerIfAbsent(1);
        lockService.lock(1, "被盗", "admin", LockState.Action.RETURN_TO_LAUNCH);

        mockMvc.perform(get("/api/v1/drone-lock/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sysid").value(1))
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.lockedBy").value("admin"));
    }

    @Test
    @DisplayName("GET /{sysid} 未锁定返回 locked=false")
    void getLockStatus_notLocked() throws Exception {
        registry.registerIfAbsent(1);

        mockMvc.perform(get("/api/v1/drone-lock/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sysid").value(1))
                .andExpect(jsonPath("$.locked").value(false));
    }

    @Test
    @DisplayName("GET /{sysid} 未注册返回 404")
    void getLockStatus_unregistered_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/drone-lock/99"))
                .andExpect(status().isNotFound());
    }

    // ===== GET /locked 获取所有已锁定 =====

    @Test
    @DisplayName("testGetLockedDrones: GET /locked 返回已锁定无人机列表")
    void testGetLockedDrones() throws Exception {
        registry.registerIfAbsent(1);
        registry.registerIfAbsent(2);
        lockService.lock(1, "r1", "op", LockState.Action.DISARM);
        lockService.lock(2, "r2", "op", LockState.Action.FORCE_LAND);

        mockMvc.perform(get("/api/v1/drone-lock/locked"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sysid").value(1))
                .andExpect(jsonPath("$[1].sysid").value(2));
    }

    @Test
    @DisplayName("GET /locked 无锁定返回空数组")
    void getLockedDrones_empty() throws Exception {
        mockMvc.perform(get("/api/v1/drone-lock/locked"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ===== GET /all 获取所有锁定状态 =====

    @Test
    @DisplayName("GET /all 返回所有锁定状态记录")
    void getAllLockStates() throws Exception {
        registry.registerIfAbsent(1);
        registry.registerIfAbsent(2);
        lockService.lock(1, "r1", "op", LockState.Action.DISARM);
        lockService.lock(2, "r2", "op", LockState.Action.FORCE_LAND);
        lockService.unlock(2, "op");

        mockMvc.perform(get("/api/v1/drone-lock/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sysid").value(1))
                .andExpect(jsonPath("$[0].locked").value(true))
                .andExpect(jsonPath("$[1].sysid").value(2))
                .andExpect(jsonPath("$[1].locked").value(false));
    }

    // ===== DELETE /{sysid} 清除锁定状态 =====

    @Test
    @DisplayName("testClearLockState: DELETE /{sysid} 清除锁定记录返回 200")
    void testClearLockState() throws Exception {
        registry.registerIfAbsent(1);
        lockService.lock(1, "test", "op", LockState.Action.DISARM);

        mockMvc.perform(delete("/api/v1/drone-lock/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.sysid").value(1));

        // 清除后查询返回未锁定
        mockMvc.perform(get("/api/v1/drone-lock/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false));
    }

    @Test
    @DisplayName("DELETE /{sysid} 未注册返回 404")
    void clearLockState_unregistered_returns404() throws Exception {
        mockMvc.perform(delete("/api/v1/drone-lock/99"))
                .andExpect(status().isNotFound());
    }
}
package io.aerofleet.cloud.scenario;

import io.aerofleet.cloud.api.ApiExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link ScenarioTemplateController} REST API 单测（MockMvc standaloneSetup）。
 * <p>
 * 直接实例化 Controller（无 Spring 上下文），使用 MockMvc 验证 HTTP 请求/响应。
 * 风格与 {@code GeofenceControllerTest} 一致。
 */
@DisplayName("ScenarioTemplateController REST API (MockMvc)")
class ScenarioTemplateControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ScenarioTemplateController controller = new ScenarioTemplateController();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static String json(Map<String, Object> body) throws Exception {
        return MAPPER.writeValueAsString(body);
    }

    /** 构造自定义模板请求体。 */
    private static Map<String, Object> templateBody(String id, String name) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", id);
        b.put("name", name);
        b.put("disasterType", "FIRE");
        b.put("severityLevel", "SMALL");
        b.put("description", "test template");
        b.put("droneCount", 3);
        b.put("radiusKm", 1.5);
        b.put("hoverAltitudeM", 80.0);
        b.put("durationMin", 45);
        b.put("collaborationStrategy", "RECON_ONLY");
        b.put("communicationMode", "MESH");
        return b;
    }

    // =====================================================================
    // 预设模板查询
    // =====================================================================

    @Test
    @DisplayName("GET /templates 返回 18 个预设模板")
    void listReturnsAllPresets() throws Exception {
        mockMvc.perform(get("/api/scenarios/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(18));
    }

    @Test
    @DisplayName("GET /templates/{id} 返回预设模板详情")
    void getReturnsPresetTemplate() throws Exception {
        mockMvc.perform(get("/api/scenarios/templates/preset-FIRE-SMALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("preset-FIRE-SMALL"))
                .andExpect(jsonPath("$.disasterType").value("FIRE"))
                .andExpect(jsonPath("$.severityLevel").value("SMALL"))
                .andExpect(jsonPath("$.droneCount").value(2))
                .andExpect(jsonPath("$.radiusKm").value(1.0));
    }

    @Test
    @DisplayName("GET /templates/{id} 不存在时返回 404")
    void getReturns404ForNonExistent() throws Exception {
        mockMvc.perform(get("/api/scenarios/templates/non-existent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    // =====================================================================
    // CRUD
    // =====================================================================

    @Test
    @DisplayName("POST /templates 创建自定义模板并获取详情")
    void createCustomTemplate() throws Exception {
        // POST 创建
        mockMvc.perform(post("/api/scenarios/templates")
                        .contentType("application/json")
                        .content(json(templateBody("custom-1", "my-fire-scenario"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("custom-1"))
                .andExpect(jsonPath("$.name").value("my-fire-scenario"))
                .andExpect(jsonPath("$.disasterType").value("FIRE"))
                .andExpect(jsonPath("$.droneCount").value(3));

        // GET 单个
        mockMvc.perform(get("/api/scenarios/templates/custom-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("my-fire-scenario"));

        // 列表应包含 19 个（18 预设 + 1 自定义）
        mockMvc.perform(get("/api/scenarios/templates"))
                .andExpect(jsonPath("$.length()").value(19));
    }

    @Test
    @DisplayName("POST /templates 未指定 ID 时自动生成")
    void createAutoGeneratesId() throws Exception {
        Map<String, Object> body = templateBody(null, "auto-id-template");
        mockMvc.perform(post("/api/scenarios/templates")
                        .contentType("application/json")
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("auto-id-template"));
    }

    @Test
    @DisplayName("PUT /templates/{id} 更新模板")
    void updateTemplate() throws Exception {
        // 先创建
        mockMvc.perform(post("/api/scenarios/templates")
                .contentType("application/json")
                .content(json(templateBody("custom-2", "before-update"))));

        // PUT 更新
        Map<String, Object> updateBody = templateBody("custom-2", "after-update");
        updateBody.put("droneCount", 10);
        mockMvc.perform(put("/api/scenarios/templates/custom-2")
                        .contentType("application/json")
                        .content(json(updateBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("custom-2"))
                .andExpect(jsonPath("$.name").value("after-update"))
                .andExpect(jsonPath("$.droneCount").value(10));

        // GET 验证更新生效
        mockMvc.perform(get("/api/scenarios/templates/custom-2"))
                .andExpect(jsonPath("$.name").value("after-update"))
                .andExpect(jsonPath("$.droneCount").value(10));
    }

    @Test
    @DisplayName("PUT /templates/{id} 不存在时返回 404")
    void updateReturns404ForNonExistent() throws Exception {
        mockMvc.perform(put("/api/scenarios/templates/non-existent")
                        .contentType("application/json")
                        .content(json(templateBody("non-existent", "nope"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /templates/{id} 删除自定义模板")
    void deleteCustomTemplate() throws Exception {
        // 先创建
        mockMvc.perform(post("/api/scenarios/templates")
                .contentType("application/json")
                .content(json(templateBody("custom-3", "to-delete"))));

        // DELETE 删除
        mockMvc.perform(delete("/api/scenarios/templates/custom-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.id").value("custom-3"));

        // 删除后再获取应 404
        mockMvc.perform(get("/api/scenarios/templates/custom-3"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /templates/{id} 不存在时返回 404")
    void deleteReturns404ForNonExistent() throws Exception {
        mockMvc.perform(delete("/api/scenarios/templates/non-existent"))
                .andExpect(status().isNotFound());
    }

    // =====================================================================
    // 按灾害类型筛选
    // =====================================================================

    @Test
    @DisplayName("GET /templates/by-type/FIRE 返回 3 个火灾模板")
    void byTypeReturnsFireTemplates() throws Exception {
        mockMvc.perform(get("/api/scenarios/templates/by-type/FIRE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].disasterType").value("FIRE"));
    }

    @Test
    @DisplayName("GET /templates/by-type/EARTHQUAKE 返回 3 个地震模板")
    void byTypeReturnsEarthquakeTemplates() throws Exception {
        mockMvc.perform(get("/api/scenarios/templates/by-type/EARTHQUAKE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    @DisplayName("GET /templates/by-type/CHEMICAL_LEAK 返回 3 个化工厂泄漏模板")
    void byTypeReturnsChemicalLeakTemplates() throws Exception {
        mockMvc.perform(get("/api/scenarios/templates/by-type/CHEMICAL_LEAK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }
}
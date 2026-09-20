package io.aerofleet.cloud.citytwin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link CityModelController} REST API 端点测试（MockMvc standaloneSetup）。
 * <p>
 * 不启动完整 Spring 上下文，直接为 Controller 构建 MockMvc，验证 HTTP 请求/响应。
 */
@DisplayName("CityModelController REST API (MockMvc)")
class CityModelControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private CityModelService cityModelService;
    private CityModel sampleModel;

    @BeforeEach
    void setUp() {
        cityModelService = new CityModelService();
        sampleModel = new CityModel("m1", "Downtown", "1.0", "City center model",
                CityModel.ModelType.TILES_3D, 25.0, 5.0, "https://example.com/model",
                System.currentTimeMillis(), System.currentTimeMillis(), CityModel.ModelStatus.LOADED);
        cityModelService.registerModel(sampleModel);

        CityModelController controller = new CityModelController(cityModelService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /api/city-twin/models 列出所有城市模型")
    void listModels_returnsAllModels() throws Exception {
        mockMvc.perform(get("/api/city-twin/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("m1"))
                .andExpect(jsonPath("$[0].name").value("Downtown"));
    }

    @Test
    @DisplayName("GET /api/city-twin/models/{id} 获取模型详情")
    void getModel_returnsModelById() throws Exception {
        mockMvc.perform(get("/api/city-twin/models/m1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("m1"))
                .andExpect(jsonPath("$.name").value("Downtown"))
                .andExpect(jsonPath("$.modelType").value("TILES_3D"));
    }

    @Test
    @DisplayName("POST /api/city-twin/models 注册新模型")
    void registerModel_createsNewModel() throws Exception {
        CityModel newModel = new CityModel(null, "Uptown", "2.0", "Uptown model",
                CityModel.ModelType.OSGB, 15.0, 3.0, "https://example.com/uptown",
                0, 0, CityModel.ModelStatus.PROCESSING);

        mockMvc.perform(post("/api/city-twin/models")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(newModel)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Uptown"))
                .andExpect(jsonPath("$.modelType").value("OSGB"))
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    @DisplayName("DELETE /api/city-twin/models/{id} 删除模型")
    void deleteModel_returnsOk() throws Exception {
        mockMvc.perform(delete("/api/city-twin/models/m1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT /api/city-twin/models/{id}/refresh 刷新模型数据")
    void refreshModel_returnsUpdatedModel() throws Exception {
        mockMvc.perform(put("/api/city-twin/models/m1/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("m1"))
                .andExpect(jsonPath("$.status").value("LOADED"));
    }
}

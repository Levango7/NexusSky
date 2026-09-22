package io.aerofleet.cloud.citytwin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SimulationController} REST API 端点测试（MockMvc standaloneSetup）。
 * <p>
 * 不启动完整 Spring 上下文，直接为 Controller 构建 MockMvc，验证 HTTP 请求/响应。
 */
@DisplayName("SimulationController REST API (MockMvc)")
class SimulationControllerTest {

    private MockMvc mockMvc;
    private DisasterSimulationService simulationService;

    @BeforeEach
    void setUp() {
        simulationService = new DisasterSimulationService();
        SimulationController controller = new SimulationController(simulationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("POST /api/v1/city-twin/simulation/flood 洪水模拟")
    void simulateFlood_returnsSimulation() throws Exception {
        mockMvc.perform(post("/api/v1/city-twin/simulation/flood")
                        .param("centerLat", "39.9")
                        .param("centerLon", "116.4")
                        .param("radiusKm", "5.0")
                        .param("depthM", "2.5")
                        .param("durationMin", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.type").value("FLOOD"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.timeline").isNotEmpty());
    }

    @Test
    @DisplayName("POST /api/v1/city-twin/simulation/fire 火灾模拟")
    void simulateFire_returnsSimulation() throws Exception {
        mockMvc.perform(post("/api/v1/city-twin/simulation/fire")
                        .param("centerLat", "39.9")
                        .param("centerLon", "116.4")
                        .param("radiusKm", "3.0")
                        .param("windSpeed", "15.0")
                        .param("durationMin", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.type").value("FIRE"))
                .andExpect(jsonPath("$.result.timeline").isNotEmpty());
    }

    @Test
    @DisplayName("POST /api/v1/city-twin/simulation/earthquake 地震模拟")
    void simulateEarthquake_returnsSimulation() throws Exception {
        mockMvc.perform(post("/api/v1/city-twin/simulation/earthquake")
                        .param("centerLat", "39.9")
                        .param("centerLon", "116.4")
                        .param("magnitude", "7.0")
                        .param("durationMin", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.type").value("EARTHQUAKE"))
                .andExpect(jsonPath("$.result.timeline").isNotEmpty());
    }

    @Test
    @DisplayName("POST /api/v1/city-twin/simulation/evacuation 疏散模拟")
    void simulateEvacuation_returnsSimulation() throws Exception {
        mockMvc.perform(post("/api/v1/city-twin/simulation/evacuation")
                        .param("centerLat", "39.9")
                        .param("centerLon", "116.4")
                        .param("radiusKm", "5.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.type").value("EVACUATION"))
                .andExpect(jsonPath("$.result.estimatedDamage").value(0.0));
    }

    @Test
    @DisplayName("GET /api/v1/city-twin/simulation/{id} 获取模拟结果")
    void getSimulation_returnsById() throws Exception {
        // 先创建一个模拟
        DisasterSimulation sim = simulationService.simulateFlood(39.9, 116.4, 5.0, 2.5, 60);

        mockMvc.perform(get("/api/v1/city-twin/simulation/" + sim.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sim.getId()))
                .andExpect(jsonPath("$.type").value("FLOOD"));
    }

    @Test
    @DisplayName("GET /api/v1/city-twin/simulation/history 模拟历史")
    void getSimulationHistory_returnsAll() throws Exception {
        // 先创建两个模拟
        simulationService.simulateFlood(39.9, 116.4, 5.0, 2.5, 60);
        simulationService.simulateFire(39.9, 116.4, 3.0, 15.0, 30);

        mockMvc.perform(get("/api/v1/city-twin/simulation/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[1].id").exists());
    }
}

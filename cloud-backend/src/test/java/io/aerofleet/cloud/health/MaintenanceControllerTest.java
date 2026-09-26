package io.aerofleet.cloud.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aerofleet.cloud.api.exception.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.Arrays;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MaintenanceController} REST 端点单测（P1-2 维护管理）。
 * <p>
 * 使用 MockMvc standaloneSetup（无 Spring 上下文），验证维护记录 CRUD 与预测端点。
 */
@DisplayName("MaintenanceController REST 端点")
class MaintenanceControllerTest {

    private HealthMonitorService monitor;
    private PredictiveMaintenanceService predictionService;
    private MaintenanceController controller;
    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        monitor = new HealthMonitorService(null);
        predictionService = new PredictiveMaintenanceService(monitor);
        controller = new MaintenanceController(predictionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MaintenanceRecord newRecord(int sysid, ComponentType component,
                                          MaintenanceRecord.MaintenanceType type,
                                          MaintenanceRecord.Status status) {
        MaintenanceRecord r = new MaintenanceRecord();
        r.setSysid(sysid);
        r.setComponentType(component);
        r.setMaintenanceType(type);
        r.setStatus(status);
        r.setScheduledDate(LocalDate.now().plusDays(3));
        r.setTechnician("tech-1");
        r.setNotes("test record");
        r.setCost(100.0);
        return r;
    }

    private TelemetrySnapshot healthySnapshot() {
        TelemetrySnapshot t = new TelemetrySnapshot(System.currentTimeMillis());
        t.setBatteryPct(80);
        t.setMotorRpms(Arrays.asList(5000.0, 5000.0, 5000.0, 5000.0));
        t.setVibrationG(0.2);
        t.setTemperatureC(40);
        t.setRssiDbm(-50);
        t.setImuDrift(0.1);
        t.setGpsSatellites(12);
        t.setGpsHdop(0.8);
        t.setBatteryCycles(50);
        return t;
    }

    // ------------------------------------------------------------------
    // POST /api/v1/maintenance/records
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /records 创建维护记录")
    void createRecord_returnsCreated() throws Exception {
        MaintenanceRecord r = newRecord(1, ComponentType.BATTERY,
                MaintenanceRecord.MaintenanceType.REPLACE,
                MaintenanceRecord.Status.SCHEDULED);
        mockMvc.perform(post("/api/v1/maintenance/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(r)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.sysid").value(1))
                .andExpect(jsonPath("$.componentType").value("BATTERY"))
                .andExpect(jsonPath("$.status").value("SCHEDULED"));
    }

    @Test
    @DisplayName("POST /records 缺 componentType 返回 400")
    void createRecord_missingComponentType_returns400() throws Exception {
        MaintenanceRecord r = newRecord(1, ComponentType.BATTERY,
                MaintenanceRecord.MaintenanceType.REPLACE,
                MaintenanceRecord.Status.SCHEDULED);
        r.setComponentType(null);
        mockMvc.perform(post("/api/v1/maintenance/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(r)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /records 无效 sysid 返回 400")
    void createRecord_invalidSysid_returns400() throws Exception {
        MaintenanceRecord r = newRecord(0, ComponentType.BATTERY,
                MaintenanceRecord.MaintenanceType.REPLACE,
                MaintenanceRecord.Status.SCHEDULED);
        mockMvc.perform(post("/api/v1/maintenance/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(r)))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // GET /api/v1/maintenance/records
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /records 返回所有记录")
    void getRecords_returnsAll() throws Exception {
        controller.createRecord(newRecord(1, ComponentType.BATTERY,
                MaintenanceRecord.MaintenanceType.REPLACE, MaintenanceRecord.Status.SCHEDULED));
        controller.createRecord(newRecord(2, ComponentType.MOTOR,
                MaintenanceRecord.MaintenanceType.REPAIR, MaintenanceRecord.Status.COMPLETED));
        mockMvc.perform(get("/api/v1/maintenance/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sysid").exists());
    }

    @Test
    @DisplayName("GET /records?sysid=1 按 sysid 筛选")
    void getRecords_filterBySysid() throws Exception {
        controller.createRecord(newRecord(1, ComponentType.BATTERY,
                MaintenanceRecord.MaintenanceType.REPLACE, MaintenanceRecord.Status.SCHEDULED));
        controller.createRecord(newRecord(2, ComponentType.MOTOR,
                MaintenanceRecord.MaintenanceType.REPAIR, MaintenanceRecord.Status.COMPLETED));
        mockMvc.perform(get("/api/v1/maintenance/records").param("sysid", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sysid").value(1));
    }

    @Test
    @DisplayName("GET /records?status=SCHEDULED 按 status 筛选")
    void getRecords_filterByStatus() throws Exception {
        controller.createRecord(newRecord(1, ComponentType.BATTERY,
                MaintenanceRecord.MaintenanceType.REPLACE, MaintenanceRecord.Status.SCHEDULED));
        controller.createRecord(newRecord(2, ComponentType.MOTOR,
                MaintenanceRecord.MaintenanceType.REPAIR, MaintenanceRecord.Status.COMPLETED));
        mockMvc.perform(get("/api/v1/maintenance/records").param("status", "SCHEDULED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SCHEDULED"));
    }

    // ------------------------------------------------------------------
    // PUT /api/v1/maintenance/records/{id}
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PUT /records/{id} 更新维护记录")
    void updateRecord_returnsUpdated() throws Exception {
        MaintenanceRecord created = controller.createRecord(newRecord(1, ComponentType.BATTERY,
                MaintenanceRecord.MaintenanceType.REPLACE, MaintenanceRecord.Status.SCHEDULED));
        MaintenanceRecord update = new MaintenanceRecord();
        update.setStatus(MaintenanceRecord.Status.COMPLETED);
        update.setTechnician("tech-2");
        update.setNotes("completed");
        update.setCost(150.0);

        mockMvc.perform(put("/api/v1/maintenance/records/" + created.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.technician").value("tech-2"))
                .andExpect(jsonPath("$.cost").value(150.0));
    }

    @Test
    @DisplayName("PUT /records/{id} 不存在返回 404")
    void updateRecord_notFound_returns404() throws Exception {
        MaintenanceRecord update = new MaintenanceRecord();
        update.setStatus(MaintenanceRecord.Status.COMPLETED);
        mockMvc.perform(put("/api/v1/maintenance/records/nonexistent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(update)))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // GET /api/v1/maintenance/predictions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /predictions 返回所有预测")
    void getPredictions_returnsAll() throws Exception {
        monitor.updateScore(monitor.calculateScore(1, healthySnapshot()));
        monitor.updateScore(monitor.calculateScore(2, healthySnapshot()));
        mockMvc.perform(get("/api/v1/maintenance/predictions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sysid").exists());
    }

    @Test
    @DisplayName("GET /predictions 无数据返回空数组")
    void getPredictions_empty_returnsEmptyArray() throws Exception {
        mockMvc.perform(get("/api/v1/maintenance/predictions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ------------------------------------------------------------------
    // GET /api/v1/maintenance/predictions/{sysid}
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /predictions/{sysid} 返回单机预测")
    void getPredictionsBySysid_returnsList() throws Exception {
        monitor.updateScore(monitor.calculateScore(1, healthySnapshot()));
        mockMvc.perform(get("/api/v1/maintenance/predictions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sysid").value(1))
                .andExpect(jsonPath("$[0].predictedComponent").exists())
                .andExpect(jsonPath("$[0].urgency").exists());
    }

    @Test
    @DisplayName("GET /predictions/{sysid} 无评分返回空数组")
    void getPredictionsBySysid_noScore_returnsEmpty() throws Exception {
        mockMvc.perform(get("/api/v1/maintenance/predictions/99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ------------------------------------------------------------------
    // GET /api/v1/maintenance/schedule
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /schedule 返回维护计划（SCHEDULED + IN_PROGRESS）")
    void getSchedule_returnsPlanned() throws Exception {
        controller.createRecord(newRecord(1, ComponentType.BATTERY,
                MaintenanceRecord.MaintenanceType.REPLACE, MaintenanceRecord.Status.SCHEDULED));
        controller.createRecord(newRecord(2, ComponentType.MOTOR,
                MaintenanceRecord.MaintenanceType.REPAIR, MaintenanceRecord.Status.IN_PROGRESS));
        controller.createRecord(newRecord(3, ComponentType.GPS,
                MaintenanceRecord.MaintenanceType.INSPECT, MaintenanceRecord.Status.COMPLETED));
        mockMvc.perform(get("/api/v1/maintenance/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SCHEDULED"));
    }

    @Test
    @DisplayName("GET /schedule 无计划返回空数组")
    void getSchedule_empty_returnsEmptyArray() throws Exception {
        mockMvc.perform(get("/api/v1/maintenance/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
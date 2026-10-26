package io.aerofleet.cloud.mission.emergency;

import io.aerofleet.cloud.api.service.EmergencyOrchService;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/air-ground/situation} 的响应形状单测（MockMvc standaloneSetup）。
 * <p>
 * 为什么单独钉"键名"：审计发现这个端点的三列在前端永远显示"暂无"，
 * 原因之一是前端读的键与后端发的键不一致，之二是 `coordinationTasks` 这个键**后端从来没发过**。
 * 前端测试只能证明"面板读得到我 mock 给它的那份形状"，证明不了后端真的发这些键——
 * 所以这里从**服务端**把键名钉死：删掉任何一行 `m.put(...)`，这里就红。
 */
@DisplayName("AirGroundCoordinationController /situation 响应契约")
class AirGroundCoordinationControllerTest {

    private final org.springframework.context.ApplicationEventPublisher noopPublisher = event -> { };
    private MockMvc mockMvc;
    private DeviceRegistry fleet;

    @BeforeEach
    void setUp() {
        EmergencyOrchService orchService = new EmergencyOrchService(null, noopPublisher);
        fleet = new DeviceRegistry();
        DroneSnapshot d1 = fleet.registerIfAbsent(7);
        d1.online = true;
        d1.lat = 39.9;
        d1.lon = 116.3;
        d1.relativeAlt = 80.0;
        d1.battery = 55;
        d1.mode = "MISSION";

        AirGroundCoordinationService service = new AirGroundCoordinationService(
                orchService, null, null, null, null, null, null, null, fleet);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AirGroundCoordinationController(service))
                .build();
    }

    @Test
    @DisplayName("响应必须带前端实际在读的这几个键（缺任一键 ⇒ 面板那一格永远是'暂无'）")
    void situationExposesTheKeysTheUiReads() throws Exception {
        mockMvc.perform(get("/api/v1/air-ground/situation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.surveillanceDevices").isArray())
                .andExpect(jsonPath("$.droneStatuses").isArray())
                .andExpect(jsonPath("$.coordinationTasks").isArray())
                .andExpect(jsonPath("$.alarmEvents").isArray())
                .andExpect(jsonPath("$.meshTopology.nodeCount").isNumber());
    }

    @Test
    @DisplayName("空中单位来自机队注册表，字段逐项可见（不再是恒空列表）")
    void droneStatusesCarryRealTelemetry() throws Exception {
        mockMvc.perform(get("/api/v1/air-ground/situation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.droneStatuses[?(@.sysid == 7)].online").value(true))
                .andExpect(jsonPath("$.droneStatuses[?(@.sysid == 7)].batteryPct").value(55))
                .andExpect(jsonPath("$.droneStatuses[?(@.sysid == 7)].missionPhase").value("MISSION"))
                // mesh 节点数由在线机数派生：恒空实现下这里恒 0
                .andExpect(jsonPath("$.meshTopology.nodeCount").value(1));
    }

    @Test
    @DisplayName("batteryKnown 必须随响应带出：未上报电量夹成 0，不能与'真的 0%'同形")
    void batteryKnownFlagIsPartOfTheContract() throws Exception {
        DroneSnapshot d9 = fleet.registerIfAbsent(9);
        d9.online = true;   // 电量/位置都没上报：battery=-1、lat/lon 是 NaN

        mockMvc.perform(get("/api/v1/air-ground/situation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.droneStatuses[?(@.sysid == 7)].batteryKnown").value(true))
                .andExpect(jsonPath("$.droneStatuses[?(@.sysid == 9)].batteryKnown").value(false))
                .andExpect(jsonPath("$.droneStatuses[?(@.sysid == 9)].batteryPct").value(0))
                .andExpect(jsonPath("$.droneStatuses[?(@.sysid == 9)].lat").value(0.0));
    }
}

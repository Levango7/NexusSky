package io.aerofleet.cloud.surveillance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SurveillanceController REST API 端点测试（使用 MockMvc standaloneSetup）。
 * <p>
 * 不启动完整 Spring 上下文，直接为 Controller 构建 MockMvc，验证：
 * 设备注册/列表/详情/注销/RTSP 流/PTZ 控制/发现/错误处理。
 */
@DisplayName("SurveillanceController REST API 端点")
class SurveillanceControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SurveillanceDeviceRegistry registry;
    private OnvifClient onvifClient;
    private RapidDeployService rapidDeployService;
    private VendorAdapterRegistry vendorAdapterRegistry;
    private SurveillanceController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        registry = new SurveillanceDeviceRegistry();
        onvifClient = new OnvifClient();
        rapidDeployService = new RapidDeployService(onvifClient, registry);
        // 真实适配器组装（与 Spring 装配同形）：ONVIF 复用 OnvifClient，
        // GB28181 走国标形状层（纯函数、无外部依赖）；三个私有协议适配器为模拟实现。
        // PTZ/stream 端点按此注册表路由——测试路由行为本身，而非某个适配器内部。
        vendorAdapterRegistry = new VendorAdapterRegistry(List.of(
                new OnvifVendorAdapter(onvifClient),
                new Gb28181Adapter(new Gb28181CatalogParser(), "00000000000000000001", "127.0.0.1:5060")));
        controller = new SurveillanceController(registry, onvifClient, rapidDeployService, vendorAdapterRegistry);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static String json(Map<String, Object> body) throws Exception {
        return MAPPER.writeValueAsString(body);
    }

    private static Map<String, Object> deviceBody(String id, String vendor) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", id);
        b.put("name", "cam-" + id);
        b.put("vendor", vendor);
        b.put("ip", "192.168.1.100");
        b.put("port", 80);
        b.put("username", "admin");
        b.put("password", "pass123");
        return b;
    }

    // ===== POST /devices 注册 =====

    @Test
    @DisplayName("POST /devices 合法请求返回 200 + 设备视图")
    void registerDevice_valid_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/surveillance/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(deviceBody("cam-1", "HIKVISION"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("cam-1"))
                .andExpect(jsonPath("$.vendor").value("HIKVISION"))
                .andExpect(jsonPath("$.status").value("ONLINE"))
                .andExpect(jsonPath("$.capabilities").exists());
    }

    @Test
    @DisplayName("POST /devices 缺 id 返回 400")
    void registerDevice_missingId_returns400() throws Exception {
        Map<String, Object> body = deviceBody("cam-1", "HIKVISION");
        body.remove("id");
        mockMvc.perform(post("/api/v1/surveillance/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /devices 未知厂商返回 400")
    void registerDevice_unknownVendor_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/surveillance/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(deviceBody("cam-1", "SONY"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("vendor")));
    }

    // ===== GET /devices 列表 =====

    @Test
    @DisplayName("GET /devices 空注册表返回 count=0")
    void listDevices_empty_returnsCountZero() throws Exception {
        mockMvc.perform(get("/api/v1/surveillance/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @DisplayName("GET /devices 注册后返回 count=1")
    void listDevices_afterRegister_returnsCountOne() throws Exception {
        mockMvc.perform(post("/api/v1/surveillance/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(deviceBody("cam-1", "HIKVISION"))));

        mockMvc.perform(get("/api/v1/surveillance/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.devices[0].id").value("cam-1"));
    }

    // ===== GET /devices/{id} 详情 =====

    @Test
    @DisplayName("GET /devices/{id} 已存在返回 200 + 设备详情")
    void getDevice_existing_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/surveillance/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(deviceBody("cam-1", "DAHUA"))));

        mockMvc.perform(get("/api/v1/surveillance/devices/cam-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("cam-1"))
                .andExpect(jsonPath("$.vendor").value("DAHUA"));
    }

    @Test
    @DisplayName("GET /devices/{id} 不存在返回 404")
    void getDevice_nonExisting_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/surveillance/devices/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    // ===== DELETE /devices/{id} 注销 =====

    @Test
    @DisplayName("DELETE /devices/{id} 已存在返回 200 + status=ok")
    void unregisterDevice_existing_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/surveillance/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(deviceBody("cam-1", "HIKVISION"))));

        mockMvc.perform(delete("/api/v1/surveillance/devices/cam-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
        // 注销后再查应 404
        mockMvc.perform(get("/api/v1/surveillance/devices/cam-1"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /devices/{id} 不存在返回 404")
    void unregisterDevice_nonExisting_returns404() throws Exception {
        mockMvc.perform(delete("/api/v1/surveillance/devices/nope"))
                .andExpect(status().isNotFound());
    }

    // ===== GET /devices/{id}/stream RTSP 流 =====

    @Test
    @DisplayName("GET /devices/{id}/stream 返回 200 + rtspUrl")
    void getStreamUrl_existing_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/surveillance/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(deviceBody("cam-1", "HIKVISION"))));

        mockMvc.perform(get("/api/v1/surveillance/devices/cam-1/stream").param("channel", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rtspUrl").exists())
                .andExpect(jsonPath("$.rtspUrl").value(org.hamcrest.Matchers.startsWith("rtsp://")))
                .andExpect(jsonPath("$.channel").value(1));
    }

    @Test
    @DisplayName("GET /devices/{id}/stream 不存在设备返回 404")
    void getStreamUrl_nonExistingDevice_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/surveillance/devices/nope/stream"))
                .andExpect(status().isNotFound());
    }

    // ===== POST /devices/{id}/ptz PTZ 控制 =====

    @Test
    @DisplayName("POST /devices/{id}/ptz 合法命令返回 200 + status=ok")
    void ptzControl_validCommand_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/surveillance/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(deviceBody("cam-1", "HIKVISION"))));

        mockMvc.perform(post("/api/v1/surveillance/devices/cam-1/ptz")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("cmd", "up"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.cmd").value("up"));
    }

    @Test
    @DisplayName("POST /devices/{id}/ptz 不支持的命令返回 400")
    void ptzControl_unsupportedCommand_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/surveillance/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(deviceBody("cam-1", "HIKVISION"))));

        mockMvc.perform(post("/api/v1/surveillance/devices/cam-1/ptz")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("cmd", "rotate"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /devices/{id}/ptz 不存在设备返回 404")
    void ptzControl_nonExistingDevice_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/surveillance/devices/nope/ptz")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("cmd", "up"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PTZ 按厂商路由：GB28181 设备返回国标 A50F 指令码（camelCase 契约在适配器内翻译）")
    void ptzControl_gb28181Device_routesToGbAdapter() throws Exception {
        String gbId = "34020000001321320001";
        mockMvc.perform(post("/api/v1/surveillance/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(deviceBody(gbId, "GB28181"))));

        // 契约命令 zoomIn（camelCase）→ 国标放大位 0x80（此前直连 ONVIF 时该调用必然失败）
        mockMvc.perform(post("/api/v1/surveillance/devices/" + gbId + "/ptz")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("cmd", "zoomIn"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.result").value("A50F0180"));

        // stop → 国标「无动作位」（0x00）
        mockMvc.perform(post("/api/v1/surveillance/devices/" + gbId + "/ptz")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("cmd", "stop"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("A50F0100"));
    }

    @Test
    @DisplayName("stream 按厂商路由：GB28181 设备返回国标 INVITE 标识（不假扮可播地址）")
    void getStreamUrl_gb28181Device_returnsInviteMarker() throws Exception {
        String gbId = "34020000001321320001";
        mockMvc.perform(post("/api/v1/surveillance/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(deviceBody(gbId, "GB28181"))));

        mockMvc.perform(get("/api/v1/surveillance/devices/" + gbId + "/stream").param("channel", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rtspUrl").value(org.hamcrest.Matchers.startsWith("gb28181-invite://")));
    }

    // ===== POST /discover 发现 =====

    @Test
    @DisplayName("POST /discover 合法子网返回 200 + 设备列表")
    void discover_validSubnet_returns200() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/surveillance/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("subnet", "192.168.1.0/24"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andReturn();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("devices").size()).isEqualTo(3);
        assertThat(body.get("subnet").asText()).isEqualTo("192.168.1.0/24");
    }

    @Test
    @DisplayName("POST /discover 缺 subnet 返回 400")
    void discover_missingSubnet_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/surveillance/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ===== 综合场景 =====

    @Test
    @DisplayName("完整生命周期：注册 -> 查询 -> PTZ -> 注销 -> 404")
    void fullLifecycle_registerQueryPtzUnregister() throws Exception {
        // 注册
        mockMvc.perform(post("/api/v1/surveillance/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(deviceBody("lifecycle-1", "UNIVIEW"))))
                .andExpect(status().isOk());
        // 查询
        mockMvc.perform(get("/api/v1/surveillance/devices/lifecycle-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vendor").value("UNIVIEW"));
        // PTZ
        mockMvc.perform(post("/api/v1/surveillance/devices/lifecycle-1/ptz")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("cmd", "zoomIn"))))
                .andExpect(status().isOk());
        // 注销
        mockMvc.perform(delete("/api/v1/surveillance/devices/lifecycle-1"))
                .andExpect(status().isOk());
        // 再查 404
        mockMvc.perform(get("/api/v1/surveillance/devices/lifecycle-1"))
                .andExpect(status().isNotFound());
    }

    // ===== GET /events 全局安防事件查询 =====

    @Test
    @DisplayName("GET /events 返回 200 + 空列表 + 分页信息")
    void listEvents_returnsEmptyListWithPagination() throws Exception {
        mockMvc.perform(get("/api/v1/surveillance/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    @DisplayName("GET /events 自定义分页参数")
    void listEvents_customPagination() throws Exception {
        mockMvc.perform(get("/api/v1/surveillance/events")
                        .param("page", "2")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("GET /events 带 deviceId 过滤参数")
    void listEvents_withDeviceIdFilter() throws Exception {
        mockMvc.perform(get("/api/v1/surveillance/events")
                        .param("deviceId", "cam-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("cam-1"))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("GET /events page<0 返回 400")
    void listEvents_negativePage_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/surveillance/events")
                        .param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /events size<=0 返回 400")
    void listEvents_zeroSize_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/surveillance/events")
                        .param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /events size>1000 返回 400")
    void listEvents_oversizedSize_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/surveillance/events")
                        .param("size", "1001"))
                .andExpect(status().isBadRequest());
    }
}
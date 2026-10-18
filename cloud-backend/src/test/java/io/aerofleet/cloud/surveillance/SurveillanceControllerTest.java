package io.aerofleet.cloud.surveillance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.alarm.AlarmEvent;
import io.aerofleet.cloud.alarm.AlarmEventRepository;
import io.aerofleet.cloud.alarm.AlarmEventStore;
import org.junit.jupiter.api.AfterEach;
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
import java.util.Set;

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
    private AlarmEventStore alarmEventStore;
    /** 真实 MJPEG 服务（非 mock）：/mjpeg 的"能不能真取到帧"正是要验的行为。 */
    private MjpegStreamService mjpegStreamService;
    /** 测试期间起在本机的真帧源，@AfterEach 统一关掉。 */
    private final java.util.List<com.sun.net.httpserver.HttpServer> frameSources =
            new java.util.ArrayList<>();
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
        // GET /events 现在委托给 AlarmEventStore（此前是固定返回 items:[] 的空桩）
        alarmEventStore = newInMemoryAlarmEventStore();
        mjpegStreamService = new MjpegStreamService();
        controller = new SurveillanceController(registry, onvifClient, rapidDeployService,
                vendorAdapterRegistry, alarmEventStore, mjpegStreamService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /**
     * 造一个带内存仓储的 {@link AlarmEventStore}（与 {@code AlarmEventStoreTest} 同形：
     * Mockito 打桩 + 反射注入 private 字段）。
     */
    private static AlarmEventStore newInMemoryAlarmEventStore() {
        AlarmEventStore store = new AlarmEventStore(AlarmEventStore.DEFAULT_CAPACITY);
        AlarmEventRepository mockRepo = org.mockito.Mockito.mock(AlarmEventRepository.class);
        java.util.Map<String, AlarmEvent> eventMap = new java.util.concurrent.ConcurrentHashMap<>();
        org.mockito.Mockito.when(mockRepo.save(org.mockito.Mockito.any(AlarmEvent.class)))
                .thenAnswer(inv -> {
                    AlarmEvent e = inv.getArgument(0);
                    eventMap.put(e.getId(), e);
                    return e;
                });
        org.mockito.Mockito.when(mockRepo.findAll(org.mockito.Mockito.any(
                        org.springframework.data.domain.Pageable.class)))
                .thenAnswer(inv -> {
                    org.springframework.data.domain.Pageable pageable = inv.getArgument(0);
                    java.util.List<AlarmEvent> all = new java.util.ArrayList<>(eventMap.values());
                    all.sort(java.util.Comparator.comparingLong(AlarmEvent::getTimestampMs).reversed());
                    int start = (int) pageable.getOffset();
                    int end = Math.min(start + pageable.getPageSize(), all.size());
                    java.util.List<AlarmEvent> sub = start < all.size()
                            ? new java.util.ArrayList<>(all.subList(start, end))
                            : new java.util.ArrayList<>();
                    return new org.springframework.data.domain.PageImpl<>(sub, pageable, all.size());
                });
        org.mockito.Mockito.when(mockRepo.count())
                .thenAnswer(inv -> (long) eventMap.size());
        try {
            java.lang.reflect.Field f = AlarmEventStore.class.getDeclaredField("repository");
            f.setAccessible(true);
            f.set(store, mockRepo);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("inject repository", e);
        }
        return store;
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

    // ===== 设备状态置位与多通道（B2/B3） =====

    @Test
    @DisplayName("注册带 channels → 视图暴露 totalCameras；置位 FAULT；非法状态 400；不存在 404")
    void deviceStatusAndChannels() throws Exception {
        Map<String, Object> b = deviceBody("cam-ch", "HIKVISION");
        b.put("channels", 4);
        mockMvc.perform(post("/api/v1/surveillance/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCameras").value(4));

        mockMvc.perform(post("/api/v1/surveillance/devices/cam-ch/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "FAULT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAULT"));

        mockMvc.perform(post("/api/v1/surveillance/devices/cam-ch/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "BROKEN"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/surveillance/devices/nope/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "FAULT"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("通道端点：注册默认全在线 + /devices/{id}/channels 置位/越界 400/不存在 404（B3）")
    void deviceChannelsEndpoint() throws Exception {
        Map<String, Object> b = deviceBody("cam-ch2", "HIKVISION");
        b.put("channels", 4);
        mockMvc.perform(post("/api/v1/surveillance/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCameras").value(4))
                .andExpect(jsonPath("$.onlineCameras").value(4)); // 注册默认全在线

        // 4 路中 2 路在线（部分通道离线的真实表达）
        mockMvc.perform(post("/api/v1/surveillance/devices/cam-ch2/channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("onlineCameras", 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onlineCameras").value(2));

        // 越界（> totalCameras）400；负值 400
        mockMvc.perform(post("/api/v1/surveillance/devices/cam-ch2/channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("onlineCameras", 5))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/surveillance/devices/cam-ch2/channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("onlineCameras", -1))))
                .andExpect(status().isBadRequest());

        // 不存在 404
        mockMvc.perform(post("/api/v1/surveillance/devices/nope/channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("onlineCameras", 1))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("注册 channels 越界（0/256）返回 400")
    void registerDevice_invalidChannels_returns400() throws Exception {
        Map<String, Object> zero = deviceBody("cam-z", "HIKVISION");
        zero.put("channels", 0);
        mockMvc.perform(post("/api/v1/surveillance/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(zero)))
                .andExpect(status().isBadRequest());

        Map<String, Object> tooMany = deviceBody("cam-t", "HIKVISION");
        tooMany.put("channels", 256);
        mockMvc.perform(post("/api/v1/surveillance/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(tooMany)))
                .andExpect(status().isBadRequest());
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

    // ===== GET /devices/{id}/events SSE 事件订阅（按厂商路由） =====

    @Test
    @DisplayName("SSE 事件订阅按厂商路由：GB28181 设备经国标信令层建立订阅")
    void subscribeEvents_gb28181Device_routesToGbAdapter() throws Exception {
        String gbId = "34020000001321320001";
        mockMvc.perform(post("/api/v1/surveillance/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(deviceBody(gbId, "GB28181"))));

        // 此前直连 OnvifClient：国标设备的事件源语义错置（进 ONVIF 模拟回调表，
        // 国标信令永远不触发）。路由后走 Gb28181Adapter（构造 SUBSCRIBE 信令返回 handle）
        MvcResult result = mockMvc.perform(get("/api/v1/surveillance/devices/" + gbId + "/events"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();
        result.getRequest().getAsyncContext().complete();
    }

    @Test
    @DisplayName("SSE 事件订阅：不存在设备立即以错误终止流（200——SSE 语义，错误在流内表达）")
    void subscribeEvents_unknownDevice_errorStream() throws Exception {
        mockMvc.perform(get("/api/v1/surveillance/devices/nope/events"))
                .andExpect(status().isOk())
                .andReturn(); // emitter.completeWithError：SSE 响应头已提交为 200，
                              // 错误只能以流终止表达（HTTP 语义，非服务端 5xx）
    }

    // ===== GET /events 全局安防事件查询 =====

    /** 塞一条事件进 store（走 store.store()，与 POST /api/v1/alarms/events 同一条路）。 */
    private void seed(String id, String deviceId, AlarmEvent.EventType type,
                      AlarmEvent.Severity sev, long ts) {
        alarmEventStore.store(new AlarmEvent(id, deviceId, deviceId + "-name",
                type, sev, "desc-" + id, 39.9, 116.3, 0, ts, false));
    }

    /**
     * GET /events 真的返回事件（此前是固定 {@code items: []} 的空桩）。
     * <p>
     * <b>为什么必须先 seed 再断言</b>：旧版这条测试断言 {@code total == 0}，
     * 而空桩也返回 {@code total == 0} —— 端点是委托还是空桩，它都绿。
     * 只有先放进真实事件，才能区分这两种实现。
     */
    @Test
    @DisplayName("GET /events 返回真实事件（不是空桩），且事件形状与 /alarms/events 一致")
    void listEvents_returnsRealEventsNotStub() throws Exception {
        seed("e-1", "cam-1", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.INFO, 1000L);
        seed("e-2", "cam-2", AlarmEvent.EventType.INTRUSION, AlarmEvent.Severity.CRITICAL, 2000L);

        mockMvc.perform(get("/api/v1/surveillance/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                // 最新在前
                .andExpect(jsonPath("$.items[0].id").value("e-2"))
                // 字段投影必须与 /api/v1/alarms/events 逐字段一致
                .andExpect(jsonPath("$.items[0].sourceDeviceId").value("cam-2"))
                .andExpect(jsonPath("$.items[0].eventType").value("INTRUSION"))
                .andExpect(jsonPath("$.items[0].severity").value("CRITICAL"))
                .andExpect(jsonPath("$.items[0].description").value("desc-e-2"))
                .andExpect(jsonPath("$.items[0].timestampMs").value(2000))
                .andExpect(jsonPath("$.items[0].acknowledged").value(false));
    }

    @Test
    @DisplayName("GET /events 空 store 时返回空列表（真正无数据，而不是桩）")
    void listEvents_emptyStoreStillReturnsEmptyArray() throws Exception {
        mockMvc.perform(get("/api/v1/surveillance/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("GET /events 分页真的生效（旧版 total=0 让分页无从验证）")
    void listEvents_customPagination() throws Exception {
        seed("e-1", "cam-1", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.INFO, 1000L);
        seed("e-2", "cam-1", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.INFO, 2000L);
        seed("e-3", "cam-1", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.INFO, 3000L);

        mockMvc.perform(get("/api/v1/surveillance/events")
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value("e-3"))
                .andExpect(jsonPath("$.items[1].id").value("e-2"));

        // 第 2 页只剩 1 条 —— 旧版 total=0 时这一条无从判断
        mockMvc.perform(get("/api/v1/surveillance/events")
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("e-1"));
    }

    /**
     * deviceId 过滤**真正生效**。
     * <p>
     * 旧版这条测试同样只断言 {@code total == 0}，而空桩对任何 deviceId 都返回 0
     * —— 也就是说这个参数在它被"声明"的那段时间里**从未被验证过一次**。
     */
    @Test
    @DisplayName("GET /events 的 deviceId 过滤真正生效（此前声明了但恒返回空）")
    void listEvents_deviceIdFilterActuallyFilters() throws Exception {
        seed("e-1", "cam-1", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.INFO, 1000L);
        seed("e-2", "cam-2", AlarmEvent.EventType.FIRE, AlarmEvent.Severity.CRITICAL, 2000L);
        seed("e-3", "cam-1", AlarmEvent.EventType.DOOR, AlarmEvent.Severity.WARN, 3000L);

        mockMvc.perform(get("/api/v1/surveillance/events")
                        .param("deviceId", "cam-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("cam-1"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value("e-3"))
                .andExpect(jsonPath("$.items[1].id").value("e-1"));

        mockMvc.perform(get("/api/v1/surveillance/events")
                        .param("deviceId", "cam-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value("e-2"));

        // 不存在的设备 => 0 条（过滤生效的表现，而不是"参数被忽略"）
        mockMvc.perform(get("/api/v1/surveillance/events")
                        .param("deviceId", "cam-nope"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items").isEmpty());

        // 不过滤时应看全部 3 条
        mockMvc.perform(get("/api/v1/surveillance/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3));
    }

    /** 设备 ID 大小写敏感：CAM-1 不能匹配 cam-1。 */
    @Test
    @DisplayName("deviceId 过滤大小写敏感，不做隐式忽略")
    void listEvents_deviceIdFilterIsCaseSensitive() throws Exception {
        seed("e-1", "cam-1", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.INFO, 1000L);

        mockMvc.perform(get("/api/v1/surveillance/events")
                        .param("deviceId", "CAM-1"))
                .andExpect(status().isOk())
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

    // ===== #107 能力门控：声明了能力集但缺该项时，端点必须明确拒绝 =====

    /** 注册一台设备并覆盖它的能力声明（直接测门控本身，不测适配器）。 */
    private void registerWithCaps(String id, String vendor, Set<String> caps) throws Exception {
        mockMvc.perform(post("/api/v1/surveillance/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(deviceBody(id, vendor))))
                .andExpect(status().isOk());
        registry.getDevice(id).setCapabilities(caps);
    }

    /**
     * PTZ 门控。此前该端点<b>完全不看能力</b>：一台只声明 ALARM 的设备照样能
     * 收 PTZ 指令，而 AirGroundCoordinationService 同时因为缺 PTZ 跳过它 ——
     * 同一台设备，两处结论相反。
     */
    @Test
    @DisplayName("PTZ：设备明确声明能力集但缺 PTZ 时返回 400（并说明缺哪项）")
    void ptzControl_deviceWithoutPtzCapability_returns400() throws Exception {
        registerWithCaps("cam-alarm", "HIKVISION", Set.of("ALARM", "DEVICE"));

        mockMvc.perform(post("/api/v1/surveillance/devices/cam-alarm/ptz")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("cmd", "up"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("does not support capability PTZ")))
                .andExpect(jsonPath("$.capability").value("PTZ"))
                .andExpect(jsonPath("$.deviceId").value("cam-alarm"))
                // 必须回带它声明了什么，否则前端只能显示一句无信息量的失败
                .andExpect(jsonPath("$.declared").isArray())
                .andExpect(jsonPath("$.declared").value(org.hamcrest.Matchers.hasItem("ALARM")));
    }

    @Test
    @DisplayName("取流：设备明确声明能力集但缺 MEDIA 时返回 400")
    void getStreamUrl_deviceWithoutMediaCapability_returns400() throws Exception {
        registerWithCaps("cam-alarm2", "HIKVISION", Set.of("DEVICE", "ALARM"));

        mockMvc.perform(get("/api/v1/surveillance/devices/cam-alarm2/stream"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.capability").value("MEDIA"));
    }

    /**
     * 未声明能力 ≠ 不支持。这条钉住「不明确不支持即放行」的口径：
     * 早期注册、没写能力的设备若被拒，就是功能减法而不是诚实。
     */
    @Test
    @DisplayName("能力未声明时放行（未知不等于不支持），PTZ 与取流都不拦")
    void undeclaredCapabilities_doNotBlock() throws Exception {
        registerWithCaps("cam-unknown", "HIKVISION", Set.of());

        mockMvc.perform(post("/api/v1/surveillance/devices/cam-unknown/ptz")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("cmd", "left"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/surveillance/devices/cam-unknown/stream"))
                .andExpect(status().isOk());
    }

    /**
     * GB28181 相机声明的是 {@code STREAM} 而不是 {@code MEDIA}。
     * 若门控按字面名匹配，它的取流会被误判成「不支持」—— 那正是本类
     * 要消灭的假阴性。
     */
    @Test
    @DisplayName("GB28181 的 STREAM 别名能通过 MEDIA 门控（别名折叠生效）")
    void getStreamUrl_gb28181StreamAliasPassesMediaGate() throws Exception {
        String gbId = "34020000001321320001";
        registerWithCaps(gbId, "GB28181", Set.of("PTZ", "STREAM", "RECORD"));

        mockMvc.perform(get("/api/v1/surveillance/devices/" + gbId + "/stream"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GB28181 报警设备只有 ALARM，取流被明确拒绝")
    void getStreamUrl_gb28181AlarmDevice_rejected() throws Exception {
        String gbId = "34020000001321320099";
        registerWithCaps(gbId, "GB28181", Set.of("ALARM"));

        mockMvc.perform(get("/api/v1/surveillance/devices/" + gbId + "/stream"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.capability").value("MEDIA"));
    }

    /** 设备视图必须把三态能力位交给前端，否则前端无从禁用按钮。 */
    @Test
    @DisplayName("设备视图暴露归一后的能力与三态能力位")
    void deviceView_exposesNormalizedCapabilitiesAndTriStateFlags() throws Exception {
        registerWithCaps("cam-mixed", "HIKVISION", Set.of("PTZ", "STREAM", "Device"));
        registerWithCaps("cam-blank", "HIKVISION", Set.of());

        mockMvc.perform(get("/api/v1/surveillance/devices/cam-mixed"))
                .andExpect(status().isOk())
                // STREAM 已折叠成 MEDIA，Device 已大写成 DEVICE
                .andExpect(jsonPath("$.capabilities").value(org.hamcrest.Matchers.hasItem("MEDIA")))
                .andExpect(jsonPath("$.capabilities").value(org.hamcrest.Matchers.hasItem("DEVICE")))
                .andExpect(jsonPath("$.capabilities").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("STREAM"))))
                // 归一前的原样保留，便于排查
                .andExpect(jsonPath("$.rawCapabilities").value(
                        org.hamcrest.Matchers.hasItem("STREAM")))
                .andExpect(jsonPath("$.ptzSupport").value(true))
                .andExpect(jsonPath("$.mediaSupport").value(true))
                // 没声明 Events ⇒ 明确不支持 ⇒ false
                .andExpect(jsonPath("$.eventsSupport").value(false));

        // 未声明 ⇒ null（不是 false：压成 false 会让前端灰掉其实能用的设备）
        mockMvc.perform(get("/api/v1/surveillance/devices/cam-blank"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ptzSupport").doesNotExist())
                .andExpect(jsonPath("$.mediaSupport").doesNotExist());
    }

    // ===== #109 流地址来源必须自述 =====

    /**
     * 私有协议适配器按厂商公开格式推导 {@code rtsp://}。这个格式对真实设备
     * <b>可能正确</b>，所以不能改成"不可用"（那是错杀）；但代码从未向设备确认过，
     * 所以也<b>不能只给一个 rtsp 地址就完事</b> —— 那等于把"也许能用"讲成了
     * "能用"。响应必须说清来源与是否经设备校验。
     */
    @Test
    @DisplayName("私有协议设备：rtsp 地址按厂商规范推导，playable=true 但 deviceVerified=false")
    void streamUrl_vendorStandardFormat_isPlayableButUnverified() throws Exception {
        mockMvc.perform(post("/api/v1/surveillance/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(deviceBody("cam-hik", "HIKVISION"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/surveillance/devices/cam-hik/stream"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rtspUrl").value(
                        org.hamcrest.Matchers.startsWith("rtsp://")))
                .andExpect(jsonPath("$.streamUrlOrigin").value("VENDOR_STANDARD_FORMAT"))
                .andExpect(jsonPath("$.playable").value(true))
                // 关键：从来没有向设备取过
                .andExpect(jsonPath("$.deviceVerified").value(false));
    }

    /**
     * GB28181 媒体面未实现，只能给描述性标识。它必须明确标记为不可播 ——
     * 否则前端会拿一个必然连不上的地址去挂播放器。
     */
    @Test
    @DisplayName("GB28181：返回描述性标识且 playable=false")
    void streamUrl_gb28181_isDescriptiveOnlyAndNotPlayable() throws Exception {
        String gbId = "34020000001321320001";
        mockMvc.perform(post("/api/v1/surveillance/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(deviceBody(gbId, "GB28181"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/surveillance/devices/" + gbId + "/stream"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rtspUrl").value(
                        org.hamcrest.Matchers.startsWith("gb28181-invite://")))
                // 已有守卫要求它绝不假扮可播的 rtsp://（Gb28181ShapeTest），
                // 这里再加一条：REST 契约本身也必须说清它不可播
                .andExpect(jsonPath("$.streamUrlOrigin").value("DESCRIPTIVE_ONLY"))
                .andExpect(jsonPath("$.playable").value(false))
                .andExpect(jsonPath("$.deviceVerified").value(false));
    }

    /** 凭据仍要脱敏，且脱敏不能破坏来源字段。 */
    @Test
    @DisplayName("来源字段与凭据脱敏互不影响")
    void streamUrl_originSurvivesCredentialMasking() throws Exception {
        mockMvc.perform(post("/api/v1/surveillance/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(deviceBody("cam-hik2", "HIKVISION"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/surveillance/devices/cam-hik2/stream"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.streamUrlOrigin").value("VENDOR_STANDARD_FORMAT"))
                .andExpect(jsonPath("$.playable").value(true));
    }

    // ===== 浏览器可播画面：/mjpeg =====

    /**
     * 起一个真 HTTP 帧源，产出真 JPEG 字节（不是 mock HTTP 客户端 ——
     * 要验的恰恰是"能不能从设备真的取到帧"）。
     */
    private com.sun.net.httpserver.HttpServer startFrameSource(byte[] jpegBytes, int maxFrames)
            throws Exception {
        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.atomic.AtomicInteger served = new java.util.concurrent.atomic.AtomicInteger();
        srv.createContext("/snapshot", ex -> {
            if (served.incrementAndGet() > maxFrames) {
                // 帧源掉线：pump 应当就此结束响应。MockMvc 的 asyncDispatch 需要流真的
                // 结束才能返回，所以测试里的帧源不能永续 —— 顺带把"掉线即结束"这条
                // 行为也验了（若实现改成发占位帧撑住，这里会超时或帧数对不上）。
                ex.sendResponseHeaders(500, -1);
                ex.close();
                return;
            }
            ex.getResponseHeaders().add("Content-Type", "image/jpeg");
            ex.sendResponseHeaders(200, jpegBytes.length);
            try (java.io.OutputStream os = ex.getResponseBody()) {
                os.write(jpegBytes);
            }
            ex.close();
        });
        srv.start();
        frameSources.add(srv);
        return srv;
    }

    private String startLimitedFrameSource(byte[] jpegBytes, int maxFrames) throws Exception {
        return "http://127.0.0.1:"
                + startFrameSource(jpegBytes, maxFrames).getAddress().getPort() + "/snapshot";
    }

    /**
     * {@code /mjpeg} 的每个分支都返回 {@code StreamingResponseBody}，MockMvc 可能
     * 走同步也可能走异步。两种都接住，但**断言不放松**。
     */
    private MvcResult performMjpeg(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rb)
            throws Exception {
        MvcResult r = mockMvc.perform(rb).andReturn();
        if (r.getResolvedException() != null) {
            throw new IllegalStateException("/mjpeg threw: ", r.getResolvedException());
        }
        if (r.getRequest().isAsyncStarted()) {
            r = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .asyncDispatch(r)).andReturn();
        }
        return r;
    }

    private static byte[] realJpegBytes() throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(64, 48,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            g.setColor(java.awt.Color.DARK_GRAY);
            g.fillRect(0, 0, 64, 48);
            g.setColor(java.awt.Color.WHITE);
            g.drawString("cam", 6, 26);
        } finally {
            g.dispose();
        }
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "jpg", bo);
        return bo.toByteArray();
    }

    private static Map<String, Object> deviceBodyWithSnapshot(String id, String snapshotUrl) {
        Map<String, Object> b = deviceBody(id, "HIKVISION");
        b.put("snapshotUrl", snapshotUrl);
        return b;
    }

    /**
     * 主线：配了真帧源的设备，{@code /mjpeg} 真的吐 multipart 且第一帧是真 JPEG。
     * 这是"安防视频看不了"变成"能看"的那一步的判定。
     */
    @Test
    @DisplayName("配了帧源：/mjpeg 发 multipart 且首帧是设备返回的真 JPEG")
    void mjpeg_realFrameSource_streamsRealJpeg() throws Exception {
        byte[] jpegBytes = realJpegBytes();
        // 帧源供 3 帧后掉线：流会自然结束，asyncDispatch 才能返回
        String snapshotUrl = startLimitedFrameSource(jpegBytes, 3);

        mockMvc.perform(post("/api/v1/surveillance/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(deviceBodyWithSnapshot("cam-live", snapshotUrl))))
                .andExpect(status().isOk());

        MvcResult res = performMjpeg(get("/api/v1/surveillance/devices/cam-live/mjpeg")
                .param("fps", "50"));

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(res.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(res.getResponse().getContentType()).startsWith("multipart/x-mixed-replace");

        byte[] body = res.getResponse().getContentAsByteArray();
        String text = new String(body, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(text).contains("--" + MjpegStreamService.boundary());
        assertThat(text).contains("Content-Type: image/jpeg");
        // 帧源供 3 帧（含探帧那次），pump 写首帧 + 后续帧，然后因掉线收尾。
        // 关键不是帧数，而是**流里确实出现了设备那张图本身**：
        // 第一段 part 的数据区必须逐字节等于帧源返回的 JPEG。
        assertThat(text).contains("X-Frame-Seq: 1");
        int jpegStart = text.indexOf("X-Frame-Seq: 1");
        jpegStart = text.indexOf("\r\n\r\n", jpegStart) + 4;
        byte[] emitted = java.util.Arrays.copyOfRange(body, jpegStart,
                jpegStart + jpegBytes.length);
        assertThat(emitted).isEqualTo(jpegBytes);
    }

    /**
     * 没配帧源必须明确拒绝，<b>不能</b>返回空流或占位帧。
     * 若返回 200 空体，前端会挂一个永远不更新的 {@code <img>} ——
     * 看上去"在播"，实际是死的，正是本轮要消灭的假象。
     */
    @Test
    @DisplayName("未配帧源：/mjpeg 返回 400 并说明原因（不是空流）")
    void mjpeg_noFrameSource_is400WithReason() throws Exception {
        mockMvc.perform(post("/api/v1/surveillance/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(deviceBody("cam-nosrc", "HIKVISION"))))
                .andExpect(status().isOk());

        MvcResult r = performMjpeg(get("/api/v1/surveillance/devices/cam-nosrc/mjpeg"));

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentType()).startsWith("application/json");
        assertThat(r.getResponse().getContentAsString())
                .contains("no browser-playable frame source")
                .contains("snapshotUrl");
        // 关键：错误响应里不能出现帧流分隔符 —— 有 body 却没画面，
        // 前端挂上去就是一个"在播"的死 <img>
        assertThat(r.getResponse().getContentAsString())
                .doesNotContain("--" + MjpegStreamService.boundary());
    }

    /**
     * 帧源不可达时必须在<b>开流前</b>就报错。若先回 200 再等帧，
     * 浏览器会拿到一个 200 却永远等不到第一帧，界面上表现为"一直转圈"。
     */
    @Test
    @DisplayName("帧源不可达：/mjpeg 返回 502（不是 200 后干等）")
    void mjpeg_frameSourceUnreachable_is502NotHanging200() throws Exception {
        mockMvc.perform(post("/api/v1/surveillance/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(deviceBodyWithSnapshot("cam-dead", "http://127.0.0.1:1/snapshot"))))
                .andExpect(status().isOk());

        MvcResult r = performMjpeg(get("/api/v1/surveillance/devices/cam-dead/mjpeg"));

        assertThat(r.getResponse().getStatus()).isEqualTo(502);
        assertThat(r.getResponse().getContentAsString())
                .contains("frame source unreachable")
                .contains("http://127.0.0.1:1/snapshot");
        // 不可达时绝不能先回 200 再干等
        assertThat(r.getResponse().getContentType()).startsWith("application/json");
    }

    /**
     * 帧源回 HTML 错误页时也必须判失败。否则界面会显示"一张画得出的图"，
     * 而设备其实没在推流。
     */
    @Test
    @DisplayName("帧源返回的不是 JPEG：/mjpeg 返回 502（不当帧发）")
    void mjpeg_frameSourceNotJpeg_is502() throws Exception {
        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/snapshot", ex -> {
            byte[] html = "<html>404</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(404, html.length);
            try (java.io.OutputStream os = ex.getResponseBody()) {
                os.write(html);
            }
            ex.close();
        });
        srv.start();
        frameSources.add(srv);

        mockMvc.perform(post("/api/v1/surveillance/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(deviceBodyWithSnapshot("cam-html",
                        "http://127.0.0.1:" + srv.getAddress().getPort() + "/snapshot"))))
                .andExpect(status().isOk());

        MvcResult r = performMjpeg(get("/api/v1/surveillance/devices/cam-html/mjpeg"));

        assertThat(r.getResponse().getStatus()).isEqualTo(502);
        assertThat(r.getResponse().getContentAsString()).contains("not JPEG");
    }

    @Test
    @DisplayName("未知设备：/mjpeg 返回 404")
    void mjpeg_unknownDevice_is404() throws Exception {
        MvcResult r = performMjpeg(get("/api/v1/surveillance/devices/ghost/mjpeg"));

        assertThat(r.getResponse().getStatus()).isEqualTo(404);
        assertThat(r.getResponse().getContentAsString()).contains("ghost");
    }

    /**
     * 设备视图里的可播标志必须由"是否真的配了帧源"决定，而不是由厂商或端口推出来。
     */
    @Test
    @DisplayName("设备视图：配了帧源 browserPlayable=true 且回显 snapshotUrl")
    void deviceView_frameSourceConfigured_isPlayable() throws Exception {
        String snapshotUrl = startLimitedFrameSource(realJpegBytes(), 3);

        mockMvc.perform(post("/api/v1/surveillance/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(deviceBodyWithSnapshot("cam-play", snapshotUrl))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/surveillance/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices[0].id").value("cam-play"))
                .andExpect(jsonPath("$.devices[0].browserPlayable").value(true))
                .andExpect(jsonPath("$.devices[0].snapshotUrl").value(snapshotUrl));
    }

    @Test
    @DisplayName("设备视图：没配帧源 browserPlayable=false 且 snapshotUrl=null")
    void deviceView_noFrameSource_isNotPlayable() throws Exception {
        mockMvc.perform(post("/api/v1/surveillance/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(deviceBody("cam-nop", "HIKVISION"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/surveillance/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices[0].id").value("cam-nop"))
                .andExpect(jsonPath("$.devices[0].browserPlayable").value(false))
                .andExpect(jsonPath("$.devices[0].snapshotUrl").value(org.hamcrest.Matchers.nullValue()))
                // 关键：没配帧源时不能凭空造一个可播地址出来
                .andExpect(jsonPath("$.devices[0].rtspUrl").value(org.hamcrest.Matchers.nullValue()));
    }

    @AfterEach
    void stopFrameSources() {
        for (com.sun.net.httpserver.HttpServer s : frameSources) {
            s.stop(0);
        }
        frameSources.clear();
    }
}
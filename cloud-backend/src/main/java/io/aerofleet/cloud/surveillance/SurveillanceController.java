package io.aerofleet.cloud.surveillance;

import com.fasterxml.jackson.databind.JsonNode;
import io.aerofleet.cloud.alarm.AlarmController;
import io.aerofleet.cloud.alarm.AlarmEvent;
import io.aerofleet.cloud.alarm.AlarmEventStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;

/**
 * 安防设备 REST API。
 * <p>
 * 端点前缀 {@code /api/v1/surveillance}，覆盖 ONVIF 安防设备全生命周期：
 * <ul>
 *   <li>设备注册 / 列表 / 详情 / 注销</li>
 *   <li>RTSP 流 URL 获取（{@code /stream}）</li>
 *   <li>PTZ 控制（{@code /ptz}）</li>
 *   <li>子网设备发现（{@code /discover}）</li>
 *   <li>事件订阅 SSE（{@code /devices/{id}/events}）</li>
 *   <li>全局安防事件查询（{@code /events}）</li>
 * </ul>
 * <p>
 * 错误响应统一使用 {@code {"error": "..."}} 格式，与
 * {@link io.aerofleet.cloud.api.exception.ApiExceptionHandler} 风格一致。
 */
@RestController
@RequestMapping("/api/v1/surveillance")
@Tag(name = "Surveillance", description = "安防设备 REST API：ONVIF 设备全生命周期管理、RTSP 流、PTZ 控制、事件订阅")
@RequireRole(Role.OBSERVER)
public class SurveillanceController {

    private static final Logger log = LoggerFactory.getLogger(SurveillanceController.class);

    /** SSE 心跳间隔（秒）。 */
    private static final long SSE_HEARTBEAT_SECONDS = 15L;
    /** SSE 超时时间（毫秒，30 分钟）。 */
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    private final SurveillanceDeviceRegistry registry;
    private final OnvifClient onvifClient;
    private final RapidDeployService rapidDeployService;
    /** 按设备厂商路由（E5 后 GB28181 设备可注册：PTZ/流地址必须走对应适配器）。 */
    private final VendorAdapterRegistry vendorAdapterRegistry;
    /**
     * 安防事件存储。
     * <p>
     * 本端点此前返回的是永久空桩（固定 {@code items: [] / total: 0}），注释写着
     * 「全局 SurveillanceEventStore 尚未实现」。但真正的原因不是缺 Store，而是：
     * <ol>
     *   <li>本包原有的 {@code SurveillanceEvent} 模型（已删除）的 EventType 枚举与
     *       {@code AlarmEvent} <b>逐字相同</b>
     *       （MOTION/INTRUSION/FIRE/DOOR/CUSTOM）、Severity 也相同，{@code AlarmEvent}
     *       的 javadoc 自己写着「描述<em>安防设备（海康/大华/宇视）</em>触发的一次报警」
     *       —— 它们是同一件事的两个模型；</li>
     *   <li>{@code AlarmEventStore} + {@code POST/GET /api/v1/alarms/events} 已经是
     *       <b>端到端可用</b>的管线（POST 收事件 → 存储 → 分页查询）；</li>
     *   <li>三个厂商适配器的 {@code subscribeEvents} 全是桩，从不触发 callback
     *       （GB28181 适配器日志里明写「callback will not fire in PoC」），
     *       所以不存在「摄像头直连事件」这条源。</li>
     * </ol>
     * 结论：再造一个 {@code SurveillanceEventStore} 只会得到另一个空桩。故本端点改为
     * 委托给真实存在的 {@link AlarmEventStore}，顺带让此前形同虚设的 {@code deviceId}
     * 过滤参数真正生效。
     */
    private final AlarmEventStore alarmEventStore;
    /**
     * 浏览器可播的 MJPEG 输出（见 {@code /devices/{id}/mjpeg}）。
     * <p>本平台唯一让浏览器看到安防画面的通道 —— 浏览器不能播 RTSP，而这里没有
     * RTSP 解码器。独立成 service 而不是塞进控制器，是为了能单独测"取帧"这件事。
     */
    private final MjpegStreamService mjpegService;
    /** SSE 心跳调度器：单线程足够，多个 emitter 共享。 */
    private final ScheduledExecutorService sseScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "surveillance-sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    public SurveillanceController(SurveillanceDeviceRegistry registry,
                                  OnvifClient onvifClient,
                                  RapidDeployService rapidDeployService,
                                  VendorAdapterRegistry vendorAdapterRegistry,
                                  AlarmEventStore alarmEventStore,
                                  MjpegStreamService mjpegService) {
        this.registry = registry;
        this.onvifClient = onvifClient;
        this.rapidDeployService = rapidDeployService;
        this.vendorAdapterRegistry = vendorAdapterRegistry;
        this.alarmEventStore = alarmEventStore;
        this.mjpegService = mjpegService;
    }

    /**
     * 容器销毁时关闭 SSE 心跳调度器，避免 Spring 热重载场景下线程泄漏。
     * <p>
     * 幂等安全：多次调用 shutdownNow() 不抛异常。
     */
    @PreDestroy
    public void shutdown() {
        sseScheduler.shutdownNow();
        log.info("SSE heartbeat scheduler shut down");
    }

    // ------------------------------------------------------------------
    // 设备 CRUD
    // ------------------------------------------------------------------

    /**
     * 注册安防设备。
     * <p>
     * body 字段：id, name, vendor(HIKVISION/DAHUA/UNIVIEW), ip, port, username, password
     */
    @Operation(summary = "注册安防设备", description = "body 字段：id, name, vendor(HIKVISION/DAHUA/UNIVIEW), ip, port, username, password")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "注册成功"),
        @ApiResponse(responseCode = "400", description = "参数错误")
    })
    @PostMapping("/devices")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> registerDevice(@RequestBody JsonNode body) {
        String id = body.path("id").asText("");
        String name = body.path("name").asText("");
        String vendorStr = body.path("vendor").asText("");
        String ip = body.path("ip").asText("");
        int port = body.path("port").asInt(OnvifClient.DEFAULT_PORT);
        int channels = body.path("channels").asInt(1);
        String username = body.path("username").asText("");
        String password = body.path("password").asText("");
        // 浏览器可播的帧源：**显式配置**，不按厂商规范推导 —— 理由见
        // SurveillanceDevice#snapshotUrl（推导出来的地址会再一次变成"看着对但没验证过"）
        String snapshotUrl = body.path("snapshotUrl").asText("");

        if (id.isBlank()) {
            return badRequest("device id is required");
        }
        SurveillanceDevice.Vendor vendor;
        try {
            vendor = SurveillanceDevice.Vendor.valueOf(vendorStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return badRequest("unknown vendor: " + vendorStr
                    + ", supported: HIKVISION/DAHUA/UNIVIEW");
        }
        if (ip.isBlank()) {
            return badRequest("ip is required");
        }
        if (port <= 0 || port > 65535) {
            return badRequest("port must be in [1, 65535]");
        }
        if (channels < 1 || channels > 255) {
            return badRequest("channels must be in [1, 255]");
        }

        SurveillanceDevice device = new SurveillanceDevice(id, name, vendor, ip, port,
                username, password);
        device.totalCameras = channels;
        device.onlineCameras = channels; // 注册时默认全在线
        if (!snapshotUrl.isBlank()) {
            device.snapshotUrl = snapshotUrl.trim();
        }
        // 注册时同步获取设备能力（模拟实现）
        try {
            device.setCapabilities(onvifClient.getDeviceCapabilities(ip, port, username, password));
        } catch (Exception e) {
            log.warn("GetCapabilities failed for {}:{}: {}", ip, port, e.getMessage());
        }
        registry.register(device);
        log.info("Surveillance device registered via REST: id={}", id);
        return ResponseEntity.ok(deviceView(device));
    }

    @Operation(summary = "列出所有安防设备")
    @ApiResponse(responseCode = "200", description = "设备列表")
    @GetMapping("/devices")
    public ResponseEntity<Map<String, Object>> listDevices() {
        List<SurveillanceDevice> all = registry.listDevices();
        List<Map<String, Object>> view = new ArrayList<>(all.size());
        for (SurveillanceDevice d : all) {
            view.add(deviceView(d));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", all.size());
        result.put("devices", view);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "获取设备详情")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "设备详情"),
        @ApiResponse(responseCode = "404", description = "设备不存在")
    })
    @GetMapping("/devices/{id}")
    public ResponseEntity<Map<String, Object>> getDevice(@PathVariable("id") String id) {
        SurveillanceDevice d = registry.getDevice(id);
        if (d == null) {
            return notFound("device " + id + " not found");
        }
        return ResponseEntity.ok(deviceView(d));
    }

    @Operation(summary = "注销设备")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "注销成功"),
        @ApiResponse(responseCode = "404", description = "设备不存在")
    })
    @DeleteMapping("/devices/{id}")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> unregisterDevice(@PathVariable("id") String id) {
        SurveillanceDevice removed = registry.unregister(id);
        if (removed == null) {
            return notFound("device " + id + " not found");
        }
        return ResponseEntity.ok(Map.of("status", "ok", "id", id));
    }

    // ------------------------------------------------------------------
    // 设备状态置位（FAULT/MAINTENANCE 四态运维面）
    // ------------------------------------------------------------------

    @Operation(summary = "置位设备状态",
            description = "body: {\"status\": \"ONLINE\"/\"OFFLINE\"/\"FAULT\"/\"MAINTENANCE\"}。"
                    + "FAULT/MAINTENANCE 是人工运维置位：心跳与离线扫描不覆盖，恢复需显式置回 ONLINE")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "置位成功"),
        @ApiResponse(responseCode = "400", description = "status 非法"),
        @ApiResponse(responseCode = "404", description = "设备不存在")
    })
    @PostMapping("/devices/{id}/status")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> setDeviceStatus(@PathVariable("id") String id,
                                                               @RequestBody JsonNode body) {
        SurveillanceDevice d = registry.getDevice(id);
        if (d == null) {
            return notFound("device " + id + " not found");
        }
        String statusStr = body.path("status").asText("");
        SurveillanceDevice.Status status;
        try {
            status = SurveillanceDevice.Status.valueOf(statusStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return badRequest("unknown status: " + statusStr
                    + ", supported: ONLINE/OFFLINE/FAULT/MAINTENANCE");
        }
        SurveillanceDevice.Status previous = d.status;
        d.status = status;
        log.info("Surveillance device status changed via REST: id={} {} -> {}", id, previous, status);
        return ResponseEntity.ok(deviceView(d));
    }

    /**
     * 置位在线通道数（B3：部分通道离线的真实表达——4 路中 2 路在线）。
     * <p>
     * 语义：{@code onlineCameras ∈ [0, totalCameras]}；链路断（设备 OFFLINE）时
     * 由扫描归零，心跳恢复时默认全恢复——本端点是运维对"设备侧通道故障"的手动标记。
     */
    @PostMapping("/devices/{id}/channels")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> setOnlineCameras(@PathVariable("id") String id,
                                                                @RequestBody JsonNode body) {
        SurveillanceDevice d = registry.getDevice(id);
        if (d == null) {
            return notFound("device " + id + " not found");
        }
        int n = body.path("onlineCameras").asInt(-1);
        if (n < 0 || n > d.totalCameras) {
            return badRequest("onlineCameras must be in [0, " + d.totalCameras + "]");
        }
        d.onlineCameras = n;
        log.info("Surveillance device channels changed via REST: id={} online={}/{}",
                id, n, d.totalCameras);
        return ResponseEntity.ok(deviceView(d));
    }

    // ------------------------------------------------------------------
    // RTSP 流 / PTZ / 发现
    // ------------------------------------------------------------------

    @Operation(summary = "获取 RTSP 流 URL", description = "channel 参数指定通道号（默认 1）")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "RTSP URL"),
        @ApiResponse(responseCode = "400", description = "通道号非法"),
        @ApiResponse(responseCode = "404", description = "设备不存在"),
        @ApiResponse(responseCode = "502", description = "获取流 URL 失败")
    })
    @GetMapping("/devices/{id}/stream")
    public ResponseEntity<Map<String, Object>> getStreamUrl(@PathVariable("id") String id,
                                                            @RequestParam(value = "channel",
                                                                    defaultValue = "1") int channel) {
        SurveillanceDevice d = registry.getDevice(id);
        if (d == null) {
            return notFound("device " + id + " not found");
        }
        if (channel < 1) {
            return badRequest("channel must be >= 1");
        }
        // 能力门控：设备声明了能力集、但其中没有 MEDIA 时明确拒绝。
        // 口径见 DeviceCapability —— 这里用"不明确不支持即放行"：这是运维的
        // 手动操作，未声明能力的早期设备不该平白失去取流（那会是功能减法）。
        if (!DeviceCapability.notExplicitlyUnsupported(d.getCapabilities(), DeviceCapability.MEDIA)) {
            return unsupported(d, DeviceCapability.MEDIA);
        }
        try {
            // 按设备厂商路由（ONVIF 适配器内部复用 OnvifClient；GB28181 返回
            // gb28181-invite:// 描述性标识——E5 口径：不假扮可播地址）
            VendorAdapter adapter = vendorAdapterRegistry.getAdapter(d);
            String url = adapter.getStreamUrl(d, channel);
            d.rtspUrl = url;
            StreamUrlOrigin origin = adapter.streamUrlOrigin();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("deviceId", id);
            result.put("channel", channel);
            result.put("rtspUrl", maskRtspCredentials(url));
            // 如实说明这个地址是怎么来的：按厂商规范推导（可能可播但未向设备校验），
            // 还是仅供描述（不可播）。前端据此决定是挂播放器还是说明放不出画面，
            // 而不是靠猜 URL 长得像不像 rtsp://。
            result.put("streamUrlOrigin", origin.name());
            result.put("playable", origin.playable());
            result.put("deviceVerified", origin.deviceVerified());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("GetStreamUri failed for {}: {}", id, e.getMessage());
            return ResponseEntity.status(502).body(
                    Map.of("error", "failed to get rtsp url: " + e.getMessage()));
        }
    }

    /**
     * 浏览器可播的实时画面（MJPEG）。
     *
     * <p>这是本平台目前<b>唯一</b>让浏览器真正看到安防画面的通道：浏览器不能原生播
     * RTSP，而本平台没有 RTSP 解码器（不引入 ffmpeg / javacv）。所以这里从设备
     * <b>显式配置</b>的 HTTP 快照源（{@link SurveillanceDevice#snapshotUrl}）按帧率取帧，
     * 发出 {@code multipart/x-mixed-replace}，前端用 {@code <img src>} 即可渲染。
     *
     * <p><b>开流之前先探一帧</b>：帧源不可达时返回明确的 502，而不是先回 200 再让浏览器
     * 干等 —— 后者表现为"一直转圈"，使用者会以为是网络慢。
     *
     * <p>没配置帧源的设备返回 400 并说明原因，不返回空流：没有画面就是没有画面。
     */
    @Operation(summary = "浏览器可播实时画面（MJPEG multipart）",
            description = "从设备配置的 HTTP 快照源取帧，multipart/x-mixed-replace。需先配置 snapshotUrl。")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "MJPEG 流"),
        @ApiResponse(responseCode = "400", description = "设备未配置帧源"),
        @ApiResponse(responseCode = "404", description = "设备不存在"),
        @ApiResponse(responseCode = "502", description = "帧源不可达或返回的不是 JPEG")
    })
    @GetMapping("/devices/{id}/mjpeg")
    public StreamingResponseBody mjpegStream(
            @PathVariable("id") String id,
            @RequestParam(value = "fps", defaultValue = "5") int fps,
            HttpServletResponse response) throws java.io.IOException {
        // 必须**裸返回** StreamingResponseBody，不能包 ResponseEntity：
        // 包了之后返回类型变成 ResponseEntity<StreamingResponseBody>，命中的是
        // ResponseEntityReturnValueHandler → HttpEntityMethodProcessor，它会把
        // 这个 lambda 当普通消息体去找转换器，直接抛
        // "No converter for StreamingResponseBody with preset Content-Type
        // multipart/x-mixed-replace"（500）。响应头因此只能靠 HttpServletResponse 设。
        //
        // 失败路径也返回 StreamingResponseBody，但写的是一个定长 JSON 体并设好状态码，
        // 所以调用方拿到的仍是普通的 4xx/5xx JSON —— "为什么看不到画面"恰恰是这里
        // 最需要说清的事，不能退化成"一个 200 然后永远等不到第一帧"。
        //
        // 鉴权不在这里：生产链上 {@code .anyRequest().authenticated()} 照常把守，
        // 而 <img> 带不了 Authorization 头，所以凭据经 ?streamToken= 短令牌进入 ——
        // 由 StreamTokenFilter 建立 SecurityContext（已含本路径）。
        // 这里**不要**再加一条"长效 JWT 进 query"的后门：那样等于把 bearer 暴露给
        // 所有能看到访问日志的人，与 StreamTokenService 存在的理由相反。
        SurveillanceDevice d = registry.getDevice(id);
        if (d == null) {
            return error(response, 404, "device " + id + " not found");
        }
        String frameSource = d.snapshotUrl;
        if (frameSource == null || frameSource.isBlank()) {
            return error(response, 400,
                    "device " + id + " has no browser-playable frame source: snapshotUrl is not"
                    + " configured (browsers cannot play the rtsp:// url, and this platform has"
                    + " no RTSP decoder)");
        }
        // 先探一帧再决定要不要开流：探不过就不给 200。
        byte[] first = mjpegService.fetchFrame(frameSource);
        if (first == null || first.length == 0) {
            return error(response, 502,
                    "frame source unreachable or not JPEG: " + frameSource);
        }
        log.info("MJPEG stream opened for device {} at {} fps (frame source {})",
                id, fps, frameSource);
        response.setContentType(MjpegStreamService.contentType());
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        // 帧循环只有 service 一份实现：这里不再内联第二份 —— 之前内联过一次，
        // 那是"同一份逻辑两处写"的典型，两边会各自漂移。
        return out -> {
            try {
                mjpegService.pump(frameSource, fps, first, out);
            } catch (java.io.IOException e) {
                // 客户端断开是常态（关标签页/切视图），不是错误
                log.debug("MJPEG stream for {} closed by client: {}", id, e.toString());
            }
            log.info("MJPEG stream for {} ended", id);
        };
    }

    /**
     * 回一个定长 JSON 错误体。
     *
     * <p>必须设 {@code Content-Length}：不定长的话响应会走 chunked，客户端与
     * 中间件对"这个错误响应到底完没完"的判断会不一致。
     */
    private static StreamingResponseBody error(
            HttpServletResponse response, int status, String message) {
        byte[] body;
        try {
            body = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsBytes(Map.of("error", message));
        } catch (Exception e) {
            body = ("{\"error\":\"internal error\"}").getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        final byte[] payload = body;
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(payload.length);
        response.setHeader("Cache-Control", "no-store");
        return out -> out.write(payload);
    }

    @Operation(summary = "PTZ 控制", description = "body: {\"cmd\": \"up\"/\"down\"/\"left\"/\"right\"/\"zoomIn\"/\"zoomOut\"/\"stop\"}")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "控制成功"),
        @ApiResponse(responseCode = "400", description = "cmd 非法"),
        @ApiResponse(responseCode = "404", description = "设备不存在"),
        @ApiResponse(responseCode = "502", description = "PTZ 控制失败")
    })
    @PostMapping("/devices/{id}/ptz")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> ptzControl(@PathVariable("id") String id,
                                                          @RequestBody JsonNode body) {
        SurveillanceDevice d = registry.getDevice(id);
        if (d == null) {
            return notFound("device " + id + " not found");
        }
        String cmd = body.path("cmd").asText("");
        if (cmd.isBlank()) {
            return badRequest("cmd is required");
        }
        if (!OnvifClient.PTZ_COMMANDS.contains(cmd)) {
            return badRequest("unsupported cmd: " + cmd
                    + ", supported: " + OnvifClient.PTZ_COMMANDS);
        }
        // 能力门控：此前完全不看能力——同一台设备，AirGroundCoordinationService
        // 会因为缺 PTZ 跳过它，这里却照单全收。口径同上（手动操作，未声明不阻断）。
        if (!DeviceCapability.notExplicitlyUnsupported(d.getCapabilities(), DeviceCapability.PTZ)) {
            return unsupported(d, DeviceCapability.PTZ);
        }
        try {
            // 按设备厂商路由：此前直连 ONVIF，GB28181 设备必然失败——
            // REST 契约用 camelCase（zoomIn/zoomOut），国标内部用 snake_case，
            // 两种命名互不认；翻译职责在适配器内（见 Gb28181Adapter.CMD_ALIASES）
            String result = vendorAdapterRegistry.getAdapter(d).ptzControl(d, cmd);
            log.info("PTZ {} on device {} (vendor={})", cmd, id, d.vendor);
            return ResponseEntity.ok(Map.of("status", "ok", "cmd", cmd, "result", result));
        } catch (Exception e) {
            log.warn("PTZ {} failed for {}: {}", cmd, id, e.getMessage());
            return ResponseEntity.status(502).body(
                    Map.of("error", "ptz control failed: " + e.getMessage()));
        }
    }

    @Operation(summary = "发现子网内设备", description = "body: {\"subnet\": \"192.168.1.0/24\"}")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "发现的设备列表"),
        @ApiResponse(responseCode = "400", description = "subnet 缺失"),
        @ApiResponse(responseCode = "502", description = "发现失败")
    })
    @PostMapping("/discover")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> discover(@RequestBody JsonNode body) {
        String subnet = body.path("subnet").asText("");
        if (subnet.isBlank()) {
            return badRequest("subnet is required");
        }
        try {
            List<SurveillanceDevice> discovered = onvifClient.discoverDevices(subnet);
            List<Map<String, Object>> view = new ArrayList<>(discovered.size());
            for (SurveillanceDevice d : discovered) {
                view.add(deviceView(d));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("subnet", subnet);
            result.put("count", discovered.size());
            result.put("devices", view);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("Discovery failed for {}: {}", subnet, e.getMessage());
            return ResponseEntity.status(502).body(
                    Map.of("error", "discovery failed: " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // 布控球快速部署
    // ------------------------------------------------------------------

    /**
     * 一键扫描子网并自动注册所有发现的布控球设备。
     * <p>
     * body 字段：
     * <ul>
     *   <li>subnet — 子网 CIDR，如 "192.168.1.0/24"（必填）</li>
     *   <li>username — ONVIF 登录用户名（可选，默认 admin）</li>
     *   <li>password — ONVIF 登录密码（可选，默认 admin123）</li>
     * </ul>
     * <p>
     * 响应：部署结果数组，每个元素包含 deviceId/ip/vendor/status/message/rtspUrl。
     */
    @Operation(summary = "一键扫描子网并自动注册布控球设备", description = "body: subnet(必填), username, password")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "部署结果"),
        @ApiResponse(responseCode = "400", description = "subnet 缺失"),
        @ApiResponse(responseCode = "502", description = "部署失败")
    })
    @PostMapping("/rapid-deploy")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> rapidDeploy(@RequestBody JsonNode body) {
        String subnet = body.path("subnet").asText("");
        String username = body.path("username").asText("");
        String password = body.path("password").asText("");
        if (subnet.isBlank()) {
            return badRequest("subnet is required");
        }
        try {
            List<DeployResult> results = rapidDeployService.scanAndDeploy(subnet, username, password);
            List<Map<String, Object>> view = new ArrayList<>(results.size());
            for (DeployResult r : results) {
                view.add(deployResultView(r));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("subnet", subnet);
            result.put("count", results.size());
            result.put("results", view);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("Rapid deploy failed for {}: {}", subnet, e.getMessage());
            return ResponseEntity.status(502).body(
                    Map.of("error", "rapid deploy failed: " + e.getMessage()));
        }
    }

    /**
     * 仅扫描子网发现设备，不注册到 Registry。
     * <p>
     * body 字段：subnet — 子网 CIDR（必填）
     * <p>
     * 响应：发现的设备列表，status 全为 SKIPPED（仅展示）。
     */
    @Operation(summary = "仅扫描子网发现设备（不注册）", description = "body: subnet(必填)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "发现的设备列表"),
        @ApiResponse(responseCode = "400", description = "subnet 缺失"),
        @ApiResponse(responseCode = "502", description = "扫描失败")
    })
    @PostMapping("/scan")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> scan(@RequestBody JsonNode body) {
        String subnet = body.path("subnet").asText("");
        if (subnet.isBlank()) {
            return badRequest("subnet is required");
        }
        try {
            List<DeployResult> results = rapidDeployService.scanOnly(subnet);
            List<Map<String, Object>> view = new ArrayList<>(results.size());
            for (DeployResult r : results) {
                view.add(deployResultView(r));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("subnet", subnet);
            result.put("count", results.size());
            result.put("results", view);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("Scan failed for {}: {}", subnet, e.getMessage());
            return ResponseEntity.status(502).body(
                    Map.of("error", "scan failed: " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // 事件订阅 SSE
    // ------------------------------------------------------------------

    /**
     * 订阅设备事件（SSE）。
     * <p>
     * 客户端通过 EventSource 连接本端点，服务端会：
     * <ol>
     *   <li>调用设备所属厂商适配器的 {@code subscribeEvents} 注册回调
     *       （ONVIF 复用 OnvifClient；GB28181 构造国标 SUBSCRIBE 信令——
     *       此前直连 OnvifClient，国标设备的事件源语义错置）</li>
     *   <li>每 15 秒发送一次 SSE 心跳注释，保持连接</li>
     *   <li>连接结束/超时/出错时经同一适配器退订（handle 契约由
     *       VendorAdapter 统一）</li>
     * </ol>
     */
    @Operation(summary = "订阅设备事件（SSE）", description = "每 15 秒发送心跳，事件触发时推送数据")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "SSE 流"),
        @ApiResponse(responseCode = "404", description = "设备不存在")
    })
    @GetMapping("/devices/{id}/events")
    public SseEmitter subscribeEvents(@PathVariable("id") String id) {
        SurveillanceDevice d = registry.getDevice(id);
        if (d == null) {
            SseEmitter emitter = new SseEmitter(0L);
            emitter.completeWithError(new IllegalArgumentException("device " + id + " not found"));
            return emitter;
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        // 建立即发一条注释：立即提交 SSE 响应头（200 + text/event-stream），
        // 客户端无需等待首个设备事件或 15s 心跳即可确认连接已建立
        try {
            emitter.send(SseEmitter.event().comment("stream-established"));
        } catch (Exception e) {
            log.debug("SSE initial send failed for device {}: {}", id, e.getMessage());
        }
        VendorAdapter adapter = vendorAdapterRegistry.getAdapter(d);
        String handle = adapter.subscribeEvents(d, payload -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("surveillance-event")
                        .data(payload));
            } catch (Exception e) {
                log.debug("SSE send failed for device {}: {}", id, e.getMessage());
                emitter.completeWithError(e);
            }
        });
        // SSE 心跳：每 15 秒发送注释行，保持连接活跃
        ScheduledFuture<?> heartbeatFuture = sseScheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                // 连接已关闭，停止心跳
                log.debug("SSE heartbeat failed for device {}: {}", id, e.getMessage());
            }
        }, SSE_HEARTBEAT_SECONDS, SSE_HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        emitter.onCompletion(() -> {
            heartbeatFuture.cancel(false);
            adapter.unsubscribeEvents(handle);
            log.info("SSE subscription completed for device {}", id);
        });
        emitter.onTimeout(() -> {
            heartbeatFuture.cancel(false);
            adapter.unsubscribeEvents(handle);
            log.info("SSE subscription timed out for device {}", id);
        });
        emitter.onError(e -> {
            heartbeatFuture.cancel(false);
            adapter.unsubscribeEvents(handle);
            log.warn("SSE subscription error for device {}: {}", id, e.getMessage());
        });
        log.info("SSE subscription established for device {}", id);
        return emitter;
    }

    // ------------------------------------------------------------------
    // 全局安防事件查询
    // ------------------------------------------------------------------

    /**
     * 查询全局安防事件列表（分页）。
     * <p>
     * 数据源是 {@link AlarmEventStore} —— 即报警联动那套**已在用**的事件管线，
     * 而不是另建一个 Store。理由见 {@link #alarmEventStore} 字段注释：厂商适配器的
     * {@code subscribeEvents} 全是桩（GB28181 适配器明写 callback 在 PoC 阶段不会触发），
     * 没有「摄像头直连事件」这条源；而 {@code AlarmEvent} 与被删除的
     * 本包原有的 {@code SurveillanceEvent}（已删除）枚举与它逐字相同、描述的是同一批设备。
     * <p>
     * 字段投影复用 {@link AlarmController#eventToMap}，保证与
     * {@code GET /api/v1/alarms/events} 逐字段一致。
     * <p>
     * {@code deviceId} 此前声明了却从不过滤（返回空桩时无所谓），现在真正生效。
     *
     * @param page     页码（0-based，默认 0）
     * @param size     每页大小（默认 20）
     * @param deviceId 设备 ID 过滤（可选，对应 {@code sourceDeviceId}）
     */
    @Operation(summary = "查询全局安防事件列表（分页）", description = "支持 page/size/deviceId 过滤")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "事件列表"),
        @ApiResponse(responseCode = "400", description = "分页参数非法")
    })
    @GetMapping("/events")
    public ResponseEntity<Map<String, Object>> listEvents(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "deviceId", required = false) String deviceId) {
        if (page < 0) {
            return badRequest("page must be >= 0");
        }
        if (size <= 0 || size > 1000) {
            return badRequest("size must be in [1, 1000]");
        }

        // 6 参显式传：租户域留 null（走 ThreadContext），deviceId 才过滤
        AlarmEventStore.PageResult result =
                alarmEventStore.query(page, size, null, null, deviceId, null);

        List<Map<String, Object>> items = new ArrayList<>();
        for (AlarmEvent e : result.getItems()) {
            items.add(AlarmController.eventToMap(e));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", result.getTotal());
        resp.put("page", result.getPage());
        resp.put("size", result.getSize());
        if (deviceId != null && !deviceId.isBlank()) {
            resp.put("deviceId", deviceId);
        }
        return ResponseEntity.ok(resp);
    }

    // ------------------------------------------------------------------
    // 视图辅助
    // ------------------------------------------------------------------

    private static Map<String, Object> deviceView(SurveillanceDevice d) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", d.id);
        v.put("name", d.name);
        v.put("vendor", d.vendor.name());
        v.put("ip", d.ip);
        v.put("port", d.port);
        v.put("username", d.username);
        v.put("status", d.status.name());
        v.put("totalCameras", d.totalCameras);
        v.put("onlineCameras", d.onlineCameras);
        v.put("capabilities", DeviceCapability.normalize(d.getCapabilities()));
        v.put("rawCapabilities", d.getCapabilities());
        // 三态能力位：前端据此禁用按钮，而不是让运维点了之后收一个 400。
        // null = 未声明（未知），true = 明确支持，false = 明确不支持。
        v.put("ptzSupport", capFlag(d, DeviceCapability.PTZ));
        v.put("mediaSupport", capFlag(d, DeviceCapability.MEDIA));
        v.put("eventsSupport", capFlag(d, DeviceCapability.EVENTS));
        // 浏览器能不能真的看到画面。**由是否配置了帧源决定，不猜**：
        // 浏览器不能播 RTSP，本平台也没有 RTSP 解码器，所以"可播"只能等于
        // "有一个显式配置、且真能连上的 HTTP 帧源"。前端据此决定挂 <img> 还是
        // 如实说明看不到画面。
        boolean hasFrameSource = d.snapshotUrl != null && !d.snapshotUrl.isBlank();
        v.put("browserPlayable", hasFrameSource);
        v.put("snapshotUrl", hasFrameSource ? d.snapshotUrl : null);
        v.put("rtspUrl", maskRtspCredentials(d.rtspUrl));
        v.put("lastHeartbeatMs", d.lastHeartbeatMs);
        return v;
    }

    /**
     * 能力三态 → 可 JSON 序列化的三态：{@code null} 表示未声明。
     *
     * <p>用 null 而不是编一个布尔值：把「不知道」压成 {@code false} 会让前端
     * 灰掉一台其实能转的设备（功能减法），压成 {@code true} 则会让前端放行一台
     * 明确不能转的设备。这两种失真都会回到"声明≠行为"。
     */
    private static Boolean capFlag(SurveillanceDevice d, String canonical) {
        return switch (DeviceCapability.supportOf(d.getCapabilities(), canonical)) {
            case YES -> Boolean.TRUE;
            case NO -> Boolean.FALSE;
            case UNKNOWN -> null;
        };
    }

    /**
     * 将 {@link DeployResult} 转为 REST 响应视图。
     * <p>
     * RTSP URL 中的凭据脱敏，防止明文密码泄露到前端。
     */
    private static Map<String, Object> deployResultView(DeployResult r) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("deviceId", r.getDeviceId());
        v.put("ip", r.getIp());
        v.put("vendor", r.getVendor());
        v.put("status", r.getStatus().name());
        v.put("message", r.getMessage());
        v.put("rtspUrl", maskRtspCredentials(r.getRtspUrl()));
        return v;
    }

    /**
     * 对 RTSP URL 中的用户凭据脱敏，防止明文密码泄露到前端。
     * <p>
     * 将 {@code rtsp://user:pass@host/path} 替换为 {@code rtsp://***@host/path}。
     * 仅脱敏 userinfo 部分，保留 host/path 供前端展示。
     *
     * @param url 原始 RTSP URL，可能为 null
     * @return 脱敏后的 URL；输入为 null/空时原样返回
     */
    private static String maskRtspCredentials(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        return url.replaceFirst("(rtsp://[^@]+@)", "rtsp://***@");
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.status(400).body(Map.of("error", message));
    }

    private static ResponseEntity<Map<String, Object>> notFound(String message) {
        return ResponseEntity.status(404).body(Map.of("error", message));
    }

    /**
     * 设备明确声明「不支持某能力」时的统一响应。
     *
     * <p>用 400 而不是 501：501 说的是「这个服务器没实现这个方法」，
     * 而这里说的是「这台设备不具备这个能力」—— 后者是请求与设备状态不匹配。
     *
     * <p>响应里带上设备实际声明的能力集（含归一前后的名字），让调用方能看出
     * 是「不支持」而不是「拼错了参数」—— 否则前端只能显示一句无信息量的失败。
     *
     * @param d         目标设备
     * @param canonical 缺失的规范能力名
     */
    private static ResponseEntity<Map<String, Object>> unsupported(SurveillanceDevice d, String canonical) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "device " + d.id + " does not support capability " + canonical);
        body.put("deviceId", d.id);
        body.put("capability", canonical);
        body.put("declared", DeviceCapability.normalize(d.getCapabilities()));
        body.put("rawCapabilities", d.getCapabilities());
        return ResponseEntity.status(400).body(body);
    }
}
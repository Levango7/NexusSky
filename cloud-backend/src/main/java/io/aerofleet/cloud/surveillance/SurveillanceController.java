package io.aerofleet.cloud.surveillance;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
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
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 安防设备 REST API。
 * <p>
 * 端点前缀 {@code /api/surveillance}，覆盖 ONVIF 安防设备全生命周期：
 * <ul>
 *   <li>设备注册 / 列表 / 详情 / 注销</li>
 *   <li>RTSP 流 URL 获取（{@code /stream}）</li>
 *   <li>PTZ 控制（{@code /ptz}）</li>
 *   <li>子网设备发现（{@code /discover}）</li>
 *   <li>事件订阅 SSE（{@code /events}）</li>
 * </ul>
 * <p>
 * 错误响应统一使用 {@code {"error": "..."}} 格式，与
 * {@link io.aerofleet.cloud.api.ApiExceptionHandler} 风格一致。
 */
@RestController
@RequestMapping("/api/surveillance")
public class SurveillanceController {

    private static final Logger log = LoggerFactory.getLogger(SurveillanceController.class);

    /** SSE 心跳间隔（秒）。 */
    private static final long SSE_HEARTBEAT_SECONDS = 15L;
    /** SSE 超时时间（毫秒，30 分钟）。 */
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    private final SurveillanceDeviceRegistry registry;
    private final OnvifClient onvifClient;
    /** SSE 心跳调度器：单线程足够，多个 emitter 共享。 */
    private final ScheduledExecutorService sseScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "surveillance-sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    public SurveillanceController(SurveillanceDeviceRegistry registry, OnvifClient onvifClient) {
        this.registry = registry;
        this.onvifClient = onvifClient;
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
    @PostMapping("/devices")
    public ResponseEntity<Map<String, Object>> registerDevice(@RequestBody JsonNode body) {
        String id = body.path("id").asText("");
        String name = body.path("name").asText("");
        String vendorStr = body.path("vendor").asText("");
        String ip = body.path("ip").asText("");
        int port = body.path("port").asInt(OnvifClient.DEFAULT_PORT);
        String username = body.path("username").asText("");
        String password = body.path("password").asText("");

        if (id.isBlank()) {
            return badRequest("device id is required");
        }
        SurveillanceDevice.Vendor vendor;
        try {
            vendor = SurveillanceDevice.Vendor.valueOf(vendorStr.toUpperCase());
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

        SurveillanceDevice device = new SurveillanceDevice(id, name, vendor, ip, port,
                username, password);
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

    /** 列出所有安防设备。 */
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

    /** 获取设备详情。 */
    @GetMapping("/devices/{id}")
    public ResponseEntity<Map<String, Object>> getDevice(@PathVariable("id") String id) {
        SurveillanceDevice d = registry.getDevice(id);
        if (d == null) {
            return notFound("device " + id + " not found");
        }
        return ResponseEntity.ok(deviceView(d));
    }

    /** 注销设备。 */
    @DeleteMapping("/devices/{id}")
    public ResponseEntity<Map<String, Object>> unregisterDevice(@PathVariable("id") String id) {
        SurveillanceDevice removed = registry.unregister(id);
        if (removed == null) {
            return notFound("device " + id + " not found");
        }
        return ResponseEntity.ok(Map.of("status", "ok", "id", id));
    }

    // ------------------------------------------------------------------
    // RTSP 流 / PTZ / 发现
    // ------------------------------------------------------------------

    /** 获取 RTSP 流 URL。 */
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
        try {
            String url = onvifClient.getRtspUrl(d.ip, d.port, d.username, d.password, channel);
            d.rtspUrl = url;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("deviceId", id);
            result.put("channel", channel);
            result.put("rtspUrl", maskRtspCredentials(url));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("GetStreamUri failed for {}: {}", id, e.getMessage());
            return ResponseEntity.status(502).body(
                    Map.of("error", "failed to get rtsp url: " + e.getMessage()));
        }
    }

    /** PTZ 控制。body: {"cmd": "up"/"down"/"left"/"right"/"zoomIn"/"zoomOut"/"stop"} */
    @PostMapping("/devices/{id}/ptz")
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
        try {
            String result = onvifClient.ptzControl(d.ip, d.port, d.username, d.password, cmd);
            log.info("PTZ {} on device {}", cmd, id);
            return ResponseEntity.ok(Map.of("status", "ok", "cmd", cmd, "result", result));
        } catch (Exception e) {
            log.warn("PTZ {} failed for {}: {}", cmd, id, e.getMessage());
            return ResponseEntity.status(502).body(
                    Map.of("error", "ptz control failed: " + e.getMessage()));
        }
    }

    /** 发现子网内设备。body: {"subnet": "192.168.1.0/24"} */
    @PostMapping("/discover")
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
    // 事件订阅 SSE
    // ------------------------------------------------------------------

    /**
     * 订阅设备事件（SSE）。
     * <p>
     * 客户端通过 EventSource 连接本端点，服务端会：
     * <ol>
     *   <li>调用 OnvifClient.subscribeEvents 注册回调</li>
     *   <li>每 15 秒发送一次 SSE 心跳注释，保持连接</li>
     *   <li>当 OnvifClient 模拟事件触发时，通过 emitter 推送事件数据</li>
     * </ol>
     */
    @GetMapping("/devices/{id}/events")
    public SseEmitter subscribeEvents(@PathVariable("id") String id) {
        SurveillanceDevice d = registry.getDevice(id);
        if (d == null) {
            SseEmitter emitter = new SseEmitter(0L);
            emitter.completeWithError(new IllegalArgumentException("device " + id + " not found"));
            return emitter;
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        String handle = onvifClient.subscribeEvents(d.ip, d.port, d.username, d.password,
                payload -> {
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
            onvifClient.unsubscribeEvents(handle);
            log.info("SSE subscription completed for device {}", id);
        });
        emitter.onTimeout(() -> {
            heartbeatFuture.cancel(false);
            onvifClient.unsubscribeEvents(handle);
            log.info("SSE subscription timed out for device {}", id);
        });
        emitter.onError(e -> {
            heartbeatFuture.cancel(false);
            onvifClient.unsubscribeEvents(handle);
            log.warn("SSE subscription error for device {}: {}", id, e.getMessage());
        });
        log.info("SSE subscription established for device {}", id);
        return emitter;
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
        v.put("capabilities", d.getCapabilities());
        v.put("rtspUrl", maskRtspCredentials(d.rtspUrl));
        v.put("lastHeartbeatMs", d.lastHeartbeatMs);
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
}
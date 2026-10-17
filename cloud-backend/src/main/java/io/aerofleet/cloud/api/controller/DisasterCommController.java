package io.aerofleet.cloud.api.controller;

import io.aerofleet.cloud.api.service.DisasterCommService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;

/**
 * 灾害通信监控 REST 控制器（P2 灾害应急通讯组网扩展）。
 * <p>
 * 独立路径前缀 /api/v1/disaster/*，既有端点不受影响（DFX 4.5）。
 * <p>
 * 端点清单：
 * <pre>
 * GET  /api/v1/disaster/status      获取灾害模式状态
 * POST /api/v1/disaster/activate    手动激活灾害模式
 * POST /api/v1/disaster/deactivate  手动退出灾害模式
 * GET  /api/v1/disaster/clusters    获取分簇拓扑
 * GET  /api/v1/disaster/qos         获取 QoS 优先级队列状态
 * GET  /api/v1/disaster/links       获取异构链路桥接状态
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/disaster")
@RequireRole(Role.OBSERVER)
public class DisasterCommController {

    private final DisasterCommService service;

    public DisasterCommController(DisasterCommService service) {
        this.service = service;
    }

    /** 获取灾害模式状态。 */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(service.getDisasterStatus());
    }

    /**
     * 手动激活灾害模式。
     * <p>
     * 请求体可选：{@code {"reason":"earthquake"}}。缺省 / 空白 reason 按 {@code manual} 处理，
     * 以便不带 body 的旧调用方（含既有测试）行为不变。
     * <p>
     * 非法 reason 返回 400 并回列允许的取值，**不**静默改写成 {@code manual} —— 静默改写会让
     * 面板显示一个调用方并未声明过的原因，正是本缺陷在界面上的表现。
     * <p>
     * 此前本方法既无参数也无 {@code @RequestBody}，前端发来的 reason 被 Spring 整个丢弃，
     * 服务端硬编码 {@code "manual"}，于是面板 L367 显示的「触发原因」恒为 {@code manual}，
     * 而 L383 的下拉框让用户从 5 个选项里选 —— 该下拉框是装饰性的。
     */
    @PostMapping("/activate")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> activate(
            @RequestBody(required = false) Map<String, Object> body) {
        Object raw = body == null ? null : body.get("reason");
        String reason = raw == null ? null : String.valueOf(raw).trim();
        if (reason != null && !reason.isEmpty()
                && !DisasterCommService.ALLOWED_TRIGGER_REASONS.contains(reason)) {
            Map<String, Object> error = new LinkedHashMap<>();
            // `error` 是本项目错误响应的约定键：api.js 的 jsonFetch 在 !res.ok 时只读
            // body.error，缺了它界面就只能显示 "HTTP 400"、把原因丢掉。
            error.put("error", "未知的触发原因: " + reason);
            error.put("message", "未知的触发原因: " + reason);
            error.put("allowed", DisasterCommService.ALLOWED_TRIGGER_REASONS);
            return ResponseEntity.badRequest().body(error);
        }
        Map<String, Object> status = service.activateDisasterMode(reason);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", status.get("mode"));
        result.put("triggerReason", status.get("triggerReason"));
        result.put("timestamp", status.get("timestamp"));
        result.put("message", "灾害模式已激活");
        return ResponseEntity.ok(result);
    }

    /** 手动退出灾害模式。 */
    @PostMapping("/deactivate")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> deactivate() {
        Map<String, Object> status = service.deactivateDisasterMode();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", status.get("mode"));
        result.put("triggerReason", status.get("triggerReason"));
        result.put("timestamp", status.get("timestamp"));
        result.put("message", "灾害模式已退出");
        return ResponseEntity.ok(result);
    }

    /** 获取分簇拓扑。 */
    @GetMapping("/clusters")
    public ResponseEntity<Map<String, Object>> getClusters() {
        return ResponseEntity.ok(service.getClusterTopology());
    }

    /** 获取 QoS 优先级队列状态。 */
    @GetMapping("/qos")
    public ResponseEntity<Map<String, Object>> getQoS() {
        return ResponseEntity.ok(service.getQoSStatus());
    }

    /** 获取异构链路桥接状态。 */
    @GetMapping("/links")
    public ResponseEntity<Map<String, Object>> getLinks() {
        return ResponseEntity.ok(service.getLinkBridgeStatus());
    }
}
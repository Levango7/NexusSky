package io.aerofleet.cloud.api.controller;

import io.aerofleet.cloud.api.service.DisasterCommService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

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

    /** 手动激活灾害模式。 */
    @PostMapping("/activate")
    public ResponseEntity<Map<String, Object>> activate() {
        Map<String, Object> status = service.activateDisasterMode();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", status.get("mode"));
        result.put("triggerReason", status.get("triggerReason"));
        result.put("timestamp", status.get("timestamp"));
        result.put("message", "灾害模式已激活");
        return ResponseEntity.ok(result);
    }

    /** 手动退出灾害模式。 */
    @PostMapping("/deactivate")
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
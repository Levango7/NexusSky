package io.aerofleet.cloud.mission;

import io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException;
import io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配送/物流 REST 端点（FR-33/FR-34，路径前缀 /api/v1/delivery）。
 * <p>
 * 复用 {@link FormationController} 风格，独立路径前缀，不修改现有端点（DFX 4.5）。
 * <p>
 * 端点清单：
 * <pre>
 *   POST   /api/v1/delivery              创建配送任务（FR-23）
 *   GET    /api/v1/delivery/{id}         查询配送任务状态（FR-25）
 *   POST   /api/v1/delivery/{id}/control 控制配送任务（推进/跳过站点）
 *   GET    /api/v1/delivery/{id}/payload 查询当前负载清单（FR-34）
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/delivery")
public class DeliveryController {

    private final DeliveryService deliveryService;

    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    /** 创建配送任务（FR-23）。 */
    @PostMapping
    public Map<String, Object> create(@RequestBody DeliveryRequest req) {
        if (req.sites == null || req.sites.isEmpty()) {
            throw new BadRequestException("sites must not be empty");
        }
        DeliverySequence seq = deliveryService.create(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deliveryId", seq.deliveryId());
        out.put("sites", seq.siteCount());
        out.put("state", seq.state().name());
        return out;
    }

    /** 查询配送任务状态（FR-25）。 */
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable("id") int id) {
        DeliverySequence seq = deliveryService.sequence(id);
        if (seq == null) {
            throw new NotFoundException("delivery " + id + " not found");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deliveryId", seq.deliveryId());
        out.put("state", seq.state().name());
        out.put("progress", seq.progress());
        out.put("currentIndex", seq.currentIndex());
        out.put("remainingSites", seq.remainingSites());
        // 站点列表（含每站状态/负载/到达时间/投放时间）
        List<Map<String, Object>> sites = new ArrayList<>();
        for (DeliverySite s : seq.sites()) {
            Map<String, Object> site = new LinkedHashMap<>();
            site.put("index", s.index());
            site.put("lat", s.lat());
            site.put("lon", s.lon());
            site.put("alt", s.alt());
            site.put("payloadId", s.payloadId());
            site.put("payloadWeightKg", s.payloadWeightKg());
            site.put("payloadVolumeL", s.payloadVolumeL());
            site.put("dropAccuracyM", s.dropAccuracyM());
            site.put("state", s.state().name());
            site.put("arriveTimeMs", s.arriveTimeMs());
            site.put("dropTimeMs", s.dropTimeMs());
            sites.add(site);
        }
        out.put("sites", sites);
        return out;
    }

    /** 控制配送任务（推进/跳过站点）。 */
    @PostMapping("/{id}/control")
    public Map<String, Object> control(@PathVariable("id") int id,
                                       @RequestBody ControlRequest body) {
        Map<Integer, DeliveryService.AckResult> results =
                deliveryService.control(id, body.action);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deliveryId", id);
        out.put("results", results);
        return out;
    }

    /** 查询当前负载清单（FR-34）。 */
    @GetMapping("/{id}/payload")
    public Map<String, Object> payload(@PathVariable("id") int id) {
        DeliverySequence seq = deliveryService.sequence(id);
        if (seq == null) {
            throw new NotFoundException("delivery " + id + " not found");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deliveryId", id);
        // 当前站点负载（currentIndex 指向的站点）
        List<Map<String, Object>> payloads = new ArrayList<>();
        double totalWeight = 0;
        double totalVolume = 0;
        double weightedCog = 0;
        for (DeliverySite s : seq.sites()) {
            if (s.state() == DeliverySiteState.EN_ROUTE) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("payloadId", s.payloadId());
                p.put("weightKg", s.payloadWeightKg());
                p.put("volumeL", s.payloadVolumeL());
                payloads.add(p);
                totalWeight += s.payloadWeightKg();
                totalVolume += s.payloadVolumeL();
            }
        }
        out.put("payloads", payloads);
        out.put("totalWeight", totalWeight);
        out.put("totalVolume", totalVolume);
        out.put("combinedCenterOfGravity", totalWeight > 0 ? weightedCog / totalWeight : 0);
        return out;
    }

    /** 无人机离线 → HTTP 409。 */
    @ExceptionHandler(SprayTaskService.DroneOfflineException.class)
    public ResponseEntity<Object> droneOffline(SprayTaskService.DroneOfflineException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", e.getMessage()));
    }

    /** 控制请求体 DTO。 */
    public static final class ControlRequest {
        public String action;  // START / DROP / SKIP / RESET / FINISH
    }
}
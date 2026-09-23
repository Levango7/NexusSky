package io.aerofleet.cloud.api.controller;

import io.aerofleet.cloud.api.dto.LoRaAlarmDto;
import io.aerofleet.cloud.api.service.LoRaRelayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LoRa 回传报警 REST 端点。
 * <p>
 * 独立路径前缀 /api/v1/loRa/*，提供 LoRa 回传报警接收与通道统计查询。
 * <p>
 * 端点清单：
 * <pre>
 * POST /api/v1/loRa/alarm   接收 LoRa 回传告警（布控球 → 无人机 mesh → 指挥中心）
 * GET  /api/v1/loRa/stats   获取 LoRa 回传通道统计信息
 * </pre>
 * <p>
 * 降级模式：当 {@link LoRaRelayService} 未注入时（安防/LoRa 子系统未部署），
 * 端点返回 503 Service Unavailable，而非抛出 500 内部错误。
 */
@RestController
@RequestMapping("/api/v1/loRa")
@Tag(name = "LoRaRelay", description = "LoRa 回传报警 REST API：接收布控球经无人机 mesh 路由的回传告警")
public class LoRaRelayController {

    private final LoRaRelayService loRaRelayService;

    @Autowired
    public LoRaRelayController(@Autowired(required = false) LoRaRelayService loRaRelayService) {
        this.loRaRelayService = loRaRelayService;
    }

    /**
     * 接收 LoRa 回传告警。
     * <p>
     * 接收从无人机 mesh 网络路由过来的 LoRa 回传报警事件，转换为标准 AlarmEvent 格式，
     * 转发到 AlarmEventStore 和 AlarmLinkageEngine 进行联动处理。
     * <p>
     * 当 LoRaRelayService 未注入时返回 503，表示 LoRa 回传通道不可用。
     */
    @Operation(summary = "接收 LoRa 回传告警", description = "布控球 → 无人机 mesh → 指挥中心；转换为标准 AlarmEvent 并联动处理")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "处理结果（含 eventId、status、latencyMs）"),
            @ApiResponse(responseCode = "503", description = "LoRa 回传服务不可用（依赖未注入）")
    })
    @PostMapping("/alarm")
    public ResponseEntity<Map<String, Object>> receiveLoRaAlarm(@RequestBody @Valid LoRaAlarmDto dto) {
        if (loRaRelayService == null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "UNAVAILABLE");
            result.put("message", "LoRa relay service is not available");
            return ResponseEntity.status(503).body(result);
        }

        Map<String, Object> serviceResult = loRaRelayService.receiveLoRaAlarm(dto);
        return ResponseEntity.ok(serviceResult);
    }

    /**
     * 获取 LoRa 回传通道统计信息。
     * <p>
     * 返回 LoRa 回传通道的接收总数、成功率、平均延迟等统计指标。
     * <p>
     * 当 LoRaRelayService 未注入时返回 503。
     */
    @Operation(summary = "获取 LoRa 回传通道统计信息", description = "接收总数、成功率、平均延迟等统计指标")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "统计信息"),
            @ApiResponse(responseCode = "503", description = "LoRa 回传服务不可用（依赖未注入）")
    })
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getRelayStats() {
        if (loRaRelayService == null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "UNAVAILABLE");
            result.put("message", "LoRa relay service is not available");
            return ResponseEntity.status(503).body(result);
        }

        Map<String, Object> stats = loRaRelayService.getRelayStats();
        return ResponseEntity.ok(stats);
    }
}
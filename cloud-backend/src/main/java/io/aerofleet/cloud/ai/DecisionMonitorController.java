package io.aerofleet.cloud.ai;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import io.aerofleet.cloud.security.TenantContext;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** M11 决策监控 REST API */
@RestController
@RequestMapping("/api/v1/ai")
@RequireRole(Role.OBSERVER)
public class DecisionMonitorController {
    private final DecisionMonitorService service;
    private final DeviceRegistry registry;

    public DecisionMonitorController(DecisionMonitorService service, DeviceRegistry registry) {
        this.service = service;
        this.registry = registry;
    }

    @GetMapping("/decisions")
    public Map<Integer, List<Map<String, Object>>> allDecisions() {
        // 决策按 sysid 分桶，sysid 的租户归属在 DeviceRegistry：租户会话只能看到本租户
        // 设备的决策，未归属设备只对全局管理员可见（与 dock/alarm 同一口径）。
        Integer scope = TenantContext.getEffectiveTenantId();
        if (scope == null) {
            return service.getAllDecisions();
        }
        Map<Integer, List<Map<String, Object>>> visible = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Map<String, Object>>> e : service.getAllDecisions().entrySet()) {
            if (scope.equals(registry.tenantOf(e.getKey()))) {
                visible.put(e.getKey(), e.getValue());
            }
        }
        return visible;
    }

    @GetMapping("/decisions/{sysid}")
    public List<Map<String, Object>> droneDecisions(@PathVariable int sysid) {
        Integer scope = TenantContext.getEffectiveTenantId();
        if (scope != null && !scope.equals(registry.tenantOf(sysid))) {
            return List.of();
        }
        return service.getDecisions(sysid);
    }
}

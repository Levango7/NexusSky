package io.aerofleet.cloud.audit;

import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 审计日志查询端点。
 * <p>
 * GET /api/audit/logs — 查询审计日志（仅 {@link Role#ADMIN}）。
 * <p>
 * 支持参数：
 * <ul>
 *   <li>{@code limit} — 返回最近 N 条（默认全部）</li>
 * </ul>
 * <p>
 * RBAC 通过 {@link RequireRole} 注解声明，当 {@code aerofleet.security.rbac-enabled=true}
 * 且非 dev-mode 时由 {@link io.aerofleet.cloud.security.RoleInterceptor} 拦截校验。
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * 查询审计日志。
     * <p>
     * GET /api/audit/logs?limit=100
     *
     * @param limit 最大返回条数（可选，默认全部）
     * @return 审计日志列表
     */
    @GetMapping("/logs")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<List<Map<String, Object>>> getLogs(
            @RequestParam(value = "limit", required = false) Integer limit) {

        List<AuditLog> logs = (limit != null && limit > 0)
                ? auditService.findRecent(limit)
                : auditService.findAll();

        List<Map<String, Object>> result = logs.stream()
                .map(this::toJson)
                .toList();

        return ResponseEntity.ok(result);
    }

    /**
     * 将 {@link AuditLog} 序列化为 JSON 友好的 Map 结构。
     */
    private Map<String, Object> toJson(AuditLog log) {
        return Map.of(
                "timestamp", log.getTimestamp().toString(),
                "userId", log.getUserId(),
                "action", log.getAction(),
                "target", log.getTarget(),
                "ip", log.getIp() != null ? log.getIp() : ""
        );
    }
}
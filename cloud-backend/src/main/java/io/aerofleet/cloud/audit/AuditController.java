package io.aerofleet.cloud.audit;

import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志查询端点。
 * <p>
 * GET /api/v1/audit/logs — 查询审计日志（仅 {@link Role#ADMIN}）。
 * GET /api/v1/audit/verify — 校验审计哈希链完整性（仅 {@link Role#ADMIN}）。
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
     * 校验审计日志哈希链完整性。
     * <p>
     * GET /api/v1/audit/verify
     *
     * @return 校验结果（ok / checked / brokenAtId / reason / truncated）；
     *         {@code truncated=true} 表示链首之前还有已不存在的记录（审计保留删除了前缀，
     *         或纯内存模式下容量裁剪），此时 {@code ok} 只描述现存链段
     */
    @GetMapping("/verify")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<Map<String, Object>> verify() {
        AuditService.ChainVerification v = auditService.verifyChain();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", v.ok());
        body.put("checked", v.checked());
        body.put("brokenAtId", v.brokenAtId());
        body.put("reason", v.reason());
        body.put("truncated", v.truncated());
        return ResponseEntity.ok(body);
    }

    /**
     * 将 {@link AuditLog} 序列化为 JSON 友好的 Map 结构。
     * <p>
     * 含 detail 与哈希链指针（prevHash/entryHash），便于审计侧导出后复核。
     */
    private Map<String, Object> toJson(AuditLog log) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", log.getTimestamp().toString());
        m.put("userId", log.getUserId());
        m.put("action", log.getAction());
        m.put("target", log.getTarget());
        m.put("detail", log.getDetail() != null ? log.getDetail() : "");
        m.put("ip", log.getIp() != null ? log.getIp() : "");
        m.put("prevHash", log.getPrevHash());
        m.put("entryHash", log.getEntryHash());
        return m;
    }
}
package io.aerofleet.cloud.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 租户管理 CRUD API，仅 ADMIN 可操作。
 * <p>
 * 端点：
 * <ul>
 *   <li>GET    /api/v1/tenants — 列出所有租户</li>
 *   <li>GET    /api/v1/tenants/{id} — 获取指定租户详情</li>
 *   <li>POST   /api/v1/tenants — 创建租户（code 必须唯一）</li>
 *   <li>PUT    /api/v1/tenants/{id} — 更新租户</li>
 *   <li>DELETE /api/v1/tenants/{id} — 删除租户（不允许删除有用户的租户或自己所属租户）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private static final Logger log = LoggerFactory.getLogger(TenantController.class);

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;

    public TenantController(TenantRepository tenantRepository,
                            UserRepository userRepository) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
    }

    /**
     * 列出所有租户。
     *
     * @return 租户列表
     */
    @GetMapping
    @RequireRole(Role.ADMIN)
    public ResponseEntity<List<Map<String, Object>>> listTenants() {
        List<TenantEntity> tenants = tenantRepository.findAll();
        List<Map<String, Object>> result = tenants.stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * 获取指定租户详情。
     *
     * @param id 租户 ID
     * @return 租户详情，不存在返回 404
     */
    @GetMapping("/{id}")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<?> getTenant(@PathVariable Integer id) {
        return tenantRepository.findById(id)
                .map(tenant -> ResponseEntity.ok(toResponse(tenant)))
                .orElseGet(() -> errorResponse(HttpStatus.NOT_FOUND, "tenant not found"));
    }

    /**
     * 创建租户。
     * <p>
     * code 必须唯一，重复返回 400。
     *
     * @param request 请求体 {name, code, enabled}
     * @return 201 + 创建的租户，code 重复返回 400
     */
    @PostMapping
    @RequireRole(Role.ADMIN)
    public ResponseEntity<?> createTenant(@Valid @RequestBody TenantRequest request) {
        if (tenantRepository.findByCode(request.getCode()).isPresent()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "tenant code already exists");
        }

        TenantEntity tenant = new TenantEntity();
        tenant.setName(request.getName());
        tenant.setCode(request.getCode());
        tenant.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);
        tenant.setCreatedAt(Instant.now());

        tenant = tenantRepository.save(tenant);
        log.info("租户创建成功: id={}, code={}", tenant.getId(), tenant.getCode());

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(tenant));
    }

    /**
     * 更新租户。
     *
     * @param id      租户 ID
     * @param request 请求体 {name, code, enabled}
     * @return 200 + 更新后的租户，不存在返回 404
     */
    @PutMapping("/{id}")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<?> updateTenant(@PathVariable Integer id,
                                          @Valid @RequestBody TenantRequest request) {
        return tenantRepository.findById(id)
                .map(tenant -> {
                    // 检查 code 是否与其他租户冲突
                    tenantRepository.findByCode(request.getCode())
                            .filter(existing -> !existing.getId().equals(id))
                            .ifPresent(existing -> {
                                throw new IllegalStateException("tenant code already exists");
                            });

                    tenant.setName(request.getName());
                    tenant.setCode(request.getCode());
                    tenant.setEnabled(request.getEnabled() != null ? request.getEnabled() : tenant.isEnabled());

                    tenant = tenantRepository.save(tenant);
                    log.info("租户更新成功: id={}", tenant.getId());

                    return ResponseEntity.ok(toResponse(tenant));
                })
                .orElseGet(() -> errorResponse(HttpStatus.NOT_FOUND, "tenant not found"));
    }

    /**
     * 删除租户。
     * <p>
     * 不允许删除有用户的租户（返回 400）；
     * 不允许删除自己所属租户（返回 400）。
     *
     * @param id      租户 ID
     * @param request HTTP 请求（用于获取当前用户租户 ID）
     * @return 204，不存在返回 404，有用户或为自己所属租户返回 400
     */
    @DeleteMapping("/{id}")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<?> deleteTenant(@PathVariable Integer id,
                                          HttpServletRequest request) {
        return tenantRepository.findById(id)
                .map(tenant -> {
                    // 不允许删除自己所属租户
                    Integer currentTenantId = TenantContext.getTenantId();
                    if (currentTenantId != null && currentTenantId.equals(id)) {
                        return errorResponse(HttpStatus.BAD_REQUEST,
                                "cannot delete your own tenant");
                    }

                    // 不允许删除有用户的租户
                    List<UserEntity> users = userRepository.findByTenantId(id);
                    if (!users.isEmpty()) {
                        return errorResponse(HttpStatus.BAD_REQUEST,
                                "cannot delete tenant with existing users");
                    }

                    tenantRepository.delete(tenant);
                    log.info("租户删除成功: id={}", id);

                    return ResponseEntity.noContent().build();
                })
                .orElseGet(() -> errorResponse(HttpStatus.NOT_FOUND, "tenant not found"));
    }

    // =====================================================================
    // 辅助方法
    // =====================================================================

    /**
     * 将 TenantEntity 转换为响应 Map。
     * <p>
     * 响应格式：{id, name, code, enabled, createdAt}
     */
    private Map<String, Object> toResponse(TenantEntity tenant) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", tenant.getId());
        resp.put("name", tenant.getName());
        resp.put("code", tenant.getCode());
        resp.put("enabled", tenant.isEnabled());
        resp.put("createdAt", tenant.getCreatedAt());
        return resp;
    }

    /**
     * 构建错误响应。
     */
    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
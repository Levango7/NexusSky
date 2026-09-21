package io.aerofleet.cloud.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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
 * 用户管理 CRUD API，ADMIN 可管理本租户用户。
 * <p>
 * 端点：
 * <ul>
 *   <li>GET    /api/v1/users — 列出当前租户的用户（全局管理员列出所有用户）</li>
 *   <li>GET    /api/v1/users/{id} — 获取指定用户详情</li>
 *   <li>POST   /api/v1/users — 创建用户（username 必须唯一）</li>
 *   <li>PUT    /api/v1/users/{id} — 更新用户（不允许修改 username）</li>
 *   <li>DELETE /api/v1/users/{id} — 删除用户（不允许删除自己）</li>
 * </ul>
 * <p>
 * 响应中永远不返回 passwordHash。
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtDecoder jwtDecoder;

    public UserController(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtDecoder jwtDecoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtDecoder = jwtDecoder;
    }

    /**
     * 列出当前租户的用户。
     * <p>
     * 从 TenantContext 获取当前租户 ID：
     * <ul>
     *   <li>tenantId 非 null — 只返回该租户的用户</li>
     *   <li>tenantId 为 null — 全局管理员，返回所有用户</li>
     * </ul>
     *
     * @return 用户列表（不含 passwordHash）
     */
    @GetMapping
    @RequireRole(Role.ADMIN)
    public ResponseEntity<List<Map<String, Object>>> listUsers() {
        Integer tenantId = TenantContext.getTenantId();
        List<UserEntity> users = (tenantId == null)
                ? userRepository.findAll()
                : userRepository.findByTenantId(tenantId);

        List<Map<String, Object>> result = users.stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * 获取指定用户详情。
     *
     * @param id 用户 ID
     * @return 用户详情（不含 passwordHash），不存在返回 404
     */
    @GetMapping("/{id}")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<?> getUser(@PathVariable Integer id) {
        return userRepository.findById(id)
                .map(user -> {
                    // 跨租户访问控制：非全局管理员只能访问本租户用户
                    Integer currentTenantId = TenantContext.getTenantId();
                    if (currentTenantId != null && !currentTenantId.equals(user.getTenantId())) {
                        return errorResponse(HttpStatus.NOT_FOUND, "user not found");
                    }
                    return ResponseEntity.ok(toResponse(user));
                })
                .orElseGet(() -> errorResponse(HttpStatus.NOT_FOUND, "user not found"));
    }

    /**
     * 创建用户。
     * <p>
     * password 用 PasswordEncoder 加密后存储；
     * username 必须唯一，重复返回 400。
     *
     * @param request 请求体 {username, password, role, tenantId, enabled}
     * @return 201 + 创建的用户（不含 passwordHash），username 重复返回 400
     */
    @PostMapping
    @RequireRole(Role.ADMIN)
    public ResponseEntity<?> createUser(@Valid @RequestBody UserRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            return errorResponse(HttpStatus.BAD_REQUEST, "username already exists");
        }

        // 校验 role 是否为合法枚举值
        Role role;
        try {
            role = Role.valueOf(request.getRole());
        } catch (IllegalArgumentException e) {
            return errorResponse(HttpStatus.BAD_REQUEST,
                    "invalid role, must be one of: ADMIN, OPERATOR, OBSERVER");
        }

        UserEntity user = new UserEntity();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(role.name());
        user.setTenantId(request.getTenantId());
        user.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);
        user.setCreatedAt(Instant.now());

        user = userRepository.save(user);
        log.info("用户创建成功: id={}, username={}, role={}", user.getId(), user.getUsername(), user.getRole());

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(user));
    }

    /**
     * 更新用户。
     * <p>
     * 不允许修改 username；可选修改 password（请求体包含 password 字段时）。
     *
     * @param id      用户 ID
     * @param request 请求体 {role, enabled, tenantId, password?}
     * @return 200 + 更新后的用户（不含 passwordHash），不存在返回 404
     */
    @PutMapping("/{id}")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<?> updateUser(@PathVariable Integer id,
                                        @Valid @RequestBody UserRequest request) {
        return userRepository.findById(id)
                .map(user -> {
                    // 跨租户访问控制：非全局管理员只能更新本租户用户
                    Integer currentTenantId = TenantContext.getTenantId();
                    if (currentTenantId != null && !currentTenantId.equals(user.getTenantId())) {
                        return errorResponse(HttpStatus.NOT_FOUND, "user not found");
                    }

                    // 校验 role 是否为合法枚举值（如果请求体包含 role）
                    if (request.getRole() != null && !request.getRole().isBlank()) {
                        try {
                            Role.valueOf(request.getRole());
                        } catch (IllegalArgumentException e) {
                            return errorResponse(HttpStatus.BAD_REQUEST,
                                    "invalid role, must be one of: ADMIN, OPERATOR, OBSERVER");
                        }
                        user.setRole(request.getRole());
                    }

                    // 可选修改 password
                    if (request.getPassword() != null && !request.getPassword().isBlank()) {
                        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
                    }

                    // 可选修改 tenantId
                    if (request.getTenantId() != null) {
                        user.setTenantId(request.getTenantId());
                    }

                    // 可选修改 enabled
                    if (request.getEnabled() != null) {
                        user.setEnabled(request.getEnabled());
                    }

                    user = userRepository.save(user);
                    log.info("用户更新成功: id={}", user.getId());

                    return ResponseEntity.ok(toResponse(user));
                })
                .orElseGet(() -> errorResponse(HttpStatus.NOT_FOUND, "user not found"));
    }

    /**
     * 删除用户。
     * <p>
     * 不允许删除自己（返回 400）。
     *
     * @param id      用户 ID
     * @param request HTTP 请求（用于获取当前用户名）
     * @return 204，不存在返回 404，删除自己返回 400
     */
    @DeleteMapping("/{id}")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<?> deleteUser(@PathVariable Integer id,
                                        HttpServletRequest request) {
        return userRepository.findById(id)
                .map(user -> {
                    // 跨租户访问控制：非全局管理员只能删除本租户用户
                    Integer currentTenantId = TenantContext.getTenantId();
                    if (currentTenantId != null && !currentTenantId.equals(user.getTenantId())) {
                        return errorResponse(HttpStatus.NOT_FOUND, "user not found");
                    }

                    // 不允许删除自己
                    String currentUsername = extractUsername(request);
                    if (currentUsername != null && currentUsername.equals(user.getUsername())) {
                        return errorResponse(HttpStatus.BAD_REQUEST, "cannot delete yourself");
                    }

                    userRepository.delete(user);
                    log.info("用户删除成功: id={}, username={}", id, user.getUsername());

                    return ResponseEntity.noContent().build();
                })
                .orElseGet(() -> errorResponse(HttpStatus.NOT_FOUND, "user not found"));
    }

    // =====================================================================
    // 辅助方法
    // =====================================================================

    /**
     * 将 UserEntity 转换为响应 Map。
     * <p>
     * 响应格式：{id, username, role, tenantId, enabled, createdAt}
     * 永远不返回 passwordHash。
     */
    private Map<String, Object> toResponse(UserEntity user) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", user.getId());
        resp.put("username", user.getUsername());
        resp.put("role", user.getRole());
        resp.put("tenantId", user.getTenantId());
        resp.put("enabled", user.isEnabled());
        resp.put("createdAt", user.getCreatedAt());
        return resp;
    }

    /**
     * 从请求的 Authorization 头中提取 JWT subject（用户名）。
     *
     * @param request HTTP 请求
     * @return 当前用户名，无 token 或解析失败时返回 null
     */
    private String extractUsername(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7);
        try {
            Jwt jwt = jwtDecoder.decode(token);
            return jwt.getSubject();
        } catch (Exception e) {
            log.debug("提取当前用户名失败: {}", e.getMessage());
            return null;
        }
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
package io.aerofleet.cloud.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

/**
 * RBAC 角色拦截器：基于 {@link RequireRole} 注解校验调用方角色。
 * <p>
 * 角色层级（ordinal 越小权限越大）：{@link Role#ADMIN}(0) > {@link Role#OPERATOR}(1) > {@link Role#OBSERVER}(2)。
 * <p>
 * 注解查找顺序：方法级优先，未标注时回退到类级（整个控制器同一最低角色）。
 * <p>
 * 角色来源（依次尝试）：
 * <ol>
 *   <li>{@code Authorization: Bearer} 的 JWT {@code role} claim</li>
 *   <li>API Key 上下文的角色（{@code X-API-Key} 路径，由 {@link ApiKeyFilter} 从
 *       {@link ApiKeyEntity#getRole()} 写入 {@link ApiKeyContext}——SDK 只用 API Key 认证）</li>
 * </ol>
 * <p>
 * 跳过校验的条件（任一满足即放行）：
 * <ul>
 *   <li>{@code aerofleet.security.dev-mode=true}（开发模式）</li>
 *   <li>{@code aerofleet.security.rbac-enabled=false}</li>
 *   <li>目标方法与所在类均未标注 {@link RequireRole}</li>
 *   <li>非控制器方法（HandlerMethod 之外的静态资源等）</li>
 * </ul>
 * <p>
 * 校验失败返回 403 Forbidden，响应体 {@code {"error":"forbidden: requires role XXX"}}。
 *
 * @see RequireRole
 * @see Role
 */
@Component
public class RoleInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RoleInterceptor.class);

    private final JwtDecoder jwtDecoder;
    private final boolean devMode;
    private final boolean rbacEnabled;

    public RoleInterceptor(JwtDecoder jwtDecoder,
                           @Value("${aerofleet.security.dev-mode:false}") boolean devMode,
                           @Value("${aerofleet.security.rbac-enabled:false}") boolean rbacEnabled) {
        this.jwtDecoder = jwtDecoder;
        this.devMode = devMode;
        this.rbacEnabled = rbacEnabled;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 非控制器方法直接放行
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        // 开发模式或 RBAC 关闭时跳过
        if (devMode || !rbacEnabled) {
            return true;
        }

        // 方法级优先，回退类级
        RequireRole annotation = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (annotation == null) {
            annotation = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        if (annotation == null) {
            return true;
        }

        Role required = annotation.value();

        // 解析调用方角色：JWT role claim 优先，其次 API Key 记录的角色
        String roleClaim = resolveRole(request);
        if (roleClaim == null) {
            log.warn("RBAC 拒绝: 无 JWT role claim 且 API Key 未记录角色, path={}, requires={}",
                    request.getRequestURI(), required);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"forbidden: requires role " + required + "\"}");
            return false;
        }

        Role userRole = parseRole(roleClaim);
        if (userRole == null || !hasPermission(userRole, required)) {
            log.warn("RBAC 拒绝: userRole={}, requires={}, path={}",
                    roleClaim, required, request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"forbidden: requires role " + required + "\"}");
            return false;
        }

        return true;
    }

    /**
     * 调用方角色：Bearer JWT 的 role claim 优先；无 JWT 时用 API Key 记录的角色
     * （SDK 只用 X-API-Key，否则加注解后 SDK 写操作会全部 403）。
     *
     * @return 角色名，两条来源都拿不到时返回 null
     */
    private String resolveRole(HttpServletRequest request) {
        String fromJwt = extractRoleClaim(request);
        return fromJwt != null ? fromJwt : ApiKeyContext.getRole();
    }

    /**
     * 从 Authorization: Bearer <token> 中解码 JWT，提取 role claim。
     *
     * @return role claim 值，无 token 或解析失败时返回 null
     */
    private String extractRoleClaim(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring(7);
        try {
            Jwt jwt = jwtDecoder.decode(token);
            return jwt.getClaimAsString("role");
        } catch (JwtException e) {
            log.debug("RBAC: JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将 role claim 字符串解析为 {@link Role} 枚举（大小写不敏感）。
     *
     * @return 匹配的 Role，无法匹配时返回 null
     */
    private Role parseRole(String roleClaim) {
        return Arrays.stream(Role.values())
                .filter(r -> r.name().equalsIgnoreCase(roleClaim))
                .findFirst()
                .orElse(null);
    }

    /**
     * 判断用户角色是否满足要求。
     * <p>
     * 角色层级用 ordinal 表示：ADMIN(0) > OPERATOR(1) > OBSERVER(2)。
     * 用户 ordinal <= 要求 ordinal 即有权限。
     *
     * @param userRole     JWT 中的用户角色
     * @param requiredRole 方法要求的最小角色
     * @return 有权限返回 true
     */
    private boolean hasPermission(Role userRole, Role requiredRole) {
        return userRole.ordinal() <= requiredRole.ordinal();
    }
}
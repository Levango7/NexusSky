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
 * RBAC 角色拦截器：基于 {@link RequireRole} 注解校验 JWT 中的 {@code role} claim。
 * <p>
 * 角色层级（ordinal 越小权限越大）：{@link Role#ADMIN}(0) > {@link Role#OPERATOR}(1) > {@link Role#OBSERVER}(2)。
 * <p>
 * 跳过校验的条件（任一满足即放行，不影响现有功能）：
 * <ul>
 *   <li>{@code aerofleet.security.dev-mode=true}（开发模式）</li>
 *   <li>{@code aerofleet.security.rbac-enabled=false}（默认关闭）</li>
 *   <li>目标方法未标注 {@link RequireRole}</li>
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

        // 方法未标注 @RequireRole 则放行
        RequireRole annotation = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (annotation == null) {
            return true;
        }

        Role required = annotation.value();

        // 提取 JWT 中的 role claim
        String roleClaim = extractRoleClaim(request);
        if (roleClaim == null) {
            log.warn("RBAC 拒绝: 缺少有效 JWT 或 role claim, path={}, requires={}",
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
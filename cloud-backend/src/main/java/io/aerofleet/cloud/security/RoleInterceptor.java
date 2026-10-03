package io.aerofleet.cloud.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
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
 *   <li>SSE 流令牌的角色（{@code ?streamToken=} 路径，由 {@link StreamTokenFilter} 从
 *       {@link StreamTokenService} 签发快照写入 SecurityContext——EventSource 带不了认证头）</li>
 * </ol>
 * <p>
 * 跳过校验的条件（任一满足即放行）：
 * <ul>
 *   <li>{@code aerofleet.security.dev-mode=true}（开发模式）</li>
 *   <li>{@code aerofleet.security.rbac-enabled=false}</li>
 *   <li>生效声明是 {@link PermitAll}（显式白名单）</li>
 *   <li>非控制器方法（HandlerMethod 之外的静态资源等）</li>
 * </ul>
 * <p>
 * <b>默认拒绝</b>：方法与其所在类既没有 {@link RequireRole} 也没有 {@link PermitAll} 时返回 403。
 * 此前这里是"两者都缺即放行"，等于每个新端点默认无鉴权，且漏写注解在运行时毫无信号。
 * <p>
 * 校验失败返回 403 Forbidden，响应体 {@code {"error":"forbidden: requires role XXX"}}；
 * 缺声明时响应体 {@code {"error":"forbidden: endpoint has no role declaration"}}。
 *
 * @see RequireRole
 * @see PermitAll
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

        // 生效声明解析：方法级覆盖类级，@RequireRole 与 @PermitAll 同规则；
        // 同一元素上两者并存时按"收紧优先"取 @RequireRole。
        RequireRole requiredRole = handlerMethod.getMethodAnnotation(RequireRole.class);
        boolean methodDeclaredPublic = handlerMethod.getMethodAnnotation(PermitAll.class) != null;
        if (requiredRole == null && !methodDeclaredPublic) {
            requiredRole = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
            methodDeclaredPublic = handlerMethod.getBeanType().getAnnotation(PermitAll.class) != null;
        }
        if (requiredRole == null) {
            if (methodDeclaredPublic) {
                return true;
            }
            // 默认拒绝：既无角色要求也无白名单声明，说明这个端点从未被纳入 RBAC 设计
            log.warn("RBAC 拒绝: 端点缺少 @RequireRole/@PermitAll 声明, path={}, handler={}#{}",
                    request.getRequestURI(), handlerMethod.getBeanType().getSimpleName(),
                    handlerMethod.getMethod().getName());
            writeForbidden(response, "forbidden: endpoint has no role declaration");
            return false;
        }

        Role required = requiredRole.value();

        // 解析调用方角色：JWT role claim 优先，其次 API Key 记录的角色
        String roleClaim = resolveRole(request);
        if (roleClaim == null) {
            log.warn("RBAC 拒绝: 无 JWT role claim 且 API Key 未记录角色, path={}, requires={}",
                    request.getRequestURI(), required);
            writeForbidden(response, "forbidden: requires role " + required);
            return false;
        }

        Role userRole = parseRole(roleClaim);
        if (userRole == null || !hasPermission(userRole, required)) {
            log.warn("RBAC 拒绝: userRole={}, requires={}, path={}",
                    roleClaim, required, request.getRequestURI());
            writeForbidden(response, "forbidden: requires role " + required);
            return false;
        }

        return true;
    }

    /**
     * 调用方角色：Bearer JWT 的 role claim 优先；无 JWT 时用 API Key 记录的角色
     * （SDK 只用 X-API-Key，否则加注解后 SDK 写操作会全部 403）；
     * 两者皆无时读 SecurityContext 里的流令牌认证（SSE 端点的第三通道）。
     *
     * @return 角色名，三条来源都拿不到时返回 null
     */
    private String resolveRole(HttpServletRequest request) {
        String fromJwt = extractRoleClaim(request, jwtDecoder);
        if (fromJwt != null) {
            return fromJwt;
        }
        String fromApiKey = ApiKeyContext.getRole();
        if (fromApiKey != null) {
            return fromApiKey;
        }
        if (SecurityContextHolder.getContext().getAuthentication()
                instanceof StreamTokenAuthenticationToken streamToken) {
            return streamToken.getRole();
        }
        return null;
    }

    /**
     * 从 Authorization: Bearer <token> 中解码 JWT，提取 role claim。
     * <p>
     * 静态工具：{@link AuthController#issueStreamToken} 签发流令牌时用同一份
     * 解析逻辑取「当前角色」，保证签发与鉴权两侧的角色口径永不漂移。
     *
     * @return role claim 值，无 token 或解析失败时返回 null
     */
    static String extractRoleClaim(HttpServletRequest request, JwtDecoder decoder) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring(7);
        try {
            Jwt jwt = decoder.decode(token);
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
    /** 写 403 响应体（三个拒绝分支共用）。 */
    private void writeForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    private boolean hasPermission(Role userRole, Role requiredRole) {
        return userRole.ordinal() <= requiredRole.ordinal();
    }
}
package io.aerofleet.cloud.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 租户上下文过滤器。
 * <p>
 * 从 JWT 的 {@code tenant_id} claim 提取租户 ID 并设置到 {@link TenantContext}，
 * 使业务代码能在请求处理期间通过 {@link TenantContext#getTenantId()} 获取当前租户。
 * <p>
 * 行为规则（三态租户域，集中由 {@link TenantContext#resolveTenantScope} 决定）：
 * <ul>
 *   <li>开发模式（{@code dev-mode=true}）：跳过，不设置上下文（保持 null=全局）</li>
 *   <li>JWT 带 {@code tenant_id} → 该租户</li>
 *   <li>JWT 无 {@code tenant_id} 且 role=ADMIN → null（显式全局管理员）</li>
 *   <li>JWT 无 {@code tenant_id} 且 role 非 ADMIN → {@link TenantContext#NO_ACCESS}
 *       （已认证但看不到任何租户数据；修复前这里被当成全局管理员，等价跨租户读写）</li>
 *   <li>无 Bearer（API Key 请求或后台线程）：不改上下文；JWT 解析失败同样不改，由认证链处理</li>
 * </ul>
 * <p>
 * 在 finally 中始终清理 {@link TenantContext}，防止线程池复用导致上下文泄漏。
 */
public class TenantFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantFilter.class);

    private final JwtDecoder jwtDecoder;
    private final boolean devMode;

    public TenantFilter(JwtDecoder jwtDecoder,
                        @Value("${aerofleet.security.dev-mode:false}") boolean devMode) {
        this.jwtDecoder = jwtDecoder;
        this.devMode = devMode;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            if (devMode) {
                // 开发模式：跳过租户上下文设置，不破坏现有测试
                filterChain.doFilter(request, response);
                return;
            }

            applyJwtScope(request);

            filterChain.doFilter(request, response);
        } finally {
            // 始终清理，防止线程池复用时上下文泄漏
            TenantContext.clear();
        }
    }

    /**
     * 解出 Bearer JWT 时按其 claims 落入三态租户域；<b>没有</b> Bearer 时不动上下文。
     * <p>
     * 原实现无条件 {@code setTenantId(null)}，会把 ApiKeyFilter 已经写入的 API Key 租户
     * 覆盖成 null（= 全局管理员），使租户 ADMIN 的 API Key 获得跨租户权。
     * 无 Bearer 的两种合法情形都由更早的环节负责设值：API Key 请求（ApiKeyFilter）、
     * 以及无请求上下文的后台线程（保持 null=全局，否则 UDP 摄取/调度线程会把自己锁死）。
     *
     * @param request HTTP 请求
     */
    private void applyJwtScope(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return;
        }

        try {
            Jwt jwt = jwtDecoder.decode(authHeader.substring(7));
            Integer tenantId = parseTenantClaim(jwt.getClaim("tenant_id"));
            String role = jwt.getClaim("role");
            TenantContext.setTenantId(TenantContext.resolveTenantScope(tenantId, role));
        } catch (JwtException e) {
            log.debug("TenantFilter JWT 解析失败（不阻断请求，由认证链处理）: {}", e.getMessage());
        } catch (NumberFormatException e) {
            log.debug("TenantFilter tenant_id 不是合法整数（不阻断请求）: {}", e.getMessage());
        }
    }

    /**
     * tenant_id claim 的兼容解析：JWT 反序列化可能给出 Integer/Long/其他 Number/String。
     *
     * @return 租户 ID；claim 缺失或无法解析时 null（即「无归属」，由 {@code resolveTenantScope} 定夺）
     */
    private static Integer parseTenantClaim(Object tenantIdClaim) {
        if (tenantIdClaim == null) {
            return null;
        }
        if (tenantIdClaim instanceof Integer) {
            return (Integer) tenantIdClaim;
        }
        if (tenantIdClaim instanceof Number) {
            return ((Number) tenantIdClaim).intValue();
        }
        return Integer.valueOf(tenantIdClaim.toString().trim());
    }
}
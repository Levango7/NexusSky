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
 * 行为规则：
 * <ul>
 *   <li>开发模式（{@code dev-mode=true}）：跳过，不设置 tenant_id</li>
 *   <li>无 JWT 或 JWT 无 {@code tenant_id} claim：tenant_id 为 null（全局管理员）</li>
 *   <li>JWT 解析失败：tenant_id 为 null，不阻断请求（由后续认证链处理）</li>
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

            Integer tenantId = extractTenantId(request);
            TenantContext.setTenantId(tenantId);

            filterChain.doFilter(request, response);
        } finally {
            // 始终清理，防止线程池复用时上下文泄漏
            TenantContext.clear();
        }
    }

    /**
     * 从请求的 Authorization 头中提取 JWT，解析 tenant_id claim。
     *
     * @param request HTTP 请求
     * @return 租户 ID（Integer），null 表示无 JWT、无 tenant_id claim 或解析失败
     */
    private Integer extractTenantId(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }

        String token = authHeader.substring(7);
        try {
            Jwt jwt = jwtDecoder.decode(token);
            Object tenantIdClaim = jwt.getClaim("tenant_id");
            if (tenantIdClaim == null) {
                return null;
            }
            if (tenantIdClaim instanceof Integer) {
                return (Integer) tenantIdClaim;
            }
            if (tenantIdClaim instanceof Number) {
                return ((Number) tenantIdClaim).intValue();
            }
            // JSON 解析可能返回 Long 或其他类型，做兼容处理
            return Integer.valueOf(tenantIdClaim.toString());
        } catch (JwtException e) {
            log.debug("TenantFilter JWT 解析失败（不阻断请求）: {}", e.getMessage());
            return null;
        } catch (NumberFormatException e) {
            log.debug("TenantFilter tenant_id 格式无效: {}", e.getMessage());
            return null;
        }
    }
}
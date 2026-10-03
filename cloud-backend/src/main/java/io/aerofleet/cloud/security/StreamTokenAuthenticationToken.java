package io.aerofleet.cloud.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collections;

/**
 * SSE 流令牌认证 Token。
 * <p>
 * 请求经 {@code ?streamToken=} 参数认证成功后，由 {@link StreamTokenFilter} 创建
 * 此 Token 写入 {@link org.springframework.security.core.context.SecurityContext}，
 * 使 Spring Security 授权链（{@code anyRequest().authenticated()}）放行，同时
 * {@link RoleInterceptor} 能从 SecurityContext 解析出角色（第三角色来源）。
 * <p>
 * 携带的租户域是签发时已解析的三态值（null=全局管理员 / 租户 ID /
 * {@link TenantContext#NO_ACCESS}），消费时由过滤器原样恢复进 {@link TenantContext}，
 * 令牌不重放解析、不放大权限。
 *
 * @see StreamTokenService
 * @see StreamTokenFilter
 */
public class StreamTokenAuthenticationToken extends AbstractAuthenticationToken {

    private final String subject;
    private final String role;
    private final Integer tenantScope;

    /**
     * 创建已认证的流令牌 Token。
     *
     * @param subject     签发主体（日志/审计用）
     * @param role        签发时绑定的角色名
     * @param tenantScope 签发时绑定的租户域（三态值）
     */
    public StreamTokenAuthenticationToken(String subject, String role, Integer tenantScope) {
        super(Collections.singletonList(new SimpleGrantedAuthority("ROLE_STREAM_TOKEN")));
        this.subject = subject;
        this.role = role;
        this.tenantScope = tenantScope;
        setAuthenticated(true);
    }

    /** 签发时绑定的角色名（{@link RoleInterceptor} 第三角色来源）。 */
    public String getRole() {
        return role;
    }

    /** 签发时绑定的租户域（三态值）。 */
    public Integer getTenantScope() {
        return tenantScope;
    }

    @Override
    public Object getCredentials() {
        // opaque 令牌已消费，不再持有任何凭证材料
        return null;
    }

    @Override
    public Object getPrincipal() {
        return subject;
    }
}

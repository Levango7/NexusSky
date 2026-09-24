package io.aerofleet.cloud.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collections;
import java.util.List;

/**
 * API Key 认证 Token。
 * <p>
 * 当请求通过 X-API-Key Header 认证成功后，由 {@link ApiKeyFilter} 创建此 Token
 * 并设置到 {@link org.springframework.security.core.context.SecurityContext}，
 * 使后续的 Spring Security 授权链能识别 API Key 认证的用户身份。
 * <p>
 * 与 JWT 认证（Bearer Token）并行运行：如果请求携带 X-API-Key 且验证通过，
 * 则使用此 Token；否则交由 JWT 认证链处理。
 *
 * @author AeroFleet Cloud Team
 */
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final String keyId;
    private final Integer tenantId;
    private final String scopes;

    /**
     * 创建已认证的 API Key Token。
     *
     * @param keyId    API Key ID（展示标识）
     * @param tenantId 租户 ID
     * @param scopes   权限范围（JSON 数组字符串）
     */
    public ApiKeyAuthenticationToken(String keyId, Integer tenantId, String scopes) {
        super(Collections.singletonList(new SimpleGrantedAuthority("ROLE_API_KEY")));
        this.keyId = keyId;
        this.tenantId = tenantId;
        this.scopes = scopes;
        setAuthenticated(true);
    }

    /**
     * 创建未认证的 API Key Token（用于认证前的初始状态）。
     */
    public ApiKeyAuthenticationToken() {
        super(List.of());
        this.keyId = null;
        this.tenantId = null;
        this.scopes = null;
        setAuthenticated(false);
    }

    @Override
    public Object getCredentials() {
        // API Key 不在此暴露明文凭证
        return null;
    }

    @Override
    public Object getPrincipal() {
        return keyId;
    }

    /**
     * 获取 API Key ID。
     *
     * @return API Key ID
     */
    public String getKeyId() {
        return keyId;
    }

    /**
     * 获取租户 ID。
     *
     * @return 租户 ID
     */
    public Integer getTenantId() {
        return tenantId;
    }

    /**
     * 获取权限范围。
     *
     * @return 权限范围字符串（JSON 数组）
     */
    public String getScopes() {
        return scopes;
    }
}
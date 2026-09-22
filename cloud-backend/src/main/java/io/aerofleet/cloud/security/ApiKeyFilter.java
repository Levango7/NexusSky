package io.aerofleet.cloud.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * API Key 认证过滤器。
 * <p>
 * 从 {@code X-API-Key} Header 读取 API Key，查询 {@link ApiKeyEntity} 验证有效性
 * （未撤销、未过期），设置 {@link TenantContext} 和 {@link ApiKeyContext}（ThreadLocal），
 * 并记录 lastUsedAt。
 * <p>
 * 行为规则：
 * <ul>
 *   <li>开发模式（{@code dev-mode=true}）：跳过，不处理 API Key</li>
 *   <li>无 X-API-Key Header：跳过，交由后续 JWT 认证链处理</li>
 *   <li>API Key 无效（不存在、已撤销、已过期）：跳过，交由后续 JWT 认证链处理</li>
 *   <li>API Key 有效：设置 TenantContext + ApiKeyContext，记录 lastUsedAt</li>
 * </ul>
 * <p>
 * 在 finally 中始终清理 {@link ApiKeyContext} 和 {@link TenantContext}，
 * 防止线程池复用导致上下文泄漏。
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private static final String API_KEY_HEADER = "X-API-Key";

    private final boolean devMode;

    @Autowired(required = false)
    private ApiKeyRepository apiKeyRepository;

    public ApiKeyFilter(@Value("${aerofleet.security.dev-mode:false}") boolean devMode) {
        this.devMode = devMode;
    }

    /**
     * 手动注入 ApiKeyRepository（用于 SecurityConfig 中手动 new 的场景）。
     *
     * @param apiKeyRepository API Key Repository
     */
    public void setApiKeyRepository(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            if (devMode) {
                // 开发模式：跳过 API Key 认证，不破坏现有测试
                filterChain.doFilter(request, response);
                return;
            }

            String apiKey = request.getHeader(API_KEY_HEADER);
            if (apiKey == null || apiKey.isBlank()) {
                // 无 API Key Header，交由后续 JWT 认证链处理
                filterChain.doFilter(request, response);
                return;
            }

            if (apiKeyRepository == null) {
                log.debug("ApiKeyRepository 未注入，跳过 API Key 认证");
                filterChain.doFilter(request, response);
                return;
            }

            // 查询 API Key 实体（仅查找未撤销的）
            Optional<ApiKeyEntity> entityOpt = apiKeyRepository.findByKeyIdAndRevokedFalse(apiKey);
            if (entityOpt.isEmpty()) {
                log.debug("API Key 不存在或已撤销: {}", maskForLog(apiKey));
                filterChain.doFilter(request, response);
                return;
            }

            ApiKeyEntity entity = entityOpt.get();

            // 检查是否过期
            if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(Instant.now())) {
                log.debug("API Key 已过期: keyId={}", entity.getKeyId());
                filterChain.doFilter(request, response);
                return;
            }

            // 设置 TenantContext 和 ApiKeyContext
            TenantContext.setTenantId(entity.getTenantId());
            ApiKeyContext.set(entity.getKeyId(), entity.getTenantId(), entity.getScopes());

            // 异步更新 lastUsedAt（不影响请求处理）
            try {
                entity.setLastUsedAt(Instant.now());
                apiKeyRepository.save(entity);
            } catch (Exception e) {
                log.warn("更新 API Key lastUsedAt 失败: keyId={}, error={}",
                        entity.getKeyId(), e.getMessage());
            }

            log.debug("API Key 认证成功: keyId={}, tenantId={}",
                    entity.getKeyId(), entity.getTenantId());

            filterChain.doFilter(request, response);
        } finally {
            // 始终清理，防止线程池复用时上下文泄漏
            ApiKeyContext.clear();
            TenantContext.clear();
        }
    }

    /**
     * 日志中脱敏显示 API Key，仅保留前8和后4字符。
     */
    private String maskForLog(String apiKey) {
        if (apiKey == null || apiKey.length() <= 12) {
            return "****";
        }
        return apiKey.substring(0, 8) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
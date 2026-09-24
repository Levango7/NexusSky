package io.aerofleet.cloud.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * API Key 认证过滤器。
 * <p>
 * 从 {@code X-API-Key} Header 读取 API Key，计算 SHA-256 哈希后查询 {@link ApiKeyEntity}
 * 验证有效性（未撤销、未过期），设置 {@link SecurityContextHolder}（ApiKeyAuthenticationToken）、
 * {@link TenantContext} 和 {@link ApiKeyContext}（ThreadLocal），并记录 lastUsedAt。
 * <p>
 * 安全设计：
 * <ul>
 *   <li>数据库中只存储 API Key 的 SHA-256 哈希（keyHash），不存储明文</li>
 *   <li>认证时从 Header 提取明文 Key，计算哈希后用哈希值查询数据库</li>
 *   <li>即使数据库泄露，攻击者也无法还原明文 Key</li>
 * </ul>
 * <p>
 * 行为规则：
 * <ul>
 *   <li>开发模式（{@code dev-mode=true}）：跳过，不处理 API Key</li>
 *   <li>无 X-API-Key Header：跳过，交由后续 JWT 认证链处理</li>
 *   <li>API Key 无效（不存在、已撤销、已过期）：跳过，交由后续 JWT 认证链处理</li>
 *   <li>API Key 有效：设置 SecurityContext + TenantContext + ApiKeyContext，记录 lastUsedAt</li>
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

            // 计算 API Key 的 SHA-256 哈希，用哈希值查询数据库
            String keyHash = sha256Hex(apiKey);
            Optional<ApiKeyEntity> entityOpt = apiKeyRepository.findByKeyHashAndRevokedFalse(keyHash);
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

            // 设置 SecurityContext（ApiKeyAuthenticationToken），使 Spring Security 授权链识别 API Key 认证
            ApiKeyAuthenticationToken authToken = new ApiKeyAuthenticationToken(
                    entity.getKeyId(), entity.getTenantId(), entity.getScopes());
            SecurityContextHolder.getContext().setAuthentication(authToken);

            // 设置 TenantContext 和 ApiKeyContext（ThreadLocal，供业务代码使用）
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
            // SecurityContext 由 Spring Security 框架在请求结束时自动清理
        }
    }

    /**
     * 计算字符串的 SHA-256 哈希，返回 Hex 编码的哈希值。
     *
     * @param input 待哈希的字符串
     * @return Hex 编码的 SHA-256 哈希（64 字符）
     */
    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
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

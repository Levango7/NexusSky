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
import java.util.HexFormat;
import java.util.Optional;

/**
 * API Key 认证过滤器。
 * <p>
 * 从 {@code X-API-Key} Header 读取 API Key，经 {@link ApiKeyCache}（内存快照，
 * 60s TTL，未命中才查库）验证有效性（未撤销、未过期），设置
 * {@link SecurityContextHolder}（ApiKeyAuthenticationToken）、{@link TenantContext}
 * 和 {@link ApiKeyContext}（ThreadLocal，含设备 Key 的 sysid），并经
 * {@link ApiKeyLastUsedTracker} 合并记录 lastUsedAt（每 Key 至多每 30s 一次刷库）。
 * <p>
 * 安全设计：
 * <ul>
 *   <li>数据库中只存储 API Key 的 SHA-256 哈希（keyHash），不存储明文</li>
 *   <li>认证时从 Header 提取明文 Key，计算哈希后按哈希查询/缓存</li>
 *   <li>即使数据库或缓存泄露，攻击者也无法还原明文 Key</li>
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
 * 撤销/轮换的生效时间：同一 JVM 内即时（{@link ApiKeyCache#invalidate(String)}），
 * 多节点部署下其他节点至多一个缓存 TTL（60s），见 docs/security-design.md。
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

    @Autowired(required = false)
    private ApiKeyCache apiKeyCache;

    @Autowired(required = false)
    private ApiKeyLastUsedTracker lastUsedTracker;

    public ApiKeyFilter(@Value("${aerofleet.security.dev-mode:false}") boolean devMode) {
        this.devMode = devMode;
    }

    /**
     * 手动注入依赖（用于 SecurityConfig 中手动 new 的场景）。
     *
     * @param apiKeyRepository API Key Repository
     */
    public void setApiKeyRepository(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    /** 手动注入认证缓存（SecurityConfig 手动 new 场景）。 */
    public void setApiKeyCache(ApiKeyCache apiKeyCache) {
        this.apiKeyCache = apiKeyCache;
    }

    /** 手动注入 lastUsedAt 合并写组件（SecurityConfig 手动 new 场景）。 */
    public void setLastUsedTracker(ApiKeyLastUsedTracker lastUsedTracker) {
        this.lastUsedTracker = lastUsedTracker;
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

            String keyHash = sha256Hex(apiKey);
            ApiKeyCache.CachedKey cached = resolveKey(keyHash);
            if (cached == null || !cached.valid) {
                log.debug("API Key 不存在、已撤销或已过期: {}", maskForLog(apiKey));
                filterChain.doFilter(request, response);
                return;
            }

            // 设置 SecurityContext（ApiKeyAuthenticationToken），使 Spring Security 授权链识别 API Key 认证
            ApiKeyAuthenticationToken authToken = new ApiKeyAuthenticationToken(
                    cached.keyId, cached.tenantId, cached.scopes, cached.role);
            SecurityContextHolder.getContext().setAuthentication(authToken);

            // 设置 TenantContext 和 ApiKeyContext（ThreadLocal，供业务代码使用）
            // 无租户归属的 Key 只有 ADMIN 才是全局管理员；其余落 NO_ACCESS，
            // 否则一把不带 tenant_id 的 OPERATOR Key 就能读遍全租户。
            TenantContext.setTenantId(
                    TenantContext.resolveTenantScope(cached.tenantId, cached.role));
            ApiKeyContext.set(cached.keyId, cached.tenantId, cached.scopes, cached.role, cached.sysid);

            // lastUsedAt 合并写：每 Key 至多每 30s 一次刷库，认证路径零同步数据库写
            if (lastUsedTracker != null) {
                lastUsedTracker.record(cached.keyId);
            }

            log.debug("API Key 认证成功: keyId={}, tenantId={}, sysid={}",
                    cached.keyId, cached.tenantId, cached.sysid);

            filterChain.doFilter(request, response);
        } finally {
            // 始终清理，防止线程池复用时上下文泄漏
            ApiKeyContext.clear();
            TenantContext.clear();
            // SecurityContext 由 Spring Security 框架在请求结束时自动清理
        }
    }

    /**
     * 缓存优先解析 Key：未命中才查库并回填（含负缓存）；
     * 未装配缓存时退化为直接查库（行为等价于旧版，少一层内存快照）。
     *
     * @param keyHash SHA-256 hex
     * @return 判定结果；null 或 {@code valid=false} 均表示拒绝
     */
    private ApiKeyCache.CachedKey resolveKey(String keyHash) {
        if (apiKeyCache != null) {
            ApiKeyCache.CachedKey hit = apiKeyCache.lookup(keyHash);
            if (hit != null) {
                return hit;
            }
            Optional<ApiKeyEntity> entityOpt = apiKeyRepository.findByKeyHash(keyHash);
            if (entityOpt.isPresent() && !entityOpt.get().isRevoked()) {
                apiKeyCache.putValid(keyHash, entityOpt.get());
            } else {
                apiKeyCache.putInvalid(keyHash);
            }
            return apiKeyCache.lookup(keyHash);
        }
        // 无缓存退化路径（测试/未装配场景）
        return apiKeyRepository.findByKeyHash(keyHash)
                .filter(entity -> !entity.isRevoked())
                .map(ApiKeyCache.CachedKey::from)
                .orElse(null);
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

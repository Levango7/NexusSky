package io.aerofleet.cloud.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ApiKeyFilter} 缓存化后的行为测试。
 * <p>
 * 钉住的语义：缓存命中时零数据库 IO（这是缓存存在的全部意义）、未命中查库并回填、
 * 已撤销/不存在的 Key 负缓存（不重复打库）、上下文三件套（SecurityContext/
 * TenantContext/ApiKeyContext 含 sysid）在链内可见且请求后清理、lastUsedAt 走
 * 合并写而非每请求同步 save。
 * <p>
 * 关键测试姿势：上下文是 ThreadLocal，{@code finally} 会在链返回后清理（防线程池
 * 复用泄漏，生产正确语义）——所以一切断言都在**链内**快照，不在 doFilter 返回后读。
 */
@DisplayName("ApiKeyFilter 认证过滤器（缓存化）")
class ApiKeyFilterCachedTest {

    private static final String PLAIN_KEY = "nsk_test_0123456789abcdef0123456789abcdef";

    private final ApiKeyRepository repository = mock(ApiKeyRepository.class);
    private final ApiKeyCache cache = new ApiKeyCache();
    private final ApiKeyLastUsedTracker tracker = mock(ApiKeyLastUsedTracker.class);

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        ApiKeyContext.clear();
        TenantContext.clear();
    }

    /** 链内快照：在业务代码可见的时刻捕获全部上下文。 */
    private static final class Snapshot {
        boolean chainExecuted;
        boolean apiKeyPresent;
        String keyId;
        Integer sysid;
        String role;
        Integer tenantId;
        boolean authenticated;
    }

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    private jakarta.servlet.FilterChain capturingChain() {
        return new jakarta.servlet.FilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) {
                Snapshot s = new Snapshot();
                s.chainExecuted = true;
                s.apiKeyPresent = ApiKeyContext.isPresent();
                s.keyId = ApiKeyContext.getKeyId();
                s.sysid = ApiKeyContext.getSysid();
                s.role = ApiKeyContext.getRole();
                s.tenantId = TenantContext.getTenantId();
                s.authenticated = SecurityContextHolder.getContext().getAuthentication() != null;
                snapshot.set(s);
            }
        };
    }

    private ApiKeyFilter filter(boolean devMode) {
        ApiKeyFilter filter = new ApiKeyFilter(devMode);
        filter.setApiKeyRepository(repository);
        filter.setApiKeyCache(cache);
        filter.setLastUsedTracker(tracker);
        return filter;
    }

    private MockHttpServletRequest requestWithKey(String key) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/alarms/events");
        if (key != null) {
            request.addHeader("X-API-Key", key);
        }
        return request;
    }

    private ApiKeyEntity validEntity() {
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setKeyId("nsk_1_device01");
        entity.setKeyHash(sha256Hex(PLAIN_KEY));
        entity.setTenantId(7);
        entity.setSysid(42);
        entity.setName("device-key");
        entity.setScopes("[ingest]");
        entity.setRole("OPERATOR");
        entity.setCreatedAt(Instant.now());
        entity.setRevoked(false);
        return entity;
    }

    private Snapshot run(ApiKeyFilter filter, String key) throws ServletException, IOException {
        filter.doFilter(requestWithKey(key), new MockHttpServletResponse(), capturingChain());
        Snapshot s = snapshot.get();
        assertThat(s).isNotNull();
        assertThat(s.chainExecuted).as("链必须继续执行（认证与否都不该截断请求）").isTrue();
        return s;
    }

    @Test
    @DisplayName("缓存命中：零数据库 IO，上下文三件套含 sysid，lastUsedAt 走合并写")
    void cacheHitSkipsDatabase() throws ServletException, IOException {
        cache.putValid(sha256Hex(PLAIN_KEY), validEntity());

        Snapshot s = run(filter(false), PLAIN_KEY);

        assertThat(s.apiKeyPresent).isTrue();
        assertThat(s.keyId).isEqualTo("nsk_1_device01");
        assertThat(s.sysid).isEqualTo(42);
        assertThat(s.role).isEqualTo("OPERATOR");
        assertThat(s.tenantId).isEqualTo(7);
        assertThat(s.authenticated).isTrue();
        // 合并写：认证路径只 record，不 save
        verify(tracker).record("nsk_1_device01");
        verify(repository, never()).findByKeyHash(anyString());
        verify(repository, never()).save(any());
        // 请求结束后 ThreadLocal 必须清理（防线程池泄漏）
        assertThat(ApiKeyContext.isPresent()).isFalse();
    }

    @Test
    @DisplayName("未命中：查库一次并回填缓存，第二次请求零数据库 IO")
    void missQueriesOnceThenCaches() throws ServletException, IOException {
        when(repository.findByKeyHash(sha256Hex(PLAIN_KEY))).thenReturn(Optional.of(validEntity()));

        Snapshot first = run(filter(false), PLAIN_KEY);
        assertThat(first.keyId).isEqualTo("nsk_1_device01");
        verify(repository, times(1)).findByKeyHash(sha256Hex(PLAIN_KEY));

        Snapshot second = run(filter(false), PLAIN_KEY);
        assertThat(second.keyId).isEqualTo("nsk_1_device01");
        // 第二次走缓存：查库次数不增长
        verify(repository, times(1)).findByKeyHash(sha256Hex(PLAIN_KEY));
    }

    @Test
    @DisplayName("已撤销的 Key：负缓存，两次请求只查库一次，均不认证")
    void revokedKeyIsNegativelyCached() throws ServletException, IOException {
        ApiKeyEntity revoked = validEntity();
        revoked.setRevoked(true);
        when(repository.findByKeyHash(sha256Hex(PLAIN_KEY))).thenReturn(Optional.of(revoked));

        Snapshot first = run(filter(false), PLAIN_KEY);
        Snapshot second = run(filter(false), PLAIN_KEY);

        assertThat(first.apiKeyPresent).isFalse();
        assertThat(first.authenticated).isFalse();
        assertThat(second.apiKeyPresent).isFalse();
        verify(repository, times(1)).findByKeyHash(sha256Hex(PLAIN_KEY));
        verify(tracker, never()).record(anyString());
    }

    @Test
    @DisplayName("不存在的 Key：负缓存，重复错误 Key 不重复打库")
    void unknownKeyIsNegativelyCached() throws ServletException, IOException {
        when(repository.findByKeyHash(anyString())).thenReturn(Optional.empty());

        Snapshot first = run(filter(false), PLAIN_KEY);
        Snapshot second = run(filter(false), PLAIN_KEY);

        assertThat(first.apiKeyPresent).isFalse();
        assertThat(second.apiKeyPresent).isFalse();
        verify(repository, times(1)).findByKeyHash(sha256Hex(PLAIN_KEY));
    }

    @Test
    @DisplayName("缓存失效（撤销后 invalidate）：条目清除，回库重查到已撤销 → 拒绝")
    void invalidateForcesRefetch() throws ServletException, IOException {
        cache.putValid(sha256Hex(PLAIN_KEY), validEntity());

        // 撤销 + 缓存失效（ApiKeyController 的撤销路径）
        ApiKeyEntity revoked = validEntity();
        revoked.setRevoked(true);
        when(repository.findByKeyHash(sha256Hex(PLAIN_KEY))).thenReturn(Optional.of(revoked));
        cache.invalidate(sha256Hex(PLAIN_KEY));

        Snapshot s = run(filter(false), PLAIN_KEY);

        assertThat(s.apiKeyPresent).isFalse();
        assertThat(s.authenticated).isFalse();
    }

    @Test
    @DisplayName("无 X-API-Key Header：完全跳过")
    void skipsWithoutHeader() throws ServletException, IOException {
        Snapshot s = run(filter(false), null);

        assertThat(s.apiKeyPresent).isFalse();
        verify(repository, never()).findByKeyHash(anyString());
    }

    @Test
    @DisplayName("dev-mode=true：带 Key 也跳过（不查库不认证）")
    void devModeSkipsEverything() throws ServletException, IOException {
        Snapshot s = run(filter(true), PLAIN_KEY);

        assertThat(s.apiKeyPresent).isFalse();
        verify(repository, never()).findByKeyHash(anyString());
    }

    @Test
    @DisplayName("未装配缓存（旧接线/测试场景）：退化为直查库，行为等价")
    void fallsBackWithoutCache() throws ServletException, IOException {
        when(repository.findByKeyHash(sha256Hex(PLAIN_KEY))).thenReturn(Optional.of(validEntity()));
        ApiKeyFilter bare = new ApiKeyFilter(false);
        bare.setApiKeyRepository(repository);

        Snapshot s = run(bare, PLAIN_KEY);

        assertThat(s.keyId).isEqualTo("nsk_1_device01");
        assertThat(s.sysid).isEqualTo(42);
        verify(repository, times(1)).findByKeyHash(sha256Hex(PLAIN_KEY));
    }

    /** 与 ApiKeyFilter 同口径的 SHA-256 hex（测试独立复算防口径漂移）。 */
    private static String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

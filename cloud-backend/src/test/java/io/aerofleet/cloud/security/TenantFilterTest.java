package io.aerofleet.cloud.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TenantFilter} 单元测试（P1 #7 变异审计收口）。
 *
 * <p>此前 src/test 里<b>没有任何文件</b>引用过 TenantFilter——它只在 Spring 上下文
 * （{@link SecurityConfig#securityFilterChain}）里被 new 出来，而本仓门禁测试全部
 * "直接 new 绕开上下文"，于是"上下文泄漏清理"和"claim 兼容解析"两条契约一个都没被盯过。
 *
 * <p>姿势对齐 {@link ApiKeyFilterCachedTest}：捕获式 FilterChain 在链内拍快照，
 * finally 的跨线程池泄漏清理则在 doFilter 返回后断言。
 */
@DisplayName("TenantFilter 租户上下文过滤器")
class TenantFilterTest {

    private final JwtDecoder jwtDecoder = mock(JwtDecoder.class);

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private TenantFilter filter() {
        return new TenantFilter(jwtDecoder, false);
    }

    private MockHttpServletRequest request(String token, Object tenantClaim) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users");
        request.addHeader("Authorization", "Bearer " + token);
        when(jwtDecoder.decode(token)).thenAnswer(invocation -> new Jwt(token,
                Instant.now(), Instant.now().plusSeconds(300), Map.of("typ", "JWT"),
                Map.of("tenant_id", tenantClaim, "role", "OPERATOR")));
        return request;
    }

    @Test
    @DisplayName("链内可见租户，请求结束后 TenantContext 必须清理（finally 漏清 = 跨请求串租户）")
    void tenantContextVisibleInChainAndClearedAfter() throws ServletException, IOException {
        MockHttpServletRequest request = request("tok-7", 7);
        AtomicReference<Integer> inChain = new AtomicReference<>();
        FilterChain chain = capturingChain(inChain);

        filter().doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(inChain.get()).as("链内业务代码要读得到当前租户").isEqualTo(7);
        // 变异收口：finally 里的 TenantContext.clear() 删掉后，线程池复用时
        // 下一个请求会读到上一个请求的租户——ApiKeyFilter 侧的清理早有用例，
        // TenantFilter 这条（同一个 ThreadLocal 的另一个清理点）此前零覆盖。
        assertThat(TenantContext.getTenantId())
                .as("请求结束后 TenantContext 必须清理（线程池复用防串租户）")
                .isNull();
    }

    @Test
    @DisplayName("tenant_id 兼容解析：Double 7.0 也要能落到租户 7（instanceof Number 取反会把它判成 NFE）")
    void doubleTenantClaimStillResolves() throws ServletException, IOException {
        MockHttpServletRequest request = request("tok-double", Double.valueOf(7.0));
        AtomicReference<Integer> inChain = new AtomicReference<>();
        FilterChain chain = capturingChain(inChain);

        filter().doFilter(request, new MockHttpServletResponse(), chain);

        // 变异收口：instanceof Number 分支取反后，非 Integer 的 Number 落到
        // Integer.valueOf(toString())——Long 尚能蒙混（"7"），Double 7.0 直接
        // NumberFormatException 被吞掉 ⇒ 上下文不设置 ⇒ 业务读到"无租户"。
        assertThat(inChain.get()).as("Double 形状的 tenant_id claim 也必须解析出租户 7").isEqualTo(7);
    }

    private static FilterChain capturingChain(AtomicReference<Integer> inChain) {
        return new FilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) {
                inChain.set(TenantContext.getTenantId());
            }
        };
    }
}

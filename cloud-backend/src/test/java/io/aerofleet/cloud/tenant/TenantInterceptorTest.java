package io.aerofleet.cloud.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.security.ApiKeyContext;
import io.aerofleet.cloud.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TenantInterceptor 限流 key 解析与 429 行为的纯单元测试（无 Spring 上下文）。
 * <p>
 * 双轨统一的回归保护：限流 key 必须来自认证链写入的 security.TenantContext
 * （真实租户）或客户端 IP，而<b>不是</b>客户端可控的 X-Tenant-Id header——
 * 否则轮换 header 即可无限获取新桶绕过限流。租户隔离本身由 TenantFilter /
 * ApiKeyFilter + 三态租户域负责，不在本测试范围。
 */
@DisplayName("TenantInterceptor 限流 key 与 429")
class TenantInterceptorTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
        ApiKeyContext.clear();
    }

    private TenantInterceptor interceptor(int limit) {
        return new TenantInterceptor(limit, new ObjectMapper());
    }

    private MockHttpServletRequest requestFrom(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/devices");
        request.setRemoteAddr(ip);
        return request;
    }

    @Test
    @DisplayName("未超限：同一 IP 连续请求全部放行")
    void underLimitPasses() throws Exception {
        TenantInterceptor interceptor = interceptor(3);
        for (int i = 0; i < 3; i++) {
            assertThat(interceptor.preHandle(requestFrom("10.0.0.1"), new MockHttpServletResponse(), new Object()))
                    .isTrue();
        }
    }

    @Test
    @DisplayName("超限：第 N+1 次返回 429 + JSON 错误体")
    void overLimitReturns429() throws Exception {
        TenantInterceptor interceptor = interceptor(2);
        for (int i = 0; i < 2; i++) {
            assertThat(interceptor.preHandle(requestFrom("10.0.0.2"), new MockHttpServletResponse(), new Object()))
                    .isTrue();
        }
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(requestFrom("10.0.0.2"), rejected, new Object())).isFalse();
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getContentType()).contains("application/json");
        assertThat(rejected.getContentAsString()).contains("Too Many Requests");
    }

    @Test
    @DisplayName("轮换 X-Tenant-Id header 不能绕过限流（回归：key 不取自客户端 header）")
    void headerRotationDoesNotBypassRateLimit() throws Exception {
        TenantInterceptor interceptor = interceptor(2);
        for (int i = 1; i <= 2; i++) {
            MockHttpServletRequest request = requestFrom("10.0.0.3");
            request.addHeader("X-Tenant-Id", "tenant-" + i);
            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        }
        MockHttpServletRequest third = requestFrom("10.0.0.3");
        third.addHeader("X-Tenant-Id", "tenant-3");
        assertThat(interceptor.preHandle(third, new MockHttpServletResponse(), new Object())).isFalse();
    }

    @Test
    @DisplayName("真实租户按租户分桶：租户间互不占额，且与 IP 桶隔离")
    void realTenantBucketsAreIsolated() throws Exception {
        TenantInterceptor interceptor = interceptor(2);
        // 未认证请求打满 IP 桶（10.0.0.4）
        for (int i = 0; i < 2; i++) {
            assertThat(interceptor.preHandle(requestFrom("10.0.0.4"), new MockHttpServletResponse(), new Object()))
                    .isTrue();
        }
        assertThat(interceptor.preHandle(requestFrom("10.0.0.4"), new MockHttpServletResponse(), new Object()))
                .isFalse();

        // 租户 7 / 8 各有独立额度，不受同 IP 的已满桶影响
        TenantContext.setTenantId(7);
        assertThat(interceptor.preHandle(requestFrom("10.0.0.4"), new MockHttpServletResponse(), new Object()))
                .isTrue();
        TenantContext.setTenantId(8);
        assertThat(interceptor.preHandle(requestFrom("10.0.0.4"), new MockHttpServletResponse(), new Object()))
                .isTrue();
    }

    @Test
    @DisplayName("NO_ACCESS 不是真实租户：回落 IP 桶，与未认证请求共享额度")
    void noAccessFallsBackToIpBucket() throws Exception {
        TenantInterceptor interceptor = interceptor(2);
        TenantContext.setTenantId(TenantContext.NO_ACCESS);
        assertThat(interceptor.preHandle(requestFrom("10.0.0.5"), new MockHttpServletResponse(), new Object()))
                .isTrue();
        assertThat(interceptor.preHandle(requestFrom("10.0.0.5"), new MockHttpServletResponse(), new Object()))
                .isTrue();
        // 同 IP 第 3 次（仍处 NO_ACCESS 域）超限
        assertThat(interceptor.preHandle(requestFrom("10.0.0.5"), new MockHttpServletResponse(), new Object()))
                .isFalse();
    }
}

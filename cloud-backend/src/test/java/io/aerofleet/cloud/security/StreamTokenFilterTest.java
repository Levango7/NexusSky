package io.aerofleet.cloud.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StreamTokenFilter 单测（直接实例化，无 Spring 上下文）。
 * <p>
 * 姿势对齐 ApiKeyFilterCachedTest：捕获式 FilterChain 在链内拍快照，验证
 * SecurityContext / TenantContext 在链内可见、请求后清理（线程池复用防泄漏）。
 * <p>
 * 本过滤器不自行写 401：拒绝分支的表现是「不设置认证、照常放行」，由授权层
 * 按匿名统一 401——测试断言的是这条契约而不是 HTTP 状态码。
 */
@DisplayName("StreamTokenFilter SSE 流令牌过滤器")
class StreamTokenFilterTest {

    private StreamTokenService service;
    private StreamTokenFilter filter;

    /** 链内快照：过滤器放行时刻的上下文状态。 */
    private static final class Snapshot {
        boolean chainReached;
        boolean authenticated;
        Object principal;
        Integer tenantId;
    }

    private final Snapshot snapshot = new Snapshot();

    @BeforeEach
    void setUp() {
        service = new StreamTokenService();
        filter = new StreamTokenFilter(false, service);
        snapshot.chainReached = false;
        snapshot.authenticated = false;
        snapshot.principal = null;
        snapshot.tenantId = null;
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        ApiKeyContext.clear();
    }

    private jakarta.servlet.FilterChain capturingChain() {
        return new jakarta.servlet.FilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) {
                snapshot.chainReached = true;
                snapshot.authenticated =
                        SecurityContextHolder.getContext().getAuthentication() != null;
                Object principal = SecurityContextHolder.getContext().getAuthentication() == null
                        ? null
                        : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
                snapshot.principal = principal;
                snapshot.tenantId = TenantContext.getTenantId();
            }
        };
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        return request;
    }

    private void runFilter(MockHttpServletRequest request) throws ServletException, IOException {
        filter.doFilter(request, new org.springframework.mock.web.MockHttpServletResponse(),
                capturingChain());
    }

    @Test
    @DisplayName("有效令牌：链内已认证（ROLE_STREAM_TOKEN + 绑定主体），租户域原样恢复，链后清理")
    void validTokenAuthenticatesAndRestoresTenantScope() throws ServletException, IOException {
        String token = service.issue("op-1", "OPERATOR", 7);
        MockHttpServletRequest request = request("GET", "/api/v1/alarms/stream");
        request.setParameter("streamToken", token);

        runFilter(request);

        assertThat(snapshot.chainReached).isTrue();
        assertThat(snapshot.authenticated).isTrue();
        assertThat(snapshot.principal).isEqualTo("op-1");
        assertThat(snapshot.tenantId).isEqualTo(7);
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isInstanceOf(StreamTokenAuthenticationToken.class);
        // 请求后 TenantContext 必须被清理（finally 语义，防线程池复用泄漏）
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    @DisplayName("NO_ACCESS 哨兵租户域原样恢复（签发时看不到的，建流后也看不到）")
    void noAccessScopeRestoredVerbatim() throws ServletException, IOException {
        String token = service.issue("observer-no-tenant", "OBSERVER", TenantContext.NO_ACCESS);
        MockHttpServletRequest request = request("GET", "/api/v1/alarms/stream");
        request.setParameter("streamToken", token);

        runFilter(request);

        assertThat(snapshot.chainReached).isTrue();
        assertThat(snapshot.tenantId).isEqualTo(TenantContext.NO_ACCESS);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    @DisplayName("单次用：同一令牌第二次请求不再认证（照常放行，交授权层 401）")
    void consumedTokenDoesNotAuthenticateTwice() throws ServletException, IOException {
        String token = service.issue("op-1", "OPERATOR", 7);

        MockHttpServletRequest first = request("GET", "/api/v1/alarms/stream");
        first.setParameter("streamToken", token);
        runFilter(first);
        assertThat(snapshot.authenticated).isTrue();

        // 生产链中 SecurityContext 由 Spring Security 在请求结束时清理（与 ApiKeyFilter
        // 同约定，过滤器不清理）；standalone 测试须手动模拟请求边界
        SecurityContextHolder.clearContext();

        MockHttpServletRequest second = request("GET", "/api/v1/alarms/stream");
        second.setParameter("streamToken", token);
        runFilter(second);
        assertThat(snapshot.chainReached).isTrue();
        assertThat(snapshot.authenticated).isFalse();
    }

    @Test
    @DisplayName("无效令牌：不认证但照常放行（拒绝由授权层统一 401，与 ApiKeyFilter 同姿势）")
    void invalidTokenSkipsAuthentication() throws ServletException, IOException {
        MockHttpServletRequest request = request("GET", "/api/v1/alarms/stream");
        request.setParameter("streamToken", "nsk_bogus_token_0000000000000000000");

        runFilter(request);

        assertThat(snapshot.chainReached).isTrue();
        assertThat(snapshot.authenticated).isFalse();
    }

    @Test
    @DisplayName("无 streamToken 参数：完全跳过（匿名交由后续链处理）")
    void skipsWithoutParam() throws ServletException, IOException {
        runFilter(request("GET", "/api/v1/alarms/stream"));

        assertThat(snapshot.chainReached).isTrue();
        assertThat(snapshot.authenticated).isFalse();
    }

    @Test
    @DisplayName("非 SSE 路径零参与：参数在也不处理")
    void skipsNonStreamPaths() throws ServletException, IOException {
        String token = service.issue("op-1", "OPERATOR", 7);

        MockHttpServletRequest alarmsList = request("GET", "/api/v1/alarms/events");
        alarmsList.setParameter("streamToken", token);
        runFilter(alarmsList);
        assertThat(snapshot.chainReached).isTrue();
        assertThat(snapshot.authenticated).isFalse();
        // 令牌未被非 SSE 路径消费掉（仍可正常建流）
        assertThat(service.consume(token)).isNotNull();
    }

    @Test
    @DisplayName("surveillance SSE 路径同样受保护（路径模板匹配）")
    void handlesSurveillanceEventsPath() throws ServletException, IOException {
        String token = service.issue("op-1", "OPERATOR", null);
        MockHttpServletRequest request =
                request("GET", "/api/v1/surveillance/devices/cam-9/events");
        request.setParameter("streamToken", token);

        runFilter(request);

        assertThat(snapshot.chainReached).isTrue();
        assertThat(snapshot.authenticated).isTrue();
    }

    @Test
    @DisplayName("头凭证在场时流令牌不参与：Authorization / X-API-Key 优先（一请求一凭证）")
    void headerCredentialsTakePrecedence() throws ServletException, IOException {
        String token = service.issue("op-1", "OPERATOR", 7);

        MockHttpServletRequest withBearer = request("GET", "/api/v1/alarms/stream");
        withBearer.setParameter("streamToken", token);
        withBearer.addHeader("Authorization", "Bearer some.jwt.value");
        runFilter(withBearer);
        assertThat(snapshot.authenticated).isFalse();

        MockHttpServletRequest withApiKey = request("GET", "/api/v1/alarms/stream");
        withApiKey.setParameter("streamToken", token);
        withApiKey.addHeader("X-API-Key", "nsk_some_key");
        runFilter(withApiKey);
        assertThat(snapshot.authenticated).isFalse();

        // 两次都未消费，令牌仍完整
        assertThat(service.consume(token)).isNotNull();
    }

    @Test
    @DisplayName("dev-mode=true：带有效令牌也跳过（不认证不消费）")
    void devModeSkipsEverything() throws ServletException, IOException {
        StreamTokenFilter devFilter = new StreamTokenFilter(true, service);
        String token = service.issue("op-1", "OPERATOR", 7);
        MockHttpServletRequest request = request("GET", "/api/v1/alarms/stream");
        request.setParameter("streamToken", token);

        devFilter.doFilter(request, new org.springframework.mock.web.MockHttpServletResponse(),
                capturingChain());

        assertThat(snapshot.chainReached).isTrue();
        assertThat(snapshot.authenticated).isFalse();
        assertThat(service.consume(token)).isNotNull();
    }

    @Test
    @DisplayName("服务未装配（null）：跳过不抛异常")
    void nullServiceSkipsGracefully() throws ServletException, IOException {
        StreamTokenFilter orphanFilter = new StreamTokenFilter(false, null);
        String token = service.issue("op-1", "OPERATOR", 7);
        MockHttpServletRequest request = request("GET", "/api/v1/alarms/stream");
        request.setParameter("streamToken", token);

        orphanFilter.doFilter(request, new org.springframework.mock.web.MockHttpServletResponse(),
                capturingChain());

        assertThat(snapshot.chainReached).isTrue();
        assertThat(snapshot.authenticated).isFalse();
    }
}

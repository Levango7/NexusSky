package io.aerofleet.cloud.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * SSE 流令牌认证过滤器。
 * <p>
 * 浏览器 {@code EventSource} 无法携带 {@code Authorization} 头，本过滤器让
 * {@code ?streamToken=} 参数携带的 {@link StreamTokenService} 短令牌成为
 * SSE 端点的第三认证通道（前两个：Bearer JWT、X-API-Key）。
 * <p>
 * 只拦截两个 SSE 端点（其余路径零开销直通）：
 * <ul>
 *   <li>{@code GET /api/v1/alarms/stream}</li>
 *   <li>{@code GET /api/v1/surveillance/devices/{id}/events}</li>
 * </ul>
 * <p>
 * 行为规则（与 {@link ApiKeyFilter} 同姿势：本过滤器不自行写认证错误，
 * 拒绝一律交由授权层按匿名统一返回 401）：
 * <ul>
 *   <li>开发模式（{@code dev-mode=true}）或服务未装配：跳过</li>
 *   <li>携带 {@code Authorization} 或 {@code X-API-Key} 头：跳过 ——
 *       一个请求只认一种凭证，头凭证优先（防歧义凭证请求）</li>
 *   <li>无 {@code streamToken} 参数：跳过，交由后续链处理</li>
 *   <li>令牌无效/已用/已过期：跳过（{@link StreamTokenService#consume} 已原子
 *       移除，重放同一令牌将永远走不到认证分支）</li>
 *   <li>令牌有效：写入 {@link StreamTokenAuthenticationToken}（SecurityContext）
 *       并把签发时绑定的租户域原样恢复进 {@link TenantContext}，放行</li>
 * </ul>
 * <p>
 * 与 {@code /ws} 握手的 query-JWT 做法有意不同：WS 握手是一次性短请求，
 * 而 SSE 端点的 URL 会长期躺在日志与监控里，长效 JWT 进 URL 等于把 bearer
 * 泄露给所有能看到访问日志的人。短命单次用 opaque 令牌把泄露面压缩到
 * 「一次连接建立」。见 docs/security-design.md。
 * <p>
 * 在 finally 中始终清理 {@link TenantContext}，防止线程池复用导致上下文泄漏；
 * SecurityContext 由 Spring Security 框架在请求结束时清理。
 */
public class StreamTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(StreamTokenFilter.class);

    /** 流令牌参数名。刻意区别于 WS 的 {@code token}（那是 JWT），避免误用。 */
    static final String STREAM_TOKEN_PARAM = "streamToken";

    private static final String ALARM_STREAM_PATH = "/api/v1/alarms/stream";
    private static final Pattern SURVEILLANCE_EVENTS_PATH =
            Pattern.compile("^/api/v1/surveillance/devices/[^/]+/events$");

    private final boolean devMode;
    private final StreamTokenService streamTokenService;

    public StreamTokenFilter(@Value("${aerofleet.security.dev-mode:false}") boolean devMode,
                             StreamTokenService streamTokenService) {
        this.devMode = devMode;
        this.streamTokenService = streamTokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            if (devMode || streamTokenService == null) {
                filterChain.doFilter(request, response);
                return;
            }

            if (!isStreamPath(request.getRequestURI())) {
                filterChain.doFilter(request, response);
                return;
            }

            // 头凭证（JWT / API Key）在场时流令牌不参与：一请求一凭证，防歧义
            if (request.getHeader("Authorization") != null
                    || request.getHeader("X-API-Key") != null) {
                filterChain.doFilter(request, response);
                return;
            }

            String token = request.getParameter(STREAM_TOKEN_PARAM);
            if (token == null || token.isBlank()) {
                filterChain.doFilter(request, response);
                return;
            }

            StreamTokenService.Grant grant = streamTokenService.consume(token.trim());
            if (grant == null) {
                log.debug("流令牌无效、已用或已过期: {}{}", request.getMethod(), request.getRequestURI());
                filterChain.doFilter(request, response);
                return;
            }

            SecurityContextHolder.getContext().setAuthentication(
                    new StreamTokenAuthenticationToken(grant.subject(), grant.role(), grant.tenantScope()));
            // 租户域是签发时已解析的三态值（null=全局 / 租户 ID / NO_ACCESS），原样恢复
            TenantContext.setTenantId(grant.tenantScope());

            log.debug("流令牌认证成功: subject={}, role={}, tenantScope={}",
                    grant.subject(), grant.role(), grant.tenantScope());
            filterChain.doFilter(request, response);
        } finally {
            // 始终清理，防止线程池复用时上下文泄漏（clear 幂等，与相邻过滤器的清理不冲突）
            TenantContext.clear();
        }
    }

    private static boolean isStreamPath(String uri) {
        return ALARM_STREAM_PATH.equals(uri) || SURVEILLANCE_EVENTS_PATH.matcher(uri).matches();
    }
}

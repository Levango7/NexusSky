package io.aerofleet.cloud.audit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 操作审计拦截器：自动记录 POST/PUT/DELETE 请求到审计日志。
 * <p>
 * 仅拦截写操作（POST/PUT/DELETE），GET 等读操作不记录。
 * <p>
 * 当 {@code aerofleet.audit.enabled=false} 时，{@link AuditService#record} 内部直接返回，
 * 拦截器本身始终注册但不产生副作用，不影响现有功能。
 * <p>
 * 提取信息：
 * <ul>
 *   <li>userId — JWT subject，未认证时为 "anonymous"</li>
 *   <li>action — HTTP 方法</li>
 *   <li>target — 请求 URI</li>
 *   <li>ip — X-Forwarded-For 首段或 remoteAddr</li>
 * </ul>
 *
 * @see AuditService
 * @see AuditLog
 */
@Component
public class AuditInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuditInterceptor.class);

    /** 需要审计的 HTTP 方法。 */
    private static final Set<String> AUDITED_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    private final AuditService auditService;
    private final JwtDecoder jwtDecoder;

    public AuditInterceptor(AuditService auditService, JwtDecoder jwtDecoder) {
        this.auditService = auditService;
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        if (method == null || !AUDITED_METHODS.contains(method.toUpperCase())) {
            return true;
        }

        String userId = extractUserId(request);
        String ip = extractClientIp(request);
        String target = request.getRequestURI();

        try {
            auditService.record(userId, method, target, null, ip);
        } catch (Exception e) {
            // 审计日志异常不应阻断业务请求
            log.warn("审计日志记录失败: {}", e.getMessage());
        }
        return true;
    }

    /**
     * 从 Authorization: Bearer <token> 中提取 JWT subject（用户标识）。
     *
     * @return 用户标识，无有效 JWT 时返回 "anonymous"
     */
    private String extractUserId(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return "anonymous";
        }
        String token = header.substring(7);
        try {
            Jwt jwt = jwtDecoder.decode(token);
            String subject = jwt.getSubject();
            return subject != null ? subject : "anonymous";
        } catch (JwtException e) {
            return "anonymous";
        }
    }

    /**
     * 提取客户端真实 IP：优先 X-Forwarded-For 首段，否则 remoteAddr。
     *
     * @return 客户端 IP
     */
    private String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // X-Forwarded-For 可能含多个 IP，取第一个（最原始客户端）
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
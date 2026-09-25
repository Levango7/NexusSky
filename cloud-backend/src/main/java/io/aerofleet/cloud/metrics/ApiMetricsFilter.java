package io.aerofleet.cloud.metrics;

import io.aerofleet.cloud.security.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * API 请求计量 Filter，基于 Micrometer 记录每个 API 请求的调用计数和耗时。
 *
 * <p>指标清单：
 * <ul>
 *   <li>{@code aerofleet.api.requests} — Counter，标签：method, uri, status, tenant_id</li>
 *   <li>{@code aerofleet.api.request.duration} — Timer，标签：method, uri, tenant_id</li>
 * </ul>
 *
 * <p>仅统计 {@code /api/} 路径下的请求，跳过 {@code /actuator/}、{@code /swagger-ui/}、
 * {@code /v3/api-docs} 等基础设施路径。租户 ID 通过 {@link TenantContext#getEffectiveTenantId()}
 * 获取，为 null 时记为 "global"。
 */
@Component
public class ApiMetricsFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiMetricsFilter.class);

    private static final String COUNTER_NAME = "aerofleet.api.requests";
    private static final String TIMER_NAME = "aerofleet.api.request.duration";
    private static final String API_PATH_PREFIX = "/api/";
    private static final Set<String> EXCLUDED_PREFIXES = Set.of(
            "/actuator/", "/swagger-ui/", "/v3/api-docs"
    );
    private static final String GLOBAL_TENANT = "global";

    /** 匹配纯数字路径段，用于 URI 归一化 */
    private static final Pattern NUMERIC_SEGMENT_PATTERN = Pattern.compile("/\\d+(?=/|$)");

    private final MeterRegistry meterRegistry;

    public ApiMetricsFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();

        if (!shouldRecord(uri)) {
            filterChain.doFilter(request, response);
            return;
        }

        String method = request.getMethod();
        long startTime = System.nanoTime();

        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationNanos = System.nanoTime() - startTime;
            int status = response.getStatus();
            String tenantId = resolveTenantId();
            String normalizedUri = resolveNormalizedUri(request, uri);

            Counter.builder(COUNTER_NAME)
                    .description("API 请求计数")
                    .tag("method", method)
                    .tag("uri", normalizedUri)
                    .tag("status", String.valueOf(status))
                    .tag("tenant_id", tenantId)
                    .register(meterRegistry)
                    .increment();

            Timer.builder(TIMER_NAME)
                    .description("API 请求耗时")
                    .tag("method", method)
                    .tag("uri", normalizedUri)
                    .tag("tenant_id", tenantId)
                    .register(meterRegistry)
                    .record(durationNanos, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 判断请求路径是否应被计量：必须以 {@code /api/} 开头，且不在排除列表中。
     */
    private boolean shouldRecord(String uri) {
        if (!uri.startsWith(API_PATH_PREFIX)) {
            return false;
        }
        for (String excluded : EXCLUDED_PREFIXES) {
            if (uri.startsWith(excluded)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析当前请求的租户 ID，为 null 时记为 "global"。
     */
    private String resolveTenantId() {
        Integer tenantId = TenantContext.getEffectiveTenantId();
        return tenantId != null ? String.valueOf(tenantId) : GLOBAL_TENANT;
    }

    /**
     * 解析归一化 URI，避免路径变量导致 Prometheus 指标基数爆炸。
     * <p>
     * 优先从 Spring 的 {@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE} 获取匹配的 URI 模板
     * （如 {@code /api/v1/drones/{id}}）。如果获取不到（如 Filter 链中尚未设置该属性），
     * 则使用简单归一化：将路径中的纯数字段替换为 {@code {id}}。
     * <p>
     * 示例：{@code /api/v1/drones/123} → {@code /api/v1/drones/{id}}
     */
    private String resolveNormalizedUri(HttpServletRequest request, String originalUri) {
        // 优先使用 Spring 匹配的 URI 模板
        Object bestMatchingPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (bestMatchingPattern instanceof String pattern && !pattern.isBlank()) {
            return pattern;
        }

        // 回退到简单归一化：将数字路径段替换为 {id}
        return NUMERIC_SEGMENT_PATTERN.matcher(originalUri).replaceAll("/{id}");
    }
}
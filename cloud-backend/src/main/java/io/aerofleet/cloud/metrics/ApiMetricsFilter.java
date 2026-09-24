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

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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

            Counter.builder(COUNTER_NAME)
                    .description("API 请求计数")
                    .tag("method", method)
                    .tag("uri", uri)
                    .tag("status", String.valueOf(status))
                    .tag("tenant_id", tenantId)
                    .register(meterRegistry)
                    .increment();

            Timer.builder(TIMER_NAME)
                    .description("API 请求耗时")
                    .tag("method", method)
                    .tag("uri", uri)
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
}
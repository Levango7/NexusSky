package io.aerofleet.cloud.tenant;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * API 限流拦截器注册。
 * <p>
 * 将 {@link TenantInterceptor} 注册到 Spring MVC 拦截器链，
 * 拦截 /api/** 路径，排除认证（/api/v1/auth/**——登录/刷新在 AuthController
 * 另有每 IP 每分钟 10 次的独立限制）、actuator 与 /ws 端点。
 * <p>
 * 排除路径必须写实际注册的 v1 路径：2026-10-03 第七轮核查发现此处曾停留在
 * v1 迁移前的 {@code /api/auth/**}（死模式），登录/刷新因此落进共享 IP 限流桶
 * （默认 100/分钟），与同 IP 的其他匿名流量互相挤占。回归保护见
 * {@code TenantConfigTest}。
 * <p>
 * 租户上下文的写入与清理不在拦截器中，统一由 security 包的
 * TenantFilter / ApiKeyFilter 负责；拦截器只读其结果做限流分桶。
 *
 * @author AeroFleet Cloud Team
 */
@Configuration
public class TenantConfig implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;

    public TenantConfig(TenantInterceptor tenantInterceptor) {
        this.tenantInterceptor = tenantInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/v1/auth/**", "/actuator/**", "/ws/**");
    }
}
package io.aerofleet.cloud.tenant;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 多租户拦截器注册。
 * <p>
 * 将 {@link TenantInterceptor} 注册到 Spring MVC 拦截器链，
 * 拦截 /api/** 路径（排除认证和 actuator 端点）。
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
                .excludePathPatterns("/api/auth/**", "/actuator/**", "/ws/**");
    }
}
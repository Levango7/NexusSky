package io.aerofleet.cloud.license;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * License 拦截器注册。
 * <p>
 * 将 {@link LicenseInterceptor} 注册到 Spring MVC 拦截器链，
 * 拦截 /api/** 路径（排除 /api/auth/** 和 /api/license/** 自身）。
 *
 * @author AeroFleet Cloud Team
 */
@Configuration
public class LicenseConfig implements WebMvcConfigurer {

    private final LicenseInterceptor licenseInterceptor;

    public LicenseConfig(LicenseInterceptor licenseInterceptor) {
        this.licenseInterceptor = licenseInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(licenseInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**", "/api/license/**", "/actuator/**", "/ws/**");
    }
}
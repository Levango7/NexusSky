package io.aerofleet.cloud.license;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * License 拦截器注册。
 * <p>
 * 将 {@link LicenseInterceptor} 注册到 Spring MVC 拦截器链，
 * 拦截 /api/** 路径，排除认证（/api/v1/auth/**）、License 自管理
 * （/api/v1/license/**）、actuator 与 /ws。
 * <p>
 * 排除路径必须写实际注册的 v1 路径：2026-10-03 第七轮核查发现此处曾停留在
 * v1 迁移前的 {@code /api/auth/**}、{@code /api/license/**}（死模式，匹配不到
 * 任何真实端点）——{@code aerofleet.license.enabled=true}（prod/staging 默认）
 * 且 License 运行期失效时，登录/刷新与 License 管理端点会被一并 403，部署
 * 无法经 API 自救（鸡生蛋）。回归保护见 {@code LicenseConfigTest}：以路径
 * 匹配语义断言匿名/自管理端点被排除、业务端点仍被拦截。
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
                .excludePathPatterns("/api/v1/auth/**", "/api/v1/license/**", "/actuator/**", "/ws/**");
    }
}
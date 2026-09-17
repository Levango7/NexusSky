package io.aerofleet.cloud.security;

import io.aerofleet.cloud.audit.AuditInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 安全拦截器注册配置。
 * <p>
 * 注册 {@link RoleInterceptor}（RBAC 角色校验）和 {@link AuditInterceptor}（操作审计日志），
 * 拦截 {@code /api/**} 路径。
 * <p>
 * 两个拦截器内部均通过配置开关控制是否生效：
 * <ul>
 *   <li>RoleInterceptor — {@code aerofleet.security.rbac-enabled}（默认 false）</li>
 *   <li>AuditInterceptor — {@code aerofleet.audit.enabled}（默认 true），仅记录不阻断</li>
 * </ul>
 * <p>
 * dev-mode=true 时 RBAC 自动跳过，审计日志仍记录（不破坏现有功能）。
 */
@Configuration
public class SecurityInterceptorConfig implements WebMvcConfigurer {

    private final RoleInterceptor roleInterceptor;
    private final AuditInterceptor auditInterceptor;

    public SecurityInterceptorConfig(RoleInterceptor roleInterceptor,
                                     AuditInterceptor auditInterceptor) {
        this.roleInterceptor = roleInterceptor;
        this.auditInterceptor = auditInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 审计拦截器优先执行（先记录再校验），仅记录不阻断
        registry.addInterceptor(auditInterceptor)
                .addPathPatterns("/api/**");
        // RBAC 角色拦截器
        registry.addInterceptor(roleInterceptor)
                .addPathPatterns("/api/**");
    }
}
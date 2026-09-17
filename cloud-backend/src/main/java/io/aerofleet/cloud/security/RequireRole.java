package io.aerofleet.cloud.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明接口方法所需的最小角色（RBAC）。
 * <p>
 * 用法示例：
 * <pre>
 * &#64;PostMapping("/tasks")
 * &#64;RequireRole(Role.OPERATOR)
 * public Result createTask(&#64;RequestBody TaskRequest req) { ... }
 * </pre>
 * <p>
 * 由 {@link RoleInterceptor} 在请求处理前拦截，校验 JWT 中的 {@code role} claim。
 * 当 {@code aerofleet.security.rbac-enabled=false}（默认）或
 * {@code aerofleet.security.dev-mode=true} 时跳过校验，不影响现有功能。
 *
 * @see Role
 * @see RoleInterceptor
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequireRole {

    /**
     * 允许访问的最小角色。
     * <p>
     * 角色层级：ADMIN > OPERATOR > OBSERVER。
     * 指定 OPERATOR 时，ADMIN 也可访问；指定 OBSERVER 时，所有角色均可访问。
     *
     * @return 最小角色
     */
    Role value();
}
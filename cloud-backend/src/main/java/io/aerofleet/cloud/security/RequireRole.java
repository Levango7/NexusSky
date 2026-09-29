package io.aerofleet.cloud.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明接口所需的最小角色（RBAC）。
 * <p>
 * 可标注在方法上（仅该方法）或类上（该类全部端点）；方法级优先于类级。
 * <p>
 * 用法示例：
 * <pre>
 * &#64;PostMapping("/tasks")
 * &#64;RequireRole(Role.OPERATOR)
 * public Result createTask(&#64;RequestBody TaskRequest req) { ... }
 * </pre>
 * <p>
 * 由 {@link RoleInterceptor} 在请求处理前拦截，角色来源依次为：JWT 的
 * {@code role} claim、API Key 记录上的 {@code role} 列。
 * 当 {@code aerofleet.security.rbac-enabled=false} 或
 * {@code aerofleet.security.dev-mode=true} 时跳过校验。
 *
 * @see Role
 * @see RoleInterceptor
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
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
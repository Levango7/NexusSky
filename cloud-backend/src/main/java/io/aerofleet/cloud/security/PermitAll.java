package io.aerofleet.cloud.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 显式声明"此端点不需要角色"（RBAC 白名单）。
 * <p>
 * <b>为什么需要它</b>：{@link RoleInterceptor} 早期的判定是"方法与其所在类都没有
 * {@link RequireRole} 就放行"，于是每新增一个端点默认就是裸奔状态——漏写注解不会报错，
 * 只会静默失去鉴权。改成默认拒绝之后，"公开"必须写成显式声明，编译器与
 * {@code scripts/rbac-endpoint-coverage.sh} 都能把遗漏暴露出来。
 * <p>
 * <b>与 {@link RequireRole} 的优先级</b>：两者同规则解析，<b>方法级声明覆盖类级声明</b>。
 * 因此类上标了 {@code @PermitAll} 时，某个方法仍可用方法级 {@code @RequireRole} 单独收紧；
 * 反向亦然。同一元素上两者同时出现时 {@link RequireRole} 生效（收紧优先）。
 * <p>
 * <b>它只放开 RBAC，不放开认证</b>：Spring Security 的过滤链在生产模式下仍是
 * {@code anyRequest().authenticated()}（见 {@link SecurityConfig}），匿名请求要先过认证。
 * 所以本注解的语义是"已认证的任意角色可用"，不是"互联网公开"。真正的匿名入口只有
 * 登录与刷新，它们之所以能匿名，是因为 {@code SecurityConfig} 里对这两条路径显式 permitAll。
 *
 * @see RequireRole
 * @see RoleInterceptor
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface PermitAll {
}

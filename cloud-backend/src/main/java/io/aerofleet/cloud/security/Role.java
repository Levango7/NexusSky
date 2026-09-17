package io.aerofleet.cloud.security;

/**
 * RBAC 角色枚举。
 * <p>
 * 权限层级（从高到低）：
 * <ul>
 *   <li>{@link #ADMIN} — 全部权限（用户管理、配置、审计日志、任务、设备）</li>
 *   <li>{@link #OPERATOR} — 任务管理 + 设备控制（创建/取消任务、下发指令、编队）</li>
 *   <li>{@link #OBSERVER} — 只读（查询设备状态、遥测、任务列表）</li>
 * </ul>
 * <p>
 * JWT 的 {@code role} claim 使用枚举名称（大写）匹配。
 */
public enum Role {

    /** 管理员：全部权限。 */
    ADMIN,

    /** 操作员：任务管理 + 设备控制。 */
    OPERATOR,

    /** 观察者：只读。 */
    OBSERVER
}
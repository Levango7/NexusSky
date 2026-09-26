package io.aerofleet.cloud.security;

/**
 * 用户值对象，用于运行时传递（不包含敏感字段）。
 * <p>
 * 与 {@link UserEntity} 分离：Entity 负责 JPA 持久化（含 passwordHash），
 * 本值对象仅携带认证后的运行时信息（username、role、tenantId）。
 * <p>
 * 不可变值对象；线程安全通过不可变性保证。
 *
 * @see UserEntity#toUser()
 */
public final class User {

    private final String username;
    private final Role role;
    /** 所属租户 ID；null 表示全局管理员。 */
    private final Integer tenantId;

    public User(String username, Role role, Integer tenantId) {
        this.username = username;
        this.role = role;
        this.tenantId = tenantId;
    }

    public String getUsername() { return username; }

    public Role getRole() { return role; }

    public Integer getTenantId() { return tenantId; }

    @Override
    public String toString() {
        return "User{username='" + username + "', role=" + role
                + ", tenantId=" + tenantId + "}";
    }
}
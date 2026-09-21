package io.aerofleet.cloud.security;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建/更新用户的请求体 DTO。
 * <p>
 * 用于 {@link UserController} 的 POST 和 PUT 端点，
 * 字段约束由 JSR303 Bean Validation 校验。
 * <p>
 * 创建时 username 和 password 必填；更新时 password 可选（包含则修改密码）。
 */
public class UserRequest {

    /** 用户名（创建时必填，更新时不允许修改）。 */
    @NotBlank(message = "username is required")
    private String username;

    /** 密码明文（创建时必填，更新时可选）。 */
    private String password;

    /** 角色（必填：ADMIN/OPERATOR/OBSERVER）。 */
    @NotBlank(message = "role is required")
    private String role;

    /** 所属租户 ID（可选，null 表示全局管理员）。 */
    private Integer tenantId;

    /** 是否启用（可选，默认 true）。 */
    private Boolean enabled = true;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public Integer getTenantId() { return tenantId; }
    public void setTenantId(Integer tenantId) { this.tenantId = tenantId; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
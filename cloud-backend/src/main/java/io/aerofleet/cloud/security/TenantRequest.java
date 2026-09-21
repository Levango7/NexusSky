package io.aerofleet.cloud.security;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建/更新租户的请求体 DTO。
 * <p>
 * 用于 {@link TenantController} 的 POST 和 PUT 端点，
 * 字段约束由 JSR303 Bean Validation 校验。
 */
public class TenantRequest {

    /** 租户名称（必填）。 */
    @NotBlank(message = "name is required")
    private String name;

    /** 租户编码（必填，创建时需唯一）。 */
    @NotBlank(message = "code is required")
    private String code;

    /** 是否启用（可选，默认 true）。 */
    private Boolean enabled = true;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
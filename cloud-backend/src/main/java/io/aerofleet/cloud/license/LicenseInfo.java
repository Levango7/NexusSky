package io.aerofleet.cloud.license;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * License 信息载体。
 * <p>
 * 承载一份授权的关键属性：租户、产品、设备上限、过期时间、签发信息等。
 * 同时提供与商用化校验相关的派生判断：{@link #isActive()}、{@link #isExpired()}、
 * {@link #canActivate(int)}。
 * <p>
 * 该类使用 JavaBean 风格（而非 record），便于 Jackson 反序列化 Base64 解码后的 JSON，
 * 也方便在拦截器/控制器中按需构造。
 *
 * @author AeroFleet Cloud Team
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LicenseInfo {

    /** License 原始密钥（Base64 编码串），开发版可为 null */
    @JsonProperty("licenseKey")
    private String licenseKey;

    /** 租户 ID，多租户隔离的核心标识 */
    @JsonProperty("tenantId")
    private String tenantId;

    /** 产品名称，例如 "AeroFleet Cloud Standard" */
    @JsonProperty("productName")
    private String productName;

    /** 最大设备数量，<=0 视为无限制（开发版） */
    @JsonProperty("maxDevices")
    private int maxDevices;

    /** 过期时间（UTC），null 视为永不过期（开发版） */
    @JsonProperty("expiryDate")
    private Instant expiryDate;

    /** 签发时间（UTC） */
    @JsonProperty("issuedAt")
    private Instant issuedAt;

    /** 被授权方名称（公司/个人） */
    @JsonProperty("issuedTo")
    private String issuedTo;

    /** 是否激活 */
    @JsonProperty("active")
    private boolean active;

    /** Jackson 反序列化需要的无参构造器 */
    public LicenseInfo() {
    }

    public LicenseInfo(String licenseKey, String tenantId, String productName,
                       int maxDevices, Instant expiryDate, Instant issuedAt,
                       String issuedTo, boolean active) {
        this.licenseKey = licenseKey;
        this.tenantId = tenantId;
        this.productName = productName;
        this.maxDevices = maxDevices;
        this.expiryDate = expiryDate;
        this.issuedAt = issuedAt;
        this.issuedTo = issuedTo;
        this.active = active;
    }

    /**
     * 判断 License 是否处于激活状态。
     *
     * @return active 字段为 true 时返回 true
     */
    public boolean isActive() {
        return active;
    }

    /**
     * 判断 License 是否已过期。
     * <p>
     * expiryDate 为 null 时视为永不过期（开发版语义）。
     *
     * @return 已过期返回 true，否则 false
     */
    public boolean isExpired() {
        return expiryDate != null && Instant.now().isAfter(expiryDate);
    }

    /**
     * 判断在当前设备数量下是否还能激活新设备。
     * <p>
     * 综合条件：已激活、未过期、当前设备数小于上限。
     * maxDevices <=0 视为无限制（开发版）。
     *
     * @param currentDeviceCount 当前已注册设备数
     * @return 允许激活返回 true，否则 false
     */
    public boolean canActivate(int currentDeviceCount) {
        if (!active || isExpired()) {
            return false;
        }
        if (maxDevices <= 0) {
            // 无限制（开发版）
            return true;
        }
        return currentDeviceCount < maxDevices;
    }

    // ===== 标准 Getter / Setter =====

    public String getLicenseKey() {
        return licenseKey;
    }

    public void setLicenseKey(String licenseKey) {
        this.licenseKey = licenseKey;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public int getMaxDevices() {
        return maxDevices;
    }

    public void setMaxDevices(int maxDevices) {
        this.maxDevices = maxDevices;
    }

    public Instant getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(Instant expiryDate) {
        this.expiryDate = expiryDate;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public String getIssuedTo() {
        return issuedTo;
    }

    public void setIssuedTo(String issuedTo) {
        this.issuedTo = issuedTo;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public String toString() {
        return "LicenseInfo{" +
                "tenantId='" + tenantId + '\'' +
                ", productName='" + productName + '\'' +
                ", maxDevices=" + maxDevices +
                ", expiryDate=" + expiryDate +
                ", issuedAt=" + issuedAt +
                ", issuedTo='" + issuedTo + '\'' +
                ", active=" + active +
                '}';
    }
}
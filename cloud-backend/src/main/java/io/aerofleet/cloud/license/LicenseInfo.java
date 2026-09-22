package io.aerofleet.cloud.license;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

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

    /** License 唯一标识 */
    @JsonProperty("licenseId")
    private String licenseId;

    /** 授权模块集合，取值: core, fleet, emergency, network, advanced */
    @JsonProperty("modules")
    private Set<String> modules;

    /** 每日最大 API 调用次数，<=0 视为无限制 */
    @JsonProperty("maxApiCallsPerDay")
    private int maxApiCallsPerDay;

    /** 最大并发无人机数，<=0 视为无限制 */
    @JsonProperty("maxConcurrentDrones")
    private int maxConcurrentDrones;

    /** RSA-SHA256 签名（Base64），用于验证 license 完整性 */
    @JsonProperty("signature")
    private String signature;

    /** 签名证书（Base64），用于验证签名来源 */
    @JsonProperty("signerCert")
    private String signerCert;

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

    public LicenseInfo(String licenseKey, String tenantId, String productName,
                       int maxDevices, Instant expiryDate, Instant issuedAt,
                       String issuedTo, boolean active,
                       String licenseId, Set<String> modules,
                       int maxApiCallsPerDay, int maxConcurrentDrones,
                       String signature, String signerCert) {
        this.licenseKey = licenseKey;
        this.tenantId = tenantId;
        this.productName = productName;
        this.maxDevices = maxDevices;
        this.expiryDate = expiryDate;
        this.issuedAt = issuedAt;
        this.issuedTo = issuedTo;
        this.active = active;
        this.licenseId = licenseId;
        this.modules = modules;
        this.maxApiCallsPerDay = maxApiCallsPerDay;
        this.maxConcurrentDrones = maxConcurrentDrones;
        this.signature = signature;
        this.signerCert = signerCert;
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

    public String getLicenseId() {
        return licenseId;
    }

    public void setLicenseId(String licenseId) {
        this.licenseId = licenseId;
    }

    public Set<String> getModules() {
        return modules;
    }

    public void setModules(Set<String> modules) {
        this.modules = modules;
    }

    public int getMaxApiCallsPerDay() {
        return maxApiCallsPerDay;
    }

    public void setMaxApiCallsPerDay(int maxApiCallsPerDay) {
        this.maxApiCallsPerDay = maxApiCallsPerDay;
    }

    public int getMaxConcurrentDrones() {
        return maxConcurrentDrones;
    }

    public void setMaxConcurrentDrones(int maxConcurrentDrones) {
        this.maxConcurrentDrones = maxConcurrentDrones;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getSignerCert() {
        return signerCert;
    }

    public void setSignerCert(String signerCert) {
        this.signerCert = signerCert;
    }

    /**
     * 判断指定模块是否在授权范围内。
     *
     * @param module 模块名 (core, fleet, emergency, network, advanced)
     * @return 已授权返回 true，否则 false
     */
    public boolean hasModule(String module) {
        return modules != null && modules.contains(module);
    }

    @Override
    public String toString() {
        return "LicenseInfo{" +
                "licenseId='" + licenseId + '\'' +
                ", tenantId='" + tenantId + '\'' +
                ", productName='" + productName + '\'' +
                ", maxDevices=" + maxDevices +
                ", modules=" + modules +
                ", maxApiCallsPerDay=" + maxApiCallsPerDay +
                ", maxConcurrentDrones=" + maxConcurrentDrones +
                ", expiryDate=" + expiryDate +
                ", issuedAt=" + issuedAt +
                ", issuedTo='" + issuedTo + '\'' +
                ", active=" + active +
                ", signature='" + (signature != null ? "[present]" : "null") + '\'' +
                ", signerCert='" + (signerCert != null ? "[present]" : "null") + '\'' +
                '}';
    }
}
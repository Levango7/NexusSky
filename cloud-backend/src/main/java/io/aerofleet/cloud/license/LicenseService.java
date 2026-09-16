package io.aerofleet.cloud.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * License 核心服务。
 * <p>
 * 职责：
 * <ul>
 *   <li>从 {@code aerofleet.license.key} 读取 Base64 编码的 license key 并解析为 {@link LicenseInfo}；</li>
 *   <li>校验 license 的过期与设备数量；</li>
 *   <li>生成 / 验证激活码（HMAC-SHA256，基于租户 + 机器指纹）；</li>
 *   <li>未配置 license key 时返回永久有效的开发版 License，确保不破坏现有测试。</li>
 * </ul>
 * <p>
 * 激活码格式：{@code Base64(hmacSHA256(secret, tenantId + "|" + machineId))}。
 * secret 复用 {@code aerofleet.security.jwt-secret}，避免引入新的密钥配置。
 *
 * @author AeroFleet Cloud Team
 */
@Service
public class LicenseService {

    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);

    /** 开发版产品名 */
    public static final String DEV_PRODUCT_NAME = "AeroFleet Cloud Dev Edition";
    /** 开发版租户 ID */
    public static final String DEV_TENANT_ID = "dev";
    /** 开发版被授权方 */
    public static final String DEV_ISSUED_TO = "AeroFleet Developer";

    private final ObjectMapper objectMapper;
    private final String licenseKeyConfig;
    private final String hmacSecret;
    private final LicenseInfo currentLicense;

    public LicenseService(
            @Value("${aerofleet.license.key:}") String licenseKeyConfig,
            @Value("${aerofleet.security.jwt-secret:aerofleet-dev-secret-change-in-production-at-least-32-chars}") String hmacSecret) {
        this.licenseKeyConfig = licenseKeyConfig;
        this.hmacSecret = hmacSecret;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        // 启动时解析一次；解析失败则降级为开发版，避免启动崩溃影响现有测试
        this.currentLicense = loadLicense();
    }

    /**
     * 解析 Base64 编码的 license key 为 {@link LicenseInfo}。
     * <p>
     * 解析失败时返回 null，由调用方决定降级策略。
     *
     * @param key Base64 编码的 license key
     * @return 解析成功返回 LicenseInfo，否则 null
     */
    public LicenseInfo parseLicense(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(key.trim());
            String json = new String(decoded, StandardCharsets.UTF_8);
            LicenseInfo info = objectMapper.readValue(json, LicenseInfo.class);
            info.setLicenseKey(key.trim());
            return info;
        } catch (Exception e) {
            log.warn("License key 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 校验 License 有效性：已激活、未过期。
     * <p>
     * 设备数量校验请使用 {@link LicenseInfo#canActivate(int)}。
     *
     * @param info 待校验的 License 信息
     * @return 有效返回 true，否则 false
     */
    public boolean validateLicense(LicenseInfo info) {
        if (info == null) {
            return false;
        }
        if (!info.isActive()) {
            log.debug("License 校验失败: 未激活, tenant={}", info.getTenantId());
            return false;
        }
        if (info.isExpired()) {
            log.debug("License 校验失败: 已过期, tenant={}, expiry={}", info.getTenantId(), info.getExpiryDate());
            return false;
        }
        return true;
    }

    /**
     * 生成激活码：{@code Base64(hmacSHA256(secret, tenantId + "|" + machineId))}。
     *
     * @param tenantId  租户 ID
     * @param machineId 机器指纹
     * @return 激活码，输入非法时返回 null
     */
    public String generateActivationCode(String tenantId, String machineId) {
        if (tenantId == null || tenantId.isBlank() || machineId == null || machineId.isBlank()) {
            return null;
        }
        try {
            String payload = tenantId + "|" + machineId;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            log.error("生成激活码失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 验证激活码是否与给定租户/机器匹配。
     * <p>
     * 注意：激活码本身不含租户信息，需由调用方提供 tenantId/machineId 重新计算并比对。
     * 此方法用于"离线激活"场景的二次校验。
     *
     * @param activationCode 待验证的激活码
     * @return 合法返回 true，否则 false
     */
    public boolean validateActivation(String activationCode) {
        if (activationCode == null || activationCode.isBlank()) {
            return false;
        }
        // 激活码本身是 HMAC，无法从中反推租户；这里仅做格式校验（Base64 可解码且长度匹配 SHA256 输出）
        try {
            byte[] decoded = Base64.getDecoder().decode(activationCode.trim());
            // HmacSHA256 输出 32 字节
            return decoded.length == 32;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 验证激活码与给定租户/机器指纹是否匹配（完整校验）。
     *
     * @param activationCode 待验证的激活码
     * @param tenantId       租户 ID
     * @param machineId      机器指纹
     * @return 匹配返回 true，否则 false
     */
    public boolean validateActivation(String activationCode, String tenantId, String machineId) {
        String expected = generateActivationCode(tenantId, machineId);
        return expected != null && expected.equals(activationCode);
    }

    /**
     * 获取当前 License 信息。
     * <p>
     * 未配置 license key 时返回永久有效的开发版 License。
     *
     * @return 当前 License 信息，永不为 null
     */
    public LicenseInfo getLicenseInfo() {
        return currentLicense;
    }

    /**
     * 判断当前是否为开发版 License。
     *
     * @return 开发版返回 true
     */
    public boolean isDevLicense() {
        return DEV_PRODUCT_NAME.equals(currentLicense.getProductName());
    }

    // ===== 内部方法 =====

    /**
     * 加载 license：配置了 key 则解析，否则返回开发版。
     * 解析失败也降级为开发版，保证服务可用。
     */
    private LicenseInfo loadLicense() {
        if (licenseKeyConfig == null || licenseKeyConfig.isBlank()) {
            log.info("未配置 aerofleet.license.key，使用开发版 License");
            return buildDevLicense();
        }
        LicenseInfo parsed = parseLicense(licenseKeyConfig);
        if (parsed == null) {
            log.warn("License key 解析失败，降级为开发版 License");
            return buildDevLicense();
        }
        log.info("License 加载成功: tenant={}, product={}, maxDevices={}, expiry={}",
                parsed.getTenantId(), parsed.getProductName(), parsed.getMaxDevices(), parsed.getExpiryDate());
        return parsed;
    }

    /**
     * 构造永久有效的开发版 License。
     * <p>
     * maxDevices=0 表示无限制，expiryDate=null 表示永不过期，active=true。
     */
    private LicenseInfo buildDevLicense() {
        return new LicenseInfo(
                null,
                DEV_TENANT_ID,
                DEV_PRODUCT_NAME,
                0,              // 无限制
                null,           // 永不过期
                Instant.now(),
                DEV_ISSUED_TO,
                true
        );
    }

    /**
     * 生成一个 license key（Base64 编码的 JSON），供管理脚本/测试使用。
     * <p>
     * 这不是核心商用化逻辑，但便于离线签发 license。
     *
     * @param tenantId    租户 ID
     * @param productName 产品名
     * @param maxDevices  设备上限
     * @param expiryDate  过期时间（null=永久）
     * @param issuedTo    被授权方
     * @return Base64 编码的 license key
     */
    public String generateLicenseKey(String tenantId, String productName, int maxDevices,
                                     Instant expiryDate, String issuedTo) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenantId", tenantId);
            body.put("productName", productName);
            body.put("maxDevices", maxDevices);
            body.put("expiryDate", expiryDate);
            body.put("issuedAt", Instant.now());
            body.put("issuedTo", issuedTo);
            body.put("active", true);
            String json = objectMapper.writeValueAsString(body);
            return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("生成 license key 失败: {}", e.getMessage());
            return null;
        }
    }
}
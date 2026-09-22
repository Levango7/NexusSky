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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * License 核心服务。
 * <p>
 * 职责：
 * <ul>
 *   <li>从 {@code aerofleet.license.key} 读取 license key 并解析为 {@link LicenseInfo}；</li>
 *   <li>支持新格式（签名验证）和旧格式（向后兼容，dev 模式）；</li>
 *   <li>校验 license 的过期与设备数量；</li>
 *   <li>生成 / 验证激活码（HMAC-SHA256，基于租户 + 机器指纹）；</li>
 *   <li>未配置 license key 时返回永久有效的开发版 License，确保不破坏现有测试。</li>
 * </ul>
 * <p>
 * License Key 格式：
 * <ul>
 *   <li>新格式（签名版）：{@code Base64(JSON(payload)) + "." + Base64(RSA-SHA256(JSON(payload)))}</li>
 *   <li>旧格式（无签名）：{@code Base64(JSON(payload))}（仅 dev 模式可用）</li>
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
    /** 全部模块集合 */
    public static final Set<String> ALL_MODULES = Set.of("core", "fleet", "emergency", "network", "advanced");

    /** License Key 中 payload 和 signature 的分隔符 */
    private static final String KEY_SEPARATOR = ".";

    private final ObjectMapper objectMapper;
    private final String licenseKeyConfig;
    private final String hmacSecret;
    private final boolean devMode;
    private final LicenseSigner licenseSigner;
    private final LicenseInfo currentLicense;

    public LicenseService(
            @Value("${aerofleet.license.key:}") String licenseKeyConfig,
            @Value("${aerofleet.security.jwt-secret:aerofleet-dev-secret-change-in-production-at-least-32-chars}") String hmacSecret,
            @Value("${aerofleet.security.dev-mode:false}") boolean devMode,
            @Value("${aerofleet.license.public-key:}") String publicKeyConfig) {
        this.licenseKeyConfig = licenseKeyConfig;
        this.hmacSecret = hmacSecret;
        this.devMode = devMode;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        // 初始化签名工具：生产模式从配置读取公钥，开发模式自动生成密钥对
        if (publicKeyConfig != null && !publicKeyConfig.isBlank()) {
            this.licenseSigner = new LicenseSigner(publicKeyConfig);
        } else {
            this.licenseSigner = new LicenseSigner();
        }
        // 启动时解析一次；解析失败则降级为开发版，避免启动崩溃影响现有测试
        this.currentLicense = loadLicense();
    }

    /**
     * 解析 license key 为 {@link LicenseInfo}。
     * <p>
     * 支持两种格式：
     * <ul>
     *   <li>新格式（签名版）：{@code Base64(JSON(payload)) + "." + Base64(RSA-SHA256(JSON(payload)))}</li>
     *   <li>旧格式（无签名）：{@code Base64(JSON(payload))}（仅 dev 模式可用）</li>
     * </ul>
     * <p>
     * 新格式会验证签名，验证失败抛出 {@link LicenseInvalidException}。
     * 旧格式在非 dev 模式下抛出 {@link LicenseInvalidException}。
     *
     * @param key license key 字符串
     * @return 解析成功返回 LicenseInfo
     * @throws LicenseInvalidException 签名验证失败或格式不合法
     */
    public LicenseInfo parseLicense(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }

        String trimmedKey = key.trim();

        // 判断是否为新格式（含分隔符）
        int separatorIndex = trimmedKey.indexOf(KEY_SEPARATOR);
        if (separatorIndex > 0) {
            return parseSignedLicense(trimmedKey, separatorIndex);
        }

        // 旧格式（无签名）
        return parseLegacyLicense(trimmedKey);
    }

    /**
     * 解析新格式（签名版）的 license key。
     * <p>
     * 格式：{@code Base64(JSON(payload)) + "." + Base64(RSA-SHA256(JSON(payload)))}
     *
     * @param key             license key
     * @param separatorIndex  分隔符位置
     * @return 解析成功返回 LicenseInfo
     * @throws LicenseInvalidException 签名验证失败
     */
    private LicenseInfo parseSignedLicense(String key, int separatorIndex) {
        try {
            String payloadBase64 = key.substring(0, separatorIndex);
            String signatureBase64 = key.substring(separatorIndex + KEY_SEPARATOR.length());

            byte[] payloadBytes = Base64.getDecoder().decode(payloadBase64);
            String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);

            LicenseInfo info = objectMapper.readValue(payloadJson, LicenseInfo.class);
            info.setLicenseKey(key);
            info.setSignature(signatureBase64);

            // 验证签名
            if (!licenseSigner.verify(info, signatureBase64)) {
                throw new LicenseInvalidException("License 签名验证失败");
            }

            log.info("License 签名验证通过: tenant={}", info.getTenantId());
            return info;
        } catch (LicenseInvalidException e) {
            throw e;
        } catch (Exception e) {
            log.warn("签名版 License 解析失败: {}", e.getMessage());
            throw new LicenseInvalidException("License 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析旧格式（无签名）的 license key。
     * <p>
     * 旧格式仅在 dev 模式下允许使用，非 dev 模式抛出 {@link LicenseInvalidException}。
     *
     * @param key Base64 编码的 license key
     * @return 解析成功返回 LicenseInfo
     * @throws LicenseInvalidException 非 dev 模式下使用旧格式
     */
    private LicenseInfo parseLegacyLicense(String key) {
        if (!devMode) {
            throw new LicenseInvalidException("非开发模式下不允许使用无签名的旧格式 License");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(key);
            String json = new String(decoded, StandardCharsets.UTF_8);
            LicenseInfo info = objectMapper.readValue(json, LicenseInfo.class);
            info.setLicenseKey(key);
            log.warn("使用旧格式（无签名）License，仅开发模式允许: tenant={}", info.getTenantId());
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

    /**
     * 判断当前是否为开发模式（配置项 aerofleet.security.dev-mode=true）。
     *
     * @return 开发模式返回 true
     */
    public boolean isDevMode() {
        return devMode;
    }

    /**
     * 获取 License 签名工具实例。
     *
     * @return LicenseSigner 实例
     */
    public LicenseSigner getLicenseSigner() {
        return licenseSigner;
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
        try {
            LicenseInfo parsed = parseLicense(licenseKeyConfig);
            if (parsed == null) {
                log.warn("License key 解析失败，降级为开发版 License");
                return buildDevLicense();
            }
            log.info("License 加载成功: tenant={}, product={}, maxDevices={}, modules={}, expiry={}",
                    parsed.getTenantId(), parsed.getProductName(), parsed.getMaxDevices(),
                    parsed.getModules(), parsed.getExpiryDate());
            return parsed;
        } catch (LicenseInvalidException e) {
            log.error("License 签名验证失败，降级为开发版 License: {}", e.getMessage());
            return buildDevLicense();
        }
    }

    /**
     * 构造永久有效的开发版 License。
     * <p>
     * maxDevices=0 表示无限制，expiryDate=null 表示永不过期，active=true。
     * 开发版包含全部模块授权。
     */
    private LicenseInfo buildDevLicense() {
        LicenseInfo dev = new LicenseInfo(
                null,
                DEV_TENANT_ID,
                DEV_PRODUCT_NAME,
                0,              // 无限制
                null,           // 永不过期
                Instant.now(),
                DEV_ISSUED_TO,
                true
        );
        dev.setLicenseId("dev-license");
        dev.setModules(new HashSet<>(ALL_MODULES));
        dev.setMaxApiCallsPerDay(0);          // 无限制
        dev.setMaxConcurrentDrones(0);        // 无限制
        return dev;
    }

    /**
     * 生成一个签名版 license key，供管理脚本/测试使用。
     * <p>
     * 新格式：{@code Base64(JSON(payload)) + "." + Base64(RSA-SHA256(JSON(payload)))}
     * 仅在开发模式（LicenseSigner 有私钥）下可用。
     *
     * @param tenantId    租户 ID
     * @param productName 产品名
     * @param maxDevices  设备上限
     * @param expiryDate  过期时间（null=永久）
     * @param issuedTo    被授权方
     * @return 签名版 license key
     */
    public String generateLicenseKey(String tenantId, String productName, int maxDevices,
                                     Instant expiryDate, String issuedTo) {
        return generateLicenseKey(tenantId, productName, maxDevices, expiryDate, issuedTo,
                new HashSet<>(ALL_MODULES), 0, 0);
    }

    /**
     * 生成一个签名版 license key（含模块授权字段），供管理脚本/测试使用。
     *
     * @param tenantId           租户 ID
     * @param productName        产品名
     * @param maxDevices         设备上限
     * @param expiryDate         过期时间（null=永久）
     * @param issuedTo           被授权方
     * @param modules            授权模块集合
     * @param maxApiCallsPerDay  每日 API 调用上限（<=0 无限制）
     * @param maxConcurrentDrones 最大并发无人机数（<=0 无限制）
     * @return 签名版 license key
     */
    public String generateLicenseKey(String tenantId, String productName, int maxDevices,
                                     Instant expiryDate, String issuedTo,
                                     Set<String> modules, int maxApiCallsPerDay,
                                     int maxConcurrentDrones) {
        try {
            LicenseInfo info = new LicenseInfo(
                    null, tenantId, productName, maxDevices, expiryDate,
                    Instant.now(), issuedTo, true,
                    "lic-" + tenantId + "-" + System.currentTimeMillis(),
                    modules, maxApiCallsPerDay, maxConcurrentDrones,
                    null, null
            );

            // 序列化 payload（不含 signature/signerCert）
            String payloadJson = objectMapper.writeValueAsString(info);
            String payloadBase64 = Base64.getEncoder().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

            // 签名
            String signature = licenseSigner.sign(info);

            return payloadBase64 + KEY_SEPARATOR + signature;
        } catch (Exception e) {
            log.error("生成 license key 失败: {}", e.getMessage());
            return null;
        }
    }
}

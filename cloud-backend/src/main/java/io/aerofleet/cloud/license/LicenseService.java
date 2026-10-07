package io.aerofleet.cloud.license;

import com.fasterxml.jackson.databind.ObjectMapper;

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
    /** jwt-secret 未配置时的内置开发默认值（非 dev 模式下等于该值会拒绝启动，见构造器 fail-closed 守卫）。 */
    public static final String DEV_HMAC_SECRET_FALLBACK = "aerofleet-dev-secret-change-in-production-at-least-32-chars";
    /**
     * 全部模块集合（7 个，2026-10-07 由 5 扩为 7）。
     * <p>
     * <b>为什么拆分</b>：原 5 模块下，"基础版要含 M5 mesh + M9 编排"（定价文档 §5.1 的
     * 销售承诺）与"mesh 属完整版、M9 属应急版"（{@code LicenseTier} 的原定义）直接矛盾。
     * 两条路都走不通：把 {@code network}/{@code emergency} 整块下移到基础版，会让基础版
     * 连 5G 基站、卫星中继、ONVIF 安防联动一起白送，应急版与基础版同集合 ⇒ 档位梯子塌掉、
     * 无法定价（{@code LicenseTierTest} 的"档位必须互异"会红）。
     * <p>
     * 故按交付形态把两个大模块各拆一半，边界与定价文档逐字对齐：
     * <ul>
     *   <li>{@code network} → {@code mesh}（M5 AODV-lite 自愈组网，基础版）
     *       + {@code network}（5G 基站 / 卫星中继 / 链路适配 / LoRa / 边缘，完整版）；</li>
     *   <li>{@code emergency} → {@code orch}（M9 应急任务编排 + 编排计划，基础版）
     *       + {@code emergency}（4a 空地一体化指挥 / 告警 / ONVIF 安防 / 视频视觉，应急版）。</li>
     * </ul>
     * <b>兼容性</b>：模块名进签名载荷（{@link LicenseIssuer}），改名即换授权语义。
     * 见 {@code LicenseModuleSplitMigrationTest} 对旧授权集合的处置断言。
     */
    public static final Set<String> ALL_MODULES =
            Set.of("core", "fleet", "mesh", "orch", "emergency", "network", "advanced");

    /**
     * License Key 中 payload 和 signature 的分隔符。
     * 唯一定义在 {@link LicenseIssuer#KEY_SEPARATOR}（签发端），解析端只引用——
     * wire format 分裂是这一域最贵的故障类型，签发/解析两侧不允许各写一份字面量。
     */
    private static final String KEY_SEPARATOR = LicenseIssuer.KEY_SEPARATOR;

    private final ObjectMapper objectMapper;
    private final String licenseKeyConfig;
    private final String hmacSecret;
    private final boolean devMode;
    private final LicenseSigner licenseSigner;
    private final LicenseInfo currentLicense;

    /**
     * 是否强制「License 模块集合必须恰好等于某个可售档位」。
     *
     * <p>默认 **true**（fail-closed）。关掉它等于恢复 2026-10-06 之前的行为：
     * 签名里的模块集合是什么就放行什么，于是"某档客户实际能启用哪些模块"退回为
     * 签发脚本的约定而非代码强制。
     *
     * <p><b>为什么默认 true 而不是 false</b>：本产品处于 PoC / 私有化交付准备阶段
     * （见 {@code docs/product-brief.md}），现场没有任何已签发的旧授权，因此不存在
     * "老授权会被新规则拒掉"的兼容问题；而留着一条默认关闭的口子，等于把刚建立的
     * 不变式交给部署方去记。确有非档位组合的商务需求时再显式关闭。
     */
    private final boolean enforceTierBinding;

    /**
     * 兼容构造器：等价于「强制档位绑定」。
     *
     * <p>保留它是为了不打断既有直接构造点（本仓测试有 6 处、外部集成代码也可能直接
     * {@code new}）。<b>但它不构成后门</b>：默认开启而非关闭，想关掉必须显式走
     * 6 参构造并传 {@code false}。
     */
    public LicenseService(String licenseKeyConfig, String hmacSecret, boolean devMode,
                          String publicKeyConfig, ObjectMapper objectMapper) {
        this(licenseKeyConfig, hmacSecret, devMode, publicKeyConfig, true, objectMapper);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LicenseService(
            @Value("${aerofleet.license.key:}") String licenseKeyConfig,
            @Value("${aerofleet.security.jwt-secret:" + DEV_HMAC_SECRET_FALLBACK + "}") String hmacSecret,
            @Value("${aerofleet.security.dev-mode:false}") boolean devMode,
            @Value("${aerofleet.license.public-key:}") String publicKeyConfig,
            @Value("${aerofleet.license.enforce-tier-binding:true}") boolean enforceTierBinding,
            ObjectMapper objectMapper) {
        this.licenseKeyConfig = licenseKeyConfig;
        this.hmacSecret = hmacSecret;
        this.devMode = devMode;
        this.enforceTierBinding = enforceTierBinding;
        this.objectMapper = objectMapper;
        if (!enforceTierBinding) {
            log.warn("aerofleet.license.enforce-tier-binding=false：档位→模块绑定不再强制，"
                    + "License 里签了什么模块就放行什么模块 —— 三档定价退回为签发脚本的约定，"
                    + "非代码强制。仅在确有非档位商务组合时使用。");
        }
        // fail-closed：jwt-secret 同时用于 JWT HS256 回退与 license 激活码 HMAC（generateActivationCode）。
        // 非 dev 模式下留空/未配置（等于内置默认值）一律拒绝启动，避免激活码静默不可用或弱密钥上岗；
        // dev 模式下仅告警（保持本地/CI 可启动，激活码功能明确不可用）。
        if (hmacSecret == null || hmacSecret.isBlank() || DEV_HMAC_SECRET_FALLBACK.equals(hmacSecret)) {
            if (devMode) {
                log.warn("aerofleet.security.jwt-secret 未配置或为内置默认值：license 激活码功能不可用"
                        + "（generateActivationCode 返回 null），生产环境必须显式配置");
            } else {
                throw new IllegalStateException("aerofleet.security.jwt-secret 未配置或为内置开发默认值："
                        + "非 dev 模式必须显式配置该密钥（同时用于 JWT HS256 回退与 license 激活码 HMAC）");
            }
        }
        // 初始化签名工具：生产模式从配置读取公钥，开发模式自动生成密钥对
        if (publicKeyConfig != null && !publicKeyConfig.isBlank()) {
            this.licenseSigner = new LicenseSigner(publicKeyConfig, objectMapper);
        } else {
            this.licenseSigner = new LicenseSigner(objectMapper);
        }
        // 启动时解析一次。**已配置 key 时解析失败会抛异常拒绝启动**（见 loadLicense 的
        // fail-closed 说明）；未配置 key 时走开发版，不影响开发/测试环境。
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
            info.setSignature(signatureBase64);

            // 先验签再回填 licenseKey：licenseKey 是承载签名的那串 key 本身，
            // 属于「信封」而非「内容」。虽然 serializeForSigning 已把它排除在签名之外
            // （见 LicenseSigner#serializeForSigning），但把顺序摆正可以避免后人
            // 再踩同一个坑——2026-10-01 之前正是「先 setLicenseKey 后 verify」，
            // 而 licenseKey 又参与签名，导致任何合法签名的 License 都验不过。
            if (!licenseSigner.verify(info, signatureBase64)) {
                throw new LicenseInvalidException("License 签名验证失败");
            }
            info.setLicenseKey(key);

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
     * 校验 License 有效性：已激活、未过期、模块集合绑定到可售档位。
     * <p>
     * 设备数量校验请使用 {@link LicenseInfo#canActivate(int)}。
     *
     * <h2>档位绑定为什么必须在**验证期**做</h2>
     * 签发侧（{@link #generateLicenseKeyByTier}）已有 {@link LicenseTier#mismatchOf} 自检，
     * 但那只保护"走档位入口签发"的路径。三处绕过它：
     * <ul>
     *   <li>走原始 {@link #generateLicenseKey} 直接传 {@code modules}；</li>
     *   <li>{@link LicenseTier} 引入之前签出的授权，其模块集合是当时的自由文本；</li>
     *   <li>拿到签发密钥的任何一方都能构造任意模块集合。</li>
     * </ul>
     * 只在签发侧校验 ⇒ <b>加载时那一步没有任何检查</b>，一份"基础版 + emergency"的授权
     * 照样全程有效，定价仍不可执行。故此处按 fail-closed 补上不变式：
     * 模块集合必须<b>恰好等于</b>某档（不多、不少）。{@code null}/空集同样判失败——
     * 那等价于"什么模块都没有"，与已签发授权的语义矛盾。
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
        if (enforceTierBinding) {
            String violation = tierBindingViolation(info.getModules());
            if (violation != null) {
                log.error("License 档位绑定校验失败，拒绝请求: tenant={}, modules={} —— {}",
                        info.getTenantId(), info.getModules(), violation);
                return false;
            }
        }
        return true;
    }

    /** 档位绑定是否处于强制状态（供 /api/v1/license/info 自报，便于运维核对部署口径）。 */
    public boolean isTierBindingEnforced() {
        return enforceTierBinding;
    }

    /**
     * 校验一组模块是否**恰好等于**某个可售档位的模块集合。
     *
     * <p>刻意不用 {@link LicenseTier#inferTier} 做判定：它是"包含某档全部模块"的
     * 子集语义，对超集会误判为高档（{@code FULL + 一个未知模块} 会被认成 FULL）——
     * 即"多签一个未知模块反而更宽松"。这里逐档做 {@link LicenseTier#mismatchOf}
     * 精确比对：超集、多一个未知模块、少一个模块，三种都判红。
     * （已由 {@code LicenseTierBindingEnforcementTest.supersetIsRejected} 变异验证：
     * 换成 inferTier 语义该用例立刻红。）
     *
     * @param modules License 签名负载里的模块集合
     * @return 相符返回 {@code null}；不符返回人类可读原因（供日志与测试断言）
     */
    public String tierBindingViolation(Set<String> modules) {
        if (modules == null || modules.isEmpty()) {
            return "模块集合为空（合法组合：" + tierSummary() + "）";
        }
        for (String tier : LicenseTier.allTiers()) {
            if (LicenseTier.mismatchOf(tier, modules) == null) {
                return null;
            }
        }
        return "模块集合不对应任何可售档位：" + modules
                + "（合法组合：" + tierSummary() + "）";
    }

    /** 合法档位摘要（仅日志与错误消息用，避免把 LicenseTier 的表结构复制到别处）。 */
    private static String tierSummary() {
        StringBuilder sb = new StringBuilder();
        for (String t : LicenseTier.allTiers().stream().sorted().toList()) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(LicenseTier.displayName(t)).append('=').append(LicenseTier.modulesOf(t));
        }
        return sb.toString();
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
     * 加载 license。
     * <p>
     * <b>fail-closed 语义（2026-10-01 修正）</b>：
     * <ul>
     *   <li><b>未配置</b> {@code aerofleet.license.key} → 开发版。这是开发/CI 的正常路径。</li>
     *   <li><b>已配置</b> key 但解析或验签失败 → <b>抛异常拒绝启动</b>。</li>
     * </ul>
     * 之所以按「是否配置 key」而不是「是否 enabled」来判：<b>配置 key 本身就是运营方
     * 声明「本部署要执行授权校验」</b>。此前的实现是无论验签成功与否一律降级为
     * {@link #buildDevLicense()}——而 dev license 是全模块、设备数无限制、永不过期的，
     * 等于「一个被篡改或损坏的 key 反而拿到最宽松的授权」，把商业门禁变成了摆设。
     * <p>
     * 之所以不新增 {@code fail-closed} 开关：多一个开关就多一种「配错了反而继续放行」
     * 的路径。规则本身就是开关——不配 key 就是开发版，配了就必须是对的。
     */
    private LicenseInfo loadLicense() {
        if (licenseKeyConfig == null || licenseKeyConfig.isBlank()) {
            log.info("未配置 aerofleet.license.key，使用开发版 License（不执行授权校验）");
            return buildDevLicense();
        }

        LicenseInfo parsed;
        try {
            parsed = parseLicense(licenseKeyConfig);
        } catch (LicenseInvalidException e) {
            // 不降级：静默给一份全模块、无限设备、永不过期的 dev license，
            // 等于把「key 坏了」变成「key 最好用」。
            log.error("License 验签失败，拒绝启动: {}", e.getMessage());
            throw new IllegalStateException(
                    "License 验签失败，已拒绝以开发版继续运行（fail-closed）。"
                            + "已配置 aerofleet.license.key 即表示本部署执行授权校验，"
                            + "不接受降级。排查方向：(1) key 是否被截断/篡改/换行；"
                            + "(2) 签发方私钥与 aerofleet.license.public-key 是否配对；"
                            + "(3) 新格式为 payload.signature，旧格式（无签名）仅 dev 模式可用。"
                            + "若本部署本就不需要授权校验，请清空 aerofleet.license.key。",
                    e);
        }

        if (parsed == null) {
            log.error("License key 解析结果为空，拒绝启动");
            throw new IllegalStateException(
                    "License key 解析结果为空，已拒绝以开发版继续运行（fail-closed）。"
                            + "若本部署本就不需要授权校验，请清空 aerofleet.license.key。");
        }

        // 档位绑定在启动期也要判一次，与上面两条「验签失败/解析为空即拒启」同一风格。
        // 只判在 validateLicense（每请求）会得到一个更难排查的失效形态：装了一份模块集合
        // 不对应任何可售档位的授权，进程**启动成功**，然后每个请求 403 —— 运维会先去查
        // 认证/网关，而不是去查授权本身。启动期拒绝把「定价是否可执行」变成部署时问题。
        if (enforceTierBinding) {
            String violation = tierBindingViolation(parsed.getModules());
            if (violation != null) {
                log.error("License 档位绑定校验失败，拒绝启动: tenant={}, modules={} —— {}",
                        parsed.getTenantId(), parsed.getModules(), violation);
                throw new IllegalStateException(
                        "License 已配置但模块集合不对应任何可售档位，拒绝启动（fail-closed）："
                                + violation
                                + " 若本部署确实使用非档位商务组合，请显式设置 "
                                + "aerofleet.license.enforce-tier-binding=false（会打 WARN）。");
            }
        }

        log.info("License 加载成功: tenant={}, product={}, maxDevices={}, modules={}, expiry={}",
                parsed.getTenantId(), parsed.getProductName(), parsed.getMaxDevices(),
                parsed.getModules(), parsed.getExpiryDate());
        return parsed;
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
     * 新格式：{@code Base64(JSON(payload)) + "." + Base64(RSA-SHA256(JSON(payload)))}。
     * 组装与自验收敛到 {@link LicenseIssuer#issue}（全仓唯一组装路径）。
     * 仅在开发模式（{@link LicenseSigner} 有私钥）下可用；生产模式签名器无私钥，
     * 本方法保持既有契约返回 null。
     *
     * @param tenantId    租户 ID
     * @param productName 产品名
     * @param maxDevices  设备上限
     * @param expiryDate  过期时间（null=永久）
     * @param issuedTo    被授权方
     * @return 签名版 license key；无私钥或签发失败时返回 null
     */
    public String generateLicenseKey(String tenantId, String productName, int maxDevices,
                                     Instant expiryDate, String issuedTo) {
        return generateLicenseKey(tenantId, productName, maxDevices, expiryDate, issuedTo,
                new HashSet<>(ALL_MODULES), 0, 0);
    }

    /**
     * 生成一个签名版 license key（含模块授权字段），供管理脚本/测试使用。
     * <p>
     * 组装/签名/自验全部委托 {@link LicenseIssuer#issue}——此前本方法内联一份
     * 「payload+sign 拼接」，与签发工具各写一份，一旦漂移就会出现
     * 「签出的 key 部署端验不过」这类只在真实签发时爆的问题。
     *
     * @param tenantId           租户 ID
     * @param productName        产品名
     * @param maxDevices         设备上限
     * @param expiryDate         过期时间（null=永久）
     * @param issuedTo           被授权方
     * @param modules            授权模块集合
     * @param maxApiCallsPerDay  每日 API 调用上限（<=0 无限制）
     * @param maxConcurrentDrones 最大并发无人机数（<=0 无限制）
     * @return 签名版 license key；无私钥（生产模式）或签发失败时返回 null
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
            return LicenseIssuer.issue(info, licenseSigner, objectMapper);
        } catch (Exception e) {
            log.error("生成 license key 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 按**商业档位**签发 license key（推荐入口，2026-10-05 新增）。
     * <p>
     * <b>为什么不直接用上面那个传 {@code Set<String> modules} 的重载</b>：
     * 手传模块集合时，"卖了哪一档"与"客户能开哪些模块"是两件事，可能漂移——
     * 签发脚本手滑多传一个 {@code emergency}，基础版客户就以基础版价格拿到
     * 应急模块。本方法把档位翻译成模块集合（{@link LicenseTier#modulesOf}），
     * 签发后再用 {@link LicenseTier#mismatchOf} 自检，确保签出的授权
     * **恰好等于**该档位应有的模块，多一个少一个都会拒签。
     * <p>
     * 产品名也会带上档位中文名（如 {@code "NexusSky 基础版"}），便于运维从
     * license 串直接看出客户买的是哪一档。若需自定义产品名，用
     * {@link #generateLicenseKeyByTier(String, String, String, int, Instant, String, int, int)}。
     *
     * @param tier 档位：{@link LicenseTier#BASIC} / {@link LicenseTier#EMERGENCY}
     *             / {@link LicenseTier#FULL}
     * @return 签名版 license key；档位未知、档位与模块不符、或无私钥时返回 null
     */
    public String generateLicenseKeyByTier(String tenantId, String tier, int maxDevices,
                                           Instant expiryDate, String issuedTo,
                                           int maxApiCallsPerDay, int maxConcurrentDrones) {
        return generateLicenseKeyByTier(tenantId, null, tier, maxDevices, expiryDate,
                issuedTo, maxApiCallsPerDay, maxConcurrentDrones);
    }

    /**
     * 按商业档位签发 license key（可自定义产品名）。
     *
     * @param productNameOverride 自定义产品名；传 null 则用「NexusSky &lt;档位中文名&gt;」
     * @see #generateLicenseKeyByTier(String, String, int, Instant, String, int, int)
     */
    public String generateLicenseKeyByTier(String tenantId, String productNameOverride, String tier,
                                           int maxDevices, Instant expiryDate, String issuedTo,
                                           int maxApiCallsPerDay, int maxConcurrentDrones) {
        if (!LicenseTier.isKnownTier(tier)) {
            log.error("拒绝签发：未知档位 {}（合法档位：{}）", tier, LicenseTier.allTiers());
            return null;
        }
        Set<String> modules = LicenseTier.modulesOf(tier);
        // 自检：档位 → 模块的映射不得漂移（例如有人改了 T，用错常量）
        String mismatch = LicenseTier.mismatchOf(tier, modules);
        if (mismatch != null) {
            log.error("拒绝签发：档位映射自检失败 —— {}", mismatch);
            return null;
        }
        // 反向自检：档位模块必须全部在 ALL_MODULES 内（防止档位引用了已废弃模块名）
        for (String m : modules) {
            if (!ALL_MODULES.contains(m)) {
                log.error("拒绝签发：档位 {} 引用了未定义模块 '{}'（合法模块：{}）",
                        tier, m, ALL_MODULES);
                return null;
            }
        }
        String productName = productNameOverride != null
                ? productNameOverride
                : "NexusSky " + LicenseTier.displayName(tier);
        String key = generateLicenseKey(tenantId, productName, maxDevices, expiryDate,
                issuedTo, modules, maxApiCallsPerDay, maxConcurrentDrones);
        if (key != null) {
            log.info("已按档位签发 license：tier={}({}) tenant={} modules={}",
                    tier, LicenseTier.displayName(tier), tenantId, modules);
        }
        return key;
    }
}

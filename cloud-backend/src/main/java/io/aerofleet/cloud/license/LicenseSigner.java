package io.aerofleet.cloud.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * License RSA-SHA256 签名工具。
 * <p>
 * 负责 License 信息的签名与验证，防止 License 被篡改。
 * <ul>
 *   <li>开发模式：内置自动生成的 RSA-2048 密钥对，开箱即用；</li>
 *   <li>生产模式：从配置读取公钥（Base64 编码的 X.509 格式），由外部签发私钥。</li>
 * </ul>
 * <p>
 * 签名算法：SHA256withRSA，密钥长度：2048 位。
 *
 * @author AeroFleet Cloud Team
 */
public class LicenseSigner {

    private static final Logger log = LoggerFactory.getLogger(LicenseSigner.class);

    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String KEY_ALGORITHM = "RSA";
    private static final int KEY_SIZE = 2048;

    /**
     * 签名规范化的唯一基准 mapper：jsr310 + 日期一律 ISO-8601 字符串。
     * <p>
     * <b>为什么不用注入的 objectMapper</b>：签名输入必须是「给定同一份 License，
     * 签发端与部署端算出字节级相同的 JSON」这一确定函数。此前规范化用的是注入
     * mapper——它随部署配置漂移（典型：{@code WRITE_DATES_AS_TIMESTAMPS}，
     * Jackson 原生默认开、Spring Boot 默认关）：ISO 签发 + 时间戳模式部署，
     * 同一份 key 两端算出的签名输入不同 → <b>验签必败，fail-closed 拒绝启动</b>。
     * 2026-10-03 由 LicenseIssuerTest 的跨 mapper 端到端（ISO 签发 → 原生默认
     * mapper 部署）暴露。规范化钉死在本类内部，与任何一端的 mapper 配置解耦。
     */
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final PublicKey publicKey;
    private final PrivateKey privateKey;
    private final boolean devMode;

    /**
     * 开发模式构造器：自动生成 RSA-2048 密钥对。
     *
     * @param objectMapper 已不参与签名规范化（见 {@link #CANONICAL_MAPPER} 的说明）；
     *                     参数保留以兼容既有调用方，后续大版本移除
     */
    public LicenseSigner(ObjectMapper objectMapper) {
        this.devMode = true;
        KeyPair keyPair = generateKeyPair();
        this.publicKey = keyPair.getPublic();
        this.privateKey = keyPair.getPrivate();
        log.info("LicenseSigner 初始化（开发模式）：已自动生成 RSA-2048 密钥对");
    }

    /**
     * 生产模式构造器：从配置读取公钥，无私钥（仅验证）。
     *
     * @param publicKeyBase64 Base64 编码的 X.509 公钥
     * @param objectMapper    已不参与签名规范化（见 {@link #CANONICAL_MAPPER} 的说明）；
     *                        参数保留以兼容既有调用方，后续大版本移除
     */
    public LicenseSigner(String publicKeyBase64, ObjectMapper objectMapper) {
        this.devMode = false;
        this.privateKey = null;
        this.publicKey = decodePublicKey(publicKeyBase64);
        log.info("LicenseSigner 初始化（生产模式）：已加载外部公钥");
    }

    /**
     * 签发模式构造器：外部密钥对（私钥签名 + 配套公钥自验），供 {@link LicenseIssuer} 使用。
     * <p>
     * 与开发模式构造器的区别：密钥对来自外部——通常是 {@link LicenseKeyGenerator}
     * 生成、由签发方安全保管的生产密钥——而非运行时自动生成。用同一个私钥签出的
     * 所有 License，都能被配置了配套公钥（生产模式构造器）的部署验签，这正是
     * 「签发端持私钥、部署端只持公钥」的商业授权模型。
     *
     * @param objectMapper 已不参与签名规范化（见 {@link #CANONICAL_MAPPER} 的说明）；
     *                     参数保留以兼容既有调用方，后续大版本移除
     */
    LicenseSigner(PrivateKey privateKey, PublicKey publicKey, ObjectMapper objectMapper) {
        this.devMode = false;
        if (privateKey == null || publicKey == null) {
            throw new IllegalArgumentException("签发模式需要同时提供私钥与配套公钥");
        }
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        log.info("LicenseSigner 初始化（签发模式）：已加载外部密钥对");
    }

    /**
     * 生成 RSA-2048 密钥对。
     */
    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            generator.initialize(KEY_SIZE);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("生成 RSA 密钥对失败", e);
        }
    }

    /**
     * 从 Base64 编码的 X.509 字符串解码公钥。
     */
    private static PublicKey decodePublicKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException("公钥不能为空");
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64.trim());
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory factory = KeyFactory.getInstance(KEY_ALGORITHM);
            return factory.generatePublic(spec);
        } catch (Exception e) {
            throw new RuntimeException("解码公钥失败", e);
        }
    }

    /**
     * 对 LicenseInfo 签名（开发模式可用）。
     * <p>
     * 将 LicenseInfo 序列化为 JSON（排除 signature 和 signerCert 字段），
     * 然后用私钥生成 RSA-SHA256 签名，返回 Base64 编码的签名字符串。
     *
     * @param info 待签名的 License 信息
     * @return Base64 编码的签名
     * @throws IllegalStateException 生产模式下无私钥时抛出
     */
    public String sign(LicenseInfo info) {
        if (privateKey == null) {
            throw new IllegalStateException("生产模式下无私钥，无法签名");
        }
        try {
            String payload = serializeForSigning(info);
            byte[] signatureBytes = signRaw(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signatureBytes);
        } catch (Exception e) {
            log.error("签名失败: {}", e.getMessage());
            throw new RuntimeException("License 签名失败", e);
        }
    }

    /**
     * 验证 LicenseInfo 的签名。
     * <p>
     * 将 LicenseInfo 序列化为 JSON（排除 signature 和 signerCert 字段），
     * 然后用公钥验证 RSA-SHA256 签名。
     *
     * @param info      License 信息（含 signature 字段）
     * @param signature Base64 编码的签名
     * @param key       公钥
     * @return 验证通过返回 true，否则 false
     */
    public boolean verify(LicenseInfo info, String signature, PublicKey key) {
        if (signature == null || signature.isBlank() || key == null) {
            return false;
        }
        try {
            String payload = serializeForSigning(info);
            byte[] signatureBytes = Base64.getDecoder().decode(signature.trim());
            return verifyRaw(payload.getBytes(StandardCharsets.UTF_8), signatureBytes, key);
        } catch (Exception e) {
            log.warn("签名验证失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 验证 LicenseInfo 的签名（使用内置公钥）。
     *
     * @param info      License 信息（含 signature 字段）
     * @param signature Base64 编码的签名
     * @return 验证通过返回 true，否则 false
     */
    public boolean verify(LicenseInfo info, String signature) {
        return verify(info, signature, publicKey);
    }

    /**
     * 对原始字节数据签名。
     */
    private byte[] signRaw(byte[] data) throws Exception {
        Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
        sig.initSign(privateKey);
        sig.update(data);
        return sig.sign();
    }

    /**
     * 验证原始字节数据的签名。
     */
    private boolean verifyRaw(byte[] data, byte[] signatureBytes, PublicKey key) throws Exception {
        Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
        sig.initVerify(key);
        sig.update(data);
        return sig.verify(signatureBytes);
    }

    /**
     * 将 LicenseInfo 序列化为用于签名/验证的 JSON。
     * <p>
     * 排除三个字段：
     * <ul>
     *   <li>{@code signature}、{@code signerCert}——签名结果的载体，不应参与签名计算
     *       （否则签名会自引用）。</li>
     *   <li>{@code licenseKey}——**它是承载签名的那层信封，不是被签名的内容**。
     *       2026-10-01 修正：此前它参与了签名计算，而 {@code parseSignedLicense} 又在
     *       验签**之前**把 {@code licenseKey} 覆写成完整的 key 串（payload + "." + signature）。
     *       签发方在计算签名时不可能预知自己将要产出的那串 key，于是签方签的 licenseKey
     *       与验签方算的 licenseKey 必然不同 → <b>任何合法签名的 License 都验不过</b>。
     *       这个缺陷此前被「验签失败降级为 dev license」掩盖：坏 key 反而拿到全模块授权，
     *       没人发现签名功能其实从未成功过一次。</li>
     * </ul>
     * 排除后，签名只覆盖 License 的**内容**，与它的封装形式解耦。
     * <p>
     * <b>规范化 mapper 必须与注入 mapper 解耦</b>（2026-10-03 修正）：此前用注入的
     * objectMapper 做本序列化，签名输入就成了「License + 本地 mapper 配置」的函数——
     * 部署端只要 Jackson 日期模式与签发端不同（Jackson 原生默认时间戳模式 vs
     * Spring Boot 默认 ISO），同一份 key 两端算出的签名输入就不同，合法 License
     * 验签必败且 fail-closed 拒绝启动。现在统一走 {@link #CANONICAL_MAPPER}：
     * 签名输入只是 License 内容本身的确定函数，与两端 mapper 配置无关。
     */
    private String serializeForSigning(LicenseInfo info) throws Exception {
        // 使用 CANONICAL_MAPPER 序列化，排除 signature / signerCert / licenseKey
        String fullJson = CANONICAL_MAPPER.writeValueAsString(info);
        // 解析回 Map，移除上述字段，再序列化
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> map = CANONICAL_MAPPER.readValue(fullJson, java.util.Map.class);
        map.remove("signature");
        map.remove("signerCert");
        map.remove("licenseKey");
        // modules 是 LicenseInfo 里唯一的集合型字段，必须按字典序固定下来：
        // 集合的迭代顺序既不携带语义，又在两端不可复现——签发端的集合实现由调用方决定
        // （LicenseTier.modulesOf 返回 Set.copyOf，其顺序依赖 JVM 级 SALT），解析端则由
        // Jackson 建成 HashSet（顺序由哈希与容量决定）。不排序就会出现「同一份 License、
        // 同一把密钥，签发端验得过、部署端验不过」的随机失败，后果是 fail-closed 拒绝启动，
        // 而现场很难把"起不来"与"集合迭代顺序"联系起来。
        // 实测复现：同集合、仅迭代顺序不同 → verify=false（见
        // LicenseSigningOrderInsensitivityTest；该用例修复前红）。
        // 本方法承诺「给定同一份 License，两端算出字节级相同的 JSON」，排序是这个承诺的一部分。
        Object modules = map.get("modules");
        if (modules instanceof java.util.List<?> rawList) {
            java.util.List<String> sorted = new java.util.ArrayList<>(rawList.size());
            for (Object m : rawList) {
                sorted.add(String.valueOf(m));
            }
            java.util.Collections.sort(sorted);
            map.put("modules", sorted);
        }
        return CANONICAL_MAPPER.writeValueAsString(map);
    }

    /**
     * 获取内置公钥（开发模式自动生成的，或生产模式从配置加载的）。
     *
     * @return 公钥
     */
    public PublicKey getPublicKey() {
        return publicKey;
    }

    /**
     * 获取公钥的 Base64 编码（X.509 格式）。
     *
     * @return Base64 编码的公钥字符串
     */
    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /**
     * 是否为开发模式。
     *
     * @return 开发模式返回 true
     */
    public boolean isDevMode() {
        return devMode;
    }
}
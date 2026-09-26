package io.aerofleet.cloud.license;

import com.fasterxml.jackson.databind.ObjectMapper;

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

    private final ObjectMapper objectMapper;
    private final PublicKey publicKey;
    private final PrivateKey privateKey;
    private final boolean devMode;

    /**
     * 开发模式构造器：自动生成 RSA-2048 密钥对。
     */
    public LicenseSigner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
     */
    public LicenseSigner(String publicKeyBase64, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.devMode = false;
        this.privateKey = null;
        this.publicKey = decodePublicKey(publicKeyBase64);
        log.info("LicenseSigner 初始化（生产模式）：已加载外部公钥");
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
     * 排除 signature 和 signerCert 字段，因为这些是签名结果的载体，
     * 不应参与签名计算本身（否则签名会自引用）。
     */
    private String serializeForSigning(LicenseInfo info) throws Exception {
        // 使用 ObjectMapper 序列化，排除 signature 和 signerCert
        String fullJson = objectMapper.writeValueAsString(info);
        // 解析回 Map，移除 signature 和 signerCert，再序列化
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> map = objectMapper.readValue(fullJson, java.util.Map.class);
        map.remove("signature");
        map.remove("signerCert");
        return objectMapper.writeValueAsString(map);
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
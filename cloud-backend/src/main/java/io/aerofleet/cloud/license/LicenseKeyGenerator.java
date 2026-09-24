package io.aerofleet.cloud.license;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RSA 密钥对生成工具。
 * <p>
 * 用于生成 License 签名所需的 RSA-2048 密钥对，输出 Base64 编码的公钥和私钥，
 * 供配置项 {@code aerofleet.license.public-key} 和签发工具使用。
 * <p>
 * 典型用法：
 * <pre>
 *   LicenseKeyGenerator.main(null);
 *   // 输出：
 *   //   Public Key (X.509 Base64): ...
 *   //   Private Key (PKCS#8 Base64): ...
 * </pre>
 * 将公钥配置到生产环境的 {@code aerofleet.license.public-key}，
 * 私钥由 License 签发方安全保管（不入代码库）。
 *
 * @author AeroFleet Cloud Team
 */
public final class LicenseKeyGenerator {

    private static final String KEY_ALGORITHM = "RSA";
    private static final int KEY_SIZE = 2048;

    private LicenseKeyGenerator() {
        // 工具类，禁止实例化
    }

    /**
     * 生成 RSA-2048 密钥对并输出 Base64 编码的公钥/私钥。
     *
     * @param args 命令行参数（未使用）
     */
    public static void main(String[] args) {
        KeyPair keyPair = generateKeyPair();
        String publicKeyBase64 = encodePublicKey(keyPair.getPublic());
        String privateKeyBase64 = encodePrivateKey(keyPair.getPrivate());

        System.out.println("=== License RSA-2048 Key Pair ===");
        System.out.println();
        System.out.println("Public Key (X.509 Base64) — 配置到 aerofleet.license.public-key:");
        System.out.println(publicKeyBase64);
        System.out.println();
        System.out.println("Private Key (PKCS#8 Base64) — License 签发方安全保管，不入代码库:");
        System.out.println(privateKeyBase64);
        System.out.println();
        System.out.println("=== Spring Boot 配置示例 ===");
        System.out.println("aerofleet.license.public-key=" + publicKeyBase64);
    }

    /**
     * 生成 RSA-2048 密钥对。
     *
     * @return RSA 密钥对
     * @throws RuntimeException 密钥生成失败
     */
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            generator.initialize(KEY_SIZE);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("生成 RSA 密钥对失败", e);
        }
    }

    /**
     * 将公钥编码为 Base64（X.509 格式）。
     *
     * @param publicKey RSA 公钥
     * @return Base64 编码的公钥字符串
     */
    public static String encodePublicKey(PublicKey publicKey) {
        X509EncodedKeySpec spec = new X509EncodedKeySpec(publicKey.getEncoded());
        return Base64.getEncoder().encodeToString(spec.getEncoded());
    }

    /**
     * 将私钥编码为 Base64（PKCS#8 格式）。
     *
     * @param privateKey RSA 私钥
     * @return Base64 编码的私钥字符串
     */
    public static String encodePrivateKey(PrivateKey privateKey) {
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(privateKey.getEncoded());
        return Base64.getEncoder().encodeToString(spec.getEncoded());
    }
}
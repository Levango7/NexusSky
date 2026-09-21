package io.aerofleet.cloud.surveillance;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * JPA 属性转换器：对 {@link SurveillanceDeviceEntity} 的 password 字段进行 AES 加密/解密。
 * <p>
 * 加密密钥从配置 {@code aerofleet.encryption.key} 读取，未配置时使用默认开发密钥。
 * 生产环境必须通过环境变量或配置文件覆盖默认密钥。
 * <p>
 * 加密算法：AES/ECB/PKCS5Padding，密钥经 SHA-256 哈希后取前 16 字节（AES-128）。
 * 密文以 Base64 编码存储在数据库中。
 */
@Component
@Converter
public class PasswordConverter implements AttributeConverter<String, String> {

    private static final Logger log = LoggerFactory.getLogger(PasswordConverter.class);

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";
    private static final String DEFAULT_KEY = "aerofleet-dev-encryption-key";

    @Value("${aerofleet.encryption.key:" + DEFAULT_KEY + "}")
    private String encryptionKey;

    @Override
    public String convertToDatabaseColumn(String plainPassword) {
        if (plainPassword == null || plainPassword.isEmpty()) {
            return plainPassword;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey());
            byte[] encrypted = cipher.doFinal(plainPassword.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            log.error("Failed to encrypt password, storing as-is (development mode): {}", e.getMessage());
            return plainPassword;
        }
    }

    @Override
    public String convertToEntityAttribute(String dbPassword) {
        if (dbPassword == null || dbPassword.isEmpty()) {
            return dbPassword;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, deriveKey());
            byte[] decoded = Base64.getDecoder().decode(dbPassword);
            byte[] decrypted = cipher.doFinal(decoded);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 解密失败可能是明文数据（旧数据未加密），直接返回原值
            log.warn("Failed to decrypt password, returning raw value (may be legacy plaintext): {}", e.getMessage());
            return dbPassword;
        }
    }

    /**
     * 从配置密钥派生 AES 密钥：SHA-256 哈希后取前 16 字节（AES-128）。
     */
    private SecretKeySpec deriveKey() throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = sha.digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
        keyBytes = Arrays.copyOf(keyBytes, 16); // AES-128 需要 16 字节密钥
        return new SecretKeySpec(keyBytes, ALGORITHM);
    }
}
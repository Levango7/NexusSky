package io.aerofleet.cloud.surveillance;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
@Converter
public class PasswordConverter implements AttributeConverter<String, String> {

    private static final Logger log = LoggerFactory.getLogger(PasswordConverter.class);

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;

    @Value("${aerofleet.encryption.key}")
    private String encryptionKey;

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String convertToDatabaseColumn(String plainPassword) {
        if (plainPassword == null || plainPassword.isEmpty()) {
            return plainPassword;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(), gcmSpec);
            byte[] encrypted = cipher.doFinal(plainPassword.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("Failed to encrypt password: {}", e.getMessage());
            throw new RuntimeException("Password encryption failed", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbPassword) {
        if (dbPassword == null || dbPassword.isEmpty()) {
            return dbPassword;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(dbPassword);
            if (combined.length < GCM_IV_LENGTH_BYTES) {
                return dbPassword;
            }
            byte[] iv = Arrays.copyOf(combined, GCM_IV_LENGTH_BYTES);
            byte[] encrypted = Arrays.copyOfRange(combined, GCM_IV_LENGTH_BYTES, combined.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(), gcmSpec);
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to decrypt password, returning raw value (may be legacy plaintext): {}", e.getMessage());
            return dbPassword;
        }
    }

    private SecretKeySpec deriveKey() throws Exception {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            throw new IllegalStateException("aerofleet.encryption.key is not configured");
        }
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = sha.digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
        keyBytes = Arrays.copyOf(keyBytes, 16);
        return new SecretKeySpec(keyBytes, ALGORITHM);
    }
}
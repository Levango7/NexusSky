package io.aerofleet.cloud.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.time.Instant;

/**
 * JWT 令牌生成与验证工具。
 * <p>
 * 使用 Spring Security 内置的 {@link NimbusJwtEncoder}/{@link NimbusJwtDecoder}
 * （由 spring-boot-starter-oauth2-resource-server 提供 Nimbus JOSE 依赖）。
 * 采用 HMAC-SHA256 对称签名，密钥通过 {@code aerofleet.security.jwt-secret} 配置。
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    public JwtTokenProvider(@Value("${aerofleet.security.jwt-secret}") String secret) {
        if (secret == null || secret.getBytes().length < 32) {
            throw new IllegalArgumentException(
                    "aerofleet.security.jwt-secret must be at least 32 bytes for HMAC-SHA256");
        }
        SecretKey key = new SecretKeySpec(secret.getBytes(), "HmacSHA256");
        JWK jwk = new OctetSequenceKey.Builder(key).keyID("aerofleet").build();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(
                new com.nimbusds.jose.jwk.JWKSet(jwk));
        this.encoder = new NimbusJwtEncoder(jwkSource);
        this.decoder = NimbusJwtDecoder.withSecretKey(key).build();
    }

    /** 暴露 decoder 供 SecurityConfig 的 oauth2ResourceServer 使用。 */
    public JwtDecoder getDecoder() {
        return decoder;
    }

    /**
     * 生成 JWT 令牌。
     *
     * @param username 主题（用户名）
     * @param expiry  有效期
     * @return JWT 令牌字符串
     */
    public String generateToken(String username, Duration expiry) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("aerofleet")
                .subject(username)
                .issuedAt(now)
                .expiresAt(now.plus(expiry))
                .claim("type", "access")
                .build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /**
     * 验证令牌有效性。
     *
     * @param token JWT 令牌
     * @return 有效返回 true，否则 false
     */
    public boolean validateToken(String token) {
        try {
            decoder.decode(token);
            return true;
        } catch (JwtException e) {
            log.debug("JWT 验证失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从令牌中提取用户名（subject）。
     *
     * @param token JWT 令牌
     * @return 用户名，令牌无效时返回 null
     */
    public String getUsername(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            return jwt.getSubject();
        } catch (JwtException e) {
            log.debug("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }
}
package io.aerofleet.cloud.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * JWT 令牌生成与验证工具。
 * <p>
 * 支持两种签名算法：
 * <ul>
 *   <li><b>RS256</b>（默认推荐）— RSA 非对称签名，私钥签发、公钥验证，适合多实例部署</li>
 *   <li><b>HS256</b>（兼容回退）— HMAC-SHA256 对称签名，密钥通过 {@code jwt.secret} 或
 *       {@code aerofleet.security.jwt-secret} 配置</li>
 * </ul>
 * <p>
 * 当 {@code jwt.algorithm=RS256} 但未配置 RSA 密钥且未启用 {@code jwt.generate-keys} 时，
 * 自动回退到 HS256 模式，保证向后兼容。
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    /** HMAC-SHA256 最小密钥长度（字节）。 */
    private static final int MIN_SECRET_BYTES = 32;

    /** 自动生成 RSA 密钥对的位数。 */
    private static final int RSA_KEY_SIZE = 2048;

    private JwtEncoder encoder;
    private JwtDecoder decoder;
    private JwsHeader header;

    // ───────────────────────── 构造函数 ─────────────────────────

    /**
     * HS256 兼容构造函数（测试与编程式使用）。
     *
     * @param secret HMAC-SHA256 密钥（≥32 字节）
     */
    public JwtTokenProvider(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "jwt secret must be at least " + MIN_SECRET_BYTES + " bytes for HMAC-SHA256");
        }
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JWK jwk = new OctetSequenceKey.Builder(key)
                .keyID("aerofleet")
                .algorithm(JWSAlgorithm.HS256)
                .keyUse(KeyUse.SIGNATURE)
                .build();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(
                new com.nimbusds.jose.jwk.JWKSet(jwk));
        this.encoder = new NimbusJwtEncoder(jwkSource);
        this.decoder = NimbusJwtDecoder.withSecretKey(key).build();
        this.header = JwsHeader.with(MacAlgorithm.HS256).keyId("aerofleet").build();
    }

    /**
     * RS256 编程式构造函数（测试与开发使用）。
     *
     * @param privateKey RSA 私钥（用于签发 token）
     * @param publicKey  RSA 公钥（用于验证 token）
     */
    public JwtTokenProvider(RSAPrivateKey privateKey, RSAPublicKey publicKey) {
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID("aerofleet")
                .algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE)
                .build();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(
                new com.nimbusds.jose.jwk.JWKSet(rsaKey));
        this.encoder = new NimbusJwtEncoder(jwkSource);
        this.decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        this.header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("aerofleet").build();
    }

    /**
     * Spring 注入主构造函数。
     * <p>
     * 根据 {@code jwt.algorithm} 配置选择签名算法：
     * <ul>
     *   <li>RS256 + 已配置 RSA 密钥 → 使用非对称签名</li>
     *   <li>RS256 + {@code jwt.generate-keys=true} → 自动生成 RSA 密钥对</li>
     *   <li>RS256 + 无 RSA 密钥 → 回退到 HS256（使用 jwt.secret 或 aerofleet.security.jwt-secret）</li>
     *   <li>HS256 → 使用对称签名</li>
     * </ul>
     */
    @Autowired
    public JwtTokenProvider(
            @Value("${jwt.algorithm:RS256}") String algorithm,
            @Value("${jwt.private-key:}") String privateKeyPem,
            @Value("${jwt.public-key:}") String publicKeyPem,
            @Value("${jwt.secret:}") String secret,
            @Value("${aerofleet.security.jwt-secret:}") String legacySecret,
            @Value("${jwt.generate-keys:false}") boolean generateKeys,
            @Value("${spring.profiles.active:}") String activeProfiles
    ) {
        if ("RS256".equalsIgnoreCase(algorithm)) {
            if (isNotEmpty(privateKeyPem) && isNotEmpty(publicKeyPem)) {
                // 使用配置的 RSA 密钥对
                RSAPrivateKey privKey = parsePrivateKey(privateKeyPem);
                RSAPublicKey pubKey = parsePublicKey(publicKeyPem);
                log.info("JWT 使用 RS256 非对称签名（配置密钥对）");
                RSAKey rsaKey = new RSAKey.Builder(pubKey)
                        .privateKey(privKey)
                        .keyID("aerofleet")
                        .algorithm(JWSAlgorithm.RS256)
                        .keyUse(KeyUse.SIGNATURE)
                        .build();
                JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(
                        new com.nimbusds.jose.jwk.JWKSet(rsaKey));
                this.encoder = new NimbusJwtEncoder(jwkSource);
                this.decoder = NimbusJwtDecoder.withPublicKey(pubKey).build();
                this.header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("aerofleet").build();
            } else if (generateKeys) {
                // P1-fix: 生产环境禁止自动生成密钥对，防止重启后所有 JWT token 失效
                if (activeProfiles != null && activeProfiles.contains("prod")) {
                    throw new IllegalStateException(
                            "jwt.generate-keys=true is not allowed in production profile");
                }
                // 自动生成 RSA 密钥对（开发环境）
                log.warn("jwt.generate-keys=true: 自动生成 RSA 密钥对（仅适用于开发环境，生产环境必须配置 jwt.private-key/jwt.public-key）");
                KeyPair keyPair = generateRsaKeyPair();
                RSAPrivateKey privKey = (RSAPrivateKey) keyPair.getPrivate();
                RSAPublicKey pubKey = (RSAPublicKey) keyPair.getPublic();
                log.info("自动生成的 RSA 公钥（Base64 X.509）: {}",
                        Base64.getEncoder().encodeToString(pubKey.getEncoded()));
                RSAKey rsaKey = new RSAKey.Builder(pubKey)
                        .privateKey(privKey)
                        .keyID("aerofleet")
                        .algorithm(JWSAlgorithm.RS256)
                        .keyUse(KeyUse.SIGNATURE)
                        .build();
                JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(
                        new com.nimbusds.jose.jwk.JWKSet(rsaKey));
                this.encoder = new NimbusJwtEncoder(jwkSource);
                this.decoder = NimbusJwtDecoder.withPublicKey(pubKey).build();
                this.header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("aerofleet").build();
            } else {
                // 回退到 HS256
                log.warn("jwt.algorithm=RS256 但未配置 RSA 密钥也未启用 jwt.generate-keys，回退到 HS256 模式");
                String effectiveSecret = resolveSecret(secret, legacySecret);
                initHs256(effectiveSecret);
            }
        } else {
            // HS256 模式
            String effectiveSecret = resolveSecret(secret, legacySecret);
            initHs256(effectiveSecret);
        }
    }

    // ───────────────────────── 私有初始化方法 ─────────────────────────

    private void initHs256(String effectiveSecret) {
        if (effectiveSecret == null || effectiveSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "jwt secret must be at least " + MIN_SECRET_BYTES + " bytes for HMAC-SHA256");
        }
        SecretKey key = new SecretKeySpec(effectiveSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JWK jwk = new OctetSequenceKey.Builder(key)
                .keyID("aerofleet")
                .algorithm(JWSAlgorithm.HS256)
                .keyUse(KeyUse.SIGNATURE)
                .build();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(
                new com.nimbusds.jose.jwk.JWKSet(jwk));
        this.encoder = new NimbusJwtEncoder(jwkSource);
        this.decoder = NimbusJwtDecoder.withSecretKey(key).build();
        this.header = JwsHeader.with(MacAlgorithm.HS256).keyId("aerofleet").build();
    }

    // ───────────────────────── PEM 解析与密钥生成 ─────────────────────────

    private RSAPrivateKey parsePrivateKey(String pemBase64) {
        try {
            byte[] der = Base64.getDecoder().decode(stripPemHeaders(pemBase64));
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 RSA 私钥: " + e.getMessage(), e);
        }
    }

    private RSAPublicKey parsePublicKey(String pemBase64) {
        try {
            byte[] der = Base64.getDecoder().decode(stripPemHeaders(pemBase64));
            X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 RSA 公钥: " + e.getMessage(), e);
        }
    }

    /** 移除 PEM 头尾标记和所有空白字符，返回纯 Base64 内容。 */
    private String stripPemHeaders(String pem) {
        return pem.replaceAll("-----BEGIN [^-]+-----", "")
                  .replaceAll("-----END [^-]+-----", "")
                  .replaceAll("\\s", "");
    }

    private KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_KEY_SIZE);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("无法生成 RSA 密钥对: " + e.getMessage(), e);
        }
    }

    private String resolveSecret(String secret, String legacySecret) {
        if (isNotEmpty(secret)) {
            return secret;
        }
        if (isNotEmpty(legacySecret)) {
            return legacySecret;
        }
        throw new IllegalArgumentException(
                "未配置 jwt.secret 或 aerofleet.security.jwt-secret，无法使用 HS256 模式");
    }

    private boolean isNotEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    // ───────────────────────── 公开 API ─────────────────────────

    /** 暴露 decoder 供 SecurityConfig 的 oauth2ResourceServer 使用。 */
    public JwtDecoder getDecoder() {
        return decoder;
    }

    /**
     * 生成 JWT 令牌（向后兼容版本，不含 role/tenant_id claim）。
     *
     * @param username 主题（用户名）
     * @param expiry   有效期
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
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * 生成 JWT 令牌（含角色和租户 ID claim）。
     * <p>
     * 在标准 claim 之外添加：
     * <ul>
     *   <li>{@code role} — 用户角色枚举名称（ADMIN/OPERATOR/OBSERVER）</li>
     *   <li>{@code tenant_id} — 租户 ID（null 表示全局管理员）</li>
     * </ul>
     *
     * @param username 主题（用户名）
     * @param role     用户角色
     * @param tenantId 租户 ID（null 表示全局管理员）
     * @param expiry   有效期
     * @return JWT 令牌字符串
     */
    public String generateToken(String username, Role role, Integer tenantId, Duration expiry) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer("aerofleet")
                .subject(username)
                .issuedAt(now)
                .expiresAt(now.plus(expiry))
                .claim("type", "access")
                .claim("role", role.name());
        if (tenantId != null) {
            claimsBuilder.claim("tenant_id", tenantId);
        }
        JwtClaimsSet claims = claimsBuilder.build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
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

    /**
     * 从令牌中提取租户 ID（tenant_id claim）。
     *
     * @param token JWT 令牌
     * @return 租户 ID，令牌无效或不含 tenant_id 时返回 null
     */
    public Integer getTenantId(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            Object claim = jwt.getClaim("tenant_id");
            if (claim instanceof Number) {
                return ((Number) claim).intValue();
            }
            return null;
        } catch (JwtException e) {
            log.debug("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }
}

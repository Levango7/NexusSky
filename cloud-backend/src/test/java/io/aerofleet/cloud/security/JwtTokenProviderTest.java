package io.aerofleet.cloud.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JwtTokenProvider 单测（直接实例化，无 Spring 上下文）。
 * <p>
 * 验证 HMAC-SHA256 对称签名 JWT 的生成、校验、解析与构造器边界。
 */
@DisplayName("JwtTokenProvider JWT 令牌生成与验证")
class JwtTokenProviderTest {

    /** ≥32 bytes 的测试密钥（34 bytes）。 */
    private static final String SECRET = "this-is-a-test-secret-key-32bytes!";
    /** 正好 32 bytes 的密钥。 */
    private static final String SECRET_32 = "0123456789abcdef0123456789abcdef";

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(SECRET);
    }

    @Test
    @DisplayName("generateToken 后 validateToken 返回 true")
    void generateToken_returnsValidToken() {
        String token = provider.generateToken("admin", Duration.ofMinutes(30));
        assertThat(token).isNotBlank();
        assertThat(provider.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("getUsername 提取令牌 subject 返回正确用户名")
    void getUsername_extractsSubject() {
        String token = provider.generateToken("operator", Duration.ofMinutes(30));
        assertThat(provider.getUsername(token)).isEqualTo("operator");
    }

    @Test
    @DisplayName("validateToken 对乱字符串返回 false")
    void validateToken_returnsFalseForGarbageString() {
        assertThat(provider.validateToken("not.a.jwt")).isFalse();
        assertThat(provider.validateToken("garbage")).isFalse();
        assertThat(provider.validateToken("")).isFalse();
    }

    @Test
    @DisplayName("validateToken 对篡改签名的令牌返回 false")
    void validateToken_returnsFalseForTamperedToken() {
        String token = provider.generateToken("admin", Duration.ofMinutes(30));
        // JWT 格式: header.payload.signature —— 替换签名段为无效内容
        int lastDot = token.lastIndexOf('.');
        String tampered = token.substring(0, lastDot + 1) + "tampered-signature-value";
        assertThat(provider.validateToken(tampered)).isFalse();
    }

    @Test
    @DisplayName("getUsername 对无效令牌返回 null")
    void getUsername_returnsNullForInvalidToken() {
        assertThat(provider.getUsername("invalid.token.value")).isNull();
        assertThat(provider.getUsername("garbage")).isNull();
    }

    @Test
    @DisplayName("构造器拒绝 <32 bytes 的 secret，抛 IllegalArgumentException")
    void constructor_rejectsShortSecret() {
        assertThatThrownBy(() -> new JwtTokenProvider("short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("构造器接受正好 32 bytes 的 secret，不抛异常")
    void constructor_accepts32ByteSecret() {
        assertThat(SECRET_32.getBytes().length).isEqualTo(32);
        JwtTokenProvider p = new JwtTokenProvider(SECRET_32);
        String token = p.generateToken("admin", Duration.ofMinutes(1));
        assertThat(p.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("不同过期时间生成的令牌都能验证通过")
    void generateToken_withDifferentExpiry() {
        String t1 = provider.generateToken("admin", Duration.ofSeconds(60));
        String t2 = provider.generateToken("admin", Duration.ofHours(2));
        String t3 = provider.generateToken("admin", Duration.ofDays(7));
        assertThat(provider.validateToken(t1)).isTrue();
        assertThat(provider.validateToken(t2)).isTrue();
        assertThat(provider.validateToken(t3)).isTrue();
    }

    @Test
    @DisplayName("getDecoder 返回非 null 的 JwtDecoder 实例")
    void getDecoder_returnsNonNullDecoder() {
        JwtDecoder decoder = provider.getDecoder();
        assertThat(decoder).isNotNull();
        // 解码自身生成的令牌应成功
        String token = provider.generateToken("admin", Duration.ofMinutes(30));
        assertThat(decoder.decode(token).getSubject()).isEqualTo("admin");
    }
}
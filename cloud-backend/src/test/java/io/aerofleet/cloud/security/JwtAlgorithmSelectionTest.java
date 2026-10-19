package io.aerofleet.cloud.security;

import com.nimbusds.jose.JWSObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code JwtTokenProvider} **算法选择逻辑**的钉测试。
 *
 * <h2>为什么单独写这个类（JwtTokenProviderTest 已经存在）</h2>
 *
 * <p>已有的 {@code JwtTokenProviderTest} 用的是<b>编程式构造函数</b>
 * （{@code new JwtTokenProvider(secret)} / {@code new JwtTokenProvider(privKey, pubKey)}），
 * 那两个构造函数会跳过全部选择逻辑 —— 它们验证的是"HS256 模式能不能工作"、
 * "RS256 模式能不能工作"。
 *
 * <p>真正没被测过的是 Spring 注入主构造函数里那段四路分支：
 * <pre>
 *   RS256 + 已配置 RSA 密钥        -> RS256
 *   RS256 + jwt.generate-keys=true -> 自动生成密钥对（prod 禁止）
 *   RS256 + 无 RSA 密钥           -> 回退 HS256
 *   HS256                          -> HS256
 * </pre>
 * 全仓搜 {@code jwt.generate-keys} / {@code generateKeys}：<b>零命中</b>。
 * 其中第三条正是 <b>prod 实际跑的那条</b> —— {@code application-prod.properties}
 * 只设了 {@code aerofleet.security.jwt-secret}，从不配 RSA 密钥对，也没开
 * {@code generate-keys}（而且 prod 明确禁止开）。
 *
 * <h2>断言为什么咬 token 头，而不是 {@code validateToken == true}</h2>
 *
 * <p>{@code validateToken} 返回 true 只能证明"这个 provider 能验自己签的 token"，
 * 对用的是哪种算法毫无鉴别力 —— 回退成 HS256 的 provider 同样会返回 true。
 * 唯一能分辨的办法是把签出来的 token 解开看 JOSE 头里的 {@code alg}，
 * 所以这里一律走 {@link JWSObject} 精确解析，不用子串匹配。
 *
 * <h2>与声明的对照</h2>
 *
 * <p>{@code application.properties} 写的是 {@code jwt.algorithm=RS256}，小节标题是
 * 「P2-2 RS256 非对称签名升级」，但 prod/staging 从不配置 RSA 密钥对 ——
 * 所以<b>生产环境实际签发的是 HS256</b>。这是有意保留的回退（有 Javadoc、有启动
 * warn 日志），不是 bug；本测试的作用是把这个事实钉住，免得哪天有人在 prod 悄悄
 * 少了密钥却不自知。
 */
@DisplayName("Jwt 算法选择：四路分支 + 实际签发的 alg")
class JwtAlgorithmSelectionTest {

    /** 32 字节，正好卡住 HS256 的最小长度门槛。 */
    private static final String SECRET_32 =
            "0123456789abcdef0123456789abcdef";

    /** Spring 注入主构造函数（7 参），参数顺序与实现一致。 */
    private static JwtTokenProvider spring(
            String algorithm, String privPem, String pubPem,
            String jwtSecret, String legacySecret,
            boolean generateKeys, String activeProfiles) {
        return new JwtTokenProvider(algorithm, privPem, pubPem,
                jwtSecret, legacySecret, generateKeys, activeProfiles);
    }

    /** 精确取出签发 token 的 JOSE 头算法名（不做子串匹配）。 */
    private static String algOf(String token) {
        try {
            return JWSObject.parse(token).getHeader().getAlgorithm().getName();
        } catch (Exception e) {
            throw new AssertionError("token 不是合法的 JWS: " + token, e);
        }
    }

    @Nested
    @DisplayName("分支 1：RS256 + 已配置 RSA 密钥对")
    class WithConfiguredKeyPair {

        private String privPem;
        private String pubPem;

        @BeforeEach
        void makeKeyPair() throws Exception {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            privPem = pem("PRIVATE KEY", ((RSAPrivateKey) kp.getPrivate()).getEncoded());
            pubPem = pem("PUBLIC KEY", ((RSAPublicKey) kp.getPublic()).getEncoded());
        }

        @Test
        @DisplayName("签出的 token 头是 RS256，且能自验")
        void emitsRs256() {
            JwtTokenProvider p = spring("RS256", privPem, pubPem, "", "", false, "prod");
            String token = p.generateToken("admin", Duration.ofMinutes(30));
            assertThat(algOf(token)).isEqualTo("RS256");
            assertThat(p.validateToken(token)).isTrue();
        }

        @Test
        @DisplayName("密钥对已配置时 prod 也放行（generate-keys 的禁令只针对自动生成）")
        void configuredKeyPairAllowedInProd() {
            JwtTokenProvider p = spring("RS256", privPem, pubPem, "", "", true, "prod");
            String token = p.generateToken("admin", Duration.ofMinutes(30));
            assertThat(algOf(token)).isEqualTo("RS256");
        }

        @Test
        @DisplayName("只配私钥：不算配置完整，静默退回 HS256（warn 日志是唯一提示）")
        void halfConfiguredKeyPairFallsBackToHs256() {
            JwtTokenProvider p = spring("RS256", privPem, "", "", SECRET_32, false, "prod");
            assertThat(algOf(p.generateToken("admin", Duration.ofMinutes(30))))
                    .as("只给私钥不给公钥时，判据 isNotEmpty(priv)&&isNotEmpty(pub) 不成立，"
                            + "于是掉进回退分支 —— 配错一半的后果是整个服务悄悄换成对称签名")
                    .isEqualTo("HS256");
        }

        @Test
        @DisplayName("只配公钥：同样退回 HS256")
        void halfConfiguredKeyPairOtherSideFallsBackToHs256() {
            JwtTokenProvider p = spring("RS256", "", pubPem, "", SECRET_32, false, "prod");
            assertThat(algOf(p.generateToken("admin", Duration.ofMinutes(30)))).isEqualTo("HS256");
        }

        @Test
        @DisplayName("只配私钥且没有 secret：快速失败，不会签出无密钥的 token")
        void halfConfiguredKeyPairWithoutSecretFailsFast() {
            assertThatThrownBy(() -> spring("RS256", privPem, "", "", "", false, "prod"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("jwt.secret");
        }

        private String pem(String type, byte[] der) {
            return "-----BEGIN " + type + "-----\n"
                    + java.util.Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der)
                    + "\n-----END " + type + "-----\n";
        }
    }

    @Nested
    @DisplayName("分支 2：RS256 + jwt.generate-keys=true（prod 禁止）")
    class GenerateKeys {

        @Test
        @DisplayName("非 prod 下自动生成密钥对，签出 RS256")
        void devAutoGeneratesRs256() {
            JwtTokenProvider p = spring("RS256", "", "", SECRET_32, "", true, "dev");
            String token = p.generateToken("admin", Duration.ofMinutes(30));
            assertThat(algOf(token)).isEqualTo("RS256");
            assertThat(p.validateToken(token)).isTrue();
        }

        @Test
        @DisplayName("prod 下 generate-keys=true 直接拒绝启动（不静默降级成 HS256）")
        void prodRejectsGenerateKeys() {
            assertThatThrownBy(() -> spring("RS256", "", "", SECRET_32, "", true, "prod"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("jwt.generate-keys=true is not allowed in production profile");
        }

        @Test
        @DisplayName("多 profile 串里含 prod 也必须拒绝（contains 而非 equals）")
        void prodRejectsGenerateKeysInMultiProfileString() {
            // spring.profiles.active=prod,observability 是完全合法的写法。
            // 守卫若写成 equals("prod")，这种组合会绕过禁令 —— 而多实例部署恰恰
            // 常这么配（同时开观测栈），等于在最需要守卫的场景里失效。
            for (String profiles : new String[]{"prod,observability", "observability,prod", " dev , prod "}) {
                assertThatThrownBy(() -> spring("RS256", "", "", SECRET_32, "", true, profiles))
                        .as("activeProfiles=%s 应当被 prod 守卫拦下", profiles)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("not allowed in production profile");
            }
        }

        @Test
        @DisplayName("dev,staging 等不含 prod 的串不受影响")
        void nonProdProfileStringsStillGenerate() {
            for (String profiles : new String[]{"dev", "dev,observability", "staging"}) {
                JwtTokenProvider p = spring("RS256", "", "", SECRET_32, "", true, profiles);
                assertThat(algOf(p.generateToken("admin", Duration.ofMinutes(30))))
                        .as("activeProfiles=%s 应当照常生成 RS256", profiles)
                        .isEqualTo("RS256");
            }
        }
    }

    @Nested
    @DisplayName("分支 3：RS256 + 无 RSA 密钥 -> 回退 HS256（prod 实际跑的就是这条）")
    class FallbackToHs256 {

        @Test
        @DisplayName("prod 组合签出的是 HS256，不是配置里写的 RS256")
        void prodCombinationEmitsHs256() {
            // 逐字复刻 application-prod.properties 的组合：
            // algorithm=RS256（继承基线）+ 无 RSA 密钥 + generate-keys 缺省 false
            // + aerofleet.security.jwt-secret（prod 用的是 legacy 这个键）
            JwtTokenProvider p = spring("RS256", "", "", "", SECRET_32, false, "prod");
            String token = p.generateToken("admin", Duration.ofMinutes(30));
            assertThat(algOf(token))
                    .as("配置写着 RS256，但没配密钥对 —— 实际必须是 HS256，"
                            + "这条断言就是为了让这个事实显形")
                    .isEqualTo("HS256");
            assertThat(p.validateToken(token)).isTrue();
        }

        @Test
        @DisplayName("两者都配时优先用 jwt.secret（跨 provider 互验证伪）")
        void jwtSecretWinsOverLegacySecret() {
            String legacyOnly = "legacy-secret-only-32-bytes-long!!";
            // A：两个键都配了
            JwtTokenProvider both = spring("RS256", "", "", SECRET_32, legacyOnly, false, "prod");
            // B：只配 legacy，且值等于 A 的 legacy 值
            JwtTokenProvider legacy = spring("RS256", "", "", "", legacyOnly, false, "prod");

            String token = both.generateToken("admin", Duration.ofMinutes(30));
            assertThat(algOf(token)).isEqualTo("HS256");

            assertThat(both.validateToken(token)).isTrue();
            assertThat(legacy.validateToken(token))
                    .as("A 若用的是 jwt.secret 而不是 legacy 值，B（只有 legacy 值）就验不过；"
                            + "反之若 A 用了 legacy 值，这条会为 true 而变异杀不掉")
                    .isFalse();
        }

        @Test
        @DisplayName("既无密钥对又无 secret 时快速失败，不静默签出弱令牌")
        void noSecretFailsFast() {
            assertThatThrownBy(() -> spring("RS256", "", "", "", "", false, "prod"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("jwt.secret");
        }

        @Test
        @DisplayName("secret 短于 32 字节时快速失败")
        void shortSecretRejected() {
            assertThatThrownBy(() -> spring("RS256", "", "", "", "too-short", false, "prod"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least 32 bytes");
        }
    }

    @Nested
    @DisplayName("分支 4：显式 HS256")
    class ExplicitHs256 {

        @Test
        @DisplayName("jwt.algorithm=HS256 直接签出 HS256")
        void emitsHs256() {
            JwtTokenProvider p = spring("HS256", "", "", SECRET_32, "", false, "prod");
            assertThat(algOf(p.generateToken("admin", Duration.ofMinutes(30)))).isEqualTo("HS256");
        }
    }
}
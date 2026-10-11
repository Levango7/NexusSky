package io.aerofleet.cloud.config;

import io.aerofleet.mavlink.security.MavlinkSignatureConfig;
import io.aerofleet.mavlink.security.MavlinkSigner;
import io.aerofleet.mavlink.security.MavlinkSignerFactory;
import io.aerofleet.mavlink.security.SigningKeyManager;
import io.aerofleet.mavlink.security.TimestampTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MAVLink 签名组件装配测试。
 * <p>
 * 这条测试的存在意义大于其内容：本仓此前"实现了签名却接不上"正是因为<b>没有任何测试
 * 检查过注入点是否真的有 bean</b>——{@code UdpGateway} 用 {@code @Autowired(required=false)}
 * 注入四个签名类，而裸 {@code @SpringBootApplication} 不扫 {@code io.aerofleet.mavlink} 包，
 * 于是四个字段恒 null、{@code isSigningEnabled()} 恒 false，把开关打开也没有任何效果。
 * 这里直接用上下文断言"开关开 → 四个 bean 都在且能真签名"、"开关关 → 四个 bean 都不在"，
 * 让这类死路径下次会自己变红。
 */
@DisplayName("MAVLink 签名组件装配（开关驱动）")
class MavlinkSigningConfigurationTest {

    private static final String SECRET = "wiring-test-secret-key-32-bytes!";
    private static final byte[] FRAME = {(byte) 0xFD, 9, 1, 0, 0, 1, 1, 0, 0, 0, 1, 2, 3};

    /** 密钥库格式（{@code SigningKeyManager.loadKeyStore}）：version / defaultKey / sysids。 */
    private static final String KEYSTORE_JSON = """
            {
              "version": 1,
              "defaultKey": "keystore-default-key-32-bytes!!!!",
              "sysids": {
                "1": { "key": "per-link-key-32-bytes-long!!!!!!", "linkId": 2 }
              }
            }
            """;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MavlinkSigningConfiguration.class);

    // ==================== 出厂状态：不装配 ====================

    @Test
    @DisplayName("未配置 mavlink.signing.enabled：四个签名 bean 都不创建（出厂行为不变）")
    void noPropertyCreatesNoSigningBeans() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(MavlinkSignatureConfig.class);
            assertThat(context).doesNotHaveBean(MavlinkSignerFactory.class);
            assertThat(context).doesNotHaveBean(SigningKeyManager.class);
            assertThat(context).doesNotHaveBean(TimestampTracker.class);
        });
    }

    @Test
    @DisplayName("enabled=false：同样不装配，且给了口令也不装配")
    void disabledCreatesNoSigningBeans() {
        runner.withPropertyValues("mavlink.signing.enabled=false",
                        "mavlink.signing.secret-key=" + SECRET)
                .run(context -> assertThat(context).doesNotHaveBean(MavlinkSignerFactory.class));
    }

    // ==================== 打开开关：真的接上 ====================

    @Test
    @DisplayName("enabled=true + 全局口令：四个 bean 齐备，取出的签名器真能产出 6 字节签名")
    void enabledWithGlobalSecretWiresUp() {
        runner.withPropertyValues("mavlink.signing.enabled=true",
                        "mavlink.signing.secret-key=" + SECRET)
                .run(context -> {
                    assertThat(context).hasSingleBean(MavlinkSignatureConfig.class);
                    assertThat(context).hasSingleBean(MavlinkSignerFactory.class);
                    assertThat(context).hasSingleBean(SigningKeyManager.class);
                    assertThat(context).hasSingleBean(TimestampTracker.class);

                    MavlinkSigner signer = context.getBean(MavlinkSignerFactory.class).signerFor(1);
                    assertThat(signer).isNotNull();
                    assertThat(signer.isEnabled()).isTrue();
                    assertThat(signer.isRejectUnsigned())
                            .as("配置默认 fail-closed：未签名消息应被拒")
                            .isTrue();
                    assertThat(signer.sign(FRAME, signer.getLinkId(), 1000L)).hasSize(6);
                });
    }

    @Test
    @DisplayName("只给密钥库（无全局口令）：装配成功，口令与 linkId 来自库里对应 sysid 的条目")
    void keyStoreOnlyWiresUpAndUsesPerSysidKey(@TempDir Path tmp) throws IOException {
        Path keyStore = tmp.resolve("signing-keys.json");
        Files.writeString(keyStore, KEYSTORE_JSON);

        runner.withPropertyValues("mavlink.signing.enabled=true",
                        "mavlink.signing.key-store-path=" + keyStore.toAbsolutePath())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MavlinkSignerFactory factory = context.getBean(MavlinkSignerFactory.class);
                    SigningKeyManager manager = context.getBean(SigningKeyManager.class);
                    assertThat(manager.isMultiMode()).isTrue();

                    // sysid=1 用库里那条 per-link 口令、linkId=2；sysid=9 回落到 defaultKey
                    assertThat(factory.linkIdFor(1)).isEqualTo(2);
                    assertThat(factory.linkIdFor(9)).isEqualTo(9);   // 缺省回退：sysid & 0xFF

                    MavlinkSigner one = factory.signerFor(1);
                    MavlinkSigner nine = factory.signerFor(9);
                    assertThat(one).isNotNull();
                    assertThat(nine).isNotNull();
                    assertThat(factory.cachedSignerCount())
                            .as("两把不同口令应各自缓存一个签名器")
                            .isEqualTo(2);

                    // 口令不同 → 同一帧的签名必须不同（否则"多机密钥"只是摆设）
                    byte[] sigOne = one.sign(FRAME, 2, 1000L);
                    byte[] sigNine = nine.sign(FRAME, 2, 1000L);
                    assertThat(sigOne).isNotNull().hasSize(6);
                    assertThat(sigNine).isNotEqualTo(sigOne);
                    assertThat(one.verify(FRAME, 2, 1000L, sigOne)).isTrue();
                    assertThat(one.verify(FRAME, 2, 1000L, sigNine)).isFalse();
                });
    }

    @Test
    @DisplayName("口令 + 密钥库并存：per-sysid 条目优先于全局口令")
    void secretPlusKeyStorePrefersPerSysidEntry(@TempDir Path tmp) throws IOException {
        Path keyStore = tmp.resolve("signing-keys.json");
        Files.writeString(keyStore, KEYSTORE_JSON);

        runner.withPropertyValues("mavlink.signing.enabled=true",
                        "mavlink.signing.secret-key=" + SECRET,
                        "mavlink.signing.key-store-path=" + keyStore.toAbsolutePath())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MavlinkSignerFactory factory = context.getBean(MavlinkSignerFactory.class);
                    assertThat(factory.linkIdFor(1))
                            .as("密钥库命中 sysid=1 时 linkId 取库里的 2，而不是 sysid 本身")
                            .isEqualTo(2);
                });
    }

    @Test
    @DisplayName("enabled=true 但既无口令也无密钥库：启动失败（校验真的被执行了）")
    void enabledWithoutAnyKeyFailsStartup() {
        runner.withPropertyValues("mavlink.signing.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    // Spring 会把 bean 初始化异常再包一层，消息嵌套格式不保证，
                    // 因此沿因果链拼接后再断言，避免依赖某一次的包装措辞
                    assertThat(messageChain(context.getStartupFailure()))
                            .contains("secret-key")
                            .contains("key-store-path");
                });
    }

    @Test
    @DisplayName("key-store-path 指向不存在的文件：启动失败而不是静默退回单密钥模式")
    void missingKeyStoreFileFailsStartup(@TempDir Path tmp) {
        runner.withPropertyValues("mavlink.signing.enabled=true",
                        "mavlink.signing.key-store-path=" + tmp.resolve("nope.json"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(messageChain(context.getStartupFailure()))
                            .contains("non-existent file");
                });
    }

    // ==================== 部署姿态：profile 文件本身 ====================
    //
    // 上面所有用例都是通过 withPropertyValues 手工喂值的——它们证明的是
    // "装配逻辑对"，证明不了"**部署时真的是这个姿态**"。而 C5 恰恰栽在这里：
    // 第一步（staging 开着签名但不拒未签名）落地时没有任何东西告诉后来人
    // "现在 staging 是有意 permissive 的"，于是它看起来像 bug、看起来像默认值忘了改。
    // 下面两个用例直接把 profile 文件钉死，让改姿态必须是一个显式的、被审到的一步。

    @Test
    @DisplayName("staging profile 装配成 strict（C5 第二步收紧后的实际部署姿态）")
    void stagingProfileAssemblesStrict() throws IOException {
        Properties staging = classpathProperties("application-staging.properties");

        assertThat(staging.getProperty("mavlink.signing.enabled"))
                .as("staging 必须开着签名，否则下面装配出的 strict 毫无意义")
                .isEqualTo("true");
        assertThat(staging.getProperty("mavlink.signing.reject-unsigned"))
                .as("C5 第二步：staging 已从 permissive 收紧为 fail-closed，未签名帧必须被拒。"
                        + "退回 false 是一次**行为变更**（未签名流量会重新在明文状态下被放行），"
                        + "不是调参——要改请连带改 CHANGELOG 与 ROADMAP 的三步表述。")
                .isEqualTo("true");

        // 不只读字面值：把这两个值真喂进上下文，确认它们装配出的签名器确实是 strict。
        // 读字面值只能证明"文件写了 true"，这段能证明"true 真的传导到了 MavlinkSigner"。
        runner.withPropertyValues(
                        "mavlink.signing.enabled=" + staging.getProperty("mavlink.signing.enabled"),
                        "mavlink.signing.reject-unsigned=" + staging.getProperty("mavlink.signing.reject-unsigned"),
                        "mavlink.signing.secret-key=" + SECRET)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MavlinkSigner signer = context.getBean(MavlinkSignerFactory.class).signerFor(1);
                    assertThat(signer.isEnabled()).isTrue();
                    assertThat(signer.isRejectUnsigned())
                            .as("staging 的 reject-unsigned=true 必须真的传导到签名器，"
                                    + "否则配置文件改了、行为没变，是最难查的那种假修复")
                            .isTrue();
                });
    }

    @Test
    @DisplayName("prod profile 仍是 enabled=false（第三步需单独拍板，不在本轮范围）")
    void prodProfileStaysDisabled() throws IOException {
        Properties prod = classpathProperties("application-prod.properties");

        assertThat(prod.getProperty("mavlink.signing.enabled"))
                .as("prod 打开签名是机队范围的行为变更（签名块无 key id ⇒ 硬切换、无宽限期），"
                        + "约定需单独拍板。有人顺手改成 true 时，这里会转红并要求先把拍板过程写下来。")
                .isEqualTo("false");
        assertThat(prod.getProperty("mavlink.signing.reject-unsigned"))
                .as("prod 的 reject-unsigned 保持 fail-closed 默认 true，"
                        + "这样第三步一旦翻开关就是 strict，不需要连着改两个值")
                .isEqualTo("true");
    }

    /** 读 classpath 上的 properties 文件（profile 文件在 main/resources，测试可见）。 */
    private static Properties classpathProperties(String name) throws IOException {
        try (InputStream in = MavlinkSigningConfigurationTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            assertThat(in).as("classpath 上找不到 %s——profile 文件被挪走或改了名？", name).isNotNull();
            Properties props = new Properties();
            props.load(in);
            return props;
        }
    }

    /** 拼接异常因果链上的所有消息（Spring 的 BeanCreationException 嵌套格式不保证）。 */
    private static String messageChain(Throwable failure) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append(" | ");
            }
        }
        return sb.toString();
    }
}

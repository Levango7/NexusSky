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
import java.nio.file.Files;
import java.nio.file.Path;

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

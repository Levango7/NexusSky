package io.aerofleet.mavlink.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MavlinkSignerFactory} 测试：多机密钥模式下"每个 sysid 用自己的口令"必须真的成立。
 * <p>
 * 这是 backend 侧此前的真实缺口——密钥库只提供 per-sysid 的 linkId，签名口令仍取全局值，
 * 于是 {@code key-store-path} 配了等于没配。本类不看 Spring 装配，只验工厂本身的取键与缓存语义。
 */
@DisplayName("MAVLink 签名器工厂（多机密钥）")
class MavlinkSignerFactoryTest {

    private static final byte[] FRAME = {
            (byte) 0xFD, 1, 1, 0, 7, 1, 1, 0, 0, 0, 4, 0
    };

    private static final String KEY_1 = "per-sysid-key-number-one-32bytes!!";
    private static final String KEY_2 = "per-sysid-key-number-two-32bytes!!";
    private static final String DEFAULT_KEY = "keystore-default-key-32-bytes!!!!";

    private static String keystoreJson() {
        return """
                {
                  "version": 1,
                  "defaultKey": "%s",
                  "sysids": {
                    "1": { "key": "%s", "linkId": 11 },
                    "2": { "key": "%s", "linkId": 22 }
                  }
                }
                """.formatted(DEFAULT_KEY, KEY_1, KEY_2);
    }

    private MavlinkSignerFactory factoryFor(Path keyStore) {
        return new MavlinkSignerFactory(new SigningKeyManager(keyStore, ""), true);
    }

    @Test
    @DisplayName("密钥库命中时：sysid 用自己的口令与 linkId，两机签名互不相认")
    void perSysidKeysAreActuallyUsed(@TempDir Path tmp) throws IOException {
        Path keyStore = Files.writeString(tmp.resolve("keys.json"), keystoreJson());
        MavlinkSignerFactory factory = factoryFor(keyStore);

        assertThat(factory.linkIdFor(1)).isEqualTo(11);
        assertThat(factory.linkIdFor(2)).isEqualTo(22);

        MavlinkSigner signer1 = factory.signerFor(1);
        MavlinkSigner signer2 = factory.signerFor(2);
        assertThat(signer1).isNotNull();
        assertThat(signer2).isNotNull();

        byte[] sig1 = signer1.sign(FRAME, 11, 5000L);
        byte[] sig2 = signer2.sign(FRAME, 11, 5000L);

        assertThat(sig1).hasSize(6);
        assertThat(sig2).isNotEqualTo(sig1);
        // 关键断言：机 1 的验签不能接受机 2 的签名（口令泄漏成"一把万能钥匙"就是回归）
        assertThat(signer1.verify(FRAME, 11, 5000L, sig1)).isTrue();
        assertThat(signer1.verify(FRAME, 11, 5000L, sig2)).isFalse();
    }

    @Test
    @DisplayName("同口令复用同一实例；不同口令各自一个（缓存按口令而非按 sysid）")
    void signersAreCachedByKeyNotBySysid(@TempDir Path tmp) throws IOException {
        Path keyStore = Files.writeString(tmp.resolve("keys.json"), keystoreJson());
        MavlinkSignerFactory factory = factoryFor(keyStore);

        assertThat(factory.signerFor(1)).isSameAs(factory.signerFor(1));
        assertThat(factory.cachedSignerCount()).isEqualTo(1);

        factory.signerFor(2);
        assertThat(factory.cachedSignerCount())
                .as("sysid=2 是另一把口令 → 第二个实例")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("未配置的 sysid 回落 defaultKey，linkId 用 sysid 本身（与 SigningKeyManager 一致）")
    void unknownSysidFallsBackToDefaultKey(@TempDir Path tmp) throws IOException {
        Path keyStore = Files.writeString(tmp.resolve("keys.json"), keystoreJson());
        MavlinkSignerFactory factory = factoryFor(keyStore);

        MavlinkSigner fallback = factory.signerFor(7);
        assertThat(factory.linkIdFor(7)).isEqualTo(7);
        assertThat(fallback).isNotNull();
        assertThat(fallback.sign(FRAME, 7, 1L)).hasSize(6);
    }

    @Test
    @DisplayName("单机模式（只有全局口令）：所有 sysid 共用一把签名器，行为与旧实现一致")
    void singleKeyModeSharesOneSigner() {
        MavlinkSignerFactory factory = new MavlinkSignerFactory(
                new SigningKeyManager(DEFAULT_KEY), true);

        MavlinkSigner a = factory.signerFor(1);
        MavlinkSigner b = factory.signerFor(2);

        assertThat(a).isSameAs(b);
        assertThat(factory.cachedSignerCount()).isEqualTo(1);
        assertThat(factory.linkIdFor(3)).isEqualTo(3);   // 单机模式 linkId = sysid & 0xFF
    }

    @Test
    @DisplayName("口令缺失时返回 null（调用方按 fail-closed 处理，不拿空口令签名）")
    void missingKeyYieldsNullSigner(@TempDir Path tmp) throws IOException {
        String json = """
                { "version": 1, "sysids": { "1": { "key": "", "linkId": 5 } } }
                """;
        Path keyStore = Files.writeString(tmp.resolve("keys.json"), json);
        MavlinkSignerFactory factory = new MavlinkSignerFactory(
                new SigningKeyManager(keyStore, null), true);

        assertThat(factory.signerFor(1))
                .as("条目里口令为空 → 不能凭空造一个空口令签名器")
                .isNull();
    }

    @Test
    @DisplayName("rejectUnsigned 透传给每个派生签名器")
    void rejectUnsignedIsPropagated(@TempDir Path tmp) throws IOException {
        Path keyStore = Files.writeString(tmp.resolve("keys.json"), keystoreJson());

        MavlinkSignerFactory strict = factoryFor(keyStore);
        MavlinkSignerFactory lenient = new MavlinkSignerFactory(
                new SigningKeyManager(keyStore, ""), false);

        assertThat(strict.signerFor(1).isRejectUnsigned()).isTrue();
        assertThat(lenient.signerFor(1).isRejectUnsigned()).isFalse();
        assertThat(strict.isRejectUnsigned()).isTrue();
    }
}

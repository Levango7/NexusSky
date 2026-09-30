package io.aerofleet.cloud.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DeviceIngestKeyBootstrapRunner} 单元测试。
 * <p>
 * 关注点：未配置即完全不介入（否则等于给每个默认部署塞了一把后门 key）、
 * 只存哈希不存明文、重复启动覆写同一条记录（不堆积）、弱 key 拒绝出厂。
 */
@DisplayName("DeviceIngestKeyBootstrapRunner 摄取凭据引导 (P0-2)")
class DeviceIngestKeyBootstrapRunnerTest {

    private static final String GOOD_KEY = "nsk_device_ingest_0123456789abcdef";

    private DeviceIngestKeyBootstrapRunner runner(ApiKeyRepository repository, String key) {
        return new DeviceIngestKeyBootstrapRunner(repository, key, 1);
    }

    @Test
    @DisplayName("未配置 key 时不查库不写库")
    void skippedWhenKeyAbsent() {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);

        runner(repository, "").run(null);

        verify(repository, never()).findByKeyId(anyString());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("无 ApiKeyRepository（内存模式）时跳过而不抛错")
    void skippedWithoutRepository() {
        runner(null, GOOD_KEY).run(null);
    }

    @Test
    @DisplayName("key 短于 16 时拒绝引导（避免弱密钥出厂）")
    void rejectsShortKey() {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);

        runner(repository, "short-key").run(null);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("配置合法 key 时写入 OPERATOR 档，且只存哈希")
    void bootstrapsHashNotPlaintext() {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.findByKeyId(DeviceIngestKeyBootstrapRunner.KEY_ID)).thenReturn(Optional.empty());

        runner(repository, GOOD_KEY).run(null);

        ApiKeyEntity saved = captureSave(repository);
        assertThat(saved.getKeyId()).isEqualTo("device-ingest");
        assertThat(saved.getRole()).isEqualTo("OPERATOR");
        assertThat(saved.isRevoked()).isFalse();
        assertThat(saved.getTenantId()).isEqualTo(1);
        // 库里绝不能出现明文；哈希必须与 ApiKeyFilter 认证侧的 SHA-256 hex 口径一致
        assertThat(saved.getKeyHash()).isNotEqualTo(GOOD_KEY).doesNotContain(GOOD_KEY);
        assertThat(saved.getKeyHash()).isEqualTo(sha256Hex(GOOD_KEY));
        assertThat(saved.getKeyHash()).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("已存在引导 key 时覆写同一条并保留 createdAt")
    void overwritesExistingRow() {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        ApiKeyEntity existing = new ApiKeyEntity();
        existing.setKeyId(DeviceIngestKeyBootstrapRunner.KEY_ID);
        existing.setKeyHash("stale-hash");
        Instant createdAt = Instant.now().minusSeconds(86_400);
        existing.setCreatedAt(createdAt);
        when(repository.findByKeyId(DeviceIngestKeyBootstrapRunner.KEY_ID)).thenReturn(Optional.of(existing));

        runner(repository, GOOD_KEY).run(null);

        ApiKeyEntity saved = captureSave(repository);
        assertThat(saved.getKeyHash()).isEqualTo(sha256Hex(GOOD_KEY));
        // 换 key 是轮换通路，但 createdAt 不该被重置，否则审计上看不出这条 key 的寿命
        assertThat(saved.getCreatedAt()).isEqualTo(createdAt);
    }

    private static ApiKeyEntity captureSave(ApiKeyRepository repository) {
        ArgumentCaptor<ApiKeyEntity> captor = ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    /** 与 {@link ApiKeyFilter} 一致的哈希实现，测试里独立复算以防两处口径漂移。 */
    private static String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

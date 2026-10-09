package io.aerofleet.mavlink.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SigningKeyStoreWriter} 单元测试。
 *
 * <p>重点不在"能写"，而在三件安全属性：
 * <ol>
 *   <li><b>只出指纹不出密钥</b>——返回值与服务端响应都不含密钥材料；</li>
 *   <li><b>坏输入摸不到线上文件</b>——非法 JSON / 空密钥 / 越界 sysid 一律先拒，
 *       原文件保持逐字节不变；</li>
 *   <li><b>不写丢 operator 的其它字段</b>——轮换只改目标条目。</li>
 * </ol>
 *
 * <p>协议边界也在这里钉住：MAVLink 签名没有 key id，所以<b>没有宽限期参数</b>。
 */
@DisplayName("SigningKeyStoreWriter: 密钥库原子轮换")
class SigningKeyStoreWriterTest {

    private final SigningKeyStoreWriter writer = new SigningKeyStoreWriter();

    @TempDir
    Path tempDir;

    private Path storeWith(String json) throws Exception {
        Path p = tempDir.resolve("signing-keys.json");
        Files.writeString(p, json);
        return p;
    }

    private SigningKeyManager managerFor(Path store) {
        return new SigningKeyManager(store, null);
    }

    @Test
    @DisplayName("1. 轮换已有 sysid：旧/新指纹都返回，密钥材料绝不外泄")
    void rotate_existingSysid_returnsFingerprintsOnly() throws Exception {
        Path store = storeWith("""
                {
                  "version": 1,
                  "sysids": {
                    "7": {"key": "old-key-7", "linkId": 7}
                  }
                }
                """);

        SigningKeyStoreWriter.RotationResult r =
                writer.rotate(managerFor(store), 7, "brand-new-key-7");

        assertThat(r.oldFingerprint()).isEqualTo(
                SigningKeyStoreWriter.fingerprint("old-key-7"));
        assertThat(r.newFingerprint()).isEqualTo(
                SigningKeyStoreWriter.fingerprint("brand-new-key-7"));
        assertThat(r.oldFingerprint()).isNotEqualTo(r.newFingerprint());
        assertThat(r.linkId()).isEqualTo(7);
        assertThat(r.totalEntries()).isEqualTo(1);
        assertThat(r.path()).isEqualTo(store.toString());

        // 整个结果对象的字符串形态里也不能出现密钥本体
        String dumped = r.toString();
        assertThat(dumped).doesNotContain("brand-new-key-7").doesNotContain("old-key-7");
    }

    @Test
    @DisplayName("2. 轮换后文件里的 key 真的是新值，linkId 被保留")
    void rotate_writesNewKeyAndKeepsLinkId() throws Exception {
        Path store = storeWith("""
                {
                  "version": 1,
                  "sysids": {
                    "8": {"key": "k8-old", "linkId": 77}
                  }
                }
                """);

        writer.rotate(managerFor(store), 8, "k8-new");

        String content = Files.readString(store);
        assertThat(content).contains("k8-new").doesNotContain("k8-old");
        assertThat(content).contains("77"); // linkId 未被重置成 sysid&0xFF
    }

    @Test
    @DisplayName("3. 轮换后只读管理器立刻能用新密钥验签（mtime 热更生效）")
    void rotate_thenKeyManagerSeesNewKey() throws Exception {
        Path store = storeWith("""
                {
                  "version": 1,
                  "sysids": {
                    "3": {"key": "before", "linkId": 3}
                  }
                }
                """);

        SigningKeyManager km = managerFor(store);
        assertThat(km.keyFor(3).key()).isEqualTo("before");

        writer.rotate(km, 3, "after");

        // reloadIfNeeded 在 keyFor 内部被调用，无需重启
        assertThat(km.keyFor(3).key()).isEqualTo("after");
    }

    @Test
    @DisplayName("4. 新 sysid：旧指纹为 none，条目数从 0 增到 1")
    void rotate_newSysid_oldFingerprintIsNone() throws Exception {
        Path store = storeWith("""
                {
                  "version": 1,
                  "sysids": {}
                }
                """);

        SigningKeyStoreWriter.RotationResult r =
                writer.rotate(managerFor(store), 42, "fresh-key");

        assertThat(r.oldFingerprint()).isEqualTo("none");
        assertThat(r.totalEntries()).isEqualTo(1);
        assertThat(r.linkId()).isEqualTo(42 & 0xFF); // 新建时按仓内默认口径
    }

    @Test
    @DisplayName("5. 不写丢其它 sysid 与自定义顶层字段")
    void rotate_preservesOtherEntriesAndTopLevelFields() throws Exception {
        Path store = storeWith("""
                {
                  "version": 1,
                  "note": "operator-managed",
                  "sysids": {
                    "1": {"key": "keep-me-1", "linkId": 1},
                    "2": {"key": "old-2", "linkId": 2}
                  }
                }
                """);

        writer.rotate(managerFor(store), 2, "new-2");

        String content = Files.readString(store);
        assertThat(content).contains("keep-me-1");   // 别的机器密钥没被动
        assertThat(content).contains("operator-managed"); // operator 自定义字段没丢
    }

    @Test
    @DisplayName("6. 单机模式（无 key-store-path）：拒绝轮换")
    void rotate_singleKeyMode_rejected() {
        SigningKeyManager single = new SigningKeyManager("just-one-key");

        assertThatThrownBy(() -> writer.rotate(single, 7, "new"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多机模式");
    }

    @Test
    @DisplayName("7. 非法 JSON 对象：拒绝，且原文件逐字节不变")
    void rotate_malformedStore_rejectedAndFileUntouched() throws Exception {
        Path store = storeWith("[1, 2, 3]");
        byte[] before = Files.readAllBytes(store);

        assertThatThrownBy(() -> writer.rotate(managerFor(store), 7, "new"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("拒绝覆盖");

        assertThat(Files.readAllBytes(store)).isEqualTo(before);
    }

    @Test
    @DisplayName("8. 空密钥 / null / 越界 sysid / 超长：一律拒绝且文件不动")
    void rotate_badInputs_rejected() throws Exception {
        Path store = storeWith("""
                {
                  "version": 1,
                  "sysids": {"7": {"key": "keep", "linkId": 7}}
                }
                """);
        byte[] before = Files.readAllBytes(store);
        SigningKeyManager km = managerFor(store);

        assertThatThrownBy(() -> writer.rotate(km, 7, ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> writer.rotate(km, 7, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> writer.rotate(km, 0, "new"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1..255");
        assertThatThrownBy(() -> writer.rotate(km, 256, "new"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1..255");
        assertThatThrownBy(() -> writer.rotate(km, 7, "x".repeat(257)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("长度");

        assertThat(Files.readAllBytes(store)).isEqualTo(before); // 一个都没写进去
        assertThat(Files.readString(store)).contains("keep");   // 原密钥还在
    }
}

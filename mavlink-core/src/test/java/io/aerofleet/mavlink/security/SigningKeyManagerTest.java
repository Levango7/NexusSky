package io.aerofleet.mavlink.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SigningKeyManager 单元测试（C5-T15）：
 * 1. 单机模式 → 任意 sysid 返回全局密钥
 * 2. 多机模式 → sysid 在文件中返回对应密钥
 * 3. 多机模式 → sysid 不在文件中回退 defaultKey
 * 4. 多机模式 + 无 defaultKey + sysid 不在文件 → null
 * 5. 修改密钥文件后 keyFor() 返回新密钥（热更新）
 * 6. 指纹为 8 字符 hex 字符串
 * 7. 未指定 linkId 时默认 = sysid & 0xFF
 * 8. 密钥文件中指定 linkId 时使用指定值
 */
class SigningKeyManagerTest {

    @TempDir
    Path tempDir;

    // ========== 1. singleModeKeyForAnySysid ==========

    @Test
    @DisplayName("单机模式 → 任意 sysid 返回全局密钥")
    void singleModeKeyForAnySysid() {
        String globalKey = "global-secret-key";
        SigningKeyManager manager = new SigningKeyManager(globalKey);

        assertFalse(manager.isMultiMode(), "应为单机模式");

        // 任意 sysid 都应返回全局密钥
        for (int sysid : new int[]{1, 2, 100, 255}) {
            SigningKeyManager.KeyEntry entry = manager.keyFor(sysid);
            assertNotNull(entry, "sysid=" + sysid + " 应返回密钥条目");
            assertEquals(globalKey, entry.key(), "sysid=" + sysid + " 应返回全局密钥");
        }
    }

    // ========== 2. multiModeKeyForKnownSysid ==========

    @Test
    @DisplayName("多机模式 → sysid 在文件中返回对应密钥")
    void multiModeKeyForKnownSysid() throws Exception {
        Path keyFile = createKeyStoreFile(
                "{\"version\":1, \"defaultKey\":\"fallback\", \"sysids\":{\"1\":{\"key\":\"sys1-secret\",\"linkId\":1}}}");

        SigningKeyManager manager = new SigningKeyManager(keyFile, "fallback");
        assertTrue(manager.isMultiMode(), "应为多机模式");

        SigningKeyManager.KeyEntry entry = manager.keyFor(1);
        assertNotNull(entry, "sysid=1 应返回密钥条目");
        assertEquals("sys1-secret", entry.key(), "sysid=1 应返回对应密钥");
        assertEquals(1, entry.linkId(), "sysid=1 linkId 应为 1");
    }

    // ========== 3. multiModeKeyForUnknownSysidFallsBack ==========

    @Test
    @DisplayName("多机模式 → sysid 不在文件中回退 defaultKey")
    void multiModeKeyForUnknownSysidFallsBack() throws Exception {
        Path keyFile = createKeyStoreFile(
                "{\"version\":1, \"defaultKey\":\"fallback-secret\", \"sysids\":{\"1\":{\"key\":\"sys1-secret\",\"linkId\":1}}}");

        SigningKeyManager manager = new SigningKeyManager(keyFile, "fallback-secret");

        // sysid=99 不在文件中，应回退到 defaultKey
        SigningKeyManager.KeyEntry entry = manager.keyFor(99);
        assertNotNull(entry, "sysid=99 应回退到 defaultKey");
        assertEquals("fallback-secret", entry.key(), "回退密钥应为 defaultKey");
        assertEquals(99 & 0xFF, entry.linkId(), "回退时 linkId 应为 sysid & 0xFF");
    }

    // ========== 4. multiModeNoFallbackReturnsNull ==========

    @Test
    @DisplayName("多机模式 + 无 defaultKey + sysid 不在文件 → null")
    void multiModeNoFallbackReturnsNull() throws Exception {
        Path keyFile = createKeyStoreFile(
                "{\"version\":1, \"sysids\":{\"1\":{\"key\":\"sys1-secret\",\"linkId\":1}}}");

        // fallbackKey = null
        SigningKeyManager manager = new SigningKeyManager(keyFile, null);

        // sysid=99 不在文件中，且无 defaultKey → null
        SigningKeyManager.KeyEntry entry = manager.keyFor(99);
        assertNull(entry, "无 defaultKey 且 sysid 不在文件中应返回 null");
    }

    // ========== 5. hotReloadOnFileChange ==========

    @Test
    @DisplayName("修改密钥文件后 keyFor() 返回新密钥（热更新）")
    void hotReloadOnFileChange() throws Exception {
        Path keyFile = createKeyStoreFile(
                "{\"version\":1, \"defaultKey\":\"old-key\", \"sysids\":{\"1\":{\"key\":\"old-sys1\",\"linkId\":1}}}");

        SigningKeyManager manager = new SigningKeyManager(keyFile, "old-key");

        // 初始状态
        SigningKeyManager.KeyEntry entry1 = manager.keyFor(1);
        assertEquals("old-sys1", entry1.key(), "初始密钥应为 old-sys1");

        // 修改密钥文件（需要确保文件修改时间变化）
        Thread.sleep(50); // 确保时间戳不同
        Files.writeString(keyFile,
                "{\"version\":1, \"defaultKey\":\"new-key\", \"sysids\":{\"1\":{\"key\":\"new-sys1\",\"linkId\":2}}}");

        // keyFor() 内部调用 reloadIfNeeded()，应检测到文件变化并重新加载
        SigningKeyManager.KeyEntry entry2 = manager.keyFor(1);
        assertEquals("new-sys1", entry2.key(), "热更新后密钥应为 new-sys1");
        assertEquals(2, entry2.linkId(), "热更新后 linkId 应为 2");
    }

    // ========== 6. fingerprintFormat ==========

    @Test
    @DisplayName("指纹为 8 字符 hex 字符串")
    void fingerprintFormat() {
        SigningKeyManager manager = new SigningKeyManager("some-key");

        String fp = manager.fingerprint("test-key");
        assertNotNull(fp, "指纹不应为 null");
        assertEquals(8, fp.length(), "指纹应为 8 字符");
        assertTrue(fp.matches("[0-9a-f]{8}"), "指纹应为 8 字符 hex 字符串: " + fp);

        // 空密钥返回 "????"
        assertEquals("????", manager.fingerprint(""), "空密钥指纹应为 ????");
        assertEquals("????", manager.fingerprint(null), "null 密钥指纹应为 ????");

        // 相同密钥指纹一致
        String fp2 = manager.fingerprint("test-key");
        assertEquals(fp, fp2, "相同密钥指纹应一致");

        // 不同密钥指纹不同
        String fp3 = manager.fingerprint("other-key");
        assertNotEquals(fp, fp3, "不同密钥指纹应不同");
    }

    // ========== 7. linkIdDefault ==========

    @Test
    @DisplayName("未指定 linkId 时默认 = sysid & 0xFF")
    void linkIdDefault() {
        String globalKey = "global-secret";
        SigningKeyManager manager = new SigningKeyManager(globalKey);

        // 单机模式：linkId 默认 = sysid & 0xFF
        SigningKeyManager.KeyEntry entry = manager.keyFor(42);
        assertEquals(42 & 0xFF, entry.linkId(), "单机模式 linkId 应为 sysid & 0xFF");

        // 多机模式：sysid 在文件中但未指定 linkId → linkId = sysid & 0xFF
        // 需要创建一个不包含 linkId 的密钥文件
    }

    @Test
    @DisplayName("多机模式：密钥文件中未指定 linkId 时默认 = sysid & 0xFF")
    void multiModeLinkIdDefault() throws Exception {
        Path keyFile = createKeyStoreFile(
                "{\"version\":1, \"defaultKey\":\"fallback\", \"sysids\":{\"5\":{\"key\":\"sys5-secret\"}}}");

        SigningKeyManager manager = new SigningKeyManager(keyFile, "fallback");

        // sysid=5 在文件中但未指定 linkId → linkId = 5 & 0xFF = 5
        SigningKeyManager.KeyEntry entry = manager.keyFor(5);
        assertEquals(5, entry.linkId(), "未指定 linkId 时应为 sysid & 0xFF");
    }

    // ========== 8. linkIdExplicit ==========

    @Test
    @DisplayName("密钥文件中指定 linkId 时使用指定值")
    void linkIdExplicit() throws Exception {
        Path keyFile = createKeyStoreFile(
                "{\"version\":1, \"defaultKey\":\"fallback\", \"sysids\":{\"1\":{\"key\":\"sys1-secret\",\"linkId\":7}}}");

        SigningKeyManager manager = new SigningKeyManager(keyFile, "fallback");

        // sysid=1 在文件中且指定 linkId=7
        SigningKeyManager.KeyEntry entry = manager.keyFor(1);
        assertEquals(7, entry.linkId(), "指定 linkId 时应使用指定值");
        assertEquals("sys1-secret", entry.key(), "密钥应正确");
    }

    // ========== 辅助方法 ==========

    /**
     * 创建临时 JSON 密钥文件。
     */
    private Path createKeyStoreFile(String jsonContent) throws Exception {
        Path keyFile = tempDir.resolve("keystore.json");
        Files.writeString(keyFile, jsonContent);
        return keyFile;
    }
}
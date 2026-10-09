package io.aerofleet.mavlink.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MAVLink 签名密钥管理器：支持单机模式和多机模式。
 * <p>
 * <b>单机模式</b>：所有系统共用一个全局密钥，linkId = sysid & 0xFF。
 * <p>
 * <b>多机模式</b>：从 JSON 密钥文件加载 sysid→key 映射，每个系统有独立密钥与 linkId。
 * 支持热更新（文件修改时间检测），IO 异常时保留现有密钥不崩溃。
 * <p>
 * JSON 密钥文件格式：
 * <pre>{@code
 * {
 *   "version": 1,
 *   "defaultKey": "fallback-secret",
 *   "sysids": {
 *     "1": {"key": "sys1-secret", "linkId": 1},
 *     "2": {"key": "sys2-secret", "linkId": 2}
 *   }
 * }
 * }</pre>
 */
public class SigningKeyManager {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyManager.class);

    /** 密钥条目：包含密钥字符串和分配的 linkId。 */
    public record KeyEntry(String key, int linkId) {}

    private final boolean multiMode;
    private final Path keyStorePath;

    /** 全局密钥（单机模式的主密钥，或多机模式的回退密钥） */
    private volatile String globalKey;

    /** 多机模式的 sysid→KeyEntry 映射，volatile 保证热更新时整体替换引用可见性 */
    private volatile Map<Integer, KeyEntry> sysidKeys;

    /** 密钥文件最后修改时间，用于热更新检测 */
    private volatile long lastModified;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 单机模式构造器：所有系统共用一个全局密钥。
     *
     * @param globalKey 全局密钥字符串（非空）
     */
    public SigningKeyManager(String globalKey) {
        if (globalKey == null || globalKey.isEmpty()) {
            throw new IllegalArgumentException("Global key must not be empty");
        }
        this.multiMode = false;
        this.keyStorePath = null;
        this.globalKey = globalKey;
        this.sysidKeys = new ConcurrentHashMap<>();
        this.lastModified = 0;
        log.info("SigningKeyManager initialized in single-key mode, fingerprint={}", fingerprint(globalKey));
    }

    /**
     * 多机模式构造器：从 JSON 文件加载 sysid→key 映射。
     *
     * @param keyStorePath 密钥文件路径
     * @param fallbackKey  回退密钥（当 sysid 未命中时使用，可为 null）
     */
    public SigningKeyManager(Path keyStorePath, String fallbackKey) {
        this.multiMode = true;
        this.keyStorePath = keyStorePath;
        this.globalKey = fallbackKey;
        this.sysidKeys = new ConcurrentHashMap<>();
        this.lastModified = 0;
        loadKeyStore();
        log.info("SigningKeyManager initialized in multi-key mode, path={}, entries={}, fallbackFingerprint={}",
                keyStorePath, sysidKeys.size(),
                fallbackKey != null ? fingerprint(fallbackKey) : "none");
    }

    /**
     * 查找指定 sysid 的密钥条目。
     * <p>
     * 多机模式：先查 sysidKeys Map，命中返回；未命中回退 globalKey（构造 KeyEntry(globalKey, sysid & 0xFF)）；
     * 无全局密钥返回 null。
     * 单机模式：直接返回 KeyEntry(globalKey, sysid & 0xFF)。
     *
     * @param sysid 系统 ID
     * @return 密钥条目，或 null（无可用密钥时）
     */
    public KeyEntry keyFor(int sysid) {
        if (multiMode) {
            reloadIfNeeded();
            KeyEntry entry = sysidKeys.get(sysid);
            if (entry != null) {
                return entry;
            }
            // 回退到全局密钥
            if (globalKey != null && !globalKey.isEmpty()) {
                return new KeyEntry(globalKey, sysid & 0xFF);
            }
            return null;
        }
        // 单机模式
        return new KeyEntry(globalKey, sysid & 0xFF);
    }

    /**
     * 从 JSON 文件加载密钥映射。
     * <p>
     * JSON 格式：version / defaultKey / sysids（sysid→{key, linkId}）。
     * IO 异常时保留现有密钥不崩溃。
     */
    private void loadKeyStore() {
        if (keyStorePath == null) {
            return;
        }
        try {
            byte[] data = Files.readAllBytes(keyStorePath);
            JsonNode root = objectMapper.readTree(data);

            // 解析 defaultKey
            JsonNode defaultKeyNode = root.get("defaultKey");
            if (defaultKeyNode != null && !defaultKeyNode.isNull()) {
                String newDefault = defaultKeyNode.asText();
                if (!newDefault.isEmpty()) {
                    this.globalKey = newDefault;
                }
            }

            // 解析 sysids 映射
            Map<Integer, KeyEntry> newKeys = new ConcurrentHashMap<>();
            JsonNode sysidsNode = root.get("sysids");
            if (sysidsNode != null) {
                sysidsNode.fields().forEachRemaining(entry -> {
                    try {
                        int sysid = Integer.parseInt(entry.getKey());
                        JsonNode val = entry.getValue();
                        String key = val.get("key").asText();
                        int linkId = val.has("linkId") ? val.get("linkId").asInt() : (sysid & 0xFF);
                        newKeys.put(sysid, new KeyEntry(key, linkId));
                        log.info("Loaded key for sysid={}, linkId={}, fingerprint={}",
                                sysid, linkId, fingerprint(key));
                    } catch (Exception e) {
                        log.warn("Failed to parse key entry for sysid={}: {}", entry.getKey(), e.getMessage());
                    }
                });
            }

            // 整体替换引用，保证线程安全
            this.sysidKeys = newKeys;
            this.lastModified = Files.getLastModifiedTime(keyStorePath).toMillis();
            log.info("KeyStore loaded: {} entries, defaultFingerprint={}",
                    newKeys.size(),
                    globalKey != null ? fingerprint(globalKey) : "none");
        } catch (IOException e) {
            log.warn("Failed to load keystore from {}: {} (keeping existing keys)", keyStorePath, e.getMessage());
        }
    }

    /**
     * 检测密钥文件修改时间变化时重新加载。
     * <p>
     * IO 异常时保留现有密钥不崩溃。
     */
    public void reloadIfNeeded() {
        if (!multiMode || keyStorePath == null) {
            return;
        }
        try {
            long currentModified = Files.getLastModifiedTime(keyStorePath).toMillis();
            if (currentModified != lastModified) {
                log.info("Keystore file modified, reloading: {}", keyStorePath);
                loadKeyStore();
            }
        } catch (IOException e) {
            log.warn("Failed to check keystore modification time: {} (keeping existing keys)", e.getMessage());
        }
    }

    /**
     * 计算密钥指纹：SHA-256 前 4 字节的 hex 格式（如 {@code a1b2c3d4}）。
     * <p>
     * 用于日志中标识密钥而不泄露密钥本身。
     *
     * @param key 密钥字符串
     * @return 8 字符 hex 指纹，或 {@code "????"}（算法不可用时）
     */
    public String fingerprint(String key) {
        if (key == null || key.isEmpty()) {
            return "????";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(key.getBytes(StandardCharsets.UTF_8));
            return String.format("%02x%02x%02x%02x",
                    hash[0] & 0xFF, hash[1] & 0xFF, hash[2] & 0xFF, hash[3] & 0xFF);
        } catch (NoSuchAlgorithmException e) {
            return "????";
        }
    }

    /**
     * 是否为多机模式。
     *
     * @return true 表示多机模式
     */
    public boolean isMultiMode() {
        return multiMode;
    }

    /**
     * 密钥库文件路径（多机模式）。
     * <p>
     * 仅供同包写入器 {@link SigningKeyStoreWriter} 定位文件用；单机模式返回 null
     * （单密钥来自配置，改它需要重新部署，不是写文件能解决的事）。
     *
     * @return 路径，或 null（单机模式）
     */
    public String getKeyStorePath() {
        return keyStorePath == null ? null : keyStorePath.toString();
    }

    /**
     * 获取全局密钥指纹。
     *
     * @return 全局密钥的 8 字符 hex 指纹，或 {@code "none"}（无全局密钥时）
     */
    public String getGlobalKeyFingerprint() {
        if (globalKey == null || globalKey.isEmpty()) {
            return "none";
        }
        return fingerprint(globalKey);
    }
}
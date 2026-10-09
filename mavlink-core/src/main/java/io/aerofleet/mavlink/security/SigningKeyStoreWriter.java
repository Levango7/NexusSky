package io.aerofleet.mavlink.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MAVLink 签名密钥库<b>写入</b>器：按 sysid 轮换单机密钥，原子落盘。
 *
 * <p><b>为什么单独一个类</b>：{@link SigningKeyManager} 只读不写（按文件 mtime 热重载），
 * 此前的"轮换"只能由运维手工改 JSON——没校验、没原子性，改坏一半就是全机队验签失败。
 * 本类把它变成一条受控路径：先校验、再原子替换，坏输入<b>不会</b>碰到线上文件。
 *
 * <p><b>协议层诚实边界（读前须知）</b>：MAVLink v2 签名的 13 字节数据块是
 * {@code linkId(1) | timestamp(6) | signature(6)}——<b>没有 key id 字段</b>。
 * 所以密钥轮换只能是<b>硬切换</b>：不存在 API Key 那种"新旧并存宽限期"的协议表达。
 * 远端设备必须在同一维护窗口内换上新密钥，否则它们的帧会被 {@code UdpGateway}
 * 按验签失败丢弃。这不是实现取舍而是协议本身的性质，因此本类<b>不提供</b>
 * graceHours 之类参数——给了也做不到。
 *
 * <p><b>写入门禁</b>：
 * <ul>
 *   <li>非多机模式（未配 key-store-path）→ 拒绝：属性式单密钥改文件没有意义；</li>
 *   <li>密钥文件不是合法 JSON 对象 → 拒绝：不覆盖看不懂的文件；</li>
 *   <li>新密钥为空或超长 → 拒绝；</li>
 *   <li>落盘用"临时文件 + {@link StandardCopyOption#ATOMIC_MOVE}"；文件系统不支持原子移动时
 *       <b>明确报错</b>而非静默降级。</li>
 * </ul>
 *
 * <p>写成功后由 {@link SigningKeyManager#reloadIfNeeded()} 按 mtime 自动热更，<b>无需重启</b>。
 */
public class SigningKeyStoreWriter {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyStoreWriter.class);

    /** 新密钥长度上限：密钥 UTF-8 字节进 SHA-256，够长即可。 */
    public static final int MAX_KEY_LENGTH = 256;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 轮换结果——<b>不含任何密钥材料</b>，只有指纹。
     * （指纹可安全外泄，密钥不可；这是本类对外的唯一形状。）
     *
     * @param sysid           目标系统号
     * @param oldFingerprint 轮换前指纹，新建时为 {@code "none"}
     * @param newFingerprint 轮换后指纹
     * @param linkId          该 sysid 绑定的 linkId
     * @param path            密钥库文件路径
     * @param totalEntries    轮换后库内条目数
     */
    public record RotationResult(int sysid, String oldFingerprint, String newFingerprint,
                                 int linkId, String path, int totalEntries) {}

    /**
     * 轮换指定 sysid 的密钥。
     *
     * @param keyManager 只读管理器（判模式/取路径，并复用其指纹口径）
     * @param sysid      目标系统号（1..255）
     * @param newKey     新密钥（非空、≤ {@value #MAX_KEY_LENGTH} 字符）
     * @return 轮换结果（只含指纹）
     * @throws IOException              读写失败
     * @throws IllegalArgumentException 参数或状态不合法（调用方应转为 4xx / 409）
     */
    public RotationResult rotate(SigningKeyManager keyManager, int sysid, String newKey)
            throws IOException {
        if (keyManager == null) {
            throw new IllegalArgumentException("SigningKeyManager 未装配，无法轮换密钥");
        }
        if (!keyManager.isMultiMode()) {
            throw new IllegalArgumentException(
                    "当前非多机模式（未配置 mavlink.signing.key-store-path），无可轮换的密钥库文件");
        }
        String storePath = keyManager.getKeyStorePath();
        if (storePath == null || storePath.isEmpty()) {
            throw new IllegalArgumentException("多机模式下未取到密钥库路径，拒绝轮换");
        }
        if (sysid < 1 || sysid > 255) {
            throw new IllegalArgumentException("sysid 必须在 1..255，收到: " + sysid);
        }
        if (newKey == null || newKey.isEmpty()) {
            throw new IllegalArgumentException("新密钥不能为空");
        }
        if (newKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "新密钥长度不能超过 " + MAX_KEY_LENGTH + " 字符，收到: " + newKey.length());
        }

        Path store = Path.of(storePath);
        if (!Files.exists(store)) {
            throw new IllegalArgumentException("密钥库文件不存在: " + store);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(Files.readAllBytes(store));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 内容级问题（不是合法 JSON）→ 拒绝覆盖，语义是"请求/状态不合法"(409)，
            // 不是"服务器 IO 故障"(500)。这两者可观测性完全不同，别混成一个 catch。
            throw new IllegalArgumentException(
                    "密钥库文件不是合法 JSON，拒绝覆盖: " + store + " (" + e.getOriginalMessage() + ")", e);
        }
        if (root == null || !root.isObject()) {
            // 不覆盖看不懂的文件：宁可拒绝，也不要制造一个半合法密钥库
            throw new IllegalArgumentException("密钥库文件不是 JSON 对象，拒绝覆盖: " + store);
        }
        JsonNode sysids = root.get("sysids");
        if (sysids != null && !sysids.isObject()) {
            throw new IllegalArgumentException("密钥库 sysids 段不是对象，拒绝覆盖: " + store);
        }

        String sysidKey = String.valueOf(sysid);

        // 旧指纹（新建则为 none）
        String oldFingerprint = "none";
        if (sysids != null && sysids.has(sysidKey)) {
            JsonNode oldKey = sysids.get(sysidKey).get("key");
            if (oldKey != null && !oldKey.asText().isEmpty()) {
                oldFingerprint = fingerprint(oldKey.asText());
            }
        }

        // 保留既有 linkId；缺省按仓内口径 sysid & 0xFF
        int linkId = sysid & 0xFF;
        if (sysids != null && sysids.get(sysidKey) != null
                && sysids.get(sysidKey).has("linkId")) {
            linkId = sysids.get(sysidKey).get("linkId").asInt(linkId);
        }

        // 深拷贝为可变 Map，只改目标条目：不写丢 operator 手工加的其它字段
        Map<String, Object> mutable = new LinkedHashMap<>();
        root.fields().forEachRemaining(
                e -> mutable.put(e.getKey(), objectMapper.convertValue(e.getValue(), Object.class)));
        @SuppressWarnings("unchecked")
        Map<String, Object> sysidsMap =
                (Map<String, Object>) mutable.computeIfAbsent("sysids", k -> new LinkedHashMap<>());
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("key", newKey);
        entry.put("linkId", linkId);
        sysidsMap.put(sysidKey, entry);

        writeAtomically(store, objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(mutable));

        String newFingerprint = fingerprint(newKey);
        log.info("MAVLink 签名密钥已轮换: path={} sysid={} linkId={} {} -> {}",
                store, sysid, linkId, oldFingerprint, newFingerprint);
        return new RotationResult(sysid, oldFingerprint, newFingerprint, linkId,
                store.toString(), sysidsMap.size());
    }

    /**
     * 原子写：临时文件 + ATOMIC_MOVE。
     * <p>
     * 不支持原子移动的文件系统上<b>抛错</b>而不降级——密钥库写坏一半的代价是
     * 整个机队验签失败，远比"轮换失败"严重。
     */
    private void writeAtomically(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".rotate-tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.deleteIfExists(tmp);
                throw new IOException(
                        "文件系统不支持原子替换，拒绝降级写入（避免半写坏的密钥库）: " + target, e);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /** 指纹口径与 {@link SigningKeyManager#fingerprint(String)} 一致：SHA-256 前 4 字节 hex。 */
    public static String fingerprint(String key) {
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
}

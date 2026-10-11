package io.aerofleet.mavlink.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MAVLink v2 消息签名器：与官方签名规范逐字节对齐（sha256_48）。
 * <p>
 * 官方签名块共 <b>13 字节</b>，追加在 CRC 之后：
 * <pre>
 *   [0]      linkId        1 字节
 *   [1..6]   timestamp     6 字节 **小端** 48 位，单位 10 微秒、纪元 2015-01-01T00:00:00Z
 *   [7..12]  signature     6 字节 = SHA-256(secretKey + frameBytes + 上述 7 字节) 的前 6 字节
 * </pre>
 * 其中 {@code frameBytes} 是帧头（STX）到 CRC 的全部字节（不含签名块）——即签名覆盖
 * STX 本身及其后的 LEN/INC/COMPAT/SEQ/SYSID/COMPID/MSGID + payload + CRC，与 pymavlink 的
 * {@code sign_packet()} 同一输入序列。
 * <p>
 * <b>此前的实现为什么是错的</b>：旧版用 HMAC-SHA256 截取前 8 字节、时间戳按大端写入，
 * 签名块共 15 字节。哈希构造（HMAC 的密钥参与方式）与字段宽度/字节序都不同，
 * 与 PX4/ArduPilot/pymavlink 混流时既验不过签名、又会因帧长差 2 字节而错帧。
 * <p>
 * 与独立参考实现的等价性由 {@code MavlinkSigningVectorTest} 的已知答案向量证明，
 * 向量由 {@code scripts/mavlink-signing-vectors.py}（pymavlink 打包+签名）生成。
 */
public class MavlinkSigner {

    /** 官方 sha256_48：取 SHA-256 摘要的前 6 字节作为签名。 */
    public static final int SIGNATURE_LENGTH = 6;

    /** 签名块总长：linkId(1) + timestamp(6) + signature(6)。 */
    public static final int SIGNATURE_BLOCK_LENGTH = 13;

    /** 签名时间戳的时间戳单位：10 微秒。 */
    public static final long TICK_MICROSECONDS = 10;

    /** 官方签名时间戳纪元：2015-01-01T00:00:00Z（Unix 毫秒）。 */
    public static final long EPOCH_2015_MS = 1_420_070_400_000L;

    /** 48 位时间戳上限。 */
    public static final long MAX_TIMESTAMP_48 = (1L << 48) - 1;

    /** 一毫秒的 tick 数（1ms = 1000µs = 100 × 10µs）。 */
    private static final long TICKS_PER_MILLISECOND = 1_000 / TICK_MICROSECONDS;

    /** 新流可接受的最大落后量：60 秒（官方 6,000,000 个 10 微秒 tick）。 */
    public static final long REPLAY_WINDOW_TICKS = 6_000_000L;

    private final boolean enabled;
    private final byte[] secretKeyBytes;
    private final int linkId;
    private final boolean rejectUnsigned;

    /**
     * 每个逻辑流最后使用的签名时间戳。
     *
     * <p><b>为什么需要它</b>：官方规范要求「每发一帧，timestamp 比上一帧多 1」，
     * 而不是「取当前墙上时间」。旧实现直接调 {@link #currentSigningTimestamp()}，
     * 帧间隔小于 10µs 分辨率（1ms = 100 tick）时会拿到相同时间戳，接收端按
     * 严格递增判定为重放，链路遥测被大面积丢弃。
     *
     * <p>规范对此有明确预期：「the timestamp may get ahead of GMT time if the
     * packet rate averages more than 100,000 packets per second」——高帧率下
     * 时间戳<b>应该</b>跑在墙前，这是协议允许且预期的行为。
     *
     * <p>key 用 {@link TimestampTracker.StreamKey}(linkId, sysid, compid) 三元组，
     * 与接收端的流划分同一口径：不同 sysid/compid 的流互不干扰。
     */
    private final ConcurrentHashMap<TimestampTracker.StreamKey, Long> lastTimestamps =
            new ConcurrentHashMap<>();

    /**
     * 通过配置构造签名器。
     *
     * @param config 签名配置（包含 enabled、secretKey、rejectUnsigned）
     */
    public MavlinkSigner(MavlinkSignatureConfig config) {
        this.enabled = config.isEnabled();
        if (this.enabled && (config.getSecretKey() == null || config.getSecretKey().isEmpty())) {
            throw new IllegalArgumentException("MAVLink signing enabled but secret key is empty");
        }
        this.secretKeyBytes = toKeyBytes(config.getSecretKey());
        this.linkId = 0;
        this.rejectUnsigned = config.isRejectUnsigned();
    }

    /**
     * 直接指定参数构造签名器（便于测试和手动使用）。
     *
     * @param enabled   是否启用签名
     * @param secretKey 签名口令字符串
     */
    public MavlinkSigner(boolean enabled, String secretKey) {
        this(enabled, secretKey, 0, true);
    }

    /**
     * 全参数构造器。
     *
     * @param enabled        是否启用签名
     * @param secretKey      签名口令字符串
     * @param linkId         默认链路 ID
     * @param rejectUnsigned 是否拒绝未签名消息
     */
    public MavlinkSigner(boolean enabled, String secretKey, int linkId, boolean rejectUnsigned) {
        this.enabled = enabled;
        if (this.enabled && (secretKey == null || secretKey.isEmpty())) {
            throw new IllegalArgumentException("MAVLink signing enabled but secret key is empty");
        }
        this.secretKeyBytes = toKeyBytes(secretKey);
        this.linkId = linkId;
        this.rejectUnsigned = rejectUnsigned;
    }

    private static byte[] toKeyBytes(String secretKey) {
        return secretKey == null ? new byte[0] : secretKey.getBytes(StandardCharsets.UTF_8);
    }

    /** 签名是否启用。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 默认链路 ID。 */
    public int getLinkId() {
        return linkId;
    }

    /** 是否拒绝未签名消息（fail-closed 默认 true）。 */
    public boolean isRejectUnsigned() {
        return rejectUnsigned;
    }

    /**
     * 取该逻辑流下一个可用的签名时间戳。
     *
     * <p>口径：{@code max(当前墙钟, 上一帧 + 1)}。
     * <ul>
     *   <li>常规帧率（间隔 > 10µs）=> 等于墙钟，行为与旧实现一致；</li>
     *   <li>高帧率/同一 tick 内多帧 => 自动 +1 递增，正是规范描述的
     *       "timestamp may get ahead of GMT time"。</li>
     * </ul>
     *
     * <p><b>为什么用 max 而不是纯自增</b>：进程重启后自增计数器归零，若墙钟
     * 已前进，纯自增会发出<b>远小于</b>上一会话的时间戳，被对端判为重放。
     * 取 max 让"新会话从墙钟起步"，与规范「startup 取 clock 与 stored 的较大者」
     * 同向（我们不持久化 stored 项，故只有 clock 一侧）。
     *
     * @param linkId     链路 ID
     * @param sysid      源系统 ID
     * @param componentId 源组件 ID
     * @return 单调递增的时间戳（10µs 单位）
     */
    public long nextTimestamp(int linkId, int sysid, int componentId) {
        TimestampTracker.StreamKey key =
                new TimestampTracker.StreamKey(linkId, sysid, componentId);
        long now = currentSigningTimestamp();
        Long last = lastTimestamps.get(key);
        long next = (last == null) ? now : Math.max(now, last + 1);
        if (next > MAX_TIMESTAMP_48) {
            // 48 位耗尽（ pract不可能，但避免静默回绕）
            throw new IllegalStateException(
                    "MAVLink signing timestamp exhausted 48-bit space for stream " + key);
        }
        lastTimestamps.put(key, next);
        return next;
    }

    /** 该流当前最后使用的时间戳（null=未发过）；供测试与观测。 */
    public Long lastTimestamp(int linkId, int sysid, int componentId) {
        return lastTimestamps.get(
                new TimestampTracker.StreamKey(linkId, sysid, componentId));
    }

    /** 已跟踪的流数量（测试用）。 */
    public int trackedStreamCount() {
        return lastTimestamps.size();
    }

    // ==================== 签名与验证 ====================

    /**
     * 计算官方 6 字节签名（sha256_48）。
     *
     * @param frameBytes 帧头至 CRC 的全部字节（不含签名块）
     * @param linkId     链路 ID
     * @param timestamp  签名时间戳（10 微秒 tick，见 {@link #currentSigningTimestamp()}）
     * @return 6 字节签名；签名未启用时返回 null
     */
    public byte[] sign(byte[] frameBytes, int linkId, long timestamp) {
        if (!enabled) {
            return null;
        }
        byte[] input = buildSignInput(frameBytes, linkId, timestamp);
        return sha256_48(secretKeyBytes, input);
    }

    /**
     * 验证签名：以恒定时间比对重算结果（{@link MessageDigest#isEqual}）。
     *
     * @param frameBytes 帧头至 CRC 的全部字节（不含签名块）
     * @param linkId     链路 ID
     * @param timestamp  签名时间戳（10 微秒 tick）
     * @param signature  待验证签名，长度必须为 6
     * @return true 表示通过；签名未启用时也返回 true；signature 为 null 或长度≠6 时 false
     */
    public boolean verify(byte[] frameBytes, int linkId, long timestamp, byte[] signature) {
        if (!enabled) {
            return true;
        }
        if (signature == null || signature.length != SIGNATURE_LENGTH) {
            return false;
        }
        byte[] expected = sign(frameBytes, linkId, timestamp);
        return MessageDigest.isEqual(expected, signature);
    }

    /**
     * 官方签名输入序列：{@code frameBytes + linkId(1B) + timestamp(6B 小端)}。
     * 口令不参与拼接位置，而是在摘要计算时前置（见 {@link #sha256_48}）。
     */
    static byte[] buildSignInput(byte[] frameBytes, int linkId, long timestamp) {
        byte[] input = new byte[frameBytes.length + 1 + 6];
        System.arraycopy(frameBytes, 0, input, 0, frameBytes.length);
        int offset = frameBytes.length;
        input[offset] = (byte) (linkId & 0xFF);
        writeTimestampLittleEndian(timestamp, input, offset + 1);
        return input;
    }

    /**
     * 计算 sha256_48：{@code SHA-256(secretKey + data)} 的前 6 字节。
     * <p>
     * 注意是"口令前置后整体求摘要"，不是 HMAC——HMAC 有两次密钥混合与分块填充，
     * 结果必然与官方不同，这一点就是旧实现无法互通的根因。
     */
    static byte[] sha256_48(byte[] secretKey, byte[] data) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(secretKey);
            sha.update(data);
            return Arrays.copyOf(sha.digest(), SIGNATURE_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ==================== 时间戳：单位与字节序的唯一出处 ====================

    /**
     * 当前签名时间戳：自 2015-01-01T00:00:00Z 起、以 10 微秒为单位的 48 位计数。
     * <p>
     * 由毫秒时钟换算，因此实际粒度是 1 毫秒（100 tick）。官方只要求同一条流上严格
     * 递增（{@code TimestampTracker} 负责），粒度粗不会让对端拒收，但连续毫秒内多发
     * 几帧时会出现相同 tick——调用方若要在同一毫秒内发多帧，应自行递增并写入
     * （pymavlink 每发一帧 {@code timestamp += 1}）。
     *
     * @return 48 位范围内的 tick 值；系统时钟早于 2015 纪元时返回 0
     */
    public static long currentSigningTimestamp() {
        long millisSinceEpoch = System.currentTimeMillis() - EPOCH_2015_MS;
        if (millisSinceEpoch <= 0) {
            return 0;
        }
        return Math.min(millisSinceEpoch * TICKS_PER_MILLISECOND, MAX_TIMESTAMP_48);
    }

    /** 把 48 位时间戳按**小端**写入 buf 的 offset 起 6 个字节。 */
    public static void writeTimestampLittleEndian(long timestamp, byte[] buf, int offset) {
        long value = timestamp & MAX_TIMESTAMP_48;
        for (int i = 0; i < 6; i++) {
            buf[offset + i] = (byte) ((value >> (8 * i)) & 0xFF);
        }
    }

    /** 从 buf 的 offset 起读 6 字节**小端** 48 位时间戳。 */
    public static long readTimestampLittleEndian(byte[] buf, int offset) {
        long value = 0;
        for (int i = 0; i < 6; i++) {
            value |= (buf[offset + i] & 0xFFL) << (8 * i);
        }
        return value;
    }
}

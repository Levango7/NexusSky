package io.aerofleet.mavlink.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * MAVLink 消息签名器：使用 HMAC-SHA256 对消息内容签名与验证。
 * <p>
 * 支持两种签名模式：
 * <ul>
 *   <li><b>标准协议签名</b>（推荐）：签名输入 = 帧头至 CRC 全部字节 + linkId(1B) + timestamp(6B BE)，
 *       HMAC-SHA256 截取前 8 字节。符合 MAVLink v2 签名规范。</li>
 *   <li><b>简化签名</b>（已废弃）：签名输入 = msgId（4 字节小端）+ payload bytes，
 *       HMAC-SHA256 截取前 8 字节。仅用于向后兼容。</li>
 * </ul>
 * 验证流程：重新计算签名并与传入签名用 {@link MessageDigest#isEqual} 恒定时间比对。
 */
public class MavlinkSigner {

    public static final int SIGNATURE_LENGTH = 8;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final boolean enabled;
    private final byte[] secretKeyBytes;
    private final int linkId;
    private final boolean rejectUnsigned;

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
        this.secretKeyBytes = config.getSecretKey() == null
                ? new byte[0]
                : config.getSecretKey().getBytes(StandardCharsets.UTF_8);
        this.linkId = 0;
        this.rejectUnsigned = config.isRejectUnsigned();
    }

    /**
     * 直接指定参数构造签名器（便于测试和手动使用）。
     *
     * @param enabled   是否启用签名
     * @param secretKey HMAC 密钥字符串
     */
    public MavlinkSigner(boolean enabled, String secretKey) {
        this(enabled, secretKey, 0, true);
    }

    /**
     * 全参数构造器。
     *
     * @param enabled        是否启用签名
     * @param secretKey      HMAC 密钥字符串
     * @param linkId         链路 ID
     * @param rejectUnsigned 是否拒绝未签名消息
     */
    public MavlinkSigner(boolean enabled, String secretKey, int linkId, boolean rejectUnsigned) {
        this.enabled = enabled;
        if (this.enabled && (secretKey == null || secretKey.isEmpty())) {
            throw new IllegalArgumentException("MAVLink signing enabled but secret key is empty");
        }
        this.secretKeyBytes = secretKey == null
                ? new byte[0]
                : secretKey.getBytes(StandardCharsets.UTF_8);
        this.linkId = linkId;
        this.rejectUnsigned = rejectUnsigned;
    }

    /**
     * 判断签名是否启用。
     *
     * @return true 表示签名已启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /** 获取链路 ID。 */
    public int getLinkId() {
        return linkId;
    }

    /** 是否拒绝未签名消息（fail-closed 默认 true）。 */
    public boolean isRejectUnsigned() {
        return rejectUnsigned;
    }

    // ==================== 标准协议签名（MAVLink v2 signing） ====================

    /**
     * 标准协议签名：对帧字节计算 8 字节 HMAC-SHA256 签名。
     * <p>
     * 签名输入 = frameBytes（帧头至 CRC 全部字节）+ linkId(1B) + timestamp(6B 大端)。
     * HMAC-SHA256 截取前 8 字节作为签名。
     *
     * @param frameBytes 帧头至 CRC 的全部字节（不含签名数据）
     * @param linkId     链路 ID
     * @param timestamp  签名时间戳（10ms tick）
     * @return 8 字节签名；若签名未启用则返回 null
     */
    public byte[] sign(byte[] frameBytes, int linkId, long timestamp) {
        if (!enabled) {
            return null;
        }
        byte[] input = buildStandardSignInput(frameBytes, linkId, timestamp);
        byte[] hmac = computeHmac(input);
        return Arrays.copyOf(hmac, SIGNATURE_LENGTH);
    }

    /**
     * 标准协议签名验证：重算签名并与传入签名用恒定时间比对。
     * <p>
     * 使用 {@link MessageDigest#isEqual} 进行恒定时间比较，防止时序攻击（DFX 4.3）。
     *
     * @param frameBytes 帧头至 CRC 的全部字节（不含签名数据）
     * @param linkId     链路 ID
     * @param timestamp  签名时间戳（10ms tick）
     * @param signature  待验证的 8 字节签名
     * @return true 表示签名验证通过；签名未启用时也返回 true；
     *         signature=null 或长度≠8 时返回 false
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
     * 构造标准协议签名输入数据：frameBytes + linkId(1B) + timestamp(6B 大端)。
     *
     * @param frameBytes 帧头至 CRC 的全部字节
     * @param linkId     链路 ID
     * @param timestamp  签名时间戳
     * @return 签名输入字节序列
     */
    private byte[] buildStandardSignInput(byte[] frameBytes, int linkId, long timestamp) {
        byte[] input = new byte[frameBytes.length + 7];
        System.arraycopy(frameBytes, 0, input, 0, frameBytes.length);
        int offset = frameBytes.length;
        // linkId: 1 byte
        input[offset] = (byte) (linkId & 0xFF);
        // timestamp: 6 bytes big-endian
        input[offset + 1] = (byte) ((timestamp >> 40) & 0xFF);
        input[offset + 2] = (byte) ((timestamp >> 32) & 0xFF);
        input[offset + 3] = (byte) ((timestamp >> 24) & 0xFF);
        input[offset + 4] = (byte) ((timestamp >> 16) & 0xFF);
        input[offset + 5] = (byte) ((timestamp >> 8) & 0xFF);
        input[offset + 6] = (byte) (timestamp & 0xFF);
        return input;
    }

    // ==================== 简化签名（已废弃，向后兼容） ====================

    /**
     * 对消息计算 8 字节 HMAC-SHA256 签名（简化模式）。
     * <p>
     * 签名输入 = msgId（4 字节小端）+ payload bytes。
     *
     * @param msgId   MAVLink 消息 ID
     * @param payload 消息 payload 字节
     * @return 8 字节签名；若签名未启用则返回 null
     * @deprecated 使用标准协议签名 {@link #sign(byte[], int, long)} 替代
     */
    @Deprecated
    public byte[] sign(int msgId, byte[] payload) {
        if (!enabled) {
            return null;
        }
        byte[] input = buildSignInput(msgId, payload);
        byte[] hmac = computeHmac(input);
        return Arrays.copyOf(hmac, SIGNATURE_LENGTH);
    }

    /**
     * 验证消息签名是否正确（简化模式）。
     * <p>
     * 重新计算签名并与传入签名逐字节比对。
     *
     * @param msgId     MAVLink 消息 ID
     * @param payload   消息 payload 字节
     * @param signature 待验证的 8 字节签名
     * @return true 表示签名验证通过；签名未启用时也返回 true
     * @deprecated 使用标准协议签名验证 {@link #verify(byte[], int, long, byte[])} 替代
     */
    @Deprecated
    public boolean verify(int msgId, byte[] payload, byte[] signature) {
        if (!enabled) {
            return true;
        }
        if (signature == null) {
            throw new IllegalStateException("签名验证启用但消息不包含签名数据");
        }
        if (signature.length != SIGNATURE_LENGTH) {
            return false;
        }
        byte[] expected = sign(msgId, payload);
        return MessageDigest.isEqual(expected, signature);
    }

    /**
     * 构造简化签名输入数据：msgId（4 字节小端）+ payload。
     */
    private byte[] buildSignInput(int msgId, byte[] payload) {
        byte[] input = new byte[4 + payload.length];
        input[0] = (byte) (msgId & 0xFF);
        input[1] = (byte) ((msgId >> 8) & 0xFF);
        input[2] = (byte) ((msgId >> 16) & 0xFF);
        input[3] = (byte) ((msgId >> 24) & 0xFF);
        System.arraycopy(payload, 0, input, 4, payload.length);
        return input;
    }

    /**
     * 计算 HMAC-SHA256。
     */
    private byte[] computeHmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secretKeyBytes, HMAC_ALGORITHM);
            mac.init(keySpec);
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 not available", e);
        }
    }
}
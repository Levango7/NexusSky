package io.aerofleet.mavlink.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * MAVLink 消息签名器：使用 HMAC-SHA256 对消息内容签名与验证。
 * <p>
 * 签名计算流程：
 * <ol>
 *   <li>构造输入数据：msgId（4 字节小端）+ payload bytes</li>
 *   <li>使用 secretKey 对输入数据计算 HMAC-SHA256</li>
 *   <li>截取 HMAC 结果前 8 字节作为签名</li>
 * </ol>
 * 验证流程：重新计算签名并与传入签名逐字节比对。
 */
public class MavlinkSigner {

    public static final int SIGNATURE_LENGTH = 8;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final boolean enabled;
    private final byte[] secretKeyBytes;

    /**
     * 通过配置构造签名器。
     *
     * @param config 签名配置（包含 enabled 和 secretKey）
     */
    public MavlinkSigner(MavlinkSignatureConfig config) {
        this.enabled = config.isEnabled();
        this.secretKeyBytes = config.getSecretKey().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 直接指定参数构造签名器（便于测试和手动使用）。
     *
     * @param enabled   是否启用签名
     * @param secretKey HMAC 密钥字符串
     */
    public MavlinkSigner(boolean enabled, String secretKey) {
        this.enabled = enabled;
        this.secretKeyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 判断签名是否启用。
     *
     * @return true 表示签名已启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 对消息计算 8 字节 HMAC-SHA256 签名。
     * <p>
     * 签名输入 = msgId（4 字节小端）+ payload bytes。
     *
     * @param msgId   MAVLink 消息 ID
     * @param payload 消息 payload 字节
     * @return 8 字节签名；若签名未启用则返回 null
     */
    public byte[] sign(int msgId, byte[] payload) {
        if (!enabled) {
            return null;
        }
        byte[] input = buildSignInput(msgId, payload);
        byte[] hmac = computeHmac(input);
        return Arrays.copyOf(hmac, SIGNATURE_LENGTH);
    }

    /**
     * 验证消息签名是否正确。
     * <p>
     * 重新计算签名并与传入签名逐字节比对。
     *
     * @param msgId     MAVLink 消息 ID
     * @param payload   消息 payload 字节
     * @param signature 待验证的 8 字节签名
     * @return true 表示签名验证通过；签名未启用时也返回 true
     */
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
        return Arrays.equals(expected, signature);
    }

    /**
     * 构造签名输入数据：msgId（4 字节小端）+ payload。
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
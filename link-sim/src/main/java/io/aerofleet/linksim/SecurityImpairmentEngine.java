package io.aerofleet.linksim;

import java.util.Random;

/**
 * 安全损伤引擎：模拟签名篡改和签名剥离攻击。
 * <p>
 * 根据 {@link LinkProfile} 的安全画像类型，对通过链路的 MAVLink 帧施加不同的安全损伤：
 * <ul>
 *   <li>{@link LinkProfile#SIGNING_TAMPER}：按 {@code tamperRate} 概率篡改 payload 区域</li>
 *   <li>{@link LinkProfile#SIGNING_UNSIGNED}：检测签名帧并剥离签名（清除 INC bit 0 并截断）</li>
 * </ul>
 * 统计计数器记录各类操作的累计次数，用于测试报告。
 */
final class SecurityImpairmentEngine {

    /** 安全动作枚举 */
    enum SecurityAction {
        /** 正常转发 */
        FORWARD,
        /** 篡改 payload */
        TAMPER,
        /** 剥离签名 */
        STRIP_SIGNATURE
    }

    private final LinkProfile profile;
    private final double tamperRate;
    private final Random random = new Random();

    private long forwarded = 0;
    private long tampered = 0;
    private long stripped = 0;

    /**
     * 构造安全损伤引擎。
     *
     * @param profile    链路画像（必须为安全画像 SIGNING_TAMPER 或 SIGNING_UNSIGNED）
     * @param tamperRate 篡改概率（0.0~1.0，仅对 SIGNING_TAMPER 画像有效）
     */
    SecurityImpairmentEngine(LinkProfile profile, double tamperRate) {
        this.profile = profile;
        this.tamperRate = tamperRate;
    }

    /**
     * 对帧字节做出安全裁决。
     * <p>
     * SIGNING_TAMPER 画像：按 tamperRate 概率返回 TAMPER 或 FORWARD。
     * SIGNING_UNSIGNED 画像：检测 INC bit 0 置位返回 STRIP_SIGNATURE，否则 FORWARD。
     *
     * @param frameBytes MAVLink v2 帧字节
     * @return 安全动作裁决
     */
    synchronized SecurityAction verdict(byte[] frameBytes) {
        if (profile == LinkProfile.SIGNING_TAMPER) {
            if (random.nextDouble() < tamperRate) {
                tampered++;
                return SecurityAction.TAMPER;
            }
            forwarded++;
            return SecurityAction.FORWARD;
        }

        if (profile == LinkProfile.SIGNING_UNSIGNED) {
            // 检测 INC bit 0 是否置位（签名帧）
            if (frameBytes != null && frameBytes.length >= 3 && (frameBytes[2] & 0x01) != 0) {
                stripped++;
                return SecurityAction.STRIP_SIGNATURE;
            }
            forwarded++;
            return SecurityAction.FORWARD;
        }

        // 非安全画像，正常转发
        forwarded++;
        return SecurityAction.FORWARD;
    }

    /**
     * 输出统计信息。
     *
     * @return 格式 "fwd=<n> tampered=<n> stripped=<n>"
     */
    synchronized String stats() {
        return "fwd=" + forwarded + " tampered=" + tampered + " stripped=" + stripped;
    }

    /**
     * 篡改帧的 payload 区域。
     * <p>
     * 仅篡改 payload 区域（偏移 10 ~ 10+LEN-1），随机翻转 1~3 个 bit。
     * 不修改 STX/LEN/INC/COMPAT/SEQ/SID/CID/MSGID/CRC。
     *
     * @param data 原始帧字节
     * @return 篡改后的帧字节（新数组，不修改原数组）
     */
    static byte[] applyTamper(byte[] data) {
        if (data == null || data.length < 12) {
            return data;
        }

        byte[] result = data.clone();
        int payloadLen = result[1] & 0xFF;
        if (payloadLen == 0) {
            return result; // 无 payload 可篡改
        }

        int payloadStart = 10;

        // 翻转 1~3 个互不重复的 bit：重复翻转同一 bit 会相互抵消，导致篡改后与原文相同
        Random rng = new Random();
        int bitCount = 1 + rng.nextInt(3);
        int totalBits = payloadLen * 8;
        boolean[] used = new boolean[totalBits];
        for (int i = 0; i < bitCount; i++) {
            int pos;
            do {
                pos = rng.nextInt(totalBits);
            } while (used[pos]);
            used[pos] = true;
            result[payloadStart + pos / 8] ^= (1 << (pos % 8));
        }

        return result;
    }

    /**
     * 剥离帧的签名数据。
     * <p>
     * 清除 INC bit 0（表示不再为签名帧），并截断至 12+LEN 字节（移除签名数据）。
     *
     * @param data 原始帧字节（签名帧）
     * @return 剥离签名后的帧字节（新数组）
     */
    static byte[] applyStripSignature(byte[] data) {
        if (data == null || data.length < 12) {
            return data;
        }

        int payloadLen = data[1] & 0xFF;
        int strippedLen = 12 + payloadLen;

        byte[] result = new byte[strippedLen];
        System.arraycopy(data, 0, result, 0, strippedLen);

        // 清除 INC bit 0
        result[2] &= ~0x01;

        return result;
    }
}
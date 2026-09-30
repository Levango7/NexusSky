package io.aerofleet.linksim;

import io.aerofleet.mavlink.MavlinkCrc;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkMessageInfo;
import io.aerofleet.mavlink.security.MavlinkSigner;

import java.util.Random;

/**
 * 安全损伤引擎：模拟签名篡改和签名剥离攻击。
 * <p>
 * 根据 {@link LinkProfile} 的安全画像类型，对通过链路的 MAVLink 帧施加不同的安全损伤：
 * <ul>
 *   <li>{@link LinkProfile#SIGNING_TAMPER}：按 {@code tamperRate} 概率篡改 v2 签名帧的
 *       <b>签名块</b>（保持 CRC 有效，接收方才会走到验签失败这一步）</li>
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
     * 篡改签名块：只翻转签名区（13 字节块里最后 6 字节）的 1~3 个比特。
     * <p>
     * <b>为什么不能改 payload</b>：签名不在 CRC 覆盖范围内，payload 在。改 payload 会让
     * 接收方在解析阶段就因 CRC 不符整帧丢弃（{@code MavlinkParser} 只累加 crcErrors，
     * 静默），永远走不到验签那一步——于是这个"签名篡改"画像模拟不出它声称的攻击，
     * 依赖它的端到端断言（backend 日志出现"签名验证失败"）永远不可能成立。
     * 只改签名字节则 CRC 仍然成立，接收方会进入验签并失败，那才是"签名被篡改"。
     * <p>
     * 未签名帧没有签名块可篡改：原样返回，不误伤成 CRC 破坏帧。
     *
     * @param data 原始帧字节
     * @return 篡改后的帧字节（新数组，不修改原数组）；非 v2 签名帧原样返回
     */
    static byte[] applyTamper(byte[] data) {
        if (data == null || data.length < 12) {
            return data;
        }

        int payloadLen = data[1] & 0xFF;
        int sigBlockStart = 12 + payloadLen;                       // linkId(1)+timestamp(6)+signature(6)
        int signatureStart = sigBlockStart + 7;                    // 只动最后 6 字节签名
        boolean isSignedV2 = (data[0] & 0xFF) == MavlinkFrame.STX_V2
                && (data[2] & 0x01) != 0
                && data.length >= sigBlockStart + MavlinkFrame.SIGNATURE_DATA_LENGTH;
        if (!isSignedV2) {
            return data;
        }

        byte[] result = data.clone();
        // 翻转 1~3 个互不重复的 bit：重复翻转同一 bit 会相互抵消，导致篡改后与原文相同
        Random rng = new Random();
        int bitCount = 1 + rng.nextInt(3);
        int totalBits = MavlinkSigner.SIGNATURE_LENGTH * 8;
        boolean[] used = new boolean[totalBits];
        for (int i = 0; i < bitCount; i++) {
            int pos;
            do {
                pos = rng.nextInt(totalBits);
            } while (used[pos]);
            used[pos] = true;
            result[signatureStart + pos / 8] ^= (byte) (1 << (pos % 8));
        }

        return result;
    }

    /**
     * 剥离帧的签名数据，产出一帧<b>合法的</b>未签名帧。
     * <p>
     * 关键点：INC bit 0 在 CRC 覆盖范围内（CRC 覆盖 LEN/INC/COMPAT/SEQ/SYSID/COMPID/MSGID
     * + payload + crc_extra）。所以"截掉签名块 + 清 INC bit 0"必须<b>重算 CRC</b>——
     * 否则产出的是 CRC 坏帧，接收方在解析阶段就丢弃（{@code MavlinkParser} 只加 crcErrors），
     * 永远走不到"未签名消息被拒"那一步，本画像也就模拟不出它声称的攻击（实测如此：
     * 中继报 {@code stripped=806} 而 backend 零条拒绝日志）。
     * <p>
     * msgId 不在字典里时拿不到 crc_extra，宁可原样转发也不发一帧坏数据。
     *
     * @param data 原始帧字节（签名帧）
     * @return 剥离签名并重算 CRC 后的帧字节（新数组）；无法安全剥离时原样返回
     */
    static byte[] applyStripSignature(byte[] data) {
        if (data == null || data.length < 12) {
            return data;
        }

        int payloadLen = data[1] & 0xFF;
        int strippedLen = 12 + payloadLen;
        boolean isSignedV2 = (data[0] & 0xFF) == MavlinkFrame.STX_V2
                && (data[2] & 0x01) != 0
                && data.length >= strippedLen + MavlinkFrame.SIGNATURE_DATA_LENGTH;
        if (!isSignedV2 || data.length < strippedLen) {
            return data;      // 未签名/长度不自洽：无签名可剥，原样返回（不改 CRC 也不截断）
        }
        int msgId = (data[7] & 0xFF) | ((data[8] & 0xFF) << 8) | ((data[9] & 0xFF) << 16);
        int crcExtra;
        try {
            crcExtra = MavlinkMessageInfo.crcExtraOf(msgId);
        } catch (RuntimeException unknownMsgId) {
            return data;
        }

        byte[] result = new byte[strippedLen];
        System.arraycopy(data, 0, result, 0, strippedLen);
        // 清除 INC bit 0（表示不再为签名帧）
        result[2] &= ~0x01;

        // 按官方覆盖范围重算 CRC：LEN..MSGID + payload + crc_extra（不含 STX，也不含签名块）
        int crc = MavlinkCrc.init();
        for (int i = 1; i <= 9; i++) {
            crc = MavlinkCrc.accumulate(crc, result[i]);
        }
        crc = MavlinkCrc.accumulate(crc, result, 10, payloadLen);
        crc = MavlinkCrc.accumulate(crc, crcExtra);
        result[10 + payloadLen] = (byte) (crc & 0xFF);
        result[11 + payloadLen] = (byte) ((crc >> 8) & 0xFF);

        return result;
    }
}
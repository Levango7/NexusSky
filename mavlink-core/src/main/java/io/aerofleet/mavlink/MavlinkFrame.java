package io.aerofleet.mavlink;

import io.aerofleet.mavlink.security.MavlinkSigner;

/**
 * MAVLink v2.0 帧（STX=0xFD）的不可变表示。
 * 线上布局：STX | LEN | INC | COMPAT | SEQ | SID | CID | MSGID(3B LE) | PAYLOAD | CRC(2B LE)。
 * 签名帧在 CRC 后追加官方 13 字节：LINK_ID(1B) | TIMESTAMP(6B 小端) | SIGNATURE(6B sha256_48)。
 * v1 帧（STX=0xFE，无 INC/COMPAT，MSGID 单字节）由 Parser 解析后统一为本表示。
 */
public final class MavlinkFrame {

    public static final int STX_V2 = 0xFD;
    public static final int STX_V1 = 0xFE;

    /** 官方签名块长度：linkId(1B) + timestamp(6B) + signature(6B) = 13B */
    public static final int SIGNATURE_DATA_LENGTH = 13;

    private final int payloadLength;
    private final int incompatibilityFlags;
    private final int compatibilityFlags;
    private final int sequence;
    private final int systemId;
    private final int componentId;
    private final int messageId;
    private final byte[] payload;
    private final int crc;

    // 签名帧扩展字段（非签名帧时 linkId=0, timestamp=0, signature=null）
    private final int linkId;
    private final long timestamp;
    private final byte[] signature;

    /**
     * 全参数构造器（含签名字段）。
     *
     * @param payloadLength       payload 长度
     * @param incompatibilityFlags 不兼容标志（bit 0 = 签名帧）
     * @param compatibilityFlags  兼容标志
     * @param sequence            序列号
     * @param systemId            系统 ID
     * @param componentId         组件 ID
     * @param messageId           消息 ID
     * @param payload             payload 字节
     * @param crc                 CRC-16/X.25
     * @param linkId              链路 ID（签名帧用，非签名帧为 0）
     * @param timestamp           签名时间戳（签名帧用，非签名帧为 0）
     * @param signature           6 字节 sha256_48 签名（签名帧用，非签名帧为 null）
     */
    public MavlinkFrame(int payloadLength, int incompatibilityFlags, int compatibilityFlags,
                        int sequence, int systemId, int componentId, int messageId,
                        byte[] payload, int crc,
                        int linkId, long timestamp, byte[] signature) {
        this.payloadLength = payloadLength;
        this.incompatibilityFlags = incompatibilityFlags;
        this.compatibilityFlags = compatibilityFlags;
        this.sequence = sequence;
        this.systemId = systemId;
        this.componentId = componentId;
        this.messageId = messageId;
        this.payload = payload;
        this.crc = crc;
        this.linkId = linkId;
        this.timestamp = timestamp;
        this.signature = signature;
    }

    /**
     * 向后兼容构造器（不含签名字段）。
     * 委托全参数构造器，签名相关字段取默认值。
     */
    public MavlinkFrame(int payloadLength, int incompatibilityFlags, int compatibilityFlags,
                        int sequence, int systemId, int componentId, int messageId,
                        byte[] payload, int crc) {
        this(payloadLength, incompatibilityFlags, compatibilityFlags,
                sequence, systemId, componentId, messageId,
                payload, crc,
                0, 0L, null);
    }

    /**
     * 构造发送帧（非签名）：按官方 finalize 算法计算完整 CRC（头字段 + payload + CRC_EXTRA）。
     */
    public static MavlinkFrame of(int systemId, int componentId, int sequence,
                                  int messageId, int crcExtra, byte[] payload) {
        if (payload != null && payload.length > 255) {
            throw new IllegalArgumentException("MAVLink payload exceeds 255 bytes: " + payload.length);
        }
        int crc = MavlinkCrc.init();
        crc = MavlinkCrc.accumulate(crc, payload.length);
        crc = MavlinkCrc.accumulate(crc, 0);
        crc = MavlinkCrc.accumulate(crc, 0);
        crc = MavlinkCrc.accumulate(crc, sequence);
        crc = MavlinkCrc.accumulate(crc, systemId);
        crc = MavlinkCrc.accumulate(crc, componentId);
        crc = MavlinkCrc.accumulate(crc, messageId & 0xFF);
        crc = MavlinkCrc.accumulate(crc, (messageId >> 8) & 0xFF);
        crc = MavlinkCrc.accumulate(crc, (messageId >> 16) & 0xFF);
        crc = MavlinkCrc.accumulate(crc, payload, 0, payload.length);
        crc = MavlinkCrc.accumulate(crc, crcExtra);
        return new MavlinkFrame(payload.length, 0, 0, sequence, systemId, componentId,
                messageId, payload, crc);
    }

    /**
     * 构造签名发送帧：CRC 计算与 {@link #of} 一致，INC=0x01。
     *
     * @param systemId    系统 ID
     * @param componentId 组件 ID
     * @param sequence    序列号
     * @param messageId   消息 ID
     * @param crcExtra    CRC_EXTRA 字节
     * @param payload     payload 字节
     * @param linkId      链路 ID
     * @param timestamp   签名时间戳（10 微秒 tick，自 2015-01-01；见 MavlinkSigner#currentSigningTimestamp）
     * @param signature   6 字节 sha256_48 签名
     * @return 签名帧实例
     */
    public static MavlinkFrame ofSigned(int systemId, int componentId, int sequence,
                                        int messageId, int crcExtra, byte[] payload,
                                        int linkId, long timestamp, byte[] signature) {
        if (payload != null && payload.length > 255) {
            throw new IllegalArgumentException("MAVLink payload exceeds 255 bytes: " + payload.length);
        }
        if (signature != null && signature.length != MavlinkSigner.SIGNATURE_LENGTH) {
            throw new IllegalArgumentException(
                    "Signature must be " + MavlinkSigner.SIGNATURE_LENGTH + " bytes, got: " + signature.length);
        }
        int crc = MavlinkCrc.init();
        crc = MavlinkCrc.accumulate(crc, payload.length);
        crc = MavlinkCrc.accumulate(crc, 0x01); // INC bit 0 置位
        crc = MavlinkCrc.accumulate(crc, 0);
        crc = MavlinkCrc.accumulate(crc, sequence);
        crc = MavlinkCrc.accumulate(crc, systemId);
        crc = MavlinkCrc.accumulate(crc, componentId);
        crc = MavlinkCrc.accumulate(crc, messageId & 0xFF);
        crc = MavlinkCrc.accumulate(crc, (messageId >> 8) & 0xFF);
        crc = MavlinkCrc.accumulate(crc, (messageId >> 16) & 0xFF);
        crc = MavlinkCrc.accumulate(crc, payload, 0, payload.length);
        crc = MavlinkCrc.accumulate(crc, crcExtra);
        return new MavlinkFrame(payload.length, 0x01, 0, sequence, systemId, componentId,
                messageId, payload, crc,
                linkId, timestamp, signature);
    }

    /**
     * 序列化为 MAVLink v2 线上字节。
     * <p>
     * 签名帧（INC bit 0 置位且 signature != null）：在 CRC 后追加 13 字节签名数据。
     * 非签名帧：输出与原有逻辑逐字节一致。
     *
     * @return v2 线上字节序列
     */
    public byte[] encodeV2() {
        int len = payload.length;
        boolean isSigned = (incompatibilityFlags & 0x01) != 0 && signature != null;
        int totalLen = isSigned ? (len + 12 + SIGNATURE_DATA_LENGTH) : (len + 12);
        byte[] buf = new byte[totalLen];
        buf[0] = (byte) STX_V2;
        buf[1] = (byte) len;
        buf[2] = (byte) incompatibilityFlags;
        buf[3] = (byte) compatibilityFlags;
        buf[4] = (byte) sequence;
        buf[5] = (byte) systemId;
        buf[6] = (byte) componentId;
        buf[7] = (byte) (messageId & 0xFF);
        buf[8] = (byte) ((messageId >> 8) & 0xFF);
        buf[9] = (byte) ((messageId >> 16) & 0xFF);
        System.arraycopy(payload, 0, buf, 10, len);
        buf[10 + len] = (byte) (crc & 0xFF);
        buf[11 + len] = (byte) ((crc >> 8) & 0xFF);

        if (isSigned) {
            int sigOffset = 12 + len;
            // linkId: 1 byte
            buf[sigOffset] = (byte) (linkId & 0xFF);
            // timestamp: 6 bytes little-endian（官方 48 位口径）
            MavlinkSigner.writeTimestampLittleEndian(timestamp, buf, sigOffset + 1);
            // signature: 6 bytes (sha256_48)
            System.arraycopy(signature, 0, buf, sigOffset + 7, MavlinkSigner.SIGNATURE_LENGTH);
        }
        return buf;
    }

    /**
     * 从原始字节解析 MAVLink v2 帧。
     * <p>
     * 解析流程：校验 STX=0xFD → 解析 LEN/INC/COMPAT/SEQ/SID/CID/MSGID/PAYLOAD/CRC →
     * 若 INC bit 0 置位且长度足够则解析签名数据（linkId + timestamp + signature）。
     * <p>
     * 注意：本方法只做结构解析，<b>不校验 CRC</b>（CRC 字段仅被读出存放）。
     * 需要一次性强校验时用 {@link #decodeV2Verified(byte[])}；
     * 生产流式接收路径用 {@link MavlinkParser}（解析时逐帧校验，坏帧自动跳过）。
     *
     * @param raw 原始字节序列
     * @return 解析后的 MavlinkFrame 实例
     * @throws IllegalArgumentException 如果 STX 不是 0xFD 或数据长度不足
     */
    public static MavlinkFrame decodeV2(byte[] raw) {
        if (raw == null || raw.length < 12) {
            throw new IllegalArgumentException("Frame too short for v2 header: " + (raw == null ? "null" : raw.length));
        }
        if ((raw[0] & 0xFF) != STX_V2) {
            throw new IllegalArgumentException("Invalid STX for v2 frame: 0x" + Integer.toHexString(raw[0] & 0xFF));
        }
        int len = raw[1] & 0xFF;
        int inc = raw[2] & 0xFF;
        int compat = raw[3] & 0xFF;
        int seq = raw[4] & 0xFF;
        int sid = raw[5] & 0xFF;
        int cid = raw[6] & 0xFF;
        int msgId = (raw[7] & 0xFF) | ((raw[8] & 0xFF) << 8) | ((raw[9] & 0xFF) << 16);

        int minLen = 12 + len;
        if (raw.length < minLen) {
            throw new IllegalArgumentException("Frame too short for declared LEN=" + len + ": " + raw.length);
        }

        byte[] payload = new byte[len];
        System.arraycopy(raw, 10, payload, 0, len);

        int crc = (raw[10 + len] & 0xFF) | ((raw[11 + len] & 0xFF) << 8);

        // 签名帧长度校验：INC bit 0 置位时必须有足够的签名数据
        if ((inc & 0x01) != 0 && raw.length < minLen + SIGNATURE_DATA_LENGTH) {
            throw new IllegalArgumentException(
                    "Signed frame too short: need " + (minLen + SIGNATURE_DATA_LENGTH) + " bytes, got " + raw.length);
        }

        // 解析签名数据（INC bit 0 置位且长度足够）
        int linkId = 0;
        long timestamp = 0L;
        byte[] signature = null;
        if ((inc & 0x01) != 0) {
            int sigOffset = 12 + len;
            linkId = raw[sigOffset] & 0xFF;
            timestamp = MavlinkSigner.readTimestampLittleEndian(raw, sigOffset + 1);
            signature = new byte[MavlinkSigner.SIGNATURE_LENGTH];
            System.arraycopy(raw, sigOffset + 7, signature, 0, MavlinkSigner.SIGNATURE_LENGTH);
        }

        return new MavlinkFrame(len, inc, compat, seq, sid, cid, msgId,
                payload, crc, linkId, timestamp, signature);
    }

    /**
     * 按 v2 语义计算本帧的期望 CRC-16/X.25：len + incompat + compat + seq + sysid + compid +
     * msgId 3 字节 + payload + CRC_EXTRA，覆盖范围与 {@link MavlinkParser} 的 computeCrc
     * 完全一致。CRC_EXTRA 取自 {@link MavlinkMessageInfo} 注册表，msgId 未注册时抛
     * {@link MavlinkException}。
     */
    private int computeExpectedCrcV2() {
        int expected = MavlinkCrc.init();
        expected = MavlinkCrc.accumulate(expected, payloadLength);
        expected = MavlinkCrc.accumulate(expected, incompatibilityFlags);
        expected = MavlinkCrc.accumulate(expected, compatibilityFlags);
        expected = MavlinkCrc.accumulate(expected, sequence);
        expected = MavlinkCrc.accumulate(expected, systemId);
        expected = MavlinkCrc.accumulate(expected, componentId);
        expected = MavlinkCrc.accumulate(expected, messageId & 0xFF);
        expected = MavlinkCrc.accumulate(expected, (messageId >> 8) & 0xFF);
        expected = MavlinkCrc.accumulate(expected, (messageId >> 16) & 0xFF);
        expected = MavlinkCrc.accumulate(expected, payload, 0, payload.length);
        expected = MavlinkCrc.accumulate(expected, MavlinkMessageInfo.crcExtraOf(messageId));
        return expected;
    }

    /**
     * 校验本帧 CRC（v2 语义，含 CRC_EXTRA）。
     * <p>
     * v1 帧经 {@link MavlinkParser} 归一化后 incompat/compat 为 0，但 v1 官方 CRC 不覆盖
     * 这两个字节，因此本方法仅对 v2 原生帧给出正确结论；v1 帧的 CRC 由 Parser 在
     * ingestion 时按 v1 公式校验。
     *
     * @return CRC 一致返回 true
     * @throws MavlinkException msgId 未注册（无 CRC_EXTRA 可查）时
     */
    public boolean verifyChecksum() {
        return computeExpectedCrcV2() == crc;
    }

    /**
     * 解析并强校验 v2 帧：{@link #decodeV2(byte[])} 的校验版。
     * <p>
     * 结构解析成功后额外校验 CRC：不一致或 msgId 未注册时抛 {@link MavlinkException}。
     * 适合测试与工具代码对单帧做一次性强校验；生产流式接收用 {@link MavlinkParser}。
     *
     * @param raw 原始字节序列
     * @return CRC 校验通过的帧
     * @throws IllegalArgumentException 结构不合法（STX 错误/长度不足）
     * @throws MavlinkException CRC 不一致或 msgId 未注册
     */
    public static MavlinkFrame decodeV2Verified(byte[] raw) {
        MavlinkFrame frame = decodeV2(raw);
        int expected = frame.computeExpectedCrcV2();
        if (expected != frame.crc) {
            throw new MavlinkException("v2 帧 CRC 校验失败: msgId=" + frame.messageId
                    + ", expected=0x" + Integer.toHexString(expected)
                    + ", received=0x" + Integer.toHexString(frame.crc));
        }
        return frame;
    }

    public int getPayloadLength() {
        return payloadLength;
    }

    public int getIncompatibilityFlags() {
        return incompatibilityFlags;
    }

    public int getCompatibilityFlags() {
        return compatibilityFlags;
    }

    public int getSequence() {
        return sequence;
    }

    public int getSystemId() {
        return systemId;
    }

    public int getComponentId() {
        return componentId;
    }

    public int getMessageId() {
        return messageId;
    }

    public byte[] getPayload() {
        return payload;
    }

    public int getCrc() {
        return crc;
    }

    /** 获取链路 ID（签名帧用，非签名帧为 0）。 */
    public int getLinkId() {
        return linkId;
    }

    /** 获取签名时间戳（签名帧用，非签名帧为 0）。 */
    public long getTimestamp() {
        return timestamp;
    }

    /** 获取 6 字节 sha256_48 签名数据（签名帧用，非签名帧为 null）。 */
    public byte[] getSignature() {
        return signature;
    }

    /** 判断是否为签名帧。 */
    public boolean isSigned() {
        return (incompatibilityFlags & 0x01) != 0 && signature != null;
    }
}

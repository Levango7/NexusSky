package io.aerofleet.mavlink;

/**
 * MAVLink v2.0 帧（STX=0xFD）的不可变表示。
 * 线上布局：STX | LEN | INC | COMPAT | SEQ | SID | CID | MSGID(3B LE) | PAYLOAD | CRC(2B LE)。
 * v1 帧（STX=0xFE，无 INC/COMPAT，MSGID 单字节）由 Parser 解析后统一为本表示。
 */
public final class MavlinkFrame {

    public static final int STX_V2 = 0xFD;
    public static final int STX_V1 = 0xFE;

    private final int payloadLength;
    private final int incompatibilityFlags;
    private final int compatibilityFlags;
    private final int sequence;
    private final int systemId;
    private final int componentId;
    private final int messageId;
    private final byte[] payload;
    private final int crc;

    public MavlinkFrame(int payloadLength, int incompatibilityFlags, int compatibilityFlags,
                        int sequence, int systemId, int componentId, int messageId,
                        byte[] payload, int crc) {
        this.payloadLength = payloadLength;
        this.incompatibilityFlags = incompatibilityFlags;
        this.compatibilityFlags = compatibilityFlags;
        this.sequence = sequence;
        this.systemId = systemId;
        this.componentId = componentId;
        this.messageId = messageId;
        this.payload = payload;
        this.crc = crc;
    }

    /** 构造发送帧：按官方 finalize 算法计算完整 CRC（头字段 + payload + CRC_EXTRA）。 */
    public static MavlinkFrame of(int systemId, int componentId, int sequence,
                                  int messageId, int crcExtra, byte[] payload) {
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

    /** 序列化为 MAVLink v2 线上字节。 */
    public byte[] encodeV2() {
        int len = payload.length;
        byte[] buf = new byte[len + 12];
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
        return buf;
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
}

package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/** OPEN_DRONE_ID_BASIC_ID (msgId=12900, LEN=22, CRC_EXTRA=223)。无人机基本标识信息。 */
public final class OpenDroneIdBasicId extends MavlinkMessage {

    public static final int ID = 12900;
    public static final int LEN = 22;

    public final int idType;    // 识别类型
    public final int uaType;    // 无人机类型
    public final byte[] uasId;  // UAS标识，20字节

    public OpenDroneIdBasicId(int idType, int uaType, byte[] uasId) {
        this.idType = idType;
        this.uaType = uaType;
        this.uasId = uasId;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, idType);
        PayloadCodec.putU8(buf, 1, uaType);
        // char[20] 字段：不足 20 字节由 alloc 的零初始化缓冲补 NUL
        System.arraycopy(uasId, 0, buf, 2, Math.min(uasId.length, 20));
        return buf;
    }

    public static OpenDroneIdBasicId decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int idType = PayloadCodec.u8(b, 0);
        int uaType = PayloadCodec.u8(b, 1);
        byte[] uasId = new byte[20];
        b.position(2);
        b.get(uasId);
        return new OpenDroneIdBasicId(idType, uaType, uasId);
    }

    @Override
    public String toString() {
        return "OpenDroneIdBasicId{idType=" + idType + ", uaType=" + uaType + "}";
    }
}
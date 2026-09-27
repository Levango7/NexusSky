package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/** OPEN_DRONE_ID_SELF_ID (msgId=12903, LEN=24, CRC_EXTRA=200)。无人机自描述信息。 */
public final class OpenDroneIdSelfId extends MavlinkMessage {

    public static final int ID = 12903;
    public static final int LEN = 24;

    public final int descriptionType;  // 描述类型
    public final byte[] description;   // 描述内容，23字节

    public OpenDroneIdSelfId(int descriptionType, byte[] description) {
        this.descriptionType = descriptionType;
        this.description = description;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, descriptionType);
        // char[23] 字段：不足 23 字节由 alloc 的零初始化缓冲补 NUL
        System.arraycopy(description, 0, buf, 1, Math.min(description.length, 23));
        return buf;
    }

    public static OpenDroneIdSelfId decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int descriptionType = PayloadCodec.u8(b, 0);
        byte[] description = new byte[23];
        b.position(1);
        b.get(description);
        return new OpenDroneIdSelfId(descriptionType, description);
    }

    @Override
    public String toString() {
        return "OpenDroneIdSelfId{descriptionType=" + descriptionType + "}";
    }
}
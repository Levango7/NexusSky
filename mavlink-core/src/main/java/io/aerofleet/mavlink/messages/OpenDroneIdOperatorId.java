package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/** OPEN_DRONE_ID_OPERATOR_ID (msgId=12905, LEN=21, CRC_EXTRA=225)。操作者标识信息。 */
public final class OpenDroneIdOperatorId extends MavlinkMessage {

    public static final int ID = 12905;
    public static final int LEN = 21;

    public final int operatorIdType;  // 操作者ID类型
    public final byte[] operatorId;   // 操作者ID，20字节

    public OpenDroneIdOperatorId(int operatorIdType, byte[] operatorId) {
        this.operatorIdType = operatorIdType;
        this.operatorId = operatorId;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, operatorIdType);
        // char[20] 字段：不足 20 字节由 alloc 的零初始化缓冲补 NUL
        System.arraycopy(operatorId, 0, buf, 1, Math.min(operatorId.length, 20));
        return buf;
    }

    public static OpenDroneIdOperatorId decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int operatorIdType = PayloadCodec.u8(b, 0);
        byte[] operatorId = new byte[20];
        b.position(1);
        b.get(operatorId);
        return new OpenDroneIdOperatorId(operatorIdType, operatorId);
    }

    @Override
    public String toString() {
        return "OpenDroneIdOperatorId{operatorIdType=" + operatorIdType + "}";
    }
}
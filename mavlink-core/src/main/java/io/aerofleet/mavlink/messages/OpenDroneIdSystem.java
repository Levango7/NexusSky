package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/** OPEN_DRONE_ID_SYSTEM (msgId=12904, LEN=23, CRC_EXTRA=233)。无人机系统信息与操作者位置。 */
public final class OpenDroneIdSystem extends MavlinkMessage {

    public static final int ID = 12904;
    public static final int LEN = 23;

    public final int flags;                // 系统标志
    public final int operatorLocationType; // 操作者位置类型
    public final int classificationType;   // 分类类型
    public final int operatorLatitude;     // 操作者纬度，1e-7deg
    public final int operatorLongitude;    // 操作者经度，1e-7deg
    public final int areaCount;            // 区域数量
    public final int areaRadius;           // 区域半径
    public final float areaCeiling;        // 区域上限，m
    public final float areaFloor;          // 区域下限，m

    public OpenDroneIdSystem(int flags, int operatorLocationType, int classificationType,
                             int operatorLatitude, int operatorLongitude, int areaCount,
                             int areaRadius, float areaCeiling, float areaFloor) {
        this.flags = flags;
        this.operatorLocationType = operatorLocationType;
        this.classificationType = classificationType;
        this.operatorLatitude = operatorLatitude;
        this.operatorLongitude = operatorLongitude;
        this.areaCount = areaCount;
        this.areaRadius = areaRadius;
        this.areaCeiling = areaCeiling;
        this.areaFloor = areaFloor;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, flags);
        PayloadCodec.putU8(buf, 1, operatorLocationType);
        PayloadCodec.putU8(buf, 2, classificationType);
        PayloadCodec.putI32(buf, 3, operatorLatitude);
        PayloadCodec.putI32(buf, 7, operatorLongitude);
        PayloadCodec.putU16(buf, 11, areaCount);
        PayloadCodec.putU16(buf, 13, areaRadius);
        PayloadCodec.putF32(buf, 15, areaCeiling);
        PayloadCodec.putF32(buf, 19, areaFloor);
        return buf;
    }

    public static OpenDroneIdSystem decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        return new OpenDroneIdSystem(
                PayloadCodec.u8(b, 0),
                PayloadCodec.u8(b, 1),
                PayloadCodec.u8(b, 2),
                PayloadCodec.i32(b, 3),
                PayloadCodec.i32(b, 7),
                PayloadCodec.u16(b, 11),
                PayloadCodec.u16(b, 13),
                PayloadCodec.f32(b, 15),
                PayloadCodec.f32(b, 19));
    }

    public double operatorLat() {
        return operatorLatitude / 1e7;
    }

    public double operatorLon() {
        return operatorLongitude / 1e7;
    }

    @Override
    public String toString() {
        return "OpenDroneIdSystem{flags=" + flags
                + ", operatorLat=" + operatorLat()
                + ", operatorLon=" + operatorLon() + "}";
    }
}
package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/** OPEN_DRONE_ID_LOCATION (msgId=12901, LEN=36, CRC_EXTRA=234)。无人机位置与速度信息。 */
public final class OpenDroneIdLocation extends MavlinkMessage {

    public static final int ID = 12901;
    public static final int LEN = 36;

    public final int status;               // 状态
    public final int direction;            // 方向，0.01deg
    public final int speedHorizontal;      // 水平速度，0.25m/s
    public final int speedVertical;        // 垂直速度，0.5m/s
    public final int latitude;             // 纬度，1e-7deg
    public final int longitude;            // 经度，1e-7deg
    public final float altitudeBarometric; // 气压高度，m
    public final float altitudeGeodetic;   // 大地高度，m
    public final int heightReference;      // 高度参考基准
    public final int horizontalAccuracy;   // 水平精度
    public final int verticalAccuracy;     // 垂直精度
    public final int barometerAccuracy;    // 气压精度
    public final int speedAccuracy;        // 速度精度
    public final float timestamp;          // 时间戳，s

    public OpenDroneIdLocation(int status, int direction, int speedHorizontal, int speedVertical,
                               int latitude, int longitude, float altitudeBarometric,
                               float altitudeGeodetic, int heightReference, int horizontalAccuracy,
                               int verticalAccuracy, int barometerAccuracy, int speedAccuracy,
                               float timestamp) {
        this.status = status;
        this.direction = direction;
        this.speedHorizontal = speedHorizontal;
        this.speedVertical = speedVertical;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitudeBarometric = altitudeBarometric;
        this.altitudeGeodetic = altitudeGeodetic;
        this.heightReference = heightReference;
        this.horizontalAccuracy = horizontalAccuracy;
        this.verticalAccuracy = verticalAccuracy;
        this.barometerAccuracy = barometerAccuracy;
        this.speedAccuracy = speedAccuracy;
        this.timestamp = timestamp;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, status);
        PayloadCodec.putU16(buf, 1, direction);
        PayloadCodec.putU16(buf, 3, speedHorizontal);
        PayloadCodec.putI16(buf, 5, speedVertical);
        PayloadCodec.putI32(buf, 7, latitude);
        PayloadCodec.putI32(buf, 11, longitude);
        PayloadCodec.putF32(buf, 15, altitudeBarometric);
        PayloadCodec.putF32(buf, 19, altitudeGeodetic);
        PayloadCodec.putU8(buf, 23, heightReference);
        PayloadCodec.putU8(buf, 24, horizontalAccuracy);
        PayloadCodec.putU8(buf, 25, verticalAccuracy);
        PayloadCodec.putU8(buf, 26, barometerAccuracy);
        PayloadCodec.putU8(buf, 27, speedAccuracy);
        PayloadCodec.putF32(buf, 28, timestamp);
        // bytes 32-35: reserved (4 bytes padding)
        return buf;
    }

    public static OpenDroneIdLocation decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        return new OpenDroneIdLocation(
                PayloadCodec.u8(b, 0),
                PayloadCodec.u16(b, 1),
                PayloadCodec.u16(b, 3),
                PayloadCodec.i16(b, 5),
                PayloadCodec.i32(b, 7),
                PayloadCodec.i32(b, 11),
                PayloadCodec.f32(b, 15),
                PayloadCodec.f32(b, 19),
                PayloadCodec.u8(b, 23),
                PayloadCodec.u8(b, 24),
                PayloadCodec.u8(b, 25),
                PayloadCodec.u8(b, 26),
                PayloadCodec.u8(b, 27),
                PayloadCodec.f32(b, 28));
    }

    public double lat() {
        return latitude / 1e7;
    }

    public double lon() {
        return longitude / 1e7;
    }

    @Override
    public String toString() {
        return "OpenDroneIdLocation{lat=" + lat() + ", lon=" + lon()
                + ", status=" + status + "}";
    }
}
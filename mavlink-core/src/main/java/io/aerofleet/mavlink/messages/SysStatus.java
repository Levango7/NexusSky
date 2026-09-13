package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/** SYS_STATUS (msgId=1, LEN=43, CRC=124)。传感器健康、负载、电池电压/电流/剩余。 */
public final class SysStatus extends MavlinkMessage {

    public static final int ID = 1;
    public static final int LEN = 43;

    public final long sensorsPresent;
    public final long sensorsEnabled;
    public final long sensorsHealth;
    public final int load;              // 0-1000: 千分比
    public final int voltageBattery;    // mV
    public final int currentBattery;    // cA（10mA）
    public final int batteryRemaining;  // %

    public SysStatus(long sensorsPresent, long sensorsEnabled, long sensorsHealth,
                     int load, int voltageBattery, int currentBattery, int batteryRemaining) {
        this.sensorsPresent = sensorsPresent;
        this.sensorsEnabled = sensorsEnabled;
        this.sensorsHealth = sensorsHealth;
        this.load = load;
        this.voltageBattery = voltageBattery;
        this.currentBattery = currentBattery;
        this.batteryRemaining = batteryRemaining;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU32(buf, 0, sensorsPresent);
        PayloadCodec.putU32(buf, 4, sensorsEnabled);
        PayloadCodec.putU32(buf, 8, sensorsHealth);
        PayloadCodec.putU16(buf, 12, load);
        PayloadCodec.putU16(buf, 14, voltageBattery);
        PayloadCodec.putI16(buf, 16, currentBattery);
        // 18..30: drop_rate/errors 与 4 个错误计数（骨架置 0）
        PayloadCodec.putI8(buf, 30, batteryRemaining);
        // 31..42: extended 传感器位图（置 0）
        return buf;
    }

    public static SysStatus decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        return new SysStatus(
                PayloadCodec.u32(b, 0),
                PayloadCodec.u32(b, 4),
                PayloadCodec.u32(b, 8),
                PayloadCodec.u16(b, 12),
                PayloadCodec.u16(b, 14),
                PayloadCodec.i16(b, 16),
                f.getPayloadLength() > 30 ? PayloadCodec.i8(b, 30) : -1);
    }

    @Override
    public String toString() {
        return "SysStatus{load=" + load + ", voltage=" + voltageBattery + "mV"
                + ", current=" + currentBattery + "cA, battery=" + batteryRemaining + "%}";
    }
}

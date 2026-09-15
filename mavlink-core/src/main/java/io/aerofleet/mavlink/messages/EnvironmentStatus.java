package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * ENVIRONMENT_STATUS (msgId=422, LEN=13) —— NexusSky 自定义扩展消息。
 * 承载环境状态五元组 + 阵风 + 能见度 + 降雨率，由 drone-sim 1Hz 周期下传（FR-24）。
 * <p>
 * 注：design.md 原设计 msgId=420，但 M1 swarm_performance 的 LedControlMsg 已占用 420，
 * 为避免冲突调整为 422（MAVLink 扩展区间 400-65535，合法）。
 * <p>
 * 字段布局（小端，按 MAVLink 惯例 uint8 先，然后 int16/uint16）：
 * <pre>
 * 偏移  字段            类型     单位/精度
 * 0    humidity        uint8   %
 * 1    weather         uint8   枚举 CLEAR=0/CLOUDY=1/RAIN=2/SNOW=3/FOG=4
 * 2    rainRate        uint8   mm/h
 * 3    temperature     int16   c°C（×100）
 * 5    windSpeed       uint16  cm/s（×100）
 * 7    windDirection   uint16  cdeg（×100）
 * 9    gust            int16   cm/s
 * 11   visibility      uint16  m
 * </pre>
 * CRC_EXTRA = 36086（按 MavlinkCrc 对消息名+字段名+类型计算）。
 */
public final class EnvironmentStatus extends MavlinkMessage {

    public static final int ID = 422;  // 原设计 420，因 LedControlMsg 冲突调整为 422
    public static final int LEN = 13;

    public final int temperature;    // c°C
    public final int humidity;       // %
    public final int windSpeed;      // cm/s
    public final int windDirection;  // cdeg
    public final int gust;           // cm/s
    public final int weather;        // 枚举 code
    public final int visibility;     // m
    public final int rainRate;       // mm/h

    public EnvironmentStatus(int temperature, int humidity, int windSpeed,
                             int windDirection, int gust, int weather,
                             int visibility, int rainRate) {
        this.temperature = temperature;
        this.humidity = humidity;
        this.windSpeed = windSpeed;
        this.windDirection = windDirection;
        this.gust = gust;
        this.weather = weather;
        this.visibility = visibility;
        this.rainRate = rainRate;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, humidity);
        PayloadCodec.putU8(buf, 1, weather);
        PayloadCodec.putU8(buf, 2, rainRate);
        PayloadCodec.putI16(buf, 3, temperature);
        PayloadCodec.putU16(buf, 5, windSpeed);
        PayloadCodec.putU16(buf, 7, windDirection);
        PayloadCodec.putI16(buf, 9, gust);
        PayloadCodec.putU16(buf, 11, visibility);
        return buf;
    }

    /**
     * 从帧解码；payload 短于所需字段时容忍解码（缺失字段填默认值 0，与 {@link RadioStatus} 一致）。
     */
    public static EnvironmentStatus decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new EnvironmentStatus(
                PayloadCodec.i16(b, 3),
                PayloadCodec.u8(b, 0),
                PayloadCodec.u16(b, 5),
                PayloadCodec.u16(b, 7),
                PayloadCodec.i16(b, 9),
                PayloadCodec.u8(b, 1),
                len > 12 ? PayloadCodec.u16(b, 11) : 0,
                PayloadCodec.u8(b, 2));
    }
}
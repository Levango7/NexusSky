package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * ALARM_TRIGGER (msgId=477, LEN=68) —— NexusSky M14 安防报警自定义扩展消息。
 * <p>
 * 地面报警触发消息（安防设备→无人机通知）：承载报警类型 + 严重程度 + 来源设备 +
 * 报警位置（纬度/经度/海拔）+ 时间戳 + 描述。
 * <p>
 * 字段布局（小端，大字段在前以自然对齐）：
 * <pre>
 * 偏移  字段              类型      单位/精度
 * 0    timestamp         u32      时间戳（ms）
 * 4    lat               i32      报警位置纬度（E7）
 * 8    lon               i32      报警位置经度（E7）
 * 12   sourceDeviceId    u16      来源设备 ID
 * 14   alt               i16      报警位置海拔（mm）
 * 16   alarmType         u8       0=运动检测, 1=入侵, 2=火灾, 3=门禁, 4=自定义
 * 17   severity          u8       0=INFO, 1=WARN, 2=CRITICAL
 * 18   description       char[50] 报警描述（UTF-8，0 填充）
 * </pre>
 * CRC_EXTRA = 261（M14 自定义扩展）。
 */
public final class AlarmTriggerMsg extends MavlinkMessage {

    public static final int ID = 477;
    public static final int LEN = 68;
    public static final int CRC_EXTRA = 261;
    private static final int DESCRIPTION_LEN = 50;

    public final long timestamp;          // ms
    public final int lat;                 // E7
    public final int lon;                 // E7
    public final int sourceDeviceId;      // 来源设备 ID
    public final int alt;                 // mm
    public final int alarmType;           // 0=运动检测, 1=入侵, 2=火灾, 3=门禁, 4=自定义
    public final int severity;            // 0=INFO, 1=WARN, 2=CRITICAL
    public final String description;      // 报警描述

    public AlarmTriggerMsg(long timestamp, int lat, int lon, int sourceDeviceId,
                           int alt, int alarmType, int severity, String description) {
        this.timestamp = timestamp;
        this.lat = lat;
        this.lon = lon;
        this.sourceDeviceId = sourceDeviceId;
        this.alt = alt;
        this.alarmType = alarmType;
        this.severity = severity;
        this.description = description;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU32(buf, 0, timestamp);
        PayloadCodec.putI32(buf, 4, lat);
        PayloadCodec.putI32(buf, 8, lon);
        PayloadCodec.putU16(buf, 12, sourceDeviceId);
        PayloadCodec.putI16(buf, 14, alt);
        PayloadCodec.putU8(buf, 16, alarmType);
        PayloadCodec.putU8(buf, 17, severity);
        PayloadCodec.putChars(buf, 18, description, DESCRIPTION_LEN);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static AlarmTriggerMsg decode(MavlinkFrame f) {
        ByteBuffer b = PayloadCodec.littleEndian(f.getPayload());
        int len = f.getPayloadLength();
        return new AlarmTriggerMsg(
                PayloadCodec.u32(b, 0),
                len > 7 ? PayloadCodec.i32(b, 4) : 0,
                len > 11 ? PayloadCodec.i32(b, 8) : 0,
                len > 13 ? PayloadCodec.u16(b, 12) : 0,
                len > 15 ? PayloadCodec.i16(b, 14) : 0,
                len > 16 ? PayloadCodec.u8(b, 16) : 0,
                len > 17 ? PayloadCodec.u8(b, 17) : 0,
                len > 18 ? PayloadCodec.chars(b, 18, DESCRIPTION_LEN) : "");
    }

    @Override
    public String toString() {
        return "AlarmTriggerMsg{type=" + alarmType + ", sev=" + severity
                + ", srcDev=" + sourceDeviceId
                + ", lat=" + lat + ", lon=" + lon + ", alt=" + alt
                + ", ts=" + timestamp
                + ", desc='" + description + "'}";
    }
}
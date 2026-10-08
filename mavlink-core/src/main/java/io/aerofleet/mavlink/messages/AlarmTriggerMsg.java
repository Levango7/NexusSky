package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * ALARM_TRIGGER (msgId=30057, LEN=72) —— NexusSky M14 安防报警自定义扩展消息。
 * <p>
 * 地面报警触发消息（安防设备→无人机通知）：承载报警类型 + 严重程度 + 来源设备 +
 * 报警位置（纬度/经度/海拔）+ 时间戳 + 描述 + **报警事件 ID**。
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
 * 68   alarmId           u32      报警事件 ID（2026-10-07 追加于尾部）
 * </pre>
 * CRC_EXTRA = 64（M14 自定义扩展；追加尾部字段不改变 CRC_EXTRA，它只与 msgId/字段名相关）。
 *
 * <p><b>为什么 alarmId 追加在尾部而不是插进头部</b>（协议变更的取舍记录）：
 * 原 LEN=68 恰好被前 7 个字段填满（18 + 50），没有空位。三个选项里：
 * <ul>
 *   <li><b>追加尾部（本方案）</b>：LEN 68→72，既有字段偏移一字不动。
 *       <b>双向兼容</b>：新解码器读到 68 字节的旧帧 ⇒ {@code len > 71} 不成立 ⇒ alarmId=0；
 *       旧解码器读到 72 字节的新帧 ⇒ 各字段仍在原偏移、尾部 4 字节被忽略
 *       （{@code decode} 对每个字段都有 {@code len > N} 守卫，帧解码不校验 payload 长度）。
 *       代价仅 4 字节/帧。</li>
 *   <li><b>插入头部 + description 50→46</b>：LEN 仍为 68，但 description 偏移从 18 变 22，
 *       <b>旧帧的 description 会被错读成 alarmId</b>——省下 0 字节却换来静默数据损坏。</li>
 *   <li><b>不改协议，靠 (sourceDeviceId, timestamp) 关联</b>：时间戳邻近在告警突发时必然撞车，
 *       且这是前端归一化补不了的（补一个假 alarmId 只会把缺口藏起来）。</li>
 * </ul>
 *
 * <p><b>alarmId=0 的语义</b>：表示"本帧未携带 ID"（旧帧，或事件 ID 为空）。
 * 与 {@code AlarmAckMsg.alarmId} 同为 u32，口径一致；消费侧应把 0 视为不可关联。
 *
 * <p><b>为什么需要它</b>：{@code AlarmAckMsg}(30058) 一直带 {@code alarmId}
 * （{@code AlarmLinkageEngine} 用 {@code idToU32(event.getId())} 填充），
 * 但本消息此前完全不带 —— 于是 trigger 帧与 ack 帧<b>在协议层无法关联</b>：
 * 操作员看到"某处发生火灾"与"Drone-3 已在响应"，却无法确认后者是不是这条报警的响应。
 */
public final class AlarmTriggerMsg extends MavlinkMessage {

    public static final int ID = 30057;
    public static final int LEN = 72;
    public static final int CRC_EXTRA = 64;
    private static final int DESCRIPTION_LEN = 50;
    /** alarmId 在 payload 中的偏移（追加于 description 之后）。 */
    private static final int ALARM_ID_OFFSET = 68;

    public final long timestamp;          // ms
    public final int lat;                 // E7
    public final int lon;                 // E7
    public final int sourceDeviceId;      // 来源设备 ID
    public final int alt;                 // mm
    public final int alarmType;           // 0=运动检测, 1=入侵, 2=火灾, 3=门禁, 4=自定义
    public final int severity;            // 0=INFO, 1=WARN, 2=CRITICAL
    public final String description;      // 报警描述
    /** 报警事件 ID；0 = 未携带（旧帧或事件 ID 为空）。与 AlarmAckMsg.alarmId 同口径。 */
    public final long alarmId;

    /** 兼容构造：不含 alarmId（等价于 alarmId=0）。 */
    public AlarmTriggerMsg(long timestamp, int lat, int lon, int sourceDeviceId,
                           int alt, int alarmType, int severity, String description) {
        this(timestamp, lat, lon, sourceDeviceId, alt, alarmType, severity,
                description, 0L);
    }

    public AlarmTriggerMsg(long timestamp, int lat, int lon, int sourceDeviceId,
                           int alt, int alarmType, int severity, String description,
                           long alarmId) {
        if (alarmType < 0 || alarmType > 4) {
            throw new IllegalArgumentException("alarmType must be in [0, 4]: " + alarmType);
        }
        if (severity < 0 || severity > 2) {
            throw new IllegalArgumentException("severity must be in [0, 2]: " + severity);
        }
        this.timestamp = timestamp;
        this.lat = lat;
        this.lon = lon;
        this.sourceDeviceId = sourceDeviceId;
        this.alt = alt;
        this.alarmType = alarmType;
        this.severity = severity;
        this.description = description;
        this.alarmId = alarmId;
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
        PayloadCodec.putU32(buf, ALARM_ID_OFFSET, alarmId);
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
                len > 18 ? PayloadCodec.chars(b, 18, DESCRIPTION_LEN) : "",
                // 旧帧（68 字节）无此字段 ⇒ 0，与"未携带"语义一致
                len > ALARM_ID_OFFSET + 3 ? PayloadCodec.u32(b, ALARM_ID_OFFSET) : 0L);
    }

    /** 本帧是否携带了可用于关联 ack 的 alarmId。 */
    public boolean hasAlarmId() {
        return alarmId != 0L;
    }

    @Override
    public String toString() {
        return "AlarmTriggerMsg{type=" + alarmType + ", sev=" + severity
                + ", srcDev=" + sourceDeviceId + ", alarmId=" + alarmId
                + ", lat=" + lat + ", lon=" + lon + ", alt=" + alt
                + ", ts=" + timestamp
                + ", desc='" + description + "'}";
    }
}
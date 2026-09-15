package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * ENVIRONMENT_ALERT (msgId=421, LEN=46) —— NexusSky 自定义扩展消息。
 * 承载环境告警事件（类型 + 级别 + 实测值 + 阈值 + 人类可读描述），由告警引擎触发下传（FR-25）。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段       类型      单位/精度
 * 0    alertType  uint8    枚举 WIND=0/TEMP=1/WEATHER=2/HUMIDITY=3
 * 1    severity   uint8    MAV_SEVERITY critical=2/warning=4/info=6
 * 2    value      int16    实测值×10
 * 4    threshold  int16    阈值×10
 * 6    text       char[40] ASCII 人类可读描述
 * </pre>
 * CRC_EXTRA = 25705（按 MavlinkCrc 对消息名+字段名+类型计算）。
 */
public final class EnvironmentAlert extends MavlinkMessage {

    public static final int ID = 421;
    public static final int LEN = 46;
    public static final int TEXT_LEN = 40;

    public final int alertType;    // 枚举
    public final int severity;     // MAV_SEVERITY
    public final int value;        // 实测值×10
    public final int threshold;    // 阈值×10
    public final String text;      // 最多 40 字节

    public EnvironmentAlert(int alertType, int severity, int value,
                            int threshold, String text) {
        this.alertType = alertType;
        this.severity = severity;
        this.value = value;
        this.threshold = threshold;
        this.text = text;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, alertType);
        PayloadCodec.putU8(buf, 1, severity);
        PayloadCodec.putI16(buf, 2, value);
        PayloadCodec.putI16(buf, 4, threshold);
        PayloadCodec.putChars(buf, 6, text, TEXT_LEN);
        return buf;
    }

    /**
     * 从帧解码；text 长度按 payload 实际长度容忍（缺失部分填默认空）。
     */
    public static EnvironmentAlert decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new EnvironmentAlert(
                PayloadCodec.u8(b, 0),
                PayloadCodec.u8(b, 1),
                PayloadCodec.i16(b, 2),
                PayloadCodec.i16(b, 4),
                PayloadCodec.chars(b, 6, Math.min(TEXT_LEN, len - 6)));
    }
}
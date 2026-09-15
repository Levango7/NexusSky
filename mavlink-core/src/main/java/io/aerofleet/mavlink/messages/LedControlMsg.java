package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * LED_CONTROL_MSG（自定义扩展 msgId=420, LEN=18, CRC_EXTRA=233）。
 * GCS/编队调度 → 飞机：灯光控制命令，承载灯效模式 + 颜色 + 亮度 + 频率 + 相位起点。
 *
 * 字段布局（小端，LEN=18）：
 *   偏移 0  colorR        u8    红色分量 0-255
 *   偏移 1  colorG        u8    绿色分量 0-255
 *   偏移 2  colorB        u8    蓝色分量 0-255
 *   偏移 3  pattern       u8    灯效模式枚举（0=STEADY..4=RAINBOW）
 *   偏移 4  brightness    u8    亮度 0-100%
 *   偏移 5  freq          u8    频率 0-20Hz
 *   偏移 6  flags         u8    bit0=on/off, bit1=sync
 *   偏移 7  phaseStartUs  u32   相位起点（队内时钟基准，微秒）
 *   偏移 11 targetSystem  u8    目标飞机 sysid
 *   偏移 12 targetComp    u8    目标组件 ID
 *   偏移 13 reserved      u8    保留（0）
 *   偏移 14 ledIndex      u8    LED 灯组索引（0=全部）
 *   偏移 15 transitionMs  u16   过渡时间（ms，0=立即切换）
 *
 * 消息类不可变；encode/decode 小端字节序。
 */
public final class LedControlMsg extends MavlinkMessage {

    public static final int ID = 420;           // 自定义扩展 msgId
    public static final int LEN = 18;
    public static final int CRC_EXTRA = 233;    // 自定义 CRC_EXTRA（标注为扩展消息）

    public final int targetSystem;
    public final int targetComponent;
    public final int colorR;
    public final int colorG;
    public final int colorB;
    public final int pattern;       // LightPattern.ordinal()
    public final int brightness;    // 0-100
    public final int freq;          // 0-20 Hz
    public final boolean on;
    public final boolean sync;
    public final long phaseStartUs;
    public final int ledIndex;
    public final int transitionMs;

    public LedControlMsg(int targetSystem, int targetComponent,
                         int colorR, int colorG, int colorB,
                         int pattern, int brightness, int freq,
                         boolean on, boolean sync, long phaseStartUs,
                         int ledIndex, int transitionMs) {
        this.targetSystem = targetSystem;
        this.targetComponent = targetComponent;
        this.colorR = colorR;
        this.colorG = colorG;
        this.colorB = colorB;
        this.pattern = pattern;
        this.brightness = brightness;
        this.freq = freq;
        this.on = on;
        this.sync = sync;
        this.phaseStartUs = phaseStartUs;
        this.ledIndex = ledIndex;
        this.transitionMs = transitionMs;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, colorR);
        PayloadCodec.putU8(buf, 1, colorG);
        PayloadCodec.putU8(buf, 2, colorB);
        PayloadCodec.putU8(buf, 3, pattern);
        PayloadCodec.putU8(buf, 4, brightness);
        PayloadCodec.putU8(buf, 5, freq);
        int flags = (on ? 1 : 0) | (sync ? 2 : 0);
        PayloadCodec.putU8(buf, 6, flags);
        PayloadCodec.putU32(buf, 7, phaseStartUs);
        PayloadCodec.putU8(buf, 11, targetSystem);
        PayloadCodec.putU8(buf, 12, targetComponent);
        PayloadCodec.putU8(buf, 13, 0);           // reserved
        PayloadCodec.putU8(buf, 14, ledIndex);
        PayloadCodec.putU16(buf, 15, transitionMs);
        return buf;
    }

    public static LedControlMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int flags = PayloadCodec.u8(b, 6);
        return new LedControlMsg(
                PayloadCodec.u8(b, 11), PayloadCodec.u8(b, 12),
                PayloadCodec.u8(b, 0), PayloadCodec.u8(b, 1), PayloadCodec.u8(b, 2),
                PayloadCodec.u8(b, 3), PayloadCodec.u8(b, 4), PayloadCodec.u8(b, 5),
                (flags & 1) != 0, (flags & 2) != 0,
                PayloadCodec.u32(b, 7),
                PayloadCodec.u8(b, 14), PayloadCodec.u16(b, 15));
    }

    @Override
    public String toString() {
        return "LedControlMsg{target=" + targetSystem + "/" + targetComponent
                + ", rgb=" + colorR + "/" + colorG + "/" + colorB
                + ", pattern=" + pattern + ", brightness=" + brightness
                + ", freq=" + freq + ", on=" + on + ", sync=" + sync
                + ", phaseStartUs=" + phaseStartUs
                + ", ledIndex=" + ledIndex + ", transitionMs=" + transitionMs + "}";
    }
}
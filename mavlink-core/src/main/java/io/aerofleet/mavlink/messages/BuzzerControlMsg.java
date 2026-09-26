package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;
import io.aerofleet.mavlink.enums.BuzzerPattern;

import java.nio.ByteBuffer;

/**
 * BUZZER_CONTROL_MSG（自定义扩展 msgId=483, LEN=7, CRC_EXTRA=267）。
 * GCS/搜救调度 → 飞机：蜂鸣器声光报警控制命令，
 * 承载开关状态 + 报警模式 + 音量 + 持续时间。
 *
 * 字段布局（小端，LEN=7）：
 *   偏移 0  sysid        u8    目标飞机 sysid
 *   偏移 1  on           u8    开关状态（0=关, 1=开）
 *   偏移 2  pattern      u8    报警模式枚举（0=CONTINUOUS, 1=INTERMITTENT, 2=SOS_MORSE）
 *   偏移 3  volume       u8    音量等级（1-10）
 *   偏移 4  durationSec  u16   持续时间（秒，0=持续到关机）
 *   偏移 6  reserved     u8    保留（0）
 *
 * 消息类不可变；encode/decode 小端字节序。
 */
public final class BuzzerControlMsg extends MavlinkMessage {

    public static final int ID = 483;           // 自定义扩展 msgId
    public static final int LEN = 7;
    public static final int CRC_EXTRA = 267;     // 自定义 CRC_EXTRA（全局唯一）

    public final int sysid;
    public final boolean on;
    public final int pattern;        // BuzzerPattern.ordinal()
    public final int volume;         // 1-10
    public final int durationSec;    // 0=持续到关机

    public BuzzerControlMsg(int sysid, boolean on, int pattern, int volume, int durationSec) {
        validateVolume(volume);
        validatePattern(pattern);
        this.sysid = sysid;
        this.on = on;
        this.pattern = pattern;
        this.volume = volume;
        this.durationSec = durationSec;
    }

    /**
     * 验证 volume 范围（0-100）。
     *
     * @param volume 音量等级
     * @throws IllegalArgumentException 如果 volume 超出 0-100 范围
     */
    private static void validateVolume(int volume) {
        if (volume < 0 || volume > 100) {
            throw new IllegalArgumentException(
                    "volume must be in range [0, 100], got: " + volume);
        }
    }

    /**
     * 验证 pattern 值是否在 BuzzerPattern 枚举的有效范围内。
     *
     * @param pattern 报警模式枚举序号
     * @throws IllegalArgumentException 如果 pattern 超出有效枚举范围
     */
    private static void validatePattern(int pattern) {
        int maxOrdinal = BuzzerPattern.values().length - 1;
        if (pattern < 0 || pattern > maxOrdinal) {
            throw new IllegalArgumentException(
                    "pattern must be in range [0, " + maxOrdinal + "], got: " + pattern);
        }
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        validateVolume(volume);
        validatePattern(pattern);
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, sysid);
        PayloadCodec.putU8(buf, 1, on ? 1 : 0);
        PayloadCodec.putU8(buf, 2, pattern);
        PayloadCodec.putU8(buf, 3, volume);
        PayloadCodec.putU16(buf, 4, durationSec);
        PayloadCodec.putU8(buf, 6, 0);            // reserved
        return buf;
    }

    public static BuzzerControlMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int pattern = PayloadCodec.u8(b, 2);
        validatePattern(pattern);
        return new BuzzerControlMsg(
                PayloadCodec.u8(b, 0),
                PayloadCodec.u8(b, 1) != 0,
                pattern,
                PayloadCodec.u8(b, 3),
                PayloadCodec.u16(b, 4));
    }

    @Override
    public String toString() {
        return "BuzzerControlMsg{sysid=" + sysid
                + ", on=" + on
                + ", pattern=" + pattern
                + ", volume=" + volume
                + ", durationSec=" + durationSec + "}";
    }
}
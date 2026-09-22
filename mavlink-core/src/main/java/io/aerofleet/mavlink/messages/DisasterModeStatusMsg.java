package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * DISASTER_MODE_STATUS (msgId=482, LEN=8) —— NexusSky P2 灾害应急通讯组网扩展消息。
 * <p>
 * 灾害模式状态通知：告知 mesh 节点当前灾害模式是否激活、触发原因、受影响节点数与恢复率。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段              类型      单位/精度
 * 0    timestamp         u32      时间戳（ms）
 * 4    mode              u8       0=inactive, 1=active
 * 5    triggerReason     u8       0=manual, 1=heartbeat_timeout, 2=terrain_change, 3=auto
 * 6    affectedNodes     u8       受影响节点数
 * 7    recoveryRate      u8       恢复率（0-100%）
 * </pre>
 * CRC_EXTRA = 266（P2 自定义扩展）。
 */
public final class DisasterModeStatusMsg extends MavlinkMessage {

    public static final int ID = 482;
    public static final int LEN = 8;
    public static final int CRC_EXTRA = 266;

    public static final int MODE_INACTIVE = 0;
    public static final int MODE_ACTIVE = 1;

    public static final int REASON_MANUAL = 0;
    public static final int REASON_HEARTBEAT_TIMEOUT = 1;
    public static final int REASON_TERRAIN_CHANGE = 2;
    public static final int REASON_AUTO = 3;

    public final long timestamp;      // ms
    public final int mode;            // 0=inactive, 1=active
    public final int triggerReason;   // 0=manual, 1=heartbeat_timeout, 2=terrain_change, 3=auto
    public final int affectedNodes;   // 受影响节点数
    public final int recoveryRate;    // 恢复率（0-100%）

    public DisasterModeStatusMsg(long timestamp, int mode, int triggerReason,
                                 int affectedNodes, int recoveryRate) {
        if (mode < 0 || mode > 1) {
            throw new IllegalArgumentException("mode must be in [0, 1]: " + mode);
        }
        if (triggerReason < 0 || triggerReason > 3) {
            throw new IllegalArgumentException("triggerReason must be in [0, 3]: " + triggerReason);
        }
        if (recoveryRate < 0 || recoveryRate > 100) {
            throw new IllegalArgumentException("recoveryRate must be in [0, 100]: " + recoveryRate);
        }
        this.timestamp = timestamp;
        this.mode = mode;
        this.triggerReason = triggerReason;
        this.affectedNodes = affectedNodes;
        this.recoveryRate = recoveryRate;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU32(buf, 0, timestamp);
        PayloadCodec.putU8(buf, 4, mode);
        PayloadCodec.putU8(buf, 5, triggerReason);
        PayloadCodec.putU8(buf, 6, affectedNodes);
        PayloadCodec.putU8(buf, 7, recoveryRate);
        return buf;
    }

    public static DisasterModeStatusMsg decode(MavlinkFrame f) {
        ByteBuffer b = PayloadCodec.littleEndian(f.getPayload());
        int len = f.getPayloadLength();
        return new DisasterModeStatusMsg(
                PayloadCodec.u32(b, 0),
                len > 4 ? PayloadCodec.u8(b, 4) : 0,
                len > 5 ? PayloadCodec.u8(b, 5) : 0,
                len > 6 ? PayloadCodec.u8(b, 6) : 0,
                len > 7 ? PayloadCodec.u8(b, 7) : 0);
    }

    @Override
    public String toString() {
        return "DisasterModeStatusMsg{mode=" + (mode == MODE_ACTIVE ? "ACTIVE" : "INACTIVE")
                + ", reason=" + triggerReason + ", affected=" + affectedNodes
                + ", recovery=" + recoveryRate + "%, ts=" + timestamp + "}";
    }
}
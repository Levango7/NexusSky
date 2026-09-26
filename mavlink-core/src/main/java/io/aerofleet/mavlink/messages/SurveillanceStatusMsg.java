package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * SURVEILLANCE_STATUS (msgId=479, LEN=14) —— NexusSky M14 安防报警自定义扩展消息。
 * <p>
 * 安防设备状态回传：承载设备 ID + 设备类型 + 状态 + 在线/总摄像头数 +
 * 最后事件时间 + 运行时间。
 * <p>
 * 字段布局（小端，大字段在前以自然对齐）：
 * <pre>
 * 偏移  字段            类型   单位/精度
 * 0    lastEventMs     u32    最后事件时间（ms）
 * 4    uptimeSec       u32    运行时间（秒）
 * 8    deviceId        u16    设备 ID
 * 10   deviceType      u8     0=海康, 1=大华, 2=宇视, 3=其他
 * 11   status          u8     0=在线, 1=离线, 2=故障, 3=维护
 * 12   onlineCameras   u8     在线摄像头数
 * 13   totalCameras    u8     总摄像头数
 * </pre>
 * CRC_EXTRA = 263（M14 自定义扩展）。
 */
public final class SurveillanceStatusMsg extends MavlinkMessage {

    public static final int ID = 479;
    public static final int LEN = 14;
    public static final int CRC_EXTRA = 263;

    public final long lastEventMs;      // 最后事件时间（ms）
    public final long uptimeSec;        // 运行时间（秒）
    public final int deviceId;          // 设备 ID
    public final int deviceType;        // 0=海康, 1=大华, 2=宇视, 3=其他
    public final int status;            // 0=在线, 1=离线, 2=故障, 3=维护
    public final int onlineCameras;     // 在线摄像头数
    public final int totalCameras;      // 总摄像头数

    public SurveillanceStatusMsg(long lastEventMs, long uptimeSec, int deviceId,
                                 int deviceType, int status,
                                 int onlineCameras, int totalCameras) {
        if (deviceType < 0 || deviceType > 3) {
            throw new IllegalArgumentException("deviceType must be in [0, 3]: " + deviceType);
        }
        if (status < 0 || status > 3) {
            throw new IllegalArgumentException("status must be in [0, 3]: " + status);
        }
        this.lastEventMs = lastEventMs;
        this.uptimeSec = uptimeSec;
        this.deviceId = deviceId;
        this.deviceType = deviceType;
        this.status = status;
        this.onlineCameras = onlineCameras;
        this.totalCameras = totalCameras;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU32(buf, 0, lastEventMs);
        PayloadCodec.putU32(buf, 4, uptimeSec);
        PayloadCodec.putU16(buf, 8, deviceId);
        PayloadCodec.putU8(buf, 10, deviceType);
        PayloadCodec.putU8(buf, 11, status);
        PayloadCodec.putU8(buf, 12, onlineCameras);
        PayloadCodec.putU8(buf, 13, totalCameras);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static SurveillanceStatusMsg decode(MavlinkFrame f) {
        ByteBuffer b = PayloadCodec.littleEndian(f.getPayload());
        int len = f.getPayloadLength();
        return new SurveillanceStatusMsg(
                PayloadCodec.u32(b, 0),
                len > 7 ? PayloadCodec.u32(b, 4) : 0,
                len > 9 ? PayloadCodec.u16(b, 8) : 0,
                len > 10 ? PayloadCodec.u8(b, 10) : 0,
                len > 11 ? PayloadCodec.u8(b, 11) : 0,
                len > 12 ? PayloadCodec.u8(b, 12) : 0,
                len > 13 ? PayloadCodec.u8(b, 13) : 0);
    }

    @Override
    public String toString() {
        return "SurveillanceStatusMsg{dev=" + deviceId + ", type=" + deviceType
                + ", status=" + status
                + ", cams=" + onlineCameras + "/" + totalCameras
                + ", lastEvt=" + lastEventMs
                + ", uptime=" + uptimeSec + "s}";
    }
}
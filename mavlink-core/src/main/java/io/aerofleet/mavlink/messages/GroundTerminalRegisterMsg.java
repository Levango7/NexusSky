package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * GROUND_TERMINAL_REGISTER (msgId=458, LEN=12) —— NexusSky M6 地面终端注册请求消息。
 * <p>
 * 由地面终端（手机/对讲机/传感器）发起，携带终端 ID、类型、GPS 坐标、请求接入的目标无人机 sysid，
 * drone-sim {@code CellTowerPayload.handleRegister} 消费（FR-MSG-05 / FR-TERM-06）。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段              类型   单位/精度
 * 0    terminalId        u16   终端唯一标识
 * 2    terminalType      u8    0=PHONE, 1=WALKIE_TALKIE, 2=SENSOR
 * 3    gpsLat            i32   纬度（1E7 度）
 * 7    gpsLon            i32   经度（1E7 度）
 * 11   requestedSysid    u8    请求接入的目标无人机 sysid
 * </pre>
 * CRC_EXTRA = 248。
 */
public final class GroundTerminalRegisterMsg extends MavlinkMessage {

    public static final int ID = 458;
    public static final int LEN = 12;
    public static final int CRC_EXTRA = 248;

    /** 终端类型枚举序数。 */
    public static final int TYPE_PHONE = 0;
    public static final int TYPE_WALKIE_TALKIE = 1;
    public static final int TYPE_SENSOR = 2;

    public final int terminalId;        // 终端唯一标识
    public final int terminalType;      // 0=PHONE, 1=WALKIE_TALKIE, 2=SENSOR
    public final int gpsLat;            // 1E7 度
    public final int gpsLon;            // 1E7 度
    public final int requestedSysid;    // 请求接入的目标无人机 sysid

    public GroundTerminalRegisterMsg(int terminalId, int terminalType,
                                     int gpsLat, int gpsLon, int requestedSysid) {
        this.terminalId = terminalId;
        this.terminalType = terminalType;
        this.gpsLat = gpsLat;
        this.gpsLon = gpsLon;
        this.requestedSysid = requestedSysid;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU16(buf, 0, terminalId);
        PayloadCodec.putU8(buf, 2, terminalType);
        PayloadCodec.putI32(buf, 3, gpsLat);
        PayloadCodec.putI32(buf, 7, gpsLon);
        PayloadCodec.putU8(buf, 11, requestedSysid);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static GroundTerminalRegisterMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new GroundTerminalRegisterMsg(
                len > 1 ? PayloadCodec.u16(b, 0) : 0,
                len > 2 ? PayloadCodec.u8(b, 2) : 0,
                len > 6 ? PayloadCodec.i32(b, 3) : 0,
                len > 10 ? PayloadCodec.i32(b, 7) : 0,
                len > 11 ? PayloadCodec.u8(b, 11) : 0);
    }

    @Override
    public String toString() {
        return "GroundTerminalRegisterMsg{terminal=" + terminalId
                + ", type=" + terminalType
                + ", lat=" + gpsLat + ", lon=" + gpsLon
                + ", reqSysid=" + requestedSysid + "}";
    }
}
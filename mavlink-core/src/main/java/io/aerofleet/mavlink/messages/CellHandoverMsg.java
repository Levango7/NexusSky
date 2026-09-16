package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * CELL_HANDOVER (msgId=457, LEN=5) —— NexusSky M6 跨无人机漫游切换信令。
 * <p>
 * 承载终端在机间覆盖区无缝切换的信令：terminalId + 源机 + 目标机 + 切换原因，
 * 经 M5 mesh 自愈网络路由传输（FR-MSG-04 / FR-HO-03 / FR-HO-04）。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段              类型   说明
 * 0    terminalId        u16   被切换终端 ID
 * 2    fromSysid         u8    源无人机 sysid
 * 3    toSysid           u8    目标无人机 sysid
 * 4    handoverReason    u8    0=SIGNAL_WEAK, 1=LOAD_BALANCE, 2=CELL_SHUTDOWN
 * </pre>
 * CRC_EXTRA = 247。
 */
public final class CellHandoverMsg extends MavlinkMessage {

    public static final int ID = 457;
    public static final int LEN = 5;
    public static final int CRC_EXTRA = 247;

    /** 切换原因枚举序数。 */
    public static final int REASON_SIGNAL_WEAK = 0;
    public static final int REASON_LOAD_BALANCE = 1;
    public static final int REASON_CELL_SHUTDOWN = 2;

    public final int terminalId;       // 被切换终端 ID
    public final int fromSysid;        // 源无人机 sysid
    public final int toSysid;          // 目标无人机 sysid
    public final int handoverReason;   // 0=SIGNAL_WEAK, 1=LOAD_BALANCE, 2=CELL_SHUTDOWN

    public CellHandoverMsg(int terminalId, int fromSysid, int toSysid, int handoverReason) {
        this.terminalId = terminalId;
        this.fromSysid = fromSysid;
        this.toSysid = toSysid;
        this.handoverReason = handoverReason;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU16(buf, 0, terminalId);
        PayloadCodec.putU8(buf, 2, fromSysid);
        PayloadCodec.putU8(buf, 3, toSysid);
        PayloadCodec.putU8(buf, 4, handoverReason);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static CellHandoverMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new CellHandoverMsg(
                len > 1 ? PayloadCodec.u16(b, 0) : 0,
                len > 2 ? PayloadCodec.u8(b, 2) : 0,
                len > 3 ? PayloadCodec.u8(b, 3) : 0,
                len > 4 ? PayloadCodec.u8(b, 4) : 0);
    }

    @Override
    public String toString() {
        return "CellHandoverMsg{terminal=" + terminalId
                + ", from=" + fromSysid + ", to=" + toSysid
                + ", reason=" + handoverReason + "}";
    }
}
package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * CELL_TOWER_STATUS (msgId=455, LEN=15) —— NexusSky M6 移动基站载荷抽象自定义扩展消息。
 * <p>
 * 承载无人机基站周期状态广播：sysid + 制式 + 覆盖中心 + 覆盖半径 + 已接入终端数 + 容量利用率，
 * 由 drone-sim {@code CellTowerPayload} 1Hz 产出并广播（FR-MSG-02 / FR-CAP-05）。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段                 类型   单位/精度
 * 0    sysid                u8    所属无人机 sysid（1-255）
 * 1    cellType             u8    制式：0=LTE_MICRO_CELL, 1=WIFI_MESH, 2=LORA
 * 2    centerLat            i32   覆盖中心纬度（1E7 度）
 * 6    centerLon            i32   覆盖中心经度（1E7 度）
 * 10   coverageRadiusM      u16   覆盖半径（米）
 * 12   connectedTerminals   u16   已接入终端数
 * 14   capacityUtilization  u8    容量利用率（0-100 表示 0.0-1.0）
 * </pre>
 * CRC_EXTRA = 245（M6 自定义扩展，避开 M7/M8 已占用的 238-244）。
 */
public final class CellTowerStatusMsg extends MavlinkMessage {

    public static final int ID = 455;
    public static final int LEN = 15;
    public static final int CRC_EXTRA = 245;

    public final int sysid;                // 1-255
    public final int cellType;             // 0=LTE, 1=WIFI, 2=LORA
    public final int centerLat;            // 1E7 度
    public final int centerLon;            // 1E7 度
    public final int coverageRadiusM;      // 米
    public final int connectedTerminals;   // 已接入终端数
    public final int capacityUtilization;  // 0-100 表示 0.0-1.0

    public CellTowerStatusMsg(int sysid, int cellType, int centerLat, int centerLon,
                              int coverageRadiusM, int connectedTerminals, int capacityUtilization) {
        this.sysid = sysid;
        this.cellType = cellType;
        this.centerLat = centerLat;
        this.centerLon = centerLon;
        this.coverageRadiusM = coverageRadiusM;
        this.connectedTerminals = connectedTerminals;
        this.capacityUtilization = capacityUtilization;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, sysid);
        PayloadCodec.putU8(buf, 1, cellType);
        PayloadCodec.putI32(buf, 2, centerLat);
        PayloadCodec.putI32(buf, 6, centerLon);
        PayloadCodec.putU16(buf, 10, coverageRadiusM);
        PayloadCodec.putU16(buf, 12, connectedTerminals);
        PayloadCodec.putU8(buf, 14, capacityUtilization);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static CellTowerStatusMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new CellTowerStatusMsg(
                PayloadCodec.u8(b, 0),
                len > 1 ? PayloadCodec.u8(b, 1) : 0,
                len > 5 ? PayloadCodec.i32(b, 2) : 0,
                len > 9 ? PayloadCodec.i32(b, 6) : 0,
                len > 11 ? PayloadCodec.u16(b, 10) : 0,
                len > 13 ? PayloadCodec.u16(b, 12) : 0,
                len > 14 ? PayloadCodec.u8(b, 14) : 0);
    }

    @Override
    public String toString() {
        return "CellTowerStatusMsg{sysid=" + sysid
                + ", cellType=" + cellType
                + ", centerLat=" + centerLat + ", centerLon=" + centerLon
                + ", radius=" + coverageRadiusM + "m"
                + ", connected=" + connectedTerminals
                + ", util=" + capacityUtilization + "%}";
    }
}
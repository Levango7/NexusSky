package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * CELL_TOWER_CONFIG (msgId=456, LEN=7) —— NexusSky M6 移动基站载荷配置下发指令。
 * <p>
 * 由 GCS（sysid=255）或 cloud-backend 下发，携带制式、发射功率、最大终端数、频段等配置参数，
 * drone-sim {@code CellTowerPayload.applyConfig} 消费（FR-MSG-03 / FR-CT-05）。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段              类型   单位/精度
 * 0    sysid             u8    目标无人机 sysid
 * 1    cellType          u8    制式：0=LTE_MICRO_CELL, 1=WIFI_MESH, 2=LORA
 * 2    txPowerDbm        i8    发射功率 dBm（-10..30）
 * 3    maxTerminals      u16   最大并发终端数
 * 5    frequencyChannel  u16   频段编号
 * </pre>
 * CRC_EXTRA = 246。
 */
public final class CellTowerConfigMsg extends MavlinkMessage {

    public static final int ID = 456;
    public static final int LEN = 7;
    public static final int CRC_EXTRA = 246;

    public final int sysid;               // 目标无人机 sysid
    public final int cellType;            // 0=LTE, 1=WIFI, 2=LORA
    public final int txPowerDbm;          // -10..30
    public final int maxTerminals;        // 最大并发终端数
    public final int frequencyChannel;    // 频段编号

    public CellTowerConfigMsg(int sysid, int cellType, int txPowerDbm,
                              int maxTerminals, int frequencyChannel) {
        this.sysid = sysid;
        this.cellType = cellType;
        this.txPowerDbm = txPowerDbm;
        this.maxTerminals = maxTerminals;
        this.frequencyChannel = frequencyChannel;
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
        PayloadCodec.putI8(buf, 2, txPowerDbm);
        PayloadCodec.putU16(buf, 3, maxTerminals);
        PayloadCodec.putU16(buf, 5, frequencyChannel);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static CellTowerConfigMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new CellTowerConfigMsg(
                PayloadCodec.u8(b, 0),
                len > 1 ? PayloadCodec.u8(b, 1) : 0,
                len > 2 ? PayloadCodec.i8(b, 2) : 0,
                len > 4 ? PayloadCodec.u16(b, 3) : 0,
                len > 6 ? PayloadCodec.u16(b, 5) : 0);
    }

    @Override
    public String toString() {
        return "CellTowerConfigMsg{sysid=" + sysid
                + ", cellType=" + cellType
                + ", txPower=" + txPowerDbm + "dBm"
                + ", maxTerminals=" + maxTerminals
                + ", freq=" + frequencyChannel + "}";
    }
}
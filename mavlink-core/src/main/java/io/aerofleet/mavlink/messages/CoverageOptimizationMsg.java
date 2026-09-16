package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * COVERAGE_OPTIMIZATION (msgId=466, LEN=24) —— NexusSky M9 应急任务编排自定义扩展消息。
 * <p>
 * 承载单架无人机覆盖优化部署方案：目标位置 + 基站类型 + 中继角色 + 发射功率 +
 * 预期覆盖贡献 + 电量预算 + 时间戳。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段              类型   单位/精度
 * 0    planId            u32   编排计划 ID
 * 4    droneId           u8    无人机 ID
 * 5    targetLat         i32   目标纬度（1E7 度）
 * 9    targetLon         i32   目标经度（1E7 度）
 * 13   targetAlt         i16   目标高度（m，相对起降点）
 * 15   cellType          u8    0=无, 1=LTE, 2=WiFi, 3=LoRa
 * 16   relayRole         u8    0=无, 1=mesh节点, 2=HAPS中继, 3=LEO中继
 * 17   txPower           u8    发射功率（dBm）
 * 18   expectedCoverage  u8    预期覆盖贡献（%）
 * 19   batteryBudget     u8    电量预算（%）
 * 20   timestamp         u32   时间戳（ms）
 * </pre>
 * CRC_EXTRA = 250（M9 自定义扩展）。
 */
public final class CoverageOptimizationMsg extends MavlinkMessage {

    public static final int ID = 466;
    public static final int LEN = 24;
    public static final int CRC_EXTRA = 250;

    public final long planId;           // 编排计划 ID
    public final int droneId;           // 无人机 ID
    public final int targetLat;         // 1E7 度
    public final int targetLon;         // 1E7 度
    public final int targetAlt;         // m
    public final int cellType;          // 0=无, 1=LTE, 2=WiFi, 3=LoRa
    public final int relayRole;         // 0=无, 1=mesh, 2=HAPS, 3=LEO
    public final int txPower;           // dBm
    public final int expectedCoverage;  // %
    public final int batteryBudget;     // %
    public final long timestamp;        // ms

    public CoverageOptimizationMsg(long planId, int droneId,
                                   int targetLat, int targetLon, int targetAlt,
                                   int cellType, int relayRole, int txPower,
                                   int expectedCoverage, int batteryBudget,
                                   long timestamp) {
        this.planId = planId;
        this.droneId = droneId;
        this.targetLat = targetLat;
        this.targetLon = targetLon;
        this.targetAlt = targetAlt;
        this.cellType = cellType;
        this.relayRole = relayRole;
        this.txPower = txPower;
        this.expectedCoverage = expectedCoverage;
        this.batteryBudget = batteryBudget;
        this.timestamp = timestamp;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU32(buf, 0, planId);
        PayloadCodec.putU8(buf, 4, droneId);
        PayloadCodec.putI32(buf, 5, targetLat);
        PayloadCodec.putI32(buf, 9, targetLon);
        PayloadCodec.putI16(buf, 13, targetAlt);
        PayloadCodec.putU8(buf, 15, cellType);
        PayloadCodec.putU8(buf, 16, relayRole);
        PayloadCodec.putU8(buf, 17, txPower);
        PayloadCodec.putU8(buf, 18, expectedCoverage);
        PayloadCodec.putU8(buf, 19, batteryBudget);
        PayloadCodec.putU32(buf, 20, timestamp);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static CoverageOptimizationMsg decode(MavlinkFrame f) {
        ByteBuffer b = PayloadCodec.littleEndian(f.getPayload());
        int len = f.getPayloadLength();
        return new CoverageOptimizationMsg(
                PayloadCodec.u32(b, 0),
                len > 4 ? PayloadCodec.u8(b, 4) : 0,
                len > 8 ? PayloadCodec.i32(b, 5) : 0,
                len > 12 ? PayloadCodec.i32(b, 9) : 0,
                len > 14 ? PayloadCodec.i16(b, 13) : 0,
                len > 15 ? PayloadCodec.u8(b, 15) : 0,
                len > 16 ? PayloadCodec.u8(b, 16) : 0,
                len > 17 ? PayloadCodec.u8(b, 17) : 0,
                len > 18 ? PayloadCodec.u8(b, 18) : 0,
                len > 19 ? PayloadCodec.u8(b, 19) : 0,
                len > 23 ? PayloadCodec.u32(b, 20) : 0);
    }

    @Override
    public String toString() {
        return "CoverageOptimizationMsg{planId=" + planId
                + ", drone=" + droneId
                + ", target=(" + targetLat + "," + targetLon + "," + targetAlt + "m)"
                + ", cell=" + cellType + ", relay=" + relayRole
                + ", tx=" + txPower + "dBm"
                + ", cov=" + expectedCoverage + "%, batt=" + batteryBudget + "%}";
    }
}
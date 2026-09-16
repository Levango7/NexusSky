package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * EMERGENCY_MISSION_PLAN (msgId=465, LEN=25) —— NexusSky M9 应急任务编排自定义扩展消息。
 * <p>
 * 承载编排计划状态广播：planId + 场景类型 + 当前阶段 + 阶段状态 + 灾区中心 + 半径 +
 * 无人机数 + 覆盖率 + 连通率 + 最高优先级 + 时间戳。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段                 类型   单位/精度
 * 0    planId               u32   编排计划 ID
 * 4    scenarioType         u8    0=地震, 1=泥石流, 2=火灾, 3=自定义
 * 5    phase                u8    0=测绘, 1=规划, 2=部署, 3=服务, 4=自愈
 * 6    phaseStatus          u8    0=待执行, 1=执行中, 2=完成, 3=失败, 4=中止
 * 7    disasterCenterLat    i32   灾区中心纬度（1E7 度）
 * 11   disasterCenterLon    i32   灾区中心经度（1E7 度）
 * 15   disasterRadius       u16   灾区半径（米）
 * 17   droneCount           u8    参与无人机数量
 * 18   coverageRate         u8    当前覆盖率（%）
 * 19   connectRate          u8    当前连通率（%）
 * 20   priority             u8    当前最高优先级任务级别（1-4）
 * 21   timestamp            u32   时间戳（ms）
 * </pre>
 * CRC_EXTRA = 249（M9 自定义扩展，避开 M5-M8 已占用的 233-248）。
 */
public final class EmergencyMissionPlanMsg extends MavlinkMessage {

    public static final int ID = 465;
    public static final int LEN = 25;
    public static final int CRC_EXTRA = 249;

    public final long planId;              // 编排计划 ID
    public final int scenarioType;         // 0=地震, 1=泥石流, 2=火灾, 3=自定义
    public final int phase;                // 0=测绘, 1=规划, 2=部署, 3=服务, 4=自愈
    public final int phaseStatus;          // 0=待执行, 1=执行中, 2=完成, 3=失败, 4=中止
    public final int disasterCenterLat;    // 1E7 度
    public final int disasterCenterLon;    // 1E7 度
    public final int disasterRadius;       // 米
    public final int droneCount;           // 参与无人机数量
    public final int coverageRate;         // %
    public final int connectRate;          // %
    public final int priority;             // 1-4
    public final long timestamp;           // ms

    public EmergencyMissionPlanMsg(long planId, int scenarioType, int phase, int phaseStatus,
                                   int disasterCenterLat, int disasterCenterLon,
                                   int disasterRadius, int droneCount,
                                   int coverageRate, int connectRate, int priority,
                                   long timestamp) {
        this.planId = planId;
        this.scenarioType = scenarioType;
        this.phase = phase;
        this.phaseStatus = phaseStatus;
        this.disasterCenterLat = disasterCenterLat;
        this.disasterCenterLon = disasterCenterLon;
        this.disasterRadius = disasterRadius;
        this.droneCount = droneCount;
        this.coverageRate = coverageRate;
        this.connectRate = connectRate;
        this.priority = priority;
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
        PayloadCodec.putU8(buf, 4, scenarioType);
        PayloadCodec.putU8(buf, 5, phase);
        PayloadCodec.putU8(buf, 6, phaseStatus);
        PayloadCodec.putI32(buf, 7, disasterCenterLat);
        PayloadCodec.putI32(buf, 11, disasterCenterLon);
        PayloadCodec.putU16(buf, 15, disasterRadius);
        PayloadCodec.putU8(buf, 17, droneCount);
        PayloadCodec.putU8(buf, 18, coverageRate);
        PayloadCodec.putU8(buf, 19, connectRate);
        PayloadCodec.putU8(buf, 20, priority);
        PayloadCodec.putU32(buf, 21, timestamp);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static EmergencyMissionPlanMsg decode(MavlinkFrame f) {
        ByteBuffer b = PayloadCodec.littleEndian(f.getPayload());
        int len = f.getPayloadLength();
        return new EmergencyMissionPlanMsg(
                PayloadCodec.u32(b, 0),
                len > 4 ? PayloadCodec.u8(b, 4) : 0,
                len > 5 ? PayloadCodec.u8(b, 5) : 0,
                len > 6 ? PayloadCodec.u8(b, 6) : 0,
                len > 10 ? PayloadCodec.i32(b, 7) : 0,
                len > 14 ? PayloadCodec.i32(b, 11) : 0,
                len > 16 ? PayloadCodec.u16(b, 15) : 0,
                len > 17 ? PayloadCodec.u8(b, 17) : 0,
                len > 18 ? PayloadCodec.u8(b, 18) : 0,
                len > 19 ? PayloadCodec.u8(b, 19) : 0,
                len > 20 ? PayloadCodec.u8(b, 20) : 0,
                len > 24 ? PayloadCodec.u32(b, 21) : 0);
    }

    @Override
    public String toString() {
        return "EmergencyMissionPlanMsg{planId=" + planId
                + ", scenario=" + scenarioType
                + ", phase=" + phase + ", status=" + phaseStatus
                + ", center=(" + disasterCenterLat + "," + disasterCenterLon + ")"
                + ", radius=" + disasterRadius + "m"
                + ", drones=" + droneCount
                + ", cov=" + coverageRate + "%, conn=" + connectRate + "%"
                + ", pri=" + priority + "}";
    }
}
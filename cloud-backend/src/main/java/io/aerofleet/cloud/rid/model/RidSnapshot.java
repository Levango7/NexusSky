package io.aerofleet.cloud.rid.model;

/**
 * Remote ID 快照，聚合单个无人机的完整 Remote ID 状态。
 * <p>
 * 将 Basic ID、Location、System、Self-ID、Operator ID 各消息数据
 * 与合规状态、系统 ID 及最后接收时间组合为一个完整快照，用于
 * 上报和持久化。
 *
 * @param sysid            系统 ID（MAVLink sysid）
 * @param ridStatus        Remote ID 合规状态
 * @param basicId          Basic ID 数据
 * @param location         位置数据
 * @param system           系统数据
 * @param selfId           Self ID 数据
 * @param operatorId       Operator ID 数据
 * @param lastReceivedTime 最后接收时间（Unix 毫秒时间戳）
 */
public record RidSnapshot(
        int sysid,
        RidComplianceState ridStatus,
        BasicIdData basicId,
        LocationData location,
        SystemData system,
        SelfIdData selfId,
        OperatorIdData operatorId,
        long lastReceivedTime
) {
}
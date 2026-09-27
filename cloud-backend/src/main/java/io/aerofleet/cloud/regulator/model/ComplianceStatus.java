package io.aerofleet.cloud.regulator.model;

/**
 * 合规状态记录。
 * <p>
 * 记录单架无人机在监管合规生命周期中的完整状态。状态流转规则为
 * {@code UNVERIFIED → VERIFIED → ACTIVATED → OPERATING → CANCELLED}，
 * 不允许逆向回退。
 * <p>
 * C1 阶段以内存态管理（{@code ConcurrentHashMap}），C4 持久化后切换为
 * Repository 实现。
 *
 * @param sysid                  无人机系统标识（MAVLink sysid）
 * @param productSerialNo        产品序列号（可空，未验证时可能未知）
 * @param status                 合规状态枚举
 * @param activationId           激活标识符（ACTIVATED/OPERATING/CANCELLED 时有值）
 * @param cancellationId         注销标识符（CANCELLED 时有值）
 * @param lastVerifyTime         最近验证时间戳（Unix epoch 毫秒）
 * @param lastActivationTime     最近激活时间戳（Unix epoch 毫秒）
 * @param lastTelemetryReportTime 最近遥测上报时间戳（Unix epoch 毫秒）
 */
public record ComplianceStatus(int sysid, String productSerialNo, ComplianceState status,
                                String activationId, String cancellationId,
                                long lastVerifyTime, long lastActivationTime,
                                long lastTelemetryReportTime) {
}
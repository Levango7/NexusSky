package io.aerofleet.cloud.mission.delivery;

/**
 * 单个配送站点 record（FR-24）。
 * <p>
 * 不可变值对象，描述一个配送站点的属性与状态：
 * <ul>
 *   <li>{@code index}：站点序号（0-based）</li>
 *   <li>{@code lat/lon/alt}：站点坐标（纬度/经度/海拔 m）</li>
 *   <li>{@code payloadId/payloadWeightKg/payloadVolumeL}：该站负载（id/重量 kg/体积 L）</li>
 *   <li>{@code dropAccuracyM}：投放精度半径 m（到达半径内方可投放）</li>
 *   <li>{@code state}：站点状态（{@link DeliverySiteState}）</li>
 *   <li>{@code arriveTimeMs}：到达时间 epoch ms（0 = 未到达）</li>
 *   <li>{@code dropTimeMs}：投放时间 epoch ms（0 = 未投放）</li>
 * </ul>
 *
 * @param index            站点序号
 * @param lat              纬度
 * @param lon              经度
 * @param alt              海拔 m
 * @param payloadId        负载标识
 * @param payloadWeightKg  负载重量 kg
 * @param payloadVolumeL   负载体积 L
 * @param dropAccuracyM    投放精度半径 m
 * @param state            站点状态
 * @param arriveTimeMs     到达时间 epoch ms
 * @param dropTimeMs       投放时间 epoch ms
 */
public record DeliverySite(
        int index,
        double lat, double lon, double alt,
        int payloadId,
        double payloadWeightKg,
        double payloadVolumeL,
        double dropAccuracyM,
        DeliverySiteState state,
        long arriveTimeMs,
        long dropTimeMs
) {
    /** 转换状态。 */
    public DeliverySite withState(DeliverySiteState newState) {
        return new DeliverySite(index, lat, lon, alt, payloadId, payloadWeightKg,
                payloadVolumeL, dropAccuracyM, newState, arriveTimeMs, dropTimeMs);
    }

    /** 设置到达时间。 */
    public DeliverySite withArriveTime(long ms) {
        return new DeliverySite(index, lat, lon, alt, payloadId, payloadWeightKg,
                payloadVolumeL, dropAccuracyM, state, ms, dropTimeMs);
    }

    /** 设置投放时间。 */
    public DeliverySite withDropTime(long ms) {
        return new DeliverySite(index, lat, lon, alt, payloadId, payloadWeightKg,
                payloadVolumeL, dropAccuracyM, state, arriveTimeMs, ms);
    }
}
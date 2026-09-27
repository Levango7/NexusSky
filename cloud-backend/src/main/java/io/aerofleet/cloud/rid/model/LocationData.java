package io.aerofleet.cloud.rid.model;

/**
 * 位置数据，对应 ASTM F3411 Remote ID Location Message。
 * <p>
 * 包含无人机的实时位置、速度、高度及精度信息。
 *
 * @param status              状态（0=Undeclared, 1=Ground, 2=Airborne, 3=Emergency）
 * @param direction           航向（度，0-359，361 表示不可用）
 * @param speedHorizontal     水平速度（m/s，254.25 表示不可用）
 * @param speedVertical       垂直速度（m/s，62 表示不可用）
 * @param latitude            纬度（度）
 * @param longitude           经度（度）
 * @param altitudeBarometric  气压高度（m）
 * @param altitudeGeodetic    大地高度（m）
 * @param heightReference     高度参考基准（0=Takeoff, 1=Ground）
 * @param height              相对高度（m）
 * @param horizontalAccuracy  水平精度等级（0-15）
 * @param verticalAccuracy    垂直精度等级（0-15）
 * @param barometerAccuracy   气压计精度等级（0-15）
 * @param speedAccuracy       速度精度等级（0-15）
 * @param timestamp           时间戳（秒）
 */
public record LocationData(
        int status,
        int direction,
        int speedHorizontal,
        int speedVertical,
        double latitude,
        double longitude,
        float altitudeBarometric,
        float altitudeGeodetic,
        int heightReference,
        float height,
        int horizontalAccuracy,
        int verticalAccuracy,
        int barometerAccuracy,
        int speedAccuracy,
        float timestamp
) {
}
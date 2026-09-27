package io.aerofleet.cloud.regulator.model;

/**
 * 遥测上报数据。
 * <p>
 * 周期性向监管平台上报的无人机遥测数据快照。包含系统标识、产品序列号、
 * 时间戳、经纬度、高度、速度、航向和飞行状态。
 *
 * @param sysid           无人机系统标识（MAVLink sysid）
 * @param productSerialNo 产品序列号
 * @param timestamp       遥测数据时间戳（Unix epoch 毫秒）
 * @param latitude        纬度坐标（WGS-84）
 * @param longitude       经度坐标（WGS-84）
 * @param altitudeM       高度（米）
 * @param groundSpeedMs   地面速度（米/秒）
 * @param headingDeg      航向角（度，0-360）
 * @param flightStatus    飞行状态枚举（AIRBORNE/GROUND）
 */
public record TelemetryReport(int sysid, String productSerialNo, long timestamp,
                               double latitude, double longitude, double altitudeM,
                               double groundSpeedMs, double headingDeg, FlightStatus flightStatus) {
}
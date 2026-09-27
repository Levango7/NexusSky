package io.aerofleet.cloud.regulator.model;

/**
 * 激活上报请求（MH/T 3030 交互二）。
 * <p>
 * 当无人机实名验证通过后，向监管平台上报激活信息。包含系统标识、产品序列号、
 * 激活时间戳和激活时的经纬度坐标。
 *
 * @param sysid           无人机系统标识（MAVLink sysid）
 * @param productSerialNo 产品序列号
 * @param activationTime  激活时间戳（Unix epoch 毫秒）
 * @param latitude        激活时纬度坐标（WGS-84）
 * @param longitude       激活时经度坐标（WGS-84）
 */
public record ActivationRequest(int sysid, String productSerialNo, long activationTime,
                                 double latitude, double longitude) {
}
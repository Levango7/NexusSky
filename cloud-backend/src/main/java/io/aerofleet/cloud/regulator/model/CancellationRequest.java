package io.aerofleet.cloud.regulator.model;

/**
 * 注销上报请求（MH/T 3030 交互三）。
 * <p>
 * 当无人机停止运营时，向监管平台上报注销信息。包含系统标识、产品序列号
 * 和注销原因。
 *
 * @param sysid              无人机系统标识（MAVLink sysid）
 * @param productSerialNo    产品序列号
 * @param cancellationReason 注销原因描述
 */
public record CancellationRequest(int sysid, String productSerialNo, String cancellationReason) {
}
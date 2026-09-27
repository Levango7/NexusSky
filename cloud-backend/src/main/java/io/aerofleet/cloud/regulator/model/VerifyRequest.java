package io.aerofleet.cloud.regulator.model;

/**
 * 实名状态验证请求（MH/T 3030 交互一）。
 * <p>
 * 用于向监管平台发起无人机实名登记状态验证。包含系统标识、产品序列号
 * 和实名认证证书号三个字段，监管平台据此查询实名登记信息。
 *
 * @param sysid           无人机系统标识（MAVLink sysid）
 * @param productSerialNo 产品序列号（无人机唯一标识）
 * @param realNameCertNo  实名认证证书号
 */
public record VerifyRequest(int sysid, String productSerialNo, String realNameCertNo) {
}
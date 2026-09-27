package io.aerofleet.cloud.regulator.model;

/**
 * 遥测上报结果。
 * <p>
 * 监管平台对 {@link TelemetryReport} 的响应。上报成功时 success 为 true；
 * 失败时 success 为 false 且 errorMessage 包含错误描述。
 *
 * @param success      是否上报成功
 * @param errorMessage 错误信息（成功时为 null）
 */
public record TelemetryResult(boolean success, String errorMessage) {
}
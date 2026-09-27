package io.aerofleet.cloud.regulator.model;

/**
 * 注销上报结果。
 * <p>
 * 监管平台对 {@link CancellationRequest} 的响应。注销成功时返回监管平台分配的
 * 注销标识符；失败时 success 为 false 且 errorMessage 包含错误描述。
 *
 * @param success         是否注销成功
 * @param cancellationId  注销标识符（监管平台分配，成功时有值）
 * @param errorMessage    错误信息（成功时为 null）
 */
public record CancellationResult(boolean success, String cancellationId, String errorMessage) {
}
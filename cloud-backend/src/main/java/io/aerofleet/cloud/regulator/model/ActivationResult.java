package io.aerofleet.cloud.regulator.model;

/**
 * 激活上报结果。
 * <p>
 * 监管平台对 {@link ActivationRequest} 的响应。激活成功时返回监管平台分配的
 * 激活标识符；失败时 success 为 false 且 errorMessage 包含错误描述。
 *
 * @param success      是否激活成功
 * @param activationId 激活标识符（监管平台分配，成功时有值）
 * @param errorMessage 错误信息（成功时为 null）
 */
public record ActivationResult(boolean success, String activationId, String errorMessage) {
}
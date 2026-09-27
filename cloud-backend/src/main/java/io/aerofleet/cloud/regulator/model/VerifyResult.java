package io.aerofleet.cloud.regulator.model;

/**
 * 实名状态验证结果。
 * <p>
 * 监管平台对 {@link VerifyRequest} 的响应。包含验证状态、所有者姓名（脱敏）、
 * 登记日期和错误信息。当状态为 {@link VerifyStatus#ERROR} 时，errorMessage
 * 包含具体错误描述；其他状态下 errorMessage 为 null。
 *
 * @param status       验证状态枚举
 * @param ownerName    所有者姓名（脱敏后，仅保留姓氏 + *）
 * @param registerDate 登记日期（格式 yyyy-MM-dd，未实名时为 null）
 * @param errorMessage 错误信息（正常时为 null）
 */
public record VerifyResult(VerifyStatus status, String ownerName, String registerDate, String errorMessage) {
}
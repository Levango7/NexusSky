package io.aerofleet.cloud.regulator.model;

/**
 * 实名验证状态枚举。
 * <p>
 * 表示监管平台对实名验证请求的响应状态：
 * <ul>
 *   <li>{@link #VERIFIED}   — 已实名登记，验证成功</li>
 *   <li>{@link #UNVERIFIED} — 未实名登记，产品序列号存在但未完成实名</li>
 *   <li>{@link #NOT_FOUND}  — 未找到，产品序列号在监管平台不存在</li>
 *   <li>{@link #ERROR}      — 错误，验证过程中发生异常（网络/服务/数据等）</li>
 * </ul>
 */
public enum VerifyStatus {
    /** 已实名登记，验证成功 */
    VERIFIED,
    /** 未实名登记，产品序列号存在但未完成实名 */
    UNVERIFIED,
    /** 未找到，产品序列号在监管平台不存在 */
    NOT_FOUND,
    /** 错误，验证过程中发生异常 */
    ERROR
}
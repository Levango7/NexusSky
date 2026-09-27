package io.aerofleet.cloud.regulator.model;

/**
 * 合规状态枚举。
 * <p>
 * 表示无人机在监管合规生命周期中的状态阶段，流转规则为正向单向：
 * <ul>
 *   <li>{@link #UNVERIFIED} — 未验证，初始状态</li>
 *   <li>{@link #VERIFIED}   — 已实名，实名验证成功后进入</li>
 *   <li>{@link #ACTIVATED}  — 已激活，激活上报成功后进入</li>
 *   <li>{@link #OPERATING}  — 运行中，首次遥测上报成功后自动进入</li>
 *   <li>{@link #CANCELLED}  — 已注销，注销上报成功后进入（终态）</li>
 * </ul>
 * 不允许逆向回退。
 */
public enum ComplianceState {
    /** 未验证，初始状态 */
    UNVERIFIED,
    /** 已实名，实名验证成功后进入 */
    VERIFIED,
    /** 已激活，激活上报成功后进入 */
    ACTIVATED,
    /** 运行中，首次遥测上报成功后自动进入 */
    OPERATING,
    /** 已注销，注销上报成功后进入（终态） */
    CANCELLED
}
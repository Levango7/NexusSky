package io.aerofleet.sim;

/**
 * 执行机构状态枚举（FR-03）。
 * <p>
 * 取值于 {@code {DISABLED, IDLE, ACTIVE, FAULT}} 之一，不得为 null。
 * <ul>
 *   <li>{@link #DISABLED}：执行机构未启用（disable() 后或构造期未 enable()）</li>
 *   <li>{@link #IDLE}：已启用但无活动（rate == 0 / 抛投器静止）</li>
 *   <li>{@link #ACTIVE}：正在执行工作（rate &gt; 0 / 抓取/投放中）</li>
 *   <li>{@link #FAULT}：故障（超时 / 超重 / 紧急停喷后）</li>
 * </ul>
 */
public enum ActuatorState {
    DISABLED,
    IDLE,
    ACTIVE,
    FAULT
}
package io.aerofleet.sim;

/**
 * 执行机构统一状态（FR-02，不可变 record）。
 * <p>
 * 由 {@link Actuator#getState()} 返回，封装三个核心字段：
 * <ul>
 *   <li>{@code enabled}：执行机构是否已使能（enable/disable 控制）</li>
 *   <li>{@code rate}：当前实际速率（喷洒泵为 mL/s，抛投器无流量概念恒为 0）</li>
 *   <li>{@code state}：状态枚举（{@link ActuatorState}）</li>
 * </ul>
 *
 * @param enabled 使能标志
 * @param rate    实际速率（已钳位到 [0, rateMax]）
 * @param state   状态枚举
 */
public record ActuatorStatus(boolean enabled, double rate, ActuatorState state) {
}
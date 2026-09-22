package io.aerofleet.cloud.spi;

/**
 * AI 决策策略插件 SPI 接口，支持不同决策策略（返航、避障、自适应路径等）的可插拔扩展。
 * <p>
 * 每种决策策略实现此接口，标注 {@code @Component} 即可被 {@link PluginRegistry} 自动发现注册。
 * 通过 {@code getStrategyType()} 标识策略类型，注册中心按类型建立映射。
 * <p>
 * 决策引擎在评估时，先通过 {@link #isApplicable(DecisionContext)} 过滤适用策略，
 * 再按 {@link #getPriority()} 排序，依次调用 {@link #evaluate(DecisionContext)} 收集决策结果。
 */
public interface DecisionStrategyPlugin extends PluginLifecycle {

    /**
     * 获取策略类型标识。
     * <p>
     * 如 "rtl"、"avoid"、"adapt-path"、"emergency-land" 等，用于注册中心索引。
     *
     * @return 策略类型字符串
     */
    String getStrategyType();

    /**
     * 评估当前态势，返回决策结果。
     *
     * @param context 决策上下文
     * @return 决策结果
     */
    DecisionResult evaluate(DecisionContext context);

    /**
     * 获取策略优先级（用于融合排序）。
     * <p>
     * 数值越大优先级越高，决策引擎按优先级从高到低依次评估。
     *
     * @return 优先级数值
     */
    int getPriority();

    /**
     * 判断当前策略是否适用于给定上下文。
     * <p>
     * 决策引擎先调用此方法过滤，仅对适用的策略调用 evaluate。
     *
     * @param context 决策上下文
     * @return 适用时返回 true
     */
    boolean isApplicable(DecisionContext context);
}
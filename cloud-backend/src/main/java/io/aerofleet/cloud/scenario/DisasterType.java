package io.aerofleet.cloud.scenario;

/**
 * 灾害类型枚举（P0-2 应急救援场景库）。
 * <p>
 * 覆盖六类典型应急场景，每类对应不同的无人机协同策略与预设参数。
 */
public enum DisasterType {
    /** 火灾：火情侦察 + 灭火指挥 + 中继通信。 */
    FIRE,
    /** 洪水：水域搜救 + 水面监测 + 物资投递。 */
    FLOOD,
    /** 地震：大范围搜救 + 通信中继 + 灾损评估。 */
    EARTHQUAKE,
    /** 泥石流：地质灾害侦察 + 警戒区监测 + 中继。 */
    MUDSLIDE,
    /** 化工厂泄漏：化学监测 + 毒气扩散建模 + 长时悬停。 */
    CHEMICAL_LEAK,
    /** 群体性事件：人群密度监测 + 秩序巡查 + 中继通信。 */
    MASS_EVENT
}
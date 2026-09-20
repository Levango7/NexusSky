package io.aerofleet.cloud.inspection;

/**
 * 航线规划类型枚举。
 * <ul>
 *   <li>{@link #LINEAR_GRID} 沿线网格扫描 — 按区域生成平行航线，按 overlap 重叠</li>
 *   <li>{@link #CROSS_GRID} 交叉网格 — 两组平行线交叉覆盖</li>
 *   <li>{@link #ORBIT} 环绕飞行 — 围绕中心点生成圆形航点</li>
 *   <li>{@link #PERIMETER} 周界巡逻 — 沿区域边界生成航点</li>
 * </ul>
 */
public enum RouteType {
    /** 沿线网格扫描。 */
    LINEAR_GRID,
    /** 交叉网格。 */
    CROSS_GRID,
    /** 环绕飞行。 */
    ORBIT,
    /** 周界巡逻。 */
    PERIMETER
}
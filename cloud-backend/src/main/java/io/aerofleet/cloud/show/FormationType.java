package io.aerofleet.cloud.show;

/**
 * 编队队形类型。
 * <p>
 * 定义无人机编队表演中可用的队形种类，每种队形由 {@link FormationService}
 * 根据队形类型、无人机数量与间距生成具体位置坐标。
 */
public enum FormationType {
    /** 直线队形 — 所有无人机沿一条直线排列。 */
    LINE,
    /** 网格队形 — 无人机排列成行列网格。 */
    GRID,
    /** 圆形队形 — 无人机均匀分布在圆周上。 */
    CIRCLE,
    /** 螺旋队形 — 无人机沿螺旋线分布。 */
    SPIRAL,
    /** V字形队形 — 无人机排列成V形（雁阵）。 */
    V_SHAPE,
    /** 菱形队形 — 无人机排列成菱形（钻石）轮廓。 */
    DIAMOND,
    /** 心形队形 — 无人机排列成心形轮廓。 */
    HEART,
    /** 星形队形 — 无人机排列成五角星轮廓。 */
    STAR
}
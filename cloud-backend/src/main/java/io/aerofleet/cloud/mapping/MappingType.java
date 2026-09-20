package io.aerofleet.cloud.mapping;

/**
 * 测绘类型枚举。
 * <p>
 * 支持四种测绘产出类型：
 * <ul>
 *   <li>{@link #ORTHO_PHOTO} 正射影像 — 生成拼接的正射影像图</li>
 *   <li>{@link #DEM} 数字高程模型 — 生成 DEM 地形数据</li>
 *   <li>{@link #THREE_D_MODEL} 三维模型 — 生成倾斜摄影三维模型</li>
 *   <li>{@link #MIXED} 混合测绘 — 同时生成多种成果</li>
 * </ul>
 */
public enum MappingType {
    /** 正射影像。 */
    ORTHO_PHOTO,
    /** 数字高程模型。 */
    DEM,
    /** 三维模型（倾斜摄影）。 */
    THREE_D_MODEL,
    /** 混合测绘（同时生成多种成果）。 */
    MIXED
}
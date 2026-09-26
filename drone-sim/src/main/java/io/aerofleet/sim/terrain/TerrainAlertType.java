package io.aerofleet.sim.terrain;

/**
 * 地形告警类型枚举（FR-06~FR-10, §7.5 扩展）。
 * <p>
 * 取值：
 * <pre>
 * WIND_SHEAR_WARN=0      山地峡谷风切变告警（FR-06）
 * SWAMP_NO_FLY=1         沼泽禁飞区告警（FR-07）
 * OLD_CITY_CONSTRAINT=2  老城密集区飞行约束告警（FR-08）
 * TERRAIN_FOLLOWING=3    地形跟随模式切换通知（FR-09）
 * OBSTACLE_UPDATE=4      灾后建筑遮挡模型更新通知（FR-10）
 * </pre>
 */
public enum TerrainAlertType {
    /** 山地峡谷风切变告警（FR-06）。 */
    WIND_SHEAR_WARN(0),
    /** 沼泽禁飞区动态标注告警（FR-07）。 */
    SWAMP_NO_FLY(1),
    /** 老城密集区飞行约束告警（FR-08）。 */
    OLD_CITY_CONSTRAINT(2),
    /** 地形跟随模式切换通知（FR-09）。 */
    TERRAIN_FOLLOWING(3),
    /** 灾后建筑遮挡模型更新通知（FR-10）。 */
    OBSTACLE_UPDATE(4);

    /** 枚举序数（与 MAVLink 消息中 u8 字段一致）。 */
    public final int code;

    TerrainAlertType(int code) {
        this.code = code;
    }

    /**
     * 由 code 反查枚举。
     * <p>
     * 非法 code（&lt;0 或 &gt;4）按 {@link #WIND_SHEAR_WARN} 处理（保守默认）。
     *
     * @param code 枚举序数（0-4）
     * @return 对应的 TerrainAlertType，非法时返回 WIND_SHEAR_WARN
     */
    public static TerrainAlertType fromCode(int code) {
        for (TerrainAlertType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return WIND_SHEAR_WARN;  // 非法 code → 保守默认
    }
}
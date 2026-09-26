package io.aerofleet.sim.terrain;

/**
 * 地形区域类型枚举（FR-06~FR-10, §7.1 扩展）。
 * <p>
 * 与 {@link TerrainType} 不同，本枚举专注于复杂地形飞行约束场景的区域分类，
 * 用于 {@link TerrainFlightConstraintChecker} 判定飞行约束规则。
 * <p>
 * 取值：
 * <pre>
 * MOUNTAIN_VALLEY=0  山地峡谷——风切变高发区（FR-06）
 * SWAMP=1            沼泽——动态禁飞区（FR-07）
 * OLD_CITY_DENSE=2   老城密集区——限速限高+近距避障（FR-08）
 * FOREST=3           森林——植被衰减区
 * OPEN_FIELD=4       开阔地——无特殊约束
 * </pre>
 */
public enum TerrainZoneType {
    /** 山地峡谷：风切变高发区，需风切变预警（FR-06）。 */
    MOUNTAIN_VALLEY(0),
    /** 沼泽：动态禁飞区，泥石流可导致新沼泽形成（FR-07）。 */
    SWAMP(1),
    /** 老城密集区：限速 5m/s、限高 50m、启用 ToF 近距避障（FR-08）。 */
    OLD_CITY_DENSE(2),
    /** 森林：植被衰减区，需注意 RF 传播影响。 */
    FOREST(3),
    /** 开阔地：无特殊地形飞行约束。 */
    OPEN_FIELD(4);

    /** 枚举序数（与 MAVLink 消息中 u8 字段一致）。 */
    public final int code;

    TerrainZoneType(int code) {
        this.code = code;
    }

    /**
     * 由 code 反查枚举。
     * <p>
     * 非法 code（&lt;0 或 &gt;4）按 {@link #OPEN_FIELD} 处理（保守默认，无约束）。
     *
     * @param code 枚举序数（0-4）
     * @return 对应的 TerrainZoneType，非法时返回 OPEN_FIELD
     */
    public static TerrainZoneType fromCode(int code) {
        for (TerrainZoneType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return OPEN_FIELD;  // 非法 code → OPEN_FIELD（保守默认，无约束）
    }

    /**
     * 从 {@link TerrainType} 转换为 {@link TerrainZoneType}。
     * <p>
     * 映射关系：
     * <ul>
     *   <li>MOUNTAIN → MOUNTAIN_VALLEY</li>
     *   <li>SWAMP → SWAMP</li>
     *   <li>OLD_CITY_DENSE → OLD_CITY_DENSE</li>
     *   <li>FOREST → FOREST</li>
     *   <li>其他 → OPEN_FIELD</li>
     * </ul>
     *
     * @param terrainType 既有地形类型
     * @return 对应的地形区域类型
     */
    public static TerrainZoneType fromTerrainType(TerrainType terrainType) {
        return switch (terrainType) {
            case MOUNTAIN -> MOUNTAIN_VALLEY;
            case SWAMP -> SWAMP;
            case OLD_CITY_DENSE -> OLD_CITY_DENSE;
            case FOREST -> FOREST;
            default -> OPEN_FIELD;
        };
    }
}
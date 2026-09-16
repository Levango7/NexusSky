package io.aerofleet.sim.terrain;

/**
 * 地形类型枚举（FR-01, §7.1）。
 * <p>
 * 9 种地形类型，每种关联一组 {@link TerrainRfProfile} RF 特性参数（FR-02）。
 * 枚举值固定不可扩展（本里程碑固定，§7.1）。
 * <p>
 * 取值：
 * <pre>
 * MOUNTAIN=0          山地
 * HILL=1              丘陵
 * FOREST=2            森林
 * SWAMP=3             沼泽
 * OLD_CITY_DENSE=4    老城密集区
 * SUPER_HIGH_RISE=5   超高层建筑区
 * NATURE_RESERVE=6    自然保护区
 * FLAT=7              平地
 * MIXED=8             混合地形
 * </pre>
 */
public enum TerrainType {
    /** 山地：高程方差大，多径中等。 */
    MOUNTAIN(0, new TerrainRfProfile(0.0, 0.0, 0.3, 0.5)),
    /** 丘陵：高程方差中等。 */
    HILL(1, new TerrainRfProfile(0.0, 0.0, 0.4, 0.3)),
    /** 森林：植被衰减系数 0.1 dB/m（ITU-R P.833 范围 0.05-0.2）。 */
    FOREST(2, new TerrainRfProfile(0.1, 0.0, 0.5, 0.6)),
    /** 沼泽：地面反射系数 0.8（范围 0.7-0.9），多径显著。 */
    SWAMP(3, new TerrainRfProfile(0.0, 0.0, 0.8, 0.9)),
    /** 老城密集区：建筑穿透损耗 20 dB（混凝土范围 10-30）。 */
    OLD_CITY_DENSE(4, new TerrainRfProfile(0.0, 20.0, 0.2, 0.7)),
    /** 超高层建筑区：建筑穿透损耗 25 dB，遮挡严重。 */
    SUPER_HIGH_RISE(5, new TerrainRfProfile(0.0, 25.0, 0.1, 0.4)),
    /** 自然保护区：限飞区，多径弱。 */
    NATURE_RESERVE(6, new TerrainRfProfile(0.0, 0.0, 0.3, 0.2)),
    /** 平地：无衰减，多径为 0。 */
    FLAT(7, new TerrainRfProfile(0.0, 0.0, 0.2, 0.0)),
    /** 混合地形：综合特性。 */
    MIXED(8, new TerrainRfProfile(0.05, 10.0, 0.5, 0.5));

    /** 枚举序数（与 MAVLink 消息中 u8 字段一致）。 */
    public final int code;
    /** 关联的 RF 特性参数。 */
    public final TerrainRfProfile rfProfile;

    TerrainType(int code, TerrainRfProfile rfProfile) {
        this.code = code;
        this.rfProfile = rfProfile;
    }

    /**
     * 由 code 反查枚举（FR-01）。
     * <p>
     * 非法 code（&lt;0 或 &gt;8）按 {@link #FLAT} 处理（§6.5.2 异常场景：地形类型枚举值非法）。
     *
     * @param code 枚举序数（0-8）
     * @return 对应的 TerrainType，非法时返回 FLAT
     */
    public static TerrainType fromCode(int code) {
        for (TerrainType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return FLAT;  // 非法 code → FLAT（保守默认）
    }
}
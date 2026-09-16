package io.aerofleet.sim.orch;

/**
 * 应急场景预设（M9 应急任务编排，T4 场景预设）。
 * <p>
 * 描述一类典型灾害场景的推荐参数：典型半径、推荐基站类型、推荐中继层级、
 * 无人机数量范围、三类任务权重、建筑损毁率、地形变化等级。
 * <p>
 * 所有字段 final 不可变。由 {@link ScenarioPresetFactory} 按类型构造。
 * <p>
 * 字段说明：
 * <pre>
 * type                   场景类型（0=地震, 1=泥石流, 2=火灾, 3=自定义）
 * name                   场景名称
 * description            场景描述
 * typicalRadiusKm        典型影响半径（km）
 * recommendedCellType    主推荐基站（1=LTE, 2=WiFi+LTE, 3=LoRa+LTE）
 * recommendedRelayLayers 推荐中继层级（1=mesh, 2=HAPS+mesh, 3=LEO+HAPS+mesh）
 * minDrones / maxDrones  推荐无人机数量范围
 * searchRescueWeight     搜救通讯任务权重（0~1）
 * commandWeight          指挥通讯任务权重（0~1）
 * mappingWeight          灾区测绘任务权重（0~1）
 * buildingDamageRate     建筑损毁率（0~1）
 * terrainChangeLevel     地形变化等级（0=轻微, 1=中等, 2=严重）
 * </pre>
 */
public final class ScenarioPreset {

    /** 场景类型（0=地震, 1=泥石流, 2=火灾, 3=自定义）。 */
    public final int type;
    /** 场景名称。 */
    public final String name;
    /** 场景描述。 */
    public final String description;
    /** 典型影响半径（km）。 */
    public final double typicalRadiusKm;
    /** 主推荐基站（1=LTE, 2=WiFi+LTE, 3=LoRa+LTE）。 */
    public final int recommendedCellType;
    /** 推荐中继层级（1=mesh, 2=HAPS+mesh, 3=LEO+HAPS+mesh）。 */
    public final int recommendedRelayLayers;
    /** 最少无人机数量。 */
    public final int minDrones;
    /** 最多无人机数量。 */
    public final int maxDrones;
    /** 搜救通讯任务权重（0~1）。 */
    public final double searchRescueWeight;
    /** 指挥通讯任务权重（0~1）。 */
    public final double commandWeight;
    /** 灾区测绘任务权重（0~1）。 */
    public final double mappingWeight;
    /** 建筑损毁率（0~1）。 */
    public final double buildingDamageRate;
    /** 地形变化等级（0=轻微, 1=中等, 2=严重）。 */
    public final int terrainChangeLevel;

    /**
     * 构造场景预设。
     *
     * @param type                   场景类型
     * @param name                   场景名称
     * @param description            场景描述
     * @param typicalRadiusKm        典型影响半径（km）
     * @param recommendedCellType    主推荐基站
     * @param recommendedRelayLayers 推荐中继层级
     * @param minDrones              最少无人机数量
     * @param maxDrones              最多无人机数量
     * @param searchRescueWeight     搜救通讯权重
     * @param commandWeight          指挥通讯权重
     * @param mappingWeight          灾区测绘权重
     * @param buildingDamageRate     建筑损毁率
     * @param terrainChangeLevel     地形变化等级
     */
    public ScenarioPreset(int type, String name, String description, double typicalRadiusKm,
                          int recommendedCellType, int recommendedRelayLayers,
                          int minDrones, int maxDrones,
                          double searchRescueWeight, double commandWeight, double mappingWeight,
                          double buildingDamageRate, int terrainChangeLevel) {
        this.type = type;
        this.name = name;
        this.description = description;
        this.typicalRadiusKm = typicalRadiusKm;
        this.recommendedCellType = recommendedCellType;
        this.recommendedRelayLayers = recommendedRelayLayers;
        this.minDrones = minDrones;
        this.maxDrones = maxDrones;
        this.searchRescueWeight = searchRescueWeight;
        this.commandWeight = commandWeight;
        this.mappingWeight = mappingWeight;
        this.buildingDamageRate = buildingDamageRate;
        this.terrainChangeLevel = terrainChangeLevel;
    }

    @Override
    public String toString() {
        return "ScenarioPreset{type=" + type + ", name=\"" + name + "\""
                + ", radiusKm=" + typicalRadiusKm
                + ", cellType=" + recommendedCellType
                + ", relayLayers=" + recommendedRelayLayers
                + ", drones=" + minDrones + "-" + maxDrones
                + ", weights=[SR=" + searchRescueWeight + ", CMD=" + commandWeight
                + ", MAP=" + mappingWeight + "]"
                + ", damageRate=" + buildingDamageRate
                + ", terrainChange=" + terrainChangeLevel + "}";
    }
}
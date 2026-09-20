package io.aerofleet.cloud.inspection;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 巡检模板预设工厂。
 * <p>
 * 提供 4 种行业预设模板，封装了不同行业的最佳实践参数（航高、速度、重叠率、相机角度、航线类型）：
 * <ul>
 *   <li>电力线路：{@link RouteType#LINEAR_GRID}，alt=80m，overlap=80%，沿线路走向</li>
 *   <li>油气管道：{@link RouteType#LINEAR_GRID}，alt=50m，overlap=70%，沿管道走向</li>
 *   <li>铁路沿线：{@link RouteType#CROSS_GRID}，alt=100m，overlap=60%，交叉覆盖</li>
 *   <li>光伏电站：{@link RouteType#ORBIT}，alt=60m，环绕+周界</li>
 * </ul>
 */
@Component
public class InspectionPresetFactory {

    /** 预设模板 ID 前缀。 */
    private static final String ID_PREFIX = "preset-";

    /**
     * 电力线路巡检模板。
     * <p>
     * LINEAR_GRID 沿线路走向，航高 80m，重叠率 80%，相机下视 90°，速度 8m/s。
     */
    public InspectionTemplate powerLinePreset() {
        return new InspectionTemplate(
                ID_PREFIX + "power-line",
                "电力线路巡检",
                IndustryType.POWER_LINE,
                "沿电力线路走向进行网格扫描，重点检测绝缘子破损",
                RouteType.LINEAR_GRID,
                80.0,    // altitudeM
                8.0,     // speedMps
                80.0,    // overlapPct
                90.0,    // cameraAngleDeg（垂直下视）
                List.of(
                        new InspectionTemplate.POI(39.90, 116.40, "杆塔#1"),
                        new InspectionTemplate.POI(39.91, 116.41, "杆塔#2"),
                        new InspectionTemplate.POI(39.92, 116.42, "杆塔#3")),
                5.0,     // totalDistanceKm
                12.0,    // estimatedDurationMin
                Instant.now());
    }

    /**
     * 油气管道巡检模板。
     * <p>
     * LINEAR_GRID 沿管道走向，航高 50m，重叠率 70%，相机下视 60°，速度 6m/s。
     */
    public InspectionTemplate oilGasPipePreset() {
        return new InspectionTemplate(
                ID_PREFIX + "oil-gas-pipe",
                "油气管道巡检",
                IndustryType.OIL_GAS_PIPE,
                "沿油气管道走向进行网格扫描，重点检测管道泄漏",
                RouteType.LINEAR_GRID,
                50.0,    // altitudeM
                6.0,     // speedMps
                70.0,    // overlapPct
                60.0,    // cameraAngleDeg
                List.of(
                        new InspectionTemplate.POI(39.85, 116.35, "阀室A"),
                        new InspectionTemplate.POI(39.87, 116.38, "阀室B")),
                8.0,     // totalDistanceKm
                25.0,    // estimatedDurationMin
                Instant.now());
    }

    /**
     * 铁路沿线巡检模板。
     * <p>
     * CROSS_GRID 交叉覆盖，航高 100m，重叠率 60%，相机下视 75°，速度 10m/s。
     */
    public InspectionTemplate railwayPreset() {
        return new InspectionTemplate(
                ID_PREFIX + "railway",
                "铁路沿线巡检",
                IndustryType.RAILWAY,
                "交叉网格覆盖铁路沿线，重点检测轨道裂缝",
                RouteType.CROSS_GRID,
                100.0,   // altitudeM
                10.0,    // speedMps
                60.0,    // overlapPct
                75.0,    // cameraAngleDeg
                List.of(
                        new InspectionTemplate.POI(39.95, 116.45, "车站X"),
                        new InspectionTemplate.POI(39.96, 116.46, "桥梁Y")),
                12.0,    // totalDistanceKm
                22.0,    // estimatedDurationMin
                Instant.now());
    }

    /**
     * 光伏电站巡检模板。
     * <p>
     * ORBIT 环绕+周界，航高 60m，重叠率 65%，相机下视 90°，速度 5m/s。
     */
    public InspectionTemplate solarFarmPreset() {
        return new InspectionTemplate(
                ID_PREFIX + "solar-farm",
                "光伏电站巡检",
                IndustryType.SOLAR_FARM,
                "环绕飞行+周界巡逻，重点检测面板裂纹",
                RouteType.ORBIT,
                60.0,    // altitudeM
                5.0,     // speedMps
                65.0,    // overlapPct
                90.0,    // cameraAngleDeg
                List.of(
                        new InspectionTemplate.POI(39.88, 116.50, "阵列#1"),
                        new InspectionTemplate.POI(39.89, 116.51, "阵列#2")),
                3.0,     // totalDistanceKm
                15.0,    // estimatedDurationMin
                Instant.now());
    }

    /** 获取所有预设模板。 */
    public List<InspectionTemplate> allPresets() {
        return List.of(
                powerLinePreset(),
                oilGasPipePreset(),
                railwayPreset(),
                solarFarmPreset());
    }

    /** 按 ID 查找预设模板，找不到返回 null。 */
    public InspectionTemplate findById(String id) {
        if (id == null) return null;
        for (InspectionTemplate t : allPresets()) {
            if (t.id().equals(id)) return t;
        }
        return null;
    }
}
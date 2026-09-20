package io.aerofleet.cloud.citytwin;

import io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 灾害模拟推演服务，支持洪水、火灾、地震、疏散等灾害类型的模拟。
 * <p>
 * 基于参数生成时间线帧和影响区域，用于灾害评估和应急规划。
 */
@Service
public class DisasterSimulationService {

    private static final Logger log = LoggerFactory.getLogger(DisasterSimulationService.class);

    /** 最大保存模拟记录数，超过时移除最旧记录。 */
    private static final int MAX_SIMULATIONS = 100;

    private final ConcurrentMap<String, DisasterSimulation> simulations = new ConcurrentHashMap<>();

    /**
     * 洪水模拟推演。
     *
     * @param centerLat  中心纬度
     * @param centerLon  中心经度
     * @param radiusKm   影响半径（公里）
     * @param depthM     水深（米）
     * @param durationMin 模拟持续时间（分钟）
     */
    public DisasterSimulation simulateFlood(double centerLat, double centerLon,
                                            double radiusKm, double depthM, int durationMin) {
        String id = UUID.randomUUID().toString();
        SimArea area = new SimArea(centerLat, centerLon, radiusKm);
        long startTime = System.currentTimeMillis();

        Map<String, Double> params = new HashMap<>();
        params.put("depthM", depthM);
        params.put("radiusKm", radiusKm);

        List<SimFrame> timeline = generateFloodTimeline(radiusKm, depthM, durationMin);
        List<Route> routes = generateEvacuationRoutes(centerLat, centerLon, radiusKm);
        List<String> zones = generateAffectedZones(centerLat, centerLon, radiusKm);

        double totalAffectedArea = timeline.isEmpty() ? 0 :
                timeline.get(timeline.size() - 1).getAffectedAreaKm2();
        int affectedPopulation = estimatePopulation(totalAffectedArea);
        double estimatedDamage = totalAffectedArea * depthM * 10; // 简化估算

        SimResult result = new SimResult(totalAffectedArea, affectedPopulation,
                estimatedDamage, routes, timeline);

        DisasterSimulation sim = new DisasterSimulation(id, DisasterSimulation.DisasterType.FLOOD,
                area, startTime, durationMin, DisasterSimulation.SimStatus.COMPLETED,
                params, zones, result);

        simulations.put(id, sim);
        evictIfFull();
        log.info("Flood simulation completed: id={} radiusKm={} depthM={} durationMin={}",
                id, radiusKm, depthM, durationMin);
        return sim;
    }

    /**
     * 火灾模拟推演。
     *
     * @param centerLat  中心纬度
     * @param centerLon  中心经度
     * @param radiusKm   影响半径（公里）
     * @param windSpeed  风速（m/s）
     * @param durationMin 模拟持续时间（分钟）
     */
    public DisasterSimulation simulateFire(double centerLat, double centerLon,
                                           double radiusKm, double windSpeed, int durationMin) {
        String id = UUID.randomUUID().toString();
        SimArea area = new SimArea(centerLat, centerLon, radiusKm);
        long startTime = System.currentTimeMillis();

        Map<String, Double> params = new HashMap<>();
        params.put("windSpeed", windSpeed);
        params.put("radiusKm", radiusKm);

        List<SimFrame> timeline = generateFireTimeline(radiusKm, windSpeed, durationMin);
        List<Route> routes = generateEvacuationRoutes(centerLat, centerLon, radiusKm);
        List<String> zones = generateAffectedZones(centerLat, centerLon, radiusKm);

        double totalAffectedArea = timeline.isEmpty() ? 0 :
                timeline.get(timeline.size() - 1).getAffectedAreaKm2();
        int affectedPopulation = estimatePopulation(totalAffectedArea);
        double estimatedDamage = totalAffectedArea * 50; // 简化估算

        SimResult result = new SimResult(totalAffectedArea, affectedPopulation,
                estimatedDamage, routes, timeline);

        DisasterSimulation sim = new DisasterSimulation(id, DisasterSimulation.DisasterType.FIRE,
                area, startTime, durationMin, DisasterSimulation.SimStatus.COMPLETED,
                params, zones, result);

        simulations.put(id, sim);
        evictIfFull();
        log.info("Fire simulation completed: id={} radiusKm={} windSpeed={} durationMin={}",
                id, radiusKm, windSpeed, durationMin);
        return sim;
    }

    /**
     * 地震模拟推演。
     *
     * @param centerLat  中心纬度
     * @param centerLon  中心经度
     * @param magnitude  震级
     * @param durationMin 模拟持续时间（分钟）
     */
    public DisasterSimulation simulateEarthquake(double centerLat, double centerLon,
                                                  double magnitude, int durationMin) {
        String id = UUID.randomUUID().toString();
        // 震级与影响半径的关系：简化估算 radiusKm = magnitude * 10
        double radiusKm = magnitude * 10;
        SimArea area = new SimArea(centerLat, centerLon, radiusKm);
        long startTime = System.currentTimeMillis();

        Map<String, Double> params = new HashMap<>();
        params.put("magnitude", magnitude);
        params.put("radiusKm", radiusKm);

        List<SimFrame> timeline = generateEarthquakeTimeline(magnitude, radiusKm, durationMin);
        List<Route> routes = generateEvacuationRoutes(centerLat, centerLon, radiusKm);
        List<String> zones = generateAffectedZones(centerLat, centerLon, radiusKm);

        double totalAffectedArea = timeline.isEmpty() ? 0 :
                timeline.get(timeline.size() - 1).getAffectedAreaKm2();
        int affectedPopulation = estimatePopulation(totalAffectedArea);
        double estimatedDamage = totalAffectedArea * magnitude * 5; // 简化估算

        SimResult result = new SimResult(totalAffectedArea, affectedPopulation,
                estimatedDamage, routes, timeline);

        DisasterSimulation sim = new DisasterSimulation(id, DisasterSimulation.DisasterType.EARTHQUAKE,
                area, startTime, durationMin, DisasterSimulation.SimStatus.COMPLETED,
                params, zones, result);

        simulations.put(id, sim);
        evictIfFull();
        log.info("Earthquake simulation completed: id={} magnitude={} durationMin={}",
                id, magnitude, durationMin);
        return sim;
    }

    /**
     * 疏散模拟推演。
     *
     * @param centerLat  中心纬度
     * @param centerLon  中心经度
     * @param radiusKm   疏散半径（公里）
     */
    public DisasterSimulation simulateEvacuation(double centerLat, double centerLon, double radiusKm) {
        String id = UUID.randomUUID().toString();
        SimArea area = new SimArea(centerLat, centerLon, radiusKm);
        long startTime = System.currentTimeMillis();
        int durationMin = 60; // 默认疏散时间 60 分钟

        Map<String, Double> params = new HashMap<>();
        params.put("radiusKm", radiusKm);

        List<SimFrame> timeline = generateEvacuationTimeline(radiusKm, durationMin);
        List<Route> routes = generateEvacuationRoutes(centerLat, centerLon, radiusKm);
        List<String> zones = generateAffectedZones(centerLat, centerLon, radiusKm);

        double totalAffectedArea = Math.PI * radiusKm * radiusKm;
        int affectedPopulation = estimatePopulation(totalAffectedArea);
        double estimatedDamage = 0; // 疏散本身不产生直接损失

        SimResult result = new SimResult(totalAffectedArea, affectedPopulation,
                estimatedDamage, routes, timeline);

        DisasterSimulation sim = new DisasterSimulation(id, DisasterSimulation.DisasterType.EVACUATION,
                area, startTime, durationMin, DisasterSimulation.SimStatus.COMPLETED,
                params, zones, result);

        simulations.put(id, sim);
        evictIfFull();
        log.info("Evacuation simulation completed: id={} radiusKm={}", id, radiusKm);
        return sim;
    }

    /**
     * 当模拟记录超过容量限制时，移除最旧的记录。
     */
    private void evictIfFull() {
        while (simulations.size() > MAX_SIMULATIONS) {
            // 移除 startTime 最小的记录（最旧的）
            String oldestId = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<String, DisasterSimulation> entry : simulations.entrySet()) {
                if (entry.getValue().getStartTime() < oldestTime) {
                    oldestTime = entry.getValue().getStartTime();
                    oldestId = entry.getKey();
                }
            }
            if (oldestId != null) {
                simulations.remove(oldestId);
                log.debug("Evicted oldest simulation: id={}", oldestId);
            } else {
                break;
            }
        }
    }

    /**
     * 获取模拟结果。
     */
    public DisasterSimulation getSimulation(String id) {
        DisasterSimulation sim = simulations.get(id);
        if (sim == null) {
            throw new NotFoundException("simulation not found: " + id);
        }
        return sim;
    }

    /**
     * 获取模拟历史。
     */
    public List<DisasterSimulation> getSimulationHistory() {
        return new ArrayList<>(simulations.values());
    }

    // --- 时间线生成 ---

    /**
     * 生成洪水模拟时间线帧。
     */
    private List<SimFrame> generateFloodTimeline(double radiusKm, double depthM, int durationMin) {
        List<SimFrame> frames = new ArrayList<>();
        int steps = Math.max(1, Math.min(durationMin, 10));
        double maxArea = Math.PI * radiusKm * radiusKm;
        for (int i = 0; i <= steps; i++) {
            int t = (durationMin * i) / steps;
            double progress = (double) i / steps;
            double affectedArea = maxArea * progress;
            String severity;
            if (depthM > 2) {
                severity = progress > 0.7 ? "CRITICAL" : progress > 0.3 ? "HIGH" : "MEDIUM";
            } else {
                severity = progress > 0.7 ? "HIGH" : progress > 0.3 ? "MEDIUM" : "LOW";
            }
            String desc = String.format("Minute %d: Water depth %.1fm, flooded area %.2f km²", t, depthM * progress, affectedArea);
            frames.add(new SimFrame(t, desc, affectedArea, severity));
        }
        return frames;
    }

    /**
     * 生成火灾模拟时间线帧。
     */
    private List<SimFrame> generateFireTimeline(double radiusKm, double windSpeed, int durationMin) {
        List<SimFrame> frames = new ArrayList<>();
        int steps = Math.max(1, Math.min(durationMin, 10));
        double maxArea = Math.PI * radiusKm * radiusKm;
        // 风速加速火势蔓延
        double spreadFactor = 1 + windSpeed / 10;
        for (int i = 0; i <= steps; i++) {
            int t = (durationMin * i) / steps;
            double progress = Math.min(1.0, ((double) i / steps) * spreadFactor);
            double affectedArea = maxArea * progress;
            String severity;
            if (windSpeed > 10) {
                severity = progress > 0.5 ? "CRITICAL" : "HIGH";
            } else {
                severity = progress > 0.7 ? "HIGH" : progress > 0.3 ? "MEDIUM" : "LOW";
            }
            String desc = String.format("Minute %d: Fire spread %.2f km², wind %.1f m/s", t, affectedArea, windSpeed);
            frames.add(new SimFrame(t, desc, affectedArea, severity));
        }
        return frames;
    }

    /**
     * 生成地震模拟时间线帧。
     */
    private List<SimFrame> generateEarthquakeTimeline(double magnitude, double radiusKm, int durationMin) {
        List<SimFrame> frames = new ArrayList<>();
        int steps = Math.max(1, Math.min(durationMin, 10));
        double maxArea = Math.PI * radiusKm * radiusKm;
        for (int i = 0; i <= steps; i++) {
            int t = (durationMin * i) / steps;
            double progress = (double) i / steps;
            double affectedArea = maxArea * progress;
            String severity;
            if (magnitude >= 7) {
                severity = progress > 0.5 ? "CRITICAL" : "HIGH";
            } else if (magnitude >= 5) {
                severity = progress > 0.7 ? "HIGH" : progress > 0.3 ? "MEDIUM" : "LOW";
            } else {
                severity = progress > 0.7 ? "MEDIUM" : "LOW";
            }
            String desc = String.format("Minute %d: Magnitude %.1f, affected area %.2f km²", t, magnitude, affectedArea);
            frames.add(new SimFrame(t, desc, affectedArea, severity));
        }
        return frames;
    }

    /**
     * 生成疏散模拟时间线帧。
     */
    private List<SimFrame> generateEvacuationTimeline(double radiusKm, int durationMin) {
        List<SimFrame> frames = new ArrayList<>();
        int steps = Math.max(1, Math.min(durationMin, 10));
        double maxArea = Math.PI * radiusKm * radiusKm;
        for (int i = 0; i <= steps; i++) {
            int t = (durationMin * i) / steps;
            double progress = (double) i / steps;
            double affectedArea = maxArea * progress;
            String severity = progress > 0.8 ? "LOW" : progress > 0.4 ? "MEDIUM" : "HIGH";
            String desc = String.format("Minute %d: Evacuation %.0f%% complete, area %.2f km²", t, progress * 100, affectedArea);
            frames.add(new SimFrame(t, desc, affectedArea, severity));
        }
        return frames;
    }

    // --- 辅助方法 ---

    private List<Route> generateEvacuationRoutes(double centerLat, double centerLon, double radiusKm) {
        List<Route> routes = new ArrayList<>();
        // 生成 3 条疏散路线（北、东、南方向）
        double[][] directions = {{0, 1}, {1, 0}, {0, -1}}; // 北、东、南
        String[] names = {"North Evacuation Route", "East Evacuation Route", "South Evacuation Route"};
        for (int i = 0; i < directions.length; i++) {
            double endLat = centerLat + directions[i][0] * radiusKm / 111.0;
            double endLon = centerLon + directions[i][1] * radiusKm / (111.0 * Math.cos(Math.toRadians(centerLat)));
            double distanceKm = radiusKm;
            int durationMin = Math.max(1, (int) (radiusKm / 30 * 60)); // 30 km/h 平均速度
            routes.add(new Route("R" + i, names[i], centerLat, centerLon, endLat, endLon, distanceKm, durationMin));
        }
        return routes;
    }

    private List<String> generateAffectedZones(double centerLat, double centerLon, double radiusKm) {
        List<String> zones = new ArrayList<>();
        // 将影响区域划分为 4 个象限
        zones.add(String.format("Zone-NW (%.4f, %.4f)", centerLat + radiusKm / 222, centerLon - radiusKm / 222));
        zones.add(String.format("Zone-NE (%.4f, %.4f)", centerLat + radiusKm / 222, centerLon + radiusKm / 222));
        zones.add(String.format("Zone-SW (%.4f, %.4f)", centerLat - radiusKm / 222, centerLon - radiusKm / 222));
        zones.add(String.format("Zone-SE (%.4f, %.4f)", centerLat - radiusKm / 222, centerLon + radiusKm / 222));
        return zones;
    }

    /**
     * 估算影响区域的人口数（简化模型：5000 人/km²）。
     */
    private int estimatePopulation(double areaKm2) {
        return (int) (areaKm2 * 5000);
    }
}
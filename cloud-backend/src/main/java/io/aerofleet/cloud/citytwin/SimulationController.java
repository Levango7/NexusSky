package io.aerofleet.cloud.citytwin;

import io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 灾害模拟推演 REST API。
 * <p>
 * 端点：
 * <ul>
 *   <li>{@code POST /api/city-twin/simulation/flood} — 洪水模拟</li>
 *   <li>{@code POST /api/city-twin/simulation/fire} — 火灾模拟</li>
 *   <li>{@code POST /api/city-twin/simulation/earthquake} — 地震模拟</li>
 *   <li>{@code POST /api/city-twin/simulation/evacuation} — 疏散模拟</li>
 *   <li>{@code GET /api/city-twin/simulation/{id}} — 获取模拟结果</li>
 *   <li>{@code GET /api/city-twin/simulation/history} — 模拟历史</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/city-twin/simulation")
@Tag(name = "CityTwin-Simulation", description = "灾害模拟推演：洪水、火灾、地震、疏散模拟及结果查询")
public class SimulationController {

    private static final Logger log = LoggerFactory.getLogger(SimulationController.class);

    private final DisasterSimulationService simulationService;

    public SimulationController(DisasterSimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping("/flood")
    @Operation(summary = "洪水模拟", description = "基于中心坐标、影响半径、水深和持续时间进行洪水灾害模拟推演")
    public DisasterSimulation simulateFlood(
            @RequestParam("centerLat") double centerLat,
            @RequestParam("centerLon") double centerLon,
            @RequestParam("radiusKm") double radiusKm,
            @RequestParam("depthM") double depthM,
            @RequestParam("durationMin") int durationMin) {
        validateGeoParams(centerLat, centerLon, radiusKm, durationMin);
        if (depthM <= 0) {
            throw new BadRequestException("depthM must be > 0");
        }
        log.info("Flood simulation request: centerLat={} centerLon={} radiusKm={} depthM={} durationMin={}",
                centerLat, centerLon, radiusKm, depthM, durationMin);
        return simulationService.simulateFlood(centerLat, centerLon, radiusKm, depthM, durationMin);
    }

    @PostMapping("/fire")
    @Operation(summary = "火灾模拟", description = "基于中心坐标、影响半径、风速和持续时间进行火灾灾害模拟推演")
    public DisasterSimulation simulateFire(
            @RequestParam("centerLat") double centerLat,
            @RequestParam("centerLon") double centerLon,
            @RequestParam("radiusKm") double radiusKm,
            @RequestParam("windSpeed") double windSpeed,
            @RequestParam("durationMin") int durationMin) {
        validateGeoParams(centerLat, centerLon, radiusKm, durationMin);
        if (windSpeed < 0) {
            throw new BadRequestException("windSpeed must be >= 0");
        }
        log.info("Fire simulation request: centerLat={} centerLon={} radiusKm={} windSpeed={} durationMin={}",
                centerLat, centerLon, radiusKm, windSpeed, durationMin);
        return simulationService.simulateFire(centerLat, centerLon, radiusKm, windSpeed, durationMin);
    }

    @PostMapping("/earthquake")
    @Operation(summary = "地震模拟", description = "基于中心坐标、震级和持续时间进行地震灾害模拟推演")
    public DisasterSimulation simulateEarthquake(
            @RequestParam("centerLat") double centerLat,
            @RequestParam("centerLon") double centerLon,
            @RequestParam("magnitude") double magnitude,
            @RequestParam("durationMin") int durationMin) {
        validateLatLon(centerLat, centerLon);
        if (magnitude <= 0) {
            throw new BadRequestException("magnitude must be > 0");
        }
        if (durationMin <= 0) {
            throw new BadRequestException("durationMin must be > 0");
        }
        log.info("Earthquake simulation request: centerLat={} centerLon={} magnitude={} durationMin={}",
                centerLat, centerLon, magnitude, durationMin);
        return simulationService.simulateEarthquake(centerLat, centerLon, magnitude, durationMin);
    }

    @PostMapping("/evacuation")
    @Operation(summary = "疏散模拟", description = "基于中心坐标和疏散半径进行疏散路线规划模拟")
    public DisasterSimulation simulateEvacuation(
            @RequestParam("centerLat") double centerLat,
            @RequestParam("centerLon") double centerLon,
            @RequestParam("radiusKm") double radiusKm) {
        validateLatLon(centerLat, centerLon);
        if (radiusKm <= 0) {
            throw new BadRequestException("radiusKm must be > 0");
        }
        log.info("Evacuation simulation request: centerLat={} centerLon={} radiusKm={}",
                centerLat, centerLon, radiusKm);
        return simulationService.simulateEvacuation(centerLat, centerLon, radiusKm);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取模拟结果", description = "根据模拟 ID 获取灾害模拟推演的详细结果")
    public DisasterSimulation getSimulation(@PathVariable("id") String id) {
        log.debug("Getting simulation: id={}", id);
        return simulationService.getSimulation(id);
    }

    @GetMapping("/history")
    @Operation(summary = "模拟历史", description = "返回所有历史灾害模拟推演记录")
    public List<DisasterSimulation> getSimulationHistory() {
        log.debug("Getting simulation history");
        return simulationService.getSimulationHistory();
    }

    // --- 参数校验辅助方法 ---

    private void validateGeoParams(double centerLat, double centerLon, double radiusKm, int durationMin) {
        validateLatLon(centerLat, centerLon);
        if (radiusKm <= 0) {
            throw new BadRequestException("radiusKm must be > 0");
        }
        if (durationMin <= 0) {
            throw new BadRequestException("durationMin must be > 0");
        }
    }

    private void validateLatLon(double centerLat, double centerLon) {
        if (centerLat < -90 || centerLat > 90) {
            throw new BadRequestException("centerLat must be between -90 and 90");
        }
        if (centerLon < -180 || centerLon > 180) {
            throw new BadRequestException("centerLon must be between -180 and 180");
        }
    }
}
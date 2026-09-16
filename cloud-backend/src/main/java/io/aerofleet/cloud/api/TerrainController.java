package io.aerofleet.cloud.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 地形 REST 端点（M8 复杂地形适配，FR-31）。
 * <p>
 * 独立路径前缀 /api/v1/terrain/*，既有端点不受影响（DFX 4.5）。
 * <p>
 * 端点清单：
 * <pre>
 * GET  /api/v1/terrain/map          获取当前地形分区图
 * GET  /api/v1/terrain/restrictions 获取飞行限制区列表
 * GET  /api/v1/terrain/changes      获取地形变更历史（分页）
 * POST /api/v1/terrain/build        触发地形建图（指挥员权限）
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/terrain")
public class TerrainController {

    private final TerrainMapService mapService;

    public TerrainController(TerrainMapService mapService) {
        this.mapService = mapService;
    }

    /** FR-31 查询当前地形分区图。 */
    @GetMapping("/map")
    public ResponseEntity<Map<String, Object>> getTerrainMap() {
        TerrainMapService.TerrainMapSnapshot snapshot = mapService.getCurrentMap();
        if (snapshot == null) {
            return ResponseEntity.ok(Map.of("available", false));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true);
        result.put("version", snapshot.version());
        result.put("mapWidth", snapshot.mapWidth());
        result.put("mapHeight", snapshot.mapHeight());
        result.put("gridResolution", snapshot.gridResolution());
        result.put("originLat", snapshot.originLat());
        result.put("originLon", snapshot.originLon());
        // gridCells 转为 List<Integer> 便于 JSON 序列化
        List<Integer> cells = new ArrayList<>(snapshot.gridCells().length);
        for (int c : snapshot.gridCells()) {
            cells.add(c);
        }
        result.put("gridCells", cells);
        result.put("timestamp", snapshot.timestamp());
        return ResponseEntity.ok(result);
    }

    /** FR-31 查询飞行限制区列表。 */
    @GetMapping("/restrictions")
    public ResponseEntity<Map<String, Object>> getRestrictions() {
        List<TerrainMapService.FlightRestrictionSnapshot> restrictions = mapService.getRestrictions();
        List<Map<String, Object>> list = new ArrayList<>();
        for (TerrainMapService.FlightRestrictionSnapshot r : restrictions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("restrictionType", r.restrictionType());
            item.put("limitValue", r.limitValue());
            List<Map<String, Double>> polygon = new ArrayList<>();
            for (double[] p : r.areaPolygon()) {
                Map<String, Double> point = new LinkedHashMap<>();
                point.put("lat", p[0]);
                point.put("lon", p[1]);
                polygon.add(point);
            }
            item.put("area", polygon);
            item.put("timestamp", r.timestamp());
            list.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", list.size());
        result.put("restrictions", list);
        return ResponseEntity.ok(result);
    }

    /** FR-31 查询地形变更历史（分页）。 */
    @GetMapping("/changes")
    public ResponseEntity<Map<String, Object>> getChanges(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        List<TerrainMapService.TerrainChangeRecord> history = mapService.getChangeHistory(offset, limit);
        List<Map<String, Object>> list = new ArrayList<>();
        for (TerrainMapService.TerrainChangeRecord r : history) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("terrainVersion", r.terrainVersion());
            item.put("changeReason", r.changeReason());
            List<Map<String, Integer>> cells = new ArrayList<>();
            for (int[] c : r.affectedCells()) {
                Map<String, Integer> cell = new LinkedHashMap<>();
                cell.put("gridIndex", c[0]);
                cell.put("newTerrainType", c[1]);
                cells.add(cell);
            }
            item.put("affectedCells", cells);
            item.put("timestamp", r.timestamp());
            list.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("offset", offset);
        result.put("limit", limit);
        result.put("count", list.size());
        result.put("changes", list);
        return ResponseEntity.ok(result);
    }

    /** FR-31 触发地形建图（指挥员权限）。 */
    @PostMapping("/build")
    public ResponseEntity<Map<String, Object>> buildTerrainMap(@RequestBody BuildRequest req) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "accepted");
        result.put("originLat", req.originLat);
        result.put("originLon", req.originLon);
        result.put("widthM", req.widthM);
        result.put("heightM", req.heightM);
        result.put("gridResolution", req.gridResolution);
        result.put("message", "terrain build triggered (async)");
        return ResponseEntity.accepted().body(result);
    }

    /** 建图请求体。 */
    public static class BuildRequest {
        public double originLat;
        public double originLon;
        public double widthM;
        public double heightM;
        public double gridResolution;
    }
}
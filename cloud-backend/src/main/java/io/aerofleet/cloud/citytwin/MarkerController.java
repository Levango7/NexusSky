package io.aerofleet.cloud.citytwin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 态势标绘 REST API。
 * <p>
 * 端点：
 * <ul>
 *   <li>{@code GET /api/city-twin/markers} — 列出所有标绘</li>
 *   <li>{@code POST /api/city-twin/markers} — 创建标绘</li>
 *   <li>{@code DELETE /api/city-twin/markers/{id}} — 删除标绘</li>
 *   <li>{@code PUT /api/city-twin/markers/{id}} — 更新标绘</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/city-twin/markers")
@Tag(name = "CityTwin-Markers", description = "态势标绘：在地图上创建、更新、删除点/线/面/圆/文本标记")
public class MarkerController {

    private static final Logger log = LoggerFactory.getLogger(MarkerController.class);

    private final SituationMarkerService markerService;

    public MarkerController(SituationMarkerService markerService) {
        this.markerService = markerService;
    }

    @GetMapping
    @Operation(summary = "列出所有标绘", description = "返回当前所有态势标绘标记列表")
    public List<SituationMarker> listMarkers() {
        log.debug("Listing all markers");
        return markerService.listMarkers();
    }

    @PostMapping
    @Operation(summary = "创建标绘", description = "创建一个新的态势标绘标记")
    public SituationMarker createMarker(@RequestBody SituationMarker marker) {
        log.info("Creating marker: type={} label={}", marker.getType(), marker.getLabel());
        return markerService.createMarker(marker);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除标绘", description = "根据标绘 ID 删除态势标绘标记")
    public void deleteMarker(@PathVariable("id") String id) {
        log.info("Deleting marker: id={}", id);
        markerService.deleteMarker(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新标绘", description = "根据标绘 ID 更新态势标绘标记的内容")
    public SituationMarker updateMarker(@PathVariable("id") String id, @RequestBody SituationMarker marker) {
        log.info("Updating marker: id={}", id);
        return markerService.updateMarker(id, marker);
    }
}
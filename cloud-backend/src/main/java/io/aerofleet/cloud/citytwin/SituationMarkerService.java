package io.aerofleet.cloud.citytwin;

import io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException;
import io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 态势标绘管理服务，支持在地图上创建、更新、删除标绘标记。
 */
@Service
public class SituationMarkerService {

    private static final Logger log = LoggerFactory.getLogger(SituationMarkerService.class);

    private final ConcurrentMap<String, SituationMarker> markers = new ConcurrentHashMap<>();

    /**
     * 列出所有标绘。
     */
    public List<SituationMarker> listMarkers() {
        return new ArrayList<>(markers.values());
    }

    /**
     * 创建标绘。
     */
    public SituationMarker createMarker(SituationMarker marker) {
        if (marker.getId() == null || marker.getId().isEmpty()) {
            marker.setId(UUID.randomUUID().toString());
        }
        if (marker.getTimestamp() == 0) {
            marker.setTimestamp(System.currentTimeMillis());
        }
        SituationMarker existing = markers.putIfAbsent(marker.getId(), marker);
        if (existing != null) {
            throw new BadRequestException("marker already exists with id: " + marker.getId());
        }
        log.info("Marker created: id={} type={}", marker.getId(), marker.getType());
        return marker;
    }

    /**
     * 删除标绘。
     */
    public void deleteMarker(String id) {
        SituationMarker removed = markers.remove(id);
        if (removed == null) {
            throw new NotFoundException("marker not found: " + id);
        }
        log.info("Marker deleted: id={}", id);
    }

    /**
     * 更新标绘。
     */
    public SituationMarker updateMarker(String id, SituationMarker marker) {
        SituationMarker[] resultHolder = new SituationMarker[1];
        markers.computeIfPresent(id, (key, existing) -> {
            marker.setId(id);
            marker.setTimestamp(System.currentTimeMillis());
            resultHolder[0] = marker;
            return marker;
        });
        if (resultHolder[0] == null) {
            throw new NotFoundException("marker not found: " + id);
        }
        log.info("Marker updated: id={}", id);
        return resultHolder[0];
    }
}
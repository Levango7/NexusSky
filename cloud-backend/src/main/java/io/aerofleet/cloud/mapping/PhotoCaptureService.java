package io.aerofleet.cloud.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 影像采集服务。
 * <p>
 * 模拟无人机在航点处采集影像数据，生成带 GPS 标注的照片记录。
 * 提供按任务、按区域查询照片的功能。
 */
@Service
public class PhotoCaptureService {

    private static final Logger log = LoggerFactory.getLogger(PhotoCaptureService.class);

    /** 按任务 ID 分组存储采集的照片。 */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<CapturedPhoto>> photosByTask
            = new ConcurrentHashMap<>();

    /**
     * 在指定航点处采集一张照片。
     *
     * @param sysid 无人机 systemId
     * @param wp    航点信息
     * @return 采集的照片记录
     */
    public CapturedPhoto capture(int sysid, MappingWaypoint wp) {
        String photoId = UUID.randomUUID().toString();
        // 模拟文件大小 3~8 MB
        long fileSize = 3_000_000 + (long) (Math.random() * 5_000_000);
        String url = "https://storage.mapping/photos/" + photoId + ".jpg";

        CapturedPhoto photo = new CapturedPhoto(
                photoId,
                null, // taskId 由调用方设置
                sysid,
                wp.lat,
                wp.lon,
                wp.alt,
                wp.headingDeg,
                wp.cameraAngleDeg, // pitchDeg = cameraAngleDeg
                0.0, // rollDeg 模拟为 0
                Instant.now(),
                fileSize,
                url
        );

        log.debug("Photo captured: sysid={} lat={} lon={} alt={} photoId={}",
                sysid, wp.lat, wp.lon, wp.alt, photoId);
        return photo;
    }

    /**
     * 在指定航点处采集一张照片，并关联到任务。
     *
     * @param sysid  无人机 systemId
     * @param wp     航点信息
     * @param taskId 测绘任务 ID
     * @return 采集的照片记录
     */
    public CapturedPhoto capture(int sysid, MappingWaypoint wp, String taskId) {
        CapturedPhoto photo = capture(sysid, wp);
        photo.setTaskId(taskId);
        photosByTask.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(photo);
        log.info("Photo captured for task {}: photoId={} total={}",
                taskId, photo.getId(), photosByTask.get(taskId).size());
        return photo;
    }

    /**
     * 获取任务的所有采集照片。
     *
     * @param taskId 测绘任务 ID
     * @return 照片列表
     */
    public List<CapturedPhoto> getPhotos(String taskId) {
        return new ArrayList<>(photosByTask.getOrDefault(taskId, new CopyOnWriteArrayList<>()));
    }

    /**
     * 获取任务中落在指定区域内的照片。
     *
     * @param taskId 测绘任务 ID
     * @param area   筛选区域
     * @return 区域内照片列表
     */
    public List<CapturedPhoto> getPhotosByArea(String taskId, MappingArea area) {
        List<CapturedPhoto> all = photosByTask.getOrDefault(taskId, new CopyOnWriteArrayList<>());
        return all.stream()
                .filter(p -> area.contains(p.getLat(), p.getLon()))
                .collect(Collectors.toList());
    }

    /**
     * 为任务批量模拟采集照片（用于测试与模拟流程）。
     *
     * @param taskId    测绘任务 ID
     * @param sysid     无人机 systemId
     * @param waypoints 航点列表
     * @return 采集的照片列表
     */
    public List<CapturedPhoto> simulateCapture(String taskId, int sysid,
                                               List<MappingWaypoint> waypoints) {
        List<CapturedPhoto> photos = new ArrayList<>();
        for (MappingWaypoint wp : waypoints) {
            if (wp.action == MappingWaypoint.Action.PHOTO) {
                photos.add(capture(sysid, wp, taskId));
            }
        }
        log.info("Simulated capture for task {}: photos={} waypoints={}",
                taskId, photos.size(), waypoints.size());
        return photos;
    }
}
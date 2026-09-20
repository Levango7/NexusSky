package io.aerofleet.cloud.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测绘成果生成服务。
 * <p>
 * 模拟正射影像、DEM、三维模型的生成过程，产出包含下载链接与质量元数据的成果记录。
 */
@Service
public class MappingResultService {

    private static final Logger log = LoggerFactory.getLogger(MappingResultService.class);

    /** 按成果 ID 存储的成果列表。 */
    private final ConcurrentHashMap<String, MappingResult> resultsById = new ConcurrentHashMap<>();
    /** 按任务 ID 分组存储的成果列表。 */
    private final ConcurrentHashMap<String, List<MappingResult>> resultsByTask = new ConcurrentHashMap<>();

    /**
     * 生成正射影像成果。
     *
     * @param taskId 测绘任务 ID
     * @param photos 采集的照片列表
     * @return 测绘成果记录
     */
    public MappingResult generateOrthophoto(String taskId, List<CapturedPhoto> photos) {
        log.info("Generating orthophoto for task {} with {} photos", taskId, photos.size());

        String resultId = UUID.randomUUID().toString();
        String orthophotoUrl = "https://storage.mapping/results/" + resultId + "/orthophoto.tif";

        // 模拟处理参数
        double coveragePct = Math.min(100.0, 85.0 + Math.random() * 15);
        double resolutionCm = photos.isEmpty() ? 5.0 : photos.get(0).getAlt() / 100.0;
        long processingTimeSec = 30 + (long) (Math.random() * 60);
        double fileSizeMB = 50 + Math.random() * 200;

        MappingResult result = new MappingResult(
                resultId,
                taskId,
                MappingType.ORTHO_PHOTO,
                MappingResult.Status.COMPLETED,
                Instant.now(),
                orthophotoUrl,
                null,
                null,
                coveragePct,
                resolutionCm,
                processingTimeSec,
                fileSizeMB
        );

        storeResult(taskId, result);
        log.info("Orthophoto generated: id={} coverage={}%, resolution={}cm, size={}MB",
                resultId, coveragePct, resolutionCm, fileSizeMB);
        return result;
    }

    /**
     * 生成 DEM 成果。
     *
     * @param taskId 测绘任务 ID
     * @param photos 采集的照片列表
     * @return 测绘成果记录
     */
    public MappingResult generateDem(String taskId, List<CapturedPhoto> photos) {
        log.info("Generating DEM for task {} with {} photos", taskId, photos.size());

        String resultId = UUID.randomUUID().toString();
        String demUrl = "https://storage.mapping/results/" + resultId + "/dem.tif";

        // 模拟处理参数
        double coveragePct = Math.min(100.0, 80.0 + Math.random() * 20);
        double resolutionCm = photos.isEmpty() ? 10.0 : photos.get(0).getAlt() / 50.0;
        long processingTimeSec = 60 + (long) (Math.random() * 120);
        double fileSizeMB = 20 + Math.random() * 100;

        MappingResult result = new MappingResult(
                resultId,
                taskId,
                MappingType.DEM,
                MappingResult.Status.COMPLETED,
                Instant.now(),
                null,
                demUrl,
                null,
                coveragePct,
                resolutionCm,
                processingTimeSec,
                fileSizeMB
        );

        storeResult(taskId, result);
        log.info("DEM generated: id={} coverage={}%, resolution={}cm, size={}MB",
                resultId, coveragePct, resolutionCm, fileSizeMB);
        return result;
    }

    /**
     * 生成三维模型成果。
     *
     * @param taskId 测绘任务 ID
     * @param photos 采集的照片列表
     * @return 测绘成果记录
     */
    public MappingResult generate3DModel(String taskId, List<CapturedPhoto> photos) {
        log.info("Generating 3D model for task {} with {} photos", taskId, photos.size());

        String resultId = UUID.randomUUID().toString();
        String modelUrl = "https://storage.mapping/results/" + resultId + "/model.obj";

        // 模拟处理参数
        double coveragePct = Math.min(100.0, 75.0 + Math.random() * 25);
        double resolutionCm = photos.isEmpty() ? 3.0 : photos.get(0).getAlt() / 150.0;
        long processingTimeSec = 120 + (long) (Math.random() * 300);
        double fileSizeMB = 100 + Math.random() * 500;

        MappingResult result = new MappingResult(
                resultId,
                taskId,
                MappingType.THREE_D_MODEL,
                MappingResult.Status.COMPLETED,
                Instant.now(),
                null,
                null,
                modelUrl,
                coveragePct,
                resolutionCm,
                processingTimeSec,
                fileSizeMB
        );

        storeResult(taskId, result);
        log.info("3D model generated: id={} coverage={}%, resolution={}cm, size={}MB",
                resultId, coveragePct, resolutionCm, fileSizeMB);
        return result;
    }

    /**
     * 获取任务的所有测绘成果。
     *
     * @param taskId 测绘任务 ID
     * @return 成果列表
     */
    public List<MappingResult> getResultsByTask(String taskId) {
        return new ArrayList<>(resultsByTask.getOrDefault(taskId, new ArrayList<>()));
    }

    /**
     * 获取所有测绘成果。
     *
     * @return 成果列表
     */
    public List<MappingResult> getAllResults() {
        return new ArrayList<>(resultsById.values());
    }

    /**
     * 根据 ID 获取测绘成果。
     *
     * @param resultId 成果 ID
     * @return 成果记录，不存在返回 null
     */
    public MappingResult getResult(String resultId) {
        return resultsById.get(resultId);
    }

    private void storeResult(String taskId, MappingResult result) {
        resultsById.put(result.getId(), result);
        resultsByTask.computeIfAbsent(taskId, k -> new ArrayList<>()).add(result);
    }
}
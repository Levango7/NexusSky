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
import java.util.concurrent.ThreadLocalRandom;

/**
 * 测绘成果生成服务。
 * <p>
 * 模拟正射影像、DEM、三维模型的生成过程，产出带质量元数据的成果记录。
 * <p>
 * <b>诚实边界（2026-10-12 外部评估整改）</b>：成果一律带 {@code simulated=true}，
 * 且**不再产出下载 URL**——没有真实成果存储，给一个能点开的假域名只会让人误以为
 * 系统坏了。真实产物要等摄影测量管线接入（属生产阶段项），届时把 simulated 置 false
 * 并回填真实 URL 即可，字段契约不变。
 */
@Service
public class MappingResultService {

    private static final Logger log = LoggerFactory.getLogger(MappingResultService.class);

    /** 模拟成果的统一原因（字段契约的一部分：调用方可据此在界面上标注）。 */
    private static final String SIMULATED_REASON =
            "真摄影测量管线未接入：无空中三角测量/正射校正/三维重建产物，本记录仅演示字段形状";

    /** 按成果 ID 存储的成果列表。 */
    private final ConcurrentHashMap<String, MappingResult> resultsById = new ConcurrentHashMap<>();
    /** 按任务 ID 分组存储的成果列表。 */
    private final ConcurrentHashMap<String, List<MappingResult>> resultsByTask = new ConcurrentHashMap<>();

    /**
     * 成果保留条数上限。超出后按写入顺序淘汰最旧，并同步清理按任务索引
     * （否则索引里会长出指向已淘汰成果的孤儿条目）。
     */
    static final int MAX_RESULTS = 500;

    /** 成果写入顺序（resultId），用于淘汰最旧。 */
    private final java.util.Deque<String> resultOrder = new java.util.concurrent.ConcurrentLinkedDeque<>();

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
        // 没有真实成果存储，就不该给一个点得开的假域名（2026-10-12 外部评估整改）
        String orthophotoUrl = null;

        // P1-fix: 使用 ThreadLocalRandom 替代 Math.random()，提升多线程性能
        double coveragePct = Math.min(100.0, 85.0 + ThreadLocalRandom.current().nextDouble() * 15);
        double resolutionCm = photos.isEmpty() ? 5.0 : photos.get(0).getAlt() / 100.0;
        long processingTimeSec = 30 + (long) (ThreadLocalRandom.current().nextDouble() * 60);
        double fileSizeMB = 50 + ThreadLocalRandom.current().nextDouble() * 200;

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
        result.setSimulated(true);
        result.setSimulatedReason(SIMULATED_REASON);

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
        String demUrl = null;

        // P1-fix: 使用 ThreadLocalRandom 替代 Math.random()，提升多线程性能
        double coveragePct = Math.min(100.0, 80.0 + ThreadLocalRandom.current().nextDouble() * 20);
        double resolutionCm = photos.isEmpty() ? 10.0 : photos.get(0).getAlt() / 50.0;
        long processingTimeSec = 60 + (long) (ThreadLocalRandom.current().nextDouble() * 120);
        double fileSizeMB = 20 + ThreadLocalRandom.current().nextDouble() * 100;

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
        result.setSimulated(true);
        result.setSimulatedReason(SIMULATED_REASON);

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
        String modelUrl = null;

        // P1-fix: 使用 ThreadLocalRandom 替代 Math.random()，提升多线程性能
        double coveragePct = Math.min(100.0, 75.0 + ThreadLocalRandom.current().nextDouble() * 25);
        double resolutionCm = photos.isEmpty() ? 3.0 : photos.get(0).getAlt() / 150.0;
        long processingTimeSec = 120 + (long) (ThreadLocalRandom.current().nextDouble() * 300);
        double fileSizeMB = 100 + ThreadLocalRandom.current().nextDouble() * 500;

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
        result.setSimulated(true);
        result.setSimulatedReason(SIMULATED_REASON);

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
        resultsByTask.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(result);
        resultOrder.addLast(result.getId());
        evictOldestResults();
    }

    /**
     * 淘汰最旧成果，直到回到上限。
     * <p>
     * **两个 map 必须一起清**：只删 {@link #resultsById} 会让 {@link #resultsByTask}
     * 留下指向已淘汰成果的孤儿条目；只删索引则 ID 检索还能查到没人引用的成果。
     * 任务列表被清空时把 key 一并移除，否则 key 本身会随任务数继续泄漏。
     */
    private void evictOldestResults() {
        while (resultsById.size() > MAX_RESULTS) {
            String oldestId = resultOrder.pollFirst();
            if (oldestId == null) {
                return;
            }
            MappingResult removed = resultsById.remove(oldestId);
            if (removed == null) {
                continue;
            }
            resultsByTask.computeIfPresent(removed.getTaskId(),
                    (id, list) -> {
                        list.remove(removed);
                        return list.isEmpty() ? null : list;
                    });
        }
    }
}
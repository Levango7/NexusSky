package io.aerofleet.cloud.delivery2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 配送状态追踪服务。
 * <p>
 * 维护配送任务的实时状态，支持状态查询和更新。
 * 状态流转：CREATED → ASSIGNED → PICKED_UP → IN_TRANSIT → APPROACHING → DELIVERING → DELIVERED
 * <p>
 * V36（P1#5 裁决：六阶段上位）：阶段是权威、粗状态由 {@link #coarseOf} 派生并在写穿时同步落库
 * （单一写入点=始终一致）；内存未命中（重启）从任务行读穿——修掉"任务在库、状态 404"的缺口。
 * 仓库缺失（纯单测构造）时退化为纯内存，语义不变。
 */
@Service
public class DeliveryStatusTracker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryStatusTracker.class);

    private final ConcurrentHashMap<String, DeliveryStatus> statusMap = new ConcurrentHashMap<>();

    /** 任务仓库（可选；缺失时纯内存语义）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DeliveryTask2Repository repository;

    /**
     * 六阶段 → 粗状态派生表（P1#5 裁决的"派生"落点）。不含 CONFIRMED/FAILED/ABORTED——
     * 那是粗状态独有的终态，由各自端点写，不由阶段派生（派生只会前向推进到 DELIVERED）。
     */
    static DeliveryTask2.Status coarseOf(DeliveryStatus.Phase phase) {
        return switch (phase) {
            case CREATED -> DeliveryTask2.Status.PENDING;
            case ASSIGNED, PICKED_UP, IN_TRANSIT, APPROACHING, DELIVERING ->
                    DeliveryTask2.Status.IN_PROGRESS;
            case DELIVERED -> DeliveryTask2.Status.DELIVERED;
        };
    }

    /** 写穿：阶段 + 位置 + 派生粗状态一次写入。异常只告警——追踪是旁路，不阻塞主链。 */
    private void persist(DeliveryStatus status) {
        if (repository == null) {
            return;
        }
        try {
            repository.findById(status.getTaskId()).ifPresent(task -> {
                task.setPhase(status.getPhase());
                task.setCurrentLat(status.getCurrentLat());
                task.setCurrentLon(status.getCurrentLon());
                task.setRemainingDistanceKm(status.getRemainingDistanceKm());
                task.setEstimatedArrivalMin(status.getEstimatedArrivalMin());
                task.setPayloadCondition(status.getPayloadCondition());
                task.setStatus(coarseOf(status.getPhase()));
                repository.save(task);
            });
        } catch (Exception e) {
            log.warn("配送状态写穿失败 taskId={}：{}", status.getTaskId(), e.getMessage());
        }
    }

    /**
     * 获取配送任务状态（内存 → 读穿）。
     *
     * @param taskId 任务 ID
     * @return 配送状态；任务行无 phase（FAILED/ABORTED 历史行或库中无此任务）时返回 null
     */
    public DeliveryStatus getStatus(String taskId) {
        DeliveryStatus cached = statusMap.get(taskId);
        if (cached != null) {
            return cached;
        }
        return readThrough(taskId);
    }

    /** 读穿（重启/多实例）：任务行有 phase 才重建并回填缓存；NULL 如实返回 null（404 语义不被污染）。 */
    private DeliveryStatus readThrough(String taskId) {
        if (repository == null) {
            return null;
        }
        try {
            return repository.findById(taskId)
                    .filter(task -> task.getPhase() != null)
                    .map(task -> {
                        DeliveryStatus status = new DeliveryStatus(taskId, task.getPhase(),
                                task.getCurrentLat() == null ? 0 : task.getCurrentLat(),
                                task.getCurrentLon() == null ? 0 : task.getCurrentLon(),
                                task.getRemainingDistanceKm() == null ? 0 : task.getRemainingDistanceKm(),
                                task.getEstimatedArrivalMin() == null ? 0 : task.getEstimatedArrivalMin(),
                                task.getPayloadCondition() == null
                                        ? DeliveryStatus.PayloadCondition.NORMAL
                                        : task.getPayloadCondition());
                        statusMap.put(taskId, status);
                        return status;
                    })
                    .orElse(null);
        } catch (Exception e) {
            log.warn("配送状态读穿失败 taskId={}：{}", taskId, e.getMessage());
            return null;
        }
    }

    /**
     * 更新配送任务状态。
     *
     * @param taskId 任务 ID
     * @param status 新状态
     */
    public void updateStatus(String taskId, DeliveryStatus status) {
        if (taskId == null || status == null) {
            log.warn("更新状态失败：taskId 或 status 为空");
            return;
        }
        status.setTaskId(taskId);
        statusMap.put(taskId, status);
        persist(status);
        log.info("配送状态更新：taskId={} phase={}", taskId, status.getPhase());
    }

    /**
     * 初始化任务状态（CREATED 阶段）。
     *
     * @param taskId 任务 ID
     */
    public void initStatus(String taskId) {
        DeliveryStatus status = new DeliveryStatus(taskId, DeliveryStatus.Phase.CREATED,
                0, 0, 0, 0, DeliveryStatus.PayloadCondition.NORMAL);
        DeliveryStatus actual = statusMap.putIfAbsent(taskId, status);
        persist(actual != null ? actual : status);
        log.info("配送状态初始化：taskId={}", taskId);
    }

    /**
     * 推进到下一阶段。
     *
     * @param taskId 任务 ID
     * @return 是否推进成功
     */
    public boolean advancePhase(String taskId) {
        boolean[] advanced = {false};
        statusMap.computeIfPresent(taskId, (key, status) -> {
            DeliveryStatus.Phase current = status.getPhase();
            DeliveryStatus.Phase next = nextPhase(current);
            if (next == null) {
                log.warn("推进状态失败：taskId={} 已处于终态 {}", taskId, current);
                return status;
            }
            status.setPhase(next);
            advanced[0] = true;
            log.info("配送状态推进：taskId={} {} → {}", taskId, current, next);
            return status;
        });
        if (advanced[0]) {
            // 写穿在 compute 之外做：不把仓库 I/O 塞进 ConcurrentHashMap 的键锁内
            DeliveryStatus current = statusMap.get(taskId);
            if (current != null) {
                persist(current);
            }
        }
        if (!advanced[0] && !statusMap.containsKey(taskId)) {
            log.warn("推进状态失败：taskId={} 不存在", taskId);
        }
        return advanced[0];
    }

    /**
     * 获取下一阶段。
     *
     * @param current 当前阶段
     * @return 下一阶段，已是终态则返回 null
     */
    private DeliveryStatus.Phase nextPhase(DeliveryStatus.Phase current) {
        return switch (current) {
            case CREATED -> DeliveryStatus.Phase.ASSIGNED;
            case ASSIGNED -> DeliveryStatus.Phase.PICKED_UP;
            case PICKED_UP -> DeliveryStatus.Phase.IN_TRANSIT;
            case IN_TRANSIT -> DeliveryStatus.Phase.APPROACHING;
            case APPROACHING -> DeliveryStatus.Phase.DELIVERING;
            case DELIVERING -> DeliveryStatus.Phase.DELIVERED;
            case DELIVERED -> null;
        };
    }
}

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
 */
@Service
public class DeliveryStatusTracker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryStatusTracker.class);

    private final ConcurrentHashMap<String, DeliveryStatus> statusMap = new ConcurrentHashMap<>();

    /**
     * 获取配送任务状态。
     *
     * @param taskId 任务 ID
     * @return 配送状态，不存在时返回 null
     */
    public DeliveryStatus getStatus(String taskId) {
        return statusMap.get(taskId);
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
        statusMap.putIfAbsent(taskId, status);
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
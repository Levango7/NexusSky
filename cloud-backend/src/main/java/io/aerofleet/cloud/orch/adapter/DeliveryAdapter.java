package io.aerofleet.cloud.orch.adapter;

import io.aerofleet.cloud.delivery2.DeliveryController2;
import io.aerofleet.cloud.delivery2.DeliveryStatus;
import io.aerofleet.cloud.delivery2.DeliveryTask2;
import io.aerofleet.cloud.delivery2.Payload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 配送模块适配器，注入 DeliveryController2 进行同进程调用。
 * <p>
 * 将配送任务的创建、启动、中止、状态查询统一封装为 ModuleAdapter 接口。
 */
@Component
public class DeliveryAdapter implements ModuleAdapter {

    private static final Logger log = LoggerFactory.getLogger(DeliveryAdapter.class);

    private final DeliveryController2 deliveryController;

    public DeliveryAdapter(DeliveryController2 deliveryController) {
        this.deliveryController = deliveryController;
    }

    @Override
    public ModuleResult createTask(Map<String, Object> params) {
        try {
            DeliveryTask2 task = buildDeliveryTask(params);
            DeliveryTask2 created = deliveryController.createTask(task);
            String status = created.getStatus() != null ? created.getStatus().name() : "PENDING";
            log.info("配送任务创建成功：id={}, type={}", created.getId(), created.getType());
            return ModuleResult.ok(created.getId(), status);
        } catch (Exception e) {
            log.warn("配送任务创建失败：{}", e.getMessage());
            return ModuleResult.fail(null, e.getMessage());
        }
    }

    @Override
    public ModuleResult startTask(String taskId) {
        try {
            DeliveryTask2 task = deliveryController.startTask(taskId);
            String status = task.getStatus() != null ? task.getStatus().name() : "IN_PROGRESS";
            log.info("配送任务启动：id={}", taskId);
            return ModuleResult.ok(taskId, status);
        } catch (Exception e) {
            log.warn("配送任务启动失败：taskId={}, error={}", taskId, e.getMessage());
            return ModuleResult.fail(taskId, e.getMessage());
        }
    }

    @Override
    public ModuleResult abortTask(String taskId) {
        try {
            DeliveryTask2 task = deliveryController.abortTask(taskId);
            String status = task.getStatus() != null ? task.getStatus().name() : "ABORTED";
            log.info("配送任务中止：id={}", taskId);
            return ModuleResult.ok(taskId, status);
        } catch (Exception e) {
            log.warn("配送任务中止失败：taskId={}, error={}", taskId, e.getMessage());
            return ModuleResult.fail(taskId, e.getMessage());
        }
    }

    @Override
    public ModuleResult getTaskStatus(String taskId) {
        try {
            DeliveryStatus status = deliveryController.getStatus(taskId);
            if (status == null) {
                return ModuleResult.fail(taskId, "no status for task " + taskId);
            }
            return ModuleResult.ok(taskId, status.getPhase().name());
        } catch (Exception e) {
            log.warn("配送状态查询失败：taskId={}, error={}", taskId, e.getMessage());
            return ModuleResult.fail(taskId, e.getMessage());
        }
    }

    @Override
    public String getModuleType() {
        return "DELIVERY";
    }

    /**
     * 从参数 Map 构造配送任务对象。
     * <p>
     * 期望参数键：
     * <ul>
     *   <li>type — 配送类型（EMERGENCY_SUPPLY / MEDICAL_SAMPLE / REGULAR_PARCEL）</li>
     *   <li>senderLat, senderLon — 发件人坐标</li>
     *   <li>receiverLat, receiverLon — 收件人坐标</li>
     *   <li>receiverName — 收件人名称</li>
     *   <li>payloadWeightKg — 货物重量</li>
     *   <li>payloadType — 货物类型</li>
     *   <li>priority — 优先级（HIGH / NORMAL / LOW）</li>
     *   <li>assignedSysid — 分配无人机 sysid</li>
     * </ul>
     */
    private DeliveryTask2 buildDeliveryTask(Map<String, Object> params) {
        DeliveryTask2 task = new DeliveryTask2();

        String typeStr = (String) params.getOrDefault("type", "REGULAR_PARCEL");
        task.setType(DeliveryTask2.Type.valueOf(typeStr.toUpperCase()));

        task.setSenderLat(toDouble(params.get("senderLat"), 0.0));
        task.setSenderLon(toDouble(params.get("senderLon"), 0.0));
        task.setReceiverLat(toDouble(params.get("receiverLat"), 0.0));
        task.setReceiverLon(toDouble(params.get("receiverLon"), 0.0));
        task.setReceiverName((String) params.getOrDefault("receiverName", ""));

        // 构造 Payload
        double weightKg = toDouble(params.get("payloadWeightKg"), 1.0);
        String payloadTypeStr = (String) params.getOrDefault("payloadType", "EQUIPMENT");
        Payload.Type payloadType = Payload.Type.valueOf(payloadTypeStr.toUpperCase());
        Payload payload = new Payload();
        payload.setWeightKg(weightKg);
        payload.setType(payloadType);
        task.setPayload(payload);

        // 优先级
        String priorityStr = (String) params.getOrDefault("priority", "NORMAL");
        task.setPriority(DeliveryTask2.Priority.valueOf(priorityStr.toUpperCase()));

        // 分配无人机
        Object sysidObj = params.get("assignedSysid");
        if (sysidObj != null) {
            task.setAssignedSysid(toInt(sysidObj, 0));
        }

        return task;
    }

    private double toDouble(Object value, double defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.doubleValue();
        return Double.parseDouble(value.toString());
    }

    private int toInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(value.toString());
    }
}
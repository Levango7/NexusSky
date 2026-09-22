package io.aerofleet.cloud.orch.adapter;

import io.aerofleet.cloud.mapping.MappingController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

import java.util.List;
import java.util.Map;

/**
 * 测绘模块适配器，注入 MappingController 进行同进程调用。
 * <p>
 * 将测绘任务的创建、启动、中止、状态查询统一封装为 ModuleAdapter 接口。
 */
@Component
public class MappingAdapter implements ModuleAdapter {

    private static final Logger log = LoggerFactory.getLogger(MappingAdapter.class);

    private final MappingController mappingController;

    public MappingAdapter(MappingController mappingController) {
        this.mappingController = mappingController;
    }

    @Override
    public ModuleResult createTask(Map<String, Object> params) {
        try {
            MappingController.CreateTaskRequest req = buildCreateRequest(params);
            Map<String, Object> result = mappingController.createTask(req);
            String taskId = (String) result.get("id");
            String status = (String) result.getOrDefault("status", "PENDING");
            log.info("测绘任务创建成功：id={}, type={}", taskId, result.get("type"));
            return ModuleResult.ok(taskId, status);
        } catch (Exception e) {
            log.warn("测绘任务创建失败：{}", e.getMessage());
            return ModuleResult.fail(null, e.getMessage());
        }
    }

    @Override
    public ModuleResult startTask(String taskId) {
        try {
            Map<String, Object> result = mappingController.startTask(taskId);
            String status = (String) result.getOrDefault("status", "IN_PROGRESS");
            log.info("测绘任务启动：id={}", taskId);
            return ModuleResult.ok(taskId, status);
        } catch (Exception e) {
            log.warn("测绘任务启动失败：taskId={}, error={}", taskId, e.getMessage());
            return ModuleResult.fail(taskId, e.getMessage());
        }
    }

    @Override
    public ModuleResult abortTask(String taskId) {
        try {
            Map<String, Object> result = mappingController.abortTask(taskId);
            String status = (String) result.getOrDefault("status", "FAILED");
            log.info("测绘任务中止：id={}", taskId);
            return ModuleResult.ok(taskId, status);
        } catch (Exception e) {
            log.warn("测绘任务中止失败：taskId={}, error={}", taskId, e.getMessage());
            return ModuleResult.fail(taskId, e.getMessage());
        }
    }

    @Override
    public ModuleResult getTaskStatus(String taskId) {
        try {
            Map<String, Object> result = mappingController.getTask(taskId);
            String status = (String) result.getOrDefault("status", "UNKNOWN");
            return ModuleResult.ok(taskId, status);
        } catch (Exception e) {
            log.warn("测绘状态查询失败：taskId={}, error={}", taskId, e.getMessage());
            return ModuleResult.fail(taskId, e.getMessage());
        }
    }

    @Override
    public String getModuleType() {
        return "MAPPING";
    }

    /**
     * 从参数 Map 构造测绘创建请求。
     * <p>
     * 期望参数键：
     * <ul>
     *   <li>name — 任务名称</li>
     *   <li>type — 测绘类型（ORTHO_PHOTO / DEM / THREE_D_MODEL / MIXED）</li>
     *   <li>sysid — 分配无人机 systemId</li>
     *   <li>altitudeM — 飞行高度（米）</li>
     *   <li>overlapPct — 航向重叠率（%）</li>
     *   <li>sidelapPct — 侧向重叠率（%）</li>
     *   <li>cameraAngleDeg — 相机俯仰角（度）</li>
     *   <li>area — 测绘区域定义</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private MappingController.CreateTaskRequest buildCreateRequest(Map<String, Object> params) {
        MappingController.CreateTaskRequest req = new MappingController.CreateTaskRequest();
        req.name = (String) params.getOrDefault("name", "mapping-task");
        req.type = (String) params.getOrDefault("type", "ORTHO_PHOTO");
        req.sysid = params.get("sysid") != null ? toInt(params.get("sysid"), 1) : null;
        req.altitudeM = params.get("altitudeM") != null ? toDouble(params.get("altitudeM"), 100.0) : null;
        req.overlapPct = params.get("overlapPct") != null ? toDouble(params.get("overlapPct"), 80.0) : null;
        req.sidelapPct = params.get("sidelapPct") != null ? toDouble(params.get("sidelapPct"), 60.0) : null;
        req.cameraAngleDeg = params.get("cameraAngleDeg") != null ? toDouble(params.get("cameraAngleDeg"), 0.0) : null;

        // 构造区域定义
        Object areaObj = params.get("area");
        if (areaObj instanceof Map<?, ?> areaMap) {
            MappingController.AreaBody area = new MappingController.AreaBody();
            Object typeVal = areaMap.get("type");
            area.type = typeVal != null ? typeVal.toString() : "polygon";
            Object pointsObj = areaMap.get("points");
            if (pointsObj instanceof List<?> pointsList) {
                area.points = new ArrayList<>();
                for (Object p : pointsList) {
                    if (p instanceof Map<?, ?> pointMap) {
                        area.points.add(new double[]{
                                toDouble(pointMap.get("lat"), 0.0),
                                toDouble(pointMap.get("lon"), 0.0)
                        });
                    }
                }
            }
            area.centerLat = areaMap.get("centerLat") != null ? toDouble(areaMap.get("centerLat"), 0.0) : null;
            area.centerLon = areaMap.get("centerLon") != null ? toDouble(areaMap.get("centerLon"), 0.0) : null;
            area.radiusM = areaMap.get("radiusM") != null ? toDouble(areaMap.get("radiusM"), 0.0) : null;
            req.area = area;
        }

        return req;
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
package io.aerofleet.cloud.orch.adapter;

import io.aerofleet.cloud.show.ShowController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 表演模块适配器，注入 ShowController 进行同进程调用。
 * <p>
 * 将表演任务的创建、启动、中止、状态查询统一封装为 ModuleAdapter 接口。
 */
@Component
public class ShowAdapter implements ModuleAdapter {

    private static final Logger log = LoggerFactory.getLogger(ShowAdapter.class);

    private final ShowController showController;

    public ShowAdapter(ShowController showController) {
        this.showController = showController;
    }

    @Override
    public ModuleResult createTask(Map<String, Object> params) {
        try {
            ShowController.CreateTaskRequest req = buildCreateRequest(params);
            Map<String, Object> result = showController.createTask(req);
            String taskId = (String) result.get("id");
            String status = (String) result.getOrDefault("status", "CREATED");
            log.info("表演任务创建成功：id={}, name={}", taskId, result.get("name"));
            return ModuleResult.ok(taskId, status);
        } catch (Exception e) {
            log.warn("表演任务创建失败：{}", e.getMessage());
            return ModuleResult.fail(null, e.getMessage());
        }
    }

    @Override
    public ModuleResult startTask(String taskId) {
        try {
            Map<String, Object> result = showController.startTask(taskId);
            String status = (String) result.getOrDefault("status", "PERFORMING");
            log.info("表演任务启动：id={}", taskId);
            return ModuleResult.ok(taskId, status);
        } catch (Exception e) {
            log.warn("表演任务启动失败：taskId={}, error={}", taskId, e.getMessage());
            return ModuleResult.fail(taskId, e.getMessage());
        }
    }

    @Override
    public ModuleResult abortTask(String taskId) {
        try {
            Map<String, Object> result = showController.abortTask(taskId);
            String status = (String) result.getOrDefault("status", "ABORTED");
            log.info("表演任务中止：id={}", taskId);
            return ModuleResult.ok(taskId, status);
        } catch (Exception e) {
            log.warn("表演任务中止失败：taskId={}, error={}", taskId, e.getMessage());
            return ModuleResult.fail(taskId, e.getMessage());
        }
    }

    @Override
    public ModuleResult getTaskStatus(String taskId) {
        try {
            Map<String, Object> result = showController.getTask(taskId);
            String status = (String) result.getOrDefault("status", "UNKNOWN");
            return ModuleResult.ok(taskId, status);
        } catch (Exception e) {
            log.warn("表演状态查询失败：taskId={}, error={}", taskId, e.getMessage());
            return ModuleResult.fail(taskId, e.getMessage());
        }
    }

    @Override
    public String getModuleType() {
        return "SHOW";
    }

    /**
     * 从参数 Map 构造表演创建请求。
     * <p>
     * 期望参数键：
     * <ul>
     *   <li>name — 任务名称</li>
     *   <li>formationId — 队形定义 ID</li>
     *   <li>droneSysids — 参与表演的无人机 sysid 列表</li>
     *   <li>durationSec — 表演持续时间（秒）</li>
     *   <li>altitudeM — 表演高度（米）</li>
     *   <li>centerLat — 队形中心纬度</li>
     *   <li>centerLon — 队形中心经度</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private ShowController.CreateTaskRequest buildCreateRequest(Map<String, Object> params) {
        ShowController.CreateTaskRequest req = new ShowController.CreateTaskRequest();
        req.name = (String) params.getOrDefault("name", "show-task");
        req.formationId = (String) params.get("formationId");

        Object sysidsObj = params.get("droneSysids");
        if (sysidsObj instanceof List<?> sysidsList) {
            req.droneSysids = new ArrayList<>();
            for (Object o : sysidsList) {
                req.droneSysids.add(((Number) o).intValue());
            }
        }

        req.durationSec = toInt(params.get("durationSec"), 60);
        req.altitudeM = toDouble(params.get("altitudeM"), 50.0);
        req.centerLat = toDouble(params.get("centerLat"), 0.0);
        req.centerLon = toDouble(params.get("centerLon"), 0.0);

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
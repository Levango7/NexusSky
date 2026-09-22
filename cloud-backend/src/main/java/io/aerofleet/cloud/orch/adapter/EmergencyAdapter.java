package io.aerofleet.cloud.orch.adapter;

import io.aerofleet.cloud.api.service.EmergencyOrchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 应急模块适配器，同进程注入 EmergencyOrchService。
 * <p>
 * 将应急编排计划的创建（启动）、启动、中止、状态查询统一封装为 ModuleAdapter 接口。
 * 注意：EmergencyOrchService 的 start() 方法同时完成创建和启动，
 * 因此 createTask 调用 start()，startTask 返回当前计划状态。
 */
@Component
public class EmergencyAdapter implements ModuleAdapter {

    private static final Logger log = LoggerFactory.getLogger(EmergencyAdapter.class);

    private final EmergencyOrchService emergencyOrchService;

    public EmergencyAdapter(EmergencyOrchService emergencyOrchService) {
        this.emergencyOrchService = emergencyOrchService;
    }

    @Override
    public ModuleResult createTask(Map<String, Object> params) {
        try {
            int scenarioType = toInt(params.get("scenarioType"), 3); // 默认自定义
            int centerLat = toInt(params.get("centerLat"), 0);
            int centerLon = toInt(params.get("centerLon"), 0);
            int radius = toInt(params.get("radius"), 1000);

            List<Integer> droneIds = new ArrayList<>();
            Object droneIdsObj = params.get("droneIds");
            if (droneIdsObj instanceof List<?> list) {
                for (Object o : list) {
                    droneIds.add(((Number) o).intValue());
                }
            }

            long planId = emergencyOrchService.start(scenarioType, centerLat, centerLon, radius, droneIds);
            Map<String, Object> plan = emergencyOrchService.getPlan(planId);
            String status = plan != null ? (String) plan.getOrDefault("status", "ACTIVE") : "ACTIVE";
            log.info("应急编排计划创建成功：planId={}, scenario={}", planId, scenarioType);
            return ModuleResult.ok(String.valueOf(planId), status);
        } catch (Exception e) {
            log.warn("应急编排计划创建失败：{}", e.getMessage());
            return ModuleResult.fail(null, e.getMessage());
        }
    }

    @Override
    public ModuleResult startTask(String taskId) {
        try {
            long planId = Long.parseLong(taskId);
            Map<String, Object> plan = emergencyOrchService.getPlan(planId);
            if (plan == null) {
                return ModuleResult.fail(taskId, "plan not found");
            }
            // EmergencyOrchService 的 start() 在创建时已启动计划，
            // 此处返回当前计划状态表示计划已在运行中。
            String status = (String) plan.getOrDefault("status", "ACTIVE");
            log.info("应急编排计划已启动：planId={}", planId);
            return ModuleResult.ok(taskId, status);
        } catch (Exception e) {
            log.warn("应急编排计划启动失败：taskId={}, error={}", taskId, e.getMessage());
            return ModuleResult.fail(taskId, e.getMessage());
        }
    }

    @Override
    public ModuleResult abortTask(String taskId) {
        try {
            long planId = Long.parseLong(taskId);
            boolean aborted = emergencyOrchService.abort(planId);
            if (aborted) {
                log.info("应急编排计划中止：planId={}", planId);
                return ModuleResult.ok(taskId, "ABORTED");
            } else {
                return ModuleResult.fail(taskId, "plan not found or already aborted");
            }
        } catch (Exception e) {
            log.warn("应急编排计划中止失败：taskId={}, error={}", taskId, e.getMessage());
            return ModuleResult.fail(taskId, e.getMessage());
        }
    }

    @Override
    public ModuleResult getTaskStatus(String taskId) {
        try {
            long planId = Long.parseLong(taskId);
            Map<String, Object> plan = emergencyOrchService.getPlan(planId);
            if (plan == null) {
                return ModuleResult.fail(taskId, "plan not found");
            }
            String status = (String) plan.getOrDefault("status", "UNKNOWN");
            return ModuleResult.ok(taskId, status);
        } catch (Exception e) {
            log.warn("应急编排状态查询失败：taskId={}, error={}", taskId, e.getMessage());
            return ModuleResult.fail(taskId, e.getMessage());
        }
    }

    @Override
    public String getModuleType() {
        return "EMERGENCY";
    }

    private int toInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(value.toString());
    }
}
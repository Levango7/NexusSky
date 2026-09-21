package io.aerofleet.cloud.orch.adapter;

import io.aerofleet.cloud.mission.Formation;
import io.aerofleet.cloud.mission.FormationCreateRequest;
import io.aerofleet.cloud.mission.FormationGeometry;
import io.aerofleet.cloud.mission.FormationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 编队模块适配器，同进程注入 FormationService。
 * <p>
 * 将编队创建、起飞、解散、状态查询等操作统一封装为 ModuleAdapter 接口。
 */
@Component
public class FormationAdapter implements ModuleAdapter {

    private static final Logger log = LoggerFactory.getLogger(FormationAdapter.class);

    private final FormationService formationService;

    public FormationAdapter(FormationService formationService) {
        this.formationService = formationService;
    }

    @Override
    public ModuleResult createTask(Map<String, Object> params) {
        try {
            FormationCreateRequest req = buildCreateRequest(params);
            FormationService.FormationCreateResult result = formationService.create(req);
            log.info("编队任务创建成功：formationId={}, state={}",
                    result.formationId(), result.state());
            return ModuleResult.ok(String.valueOf(result.formationId()), result.state().name());
        } catch (Exception e) {
            log.warn("编队任务创建失败：{}", e.getMessage());
            return ModuleResult.fail(null, e.getMessage());
        }
    }

    @Override
    public ModuleResult startTask(String taskId) {
        try {
            int formationId = Integer.parseInt(taskId);
            double alt = 10.0; // 默认起飞高度 10m
            formationService.command(formationId, FormationService.FormationCommand.takeoff(alt));
            Formation f = formationService.formation(formationId);
            String status = f != null ? f.state.name() : "UNKNOWN";
            log.info("编队任务启动：formationId={}", formationId);
            return ModuleResult.ok(taskId, status);
        } catch (Exception e) {
            log.warn("编队任务启动失败：taskId={}, error={}", taskId, e.getMessage());
            return ModuleResult.fail(taskId, e.getMessage());
        }
    }

    @Override
    public ModuleResult abortTask(String taskId) {
        try {
            int formationId = Integer.parseInt(taskId);
            formationService.command(formationId, FormationService.FormationCommand.dissolve());
            Formation f = formationService.formation(formationId);
            String status = f != null ? f.state.name() : "DISSOLVED";
            log.info("编队任务中止：formationId={}", formationId);
            return ModuleResult.ok(taskId, status);
        } catch (Exception e) {
            log.warn("编队任务中止失败：taskId={}, error={}", taskId, e.getMessage());
            return ModuleResult.fail(taskId, e.getMessage());
        }
    }

    @Override
    public ModuleResult getTaskStatus(String taskId) {
        try {
            int formationId = Integer.parseInt(taskId);
            Formation f = formationService.formation(formationId);
            if (f == null) {
                return ModuleResult.fail(taskId, "formation not found");
            }
            return ModuleResult.ok(taskId, f.state.name());
        } catch (Exception e) {
            log.warn("编队状态查询失败：taskId={}, error={}", taskId, e.getMessage());
            return ModuleResult.fail(taskId, e.getMessage());
        }
    }

    @Override
    public String getModuleType() {
        return "FORMATION";
    }

    /**
     * 从参数 Map 构造编队创建请求。
     * <p>
     * 期望参数键：
     * <ul>
     *   <li>members — 成员 sysid 列表</li>
     *   <li>shape — 队形类型字符串（如 LINE, GRID, CIRCLE 等）</li>
     *   <li>spacing — 机间距（米）</li>
     *   <li>heading — 方向角（0-359°）</li>
     *   <li>refLat — 参考点纬度</li>
     *   <li>refLon — 参考点经度</li>
     *   <li>refAlt — 参考点高度（米）</li>
     *   <li>leaderSysid — 可选 Leader sysid</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private FormationCreateRequest buildCreateRequest(Map<String, Object> params) {
        Set<Integer> members = new HashSet<>();
        Object membersObj = params.get("members");
        if (membersObj instanceof List<?> list) {
            for (Object o : list) {
                members.add(((Number) o).intValue());
            }
        } else if (membersObj instanceof Set<?> set) {
            for (Object o : set) {
                members.add(((Number) o).intValue());
            }
        }

        String shapeStr = (String) params.getOrDefault("shape", "LINE");
        FormationGeometry.Shape shape = FormationGeometry.Shape.valueOf(shapeStr.toUpperCase());

        double spacing = toDouble(params.get("spacing"), 5.0);
        double heading = toDouble(params.get("heading"), 0.0);
        double refLat = toDouble(params.get("refLat"), 0.0);
        double refLon = toDouble(params.get("refLon"), 0.0);
        double refAlt = toDouble(params.get("refAlt"), 0.0);
        int leaderSysid = toInt(params.get("leaderSysid"), 0);

        return new FormationCreateRequest(members, shape, spacing, heading,
                refLat, refLon, refAlt, leaderSysid);
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
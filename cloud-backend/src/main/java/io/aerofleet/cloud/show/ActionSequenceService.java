package io.aerofleet.cloud.show;

import io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动作序列编排服务。
 * <p>
 * 根据表演任务自动编排动作序列：
 * <pre>
 *   起飞(TAKEOFF) → 移动到队形(MOVE_TO_FORMATION) → 队形变换(TRANSITION_FORMATION)
 *                  → 悬停(HOVER) → 灯光控制(LIGHT_ON/COLOR_CHANGE/LIGHT_OFF)
 *                  → 降落(LAND)
 * </pre>
 * 每个动作包含序号、类型、起始时间、持续时间和参数。
 */
@Service
public class ActionSequenceService {

    private static final Logger log = LoggerFactory.getLogger(ActionSequenceService.class);

    /** 默认起飞持续时间（秒）。 */
    private static final double DEFAULT_TAKEOFF_DURATION = 10.0;
    /** 默认移动到队形持续时间（秒）。 */
    private static final double DEFAULT_MOVE_DURATION = 20.0;
    /** 默认队形变换持续时间（秒）。 */
    private static final double DEFAULT_TRANSITION_DURATION = 15.0;
    /** 默认悬停持续时间（秒）。 */
    private static final double DEFAULT_HOVER_DURATION = 5.0;
    /** 默认灯光操作持续时间（秒）。 */
    private static final double DEFAULT_LIGHT_DURATION = 2.0;
    /** 默认降落持续时间（秒）。 */
    private static final double DEFAULT_LAND_DURATION = 10.0;

    private final Map<String, List<ShowAction>> taskActions = new ConcurrentHashMap<>();
    private final ShowTaskService taskService;
    private final FormationService formationService;

    public ActionSequenceService(ShowTaskService taskService, FormationService formationService) {
        this.taskService = taskService;
        this.formationService = formationService;
    }

    /**
     * 为表演任务生成动作序列。
     * <p>
     * 自动编排起飞 → 移动到队形 → 悬停 → 灯光开启 → 降落。
     *
     * @param taskId 表演任务 ID
     * @return 动作序列列表
     * @throws NotFoundException 任务不存在
     */
    public List<ShowAction> generateActionSequence(String taskId) {
        ShowTask task = taskService.getTask(taskId);
        FormationDefinition formation = formationService.getFormation(task.getFormationId());

        List<ShowAction> actions = new ArrayList<>();
        int seq = 0;
        double currentTime = 0.0;

        // 1. 起飞
        Map<String, Double> takeoffParams = new LinkedHashMap<>();
        takeoffParams.put("altitude", task.getAltitudeM());
        actions.add(new ShowAction(
                UUID.randomUUID().toString(), taskId, seq++,
                ActionType.TAKEOFF, currentTime, DEFAULT_TAKEOFF_DURATION, takeoffParams));
        currentTime += DEFAULT_TAKEOFF_DURATION;

        // 2. 移动到初始队形
        Map<String, Double> moveParams = new LinkedHashMap<>();
        moveParams.put("formationId", 0.0); // 参数中存储 formationId 的 hash
        moveParams.put("altitude", task.getAltitudeM());
        actions.add(new ShowAction(
                UUID.randomUUID().toString(), taskId, seq++,
                ActionType.MOVE_TO_FORMATION, currentTime, DEFAULT_MOVE_DURATION, moveParams));
        currentTime += DEFAULT_MOVE_DURATION;

        // 3. 悬停（稳定队形）
        actions.add(new ShowAction(
                UUID.randomUUID().toString(), taskId, seq++,
                ActionType.HOVER, currentTime, DEFAULT_HOVER_DURATION, null));
        currentTime += DEFAULT_HOVER_DURATION;

        // 4. 灯光开启
        actions.add(new ShowAction(
                UUID.randomUUID().toString(), taskId, seq++,
                ActionType.LIGHT_ON, currentTime, DEFAULT_LIGHT_DURATION, null));
        currentTime += DEFAULT_LIGHT_DURATION;

        // 5. 颜色变换（可选，根据队形类型决定）
        if (formation.getType() == FormationType.HEART || formation.getType() == FormationType.STAR) {
            Map<String, Double> colorParams = new LinkedHashMap<>();
            colorParams.put("color", 1.0); // 红色
            actions.add(new ShowAction(
                    UUID.randomUUID().toString(), taskId, seq++,
                    ActionType.COLOR_CHANGE, currentTime, DEFAULT_LIGHT_DURATION, colorParams));
            currentTime += DEFAULT_LIGHT_DURATION;
        }

        // 6. 灯光关闭
        actions.add(new ShowAction(
                UUID.randomUUID().toString(), taskId, seq++,
                ActionType.LIGHT_OFF, currentTime, DEFAULT_LIGHT_DURATION, null));
        currentTime += DEFAULT_LIGHT_DURATION;

        // 7. 降落
        actions.add(new ShowAction(
                UUID.randomUUID().toString(), taskId, seq++,
                ActionType.LAND, currentTime, DEFAULT_LAND_DURATION, null));


        log.info("Action sequence generated: taskId={} actions={}", taskId, actions.size());
        return actions;
    }

    /** 获取任务的动作序列（如未生成则自动生成）。 */
    public List<ShowAction> getActions(String taskId) {
        taskService.getTask(taskId); // 校验任务存在
        return taskActions.computeIfAbsent(taskId, this::generateActionSequence);
    }
}
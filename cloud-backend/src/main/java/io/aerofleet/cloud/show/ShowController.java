package io.aerofleet.cloud.show;

import io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 无人机编队表演 REST API。
 * <p>
 * 端点：
 * <ul>
 *   <li>{@code POST /api/show/formations} — 创建队形定义</li>
 *   <li>{@code GET  /api/show/formations} — 列出所有队形</li>
 *   <li>{@code GET  /api/show/formations/{id}} — 获取队形详情</li>
 *   <li>{@code POST /api/show/formations/{id}/positions} — 计算队形位置（输入 droneCount）</li>
 *   <li>{@code POST /api/show/tasks} — 创建表演任务</li>
 *   <li>{@code GET  /api/show/tasks} — 列出所有任务</li>
 *   <li>{@code GET  /api/show/tasks/{id}} — 获取任务详情</li>
 *   <li>{@code POST /api/show/tasks/{id}/start} — 启动表演</li>
 *   <li>{@code POST /api/show/tasks/{id}/abort} — 中止表演</li>
 *   <li>{@code GET  /api/show/tasks/{id}/actions} — 获取动作序列</li>
 *   <li>{@code POST /api/show/tasks/{id}/music-sync} — 配置音乐同步</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/show")
@Tag(name = "Show", description = "无人机编队表演 REST API：队形管理、表演任务、动作序列、音乐同步")
public class ShowController {

    private static final Logger log = LoggerFactory.getLogger(ShowController.class);

    private final FormationService formationService;
    private final ShowTaskService taskService;
    private final ActionSequenceService actionSequenceService;
    private final MusicSyncService musicSyncService;

    public ShowController(FormationService formationService,
                          ShowTaskService taskService,
                          ActionSequenceService actionSequenceService,
                          MusicSyncService musicSyncService) {
        this.formationService = formationService;
        this.taskService = taskService;
        this.actionSequenceService = actionSequenceService;
        this.musicSyncService = musicSyncService;
    }

    // ---- 队形管理 ----

    /** 创建队形定义请求体。 */
    public static final class CreateFormationRequest {
        public String name;
        public String type;
        public int droneCount;
        public double spacingM;
        public Map<String, Double> parameters;
    }

    /** 计算队形位置请求体。 */
    public static final class ComputePositionsRequest {
        public int droneCount;
    }

    @Operation(summary = "创建队形定义", description = "创建编队队形定义，指定队形类型、无人机数量和间距")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "队形创建成功"),
        @ApiResponse(responseCode = "400", description = "参数非法")
    })
    @PostMapping("/formations")
    public Map<String, Object> createFormation(@RequestBody CreateFormationRequest req) {
        if (req.name == null || req.name.isBlank()) {
            throw new BadRequestException("name is required");
        }
        if (req.type == null || req.type.isBlank()) {
            throw new BadRequestException("type is required");
        }
        FormationType type;
        try {
            type = FormationType.valueOf(req.type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid formation type: " + req.type);
        }
        FormationDefinition formation = formationService.createFormation(
                req.name, type, req.droneCount, req.spacingM, req.parameters);
        log.info("Create formation: id={} name={} type={}",
                formation.getId(), formation.getName(), formation.getType());
        return formationSummary(formation);
    }

    @Operation(summary = "列出所有队形定义")
    @GetMapping("/formations")
    public List<Map<String, Object>> listFormations() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FormationDefinition f : formationService.listFormations()) {
            out.add(formationSummary(f));
        }
        return out;
    }

    @Operation(summary = "获取队形详情")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "队形详情"),
        @ApiResponse(responseCode = "404", description = "队形不存在")
    })
    @GetMapping("/formations/{id}")
    public Map<String, Object> getFormation(@PathVariable("id") String id) {
        FormationDefinition formation = formationService.getFormation(id);
        return formationSummary(formation);
    }

    @Operation(summary = "计算队形位置", description = "根据队形定义和无人机数量计算每架无人机的相对位置坐标")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "位置坐标列表"),
        @ApiResponse(responseCode = "404", description = "队形不存在")
    })
    @PostMapping("/formations/{id}/positions")
    public Map<String, Object> computePositions(@PathVariable("id") String id,
                                                 @RequestBody ComputePositionsRequest req) {
        FormationDefinition formation = formationService.getFormation(id);
        int droneCount = req.droneCount > 0 ? req.droneCount : formation.getDroneCount();
        List<double[]> positions = formationService.computePositions(
                formation.getType(), droneCount, formation.getSpacingM(),
                formation.getParameters());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("formationId", formation.getId());
        out.put("formationType", formation.getType().name());
        out.put("droneCount", droneCount);
        out.put("spacingM", formation.getSpacingM());
        List<Map<String, Double>> posList = new ArrayList<>();
        for (double[] pos : positions) {
            Map<String, Double> p = new LinkedHashMap<>();
            p.put("x", pos[0]);
            p.put("y", pos[1]);
            posList.add(p);
        }
        out.put("positions", posList);
        return out;
    }

    // ---- 表演任务管理 ----

    /** 创建表演任务请求体。 */
    public static final class CreateTaskRequest {
        public String name;
        public String formationId;
        public List<Integer> droneSysids;
        public int durationSec;
        public double altitudeM;
        public double centerLat;
        public double centerLon;
    }

    /** 配置音乐同步请求体。 */
    public static final class MusicSyncRequest {
        public String musicUrl;
        public double bpm;
        public double startTimeOffsetSec;
    }

    @Operation(summary = "创建表演任务", description = "基于队形定义创建表演任务")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "任务创建成功"),
        @ApiResponse(responseCode = "400", description = "参数非法"),
        @ApiResponse(responseCode = "404", description = "队形不存在")
    })
    @PostMapping("/tasks")
    public Map<String, Object> createTask(@RequestBody CreateTaskRequest req) {
        if (req.name == null || req.name.isBlank()) {
            throw new BadRequestException("name is required");
        }
        if (req.formationId == null || req.formationId.isBlank()) {
            throw new BadRequestException("formationId is required");
        }
        ShowTask task = taskService.createTask(
                req.name, req.formationId, req.droneSysids,
                req.durationSec, req.altitudeM, req.centerLat, req.centerLon);
        log.info("Create show task: id={} name={} formation={}",
                task.getId(), task.getName(), task.getFormationId());
        return taskSummary(task);
    }

    @Operation(summary = "列出所有表演任务", description = "可按状态筛选")
    @GetMapping("/tasks")
    public List<Map<String, Object>> listTasks(
            @RequestParam(value = "status", required = false) String status) {
        ShowStatus filter = null;
        if (status != null && !status.isBlank()) {
            try {
                filter = ShowStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("invalid status: " + status);
            }
        }
        List<ShowTask> tasks = taskService.listTasks(filter);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ShowTask t : tasks) {
            out.add(taskSummary(t));
        }
        return out;
    }

    @Operation(summary = "获取任务详情")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "任务详情"),
        @ApiResponse(responseCode = "404", description = "任务不存在")
    })
    @GetMapping("/tasks/{id}")
    public Map<String, Object> getTask(@PathVariable("id") String id) {
        ShowTask task = taskService.getTask(id);
        return taskDetail(task);
    }

    @Operation(summary = "启动表演任务")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "任务已启动"),
        @ApiResponse(responseCode = "404", description = "任务不存在")
    })
    @PostMapping("/tasks/{id}/start")
    public Map<String, Object> startTask(@PathVariable("id") String id) {
        ShowTask task = taskService.startTask(id);
        return taskSummary(task);
    }

    @Operation(summary = "中止表演任务")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "任务已中止"),
        @ApiResponse(responseCode = "404", description = "任务不存在")
    })
    @PostMapping("/tasks/{id}/abort")
    public Map<String, Object> abortTask(@PathVariable("id") String id) {
        ShowTask task = taskService.abortTask(id);
        return taskSummary(task);
    }

    @Operation(summary = "获取动作序列", description = "获取表演任务的动作序列（如未生成则自动编排）")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "动作序列列表"),
        @ApiResponse(responseCode = "404", description = "任务不存在")
    })
    @GetMapping("/tasks/{id}/actions")
    public List<Map<String, Object>> getActions(@PathVariable("id") String id) {
        List<ShowAction> actions = actionSequenceService.getActions(id);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ShowAction a : actions) {
            out.add(actionSummary(a));
        }
        return out;
    }

    @Operation(summary = "配置音乐同步", description = "为表演任务配置音乐同步（BPM、起始偏移）")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "音乐同步已配置"),
        @ApiResponse(responseCode = "400", description = "参数非法"),
        @ApiResponse(responseCode = "404", description = "任务不存在")
    })
    @PostMapping("/tasks/{id}/music-sync")
    public Map<String, Object> configureMusicSync(@PathVariable("id") String id,
                                                   @RequestBody MusicSyncRequest req) {
        MusicSync sync = musicSyncService.configureMusicSync(
                id, req.musicUrl, req.bpm, req.startTimeOffsetSec);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", sync.getTaskId());
        out.put("musicUrl", sync.getMusicUrl());
        out.put("bpm", sync.getBpm());
        out.put("startTimeOffsetSec", sync.getStartTimeOffsetSec());
        out.put("beatDurationSec", sync.beatDurationSec());
        // 同时返回节拍同步后的动作时间点
        List<Double> beatTimes = musicSyncService.syncActionsToBeat(id);
        out.put("syncedBeatTimes", beatTimes);
        return out;
    }

    // ---- 辅助方法 ----

    private Map<String, Object> formationSummary(FormationDefinition f) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", f.getId());
        out.put("name", f.getName());
        out.put("type", f.getType().name());
        out.put("droneCount", f.getDroneCount());
        out.put("spacingM", f.getSpacingM());
        out.put("parameters", f.getParameters());
        return out;
    }

    private Map<String, Object> taskSummary(ShowTask t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.getId());
        out.put("name", t.getName());
        out.put("formationId", t.getFormationId());
        out.put("status", t.getStatus().name());
        out.put("droneSysids", t.getDroneSysids());
        out.put("durationSec", t.getDurationSec());
        out.put("altitudeM", t.getAltitudeM());
        out.put("centerLat", t.getCenterLat());
        out.put("centerLon", t.getCenterLon());
        return out;
    }

    private Map<String, Object> taskDetail(ShowTask t) {
        Map<String, Object> out = taskSummary(t);
        out.put("startTime", t.getStartTime());
        return out;
    }

    private Map<String, Object> actionSummary(ShowAction a) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", a.getId());
        out.put("taskId", a.getTaskId());
        out.put("seq", a.getSeq());
        out.put("type", a.getType().name());
        out.put("startTime", a.getStartTime());
        out.put("durationSec", a.getDurationSec());
        out.put("parameters", a.getParameters());
        return out;
    }
}
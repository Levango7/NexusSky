package io.aerofleet.cloud.show;

import io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException;
import io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ShowController} REST 端点单测（P4-2 编队表演）。
 * <p>
 * 直接实例化 Controller + Service（无 MockMvc / Spring 上下文），
 * 验证端点逻辑与异常处理。与 InspectionControllerTest 风格一致。
 */
@DisplayName("ShowController REST 端点 (P4-2)")
class ShowControllerTest {

    private FormationService formationService;
    private ShowTaskService taskService;
    private ActionSequenceService actionSequenceService;
    private MusicSyncService musicSyncService;
    private ShowController controller;

    @BeforeEach
    void setUp() {
        formationService = new FormationService();
        taskService = new ShowTaskService(formationService);
        actionSequenceService = new ActionSequenceService(taskService, formationService);
        musicSyncService = new MusicSyncService(taskService, actionSequenceService);
        controller = new ShowController(
                formationService, taskService, actionSequenceService, musicSyncService);
    }

    // ---- 队形管理 ----

    @Test
    @DisplayName("POST /formations 创建队形定义")
    void createFormationSuccess() {
        ShowController.CreateFormationRequest req = new ShowController.CreateFormationRequest();
        req.name = "直线队形";
        req.type = "LINE";
        req.droneCount = 5;
        req.spacingM = 10.0;
        req.parameters = null;

        Map<String, Object> result = controller.createFormation(req);
        assertThat(result).containsKey("id");
        assertThat(result.get("name")).isEqualTo("直线队形");
        assertThat(result.get("type")).isEqualTo("LINE");
        assertThat(result.get("droneCount")).isEqualTo(5);
        assertThat(result.get("spacingM")).isEqualTo(10.0);
    }

    @Test
    @DisplayName("POST /formations 非法队形类型抛 BadRequestException")
    void createFormationInvalidType() {
        ShowController.CreateFormationRequest req = new ShowController.CreateFormationRequest();
        req.name = "test";
        req.type = "UNKNOWN";
        req.droneCount = 5;
        req.spacingM = 10.0;

        assertThatThrownBy(() -> controller.createFormation(req))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("POST /formations name 为空抛 BadRequestException")
    void createFormationBlankName() {
        ShowController.CreateFormationRequest req = new ShowController.CreateFormationRequest();
        req.name = "";
        req.type = "LINE";
        req.droneCount = 5;
        req.spacingM = 10.0;

        assertThatThrownBy(() -> controller.createFormation(req))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("GET /formations 列出所有队形")
    void listFormations() {
        createFormation("队形1", "LINE", 5, 10.0);
        createFormation("队形2", "CIRCLE", 8, 5.0);

        List<Map<String, Object>> list = controller.listFormations();
        assertThat(list).hasSize(2);
    }

    @Test
    @DisplayName("GET /formations/{id} 获取队形详情")
    void getFormationDetail() {
        Map<String, Object> created = createFormation("直线队形", "LINE", 5, 10.0);
        String id = (String) created.get("id");

        Map<String, Object> detail = controller.getFormation(id);
        assertThat(detail.get("id")).isEqualTo(id);
        assertThat(detail.get("name")).isEqualTo("直线队形");
    }

    @Test
    @DisplayName("GET /formations/{id} 不存在抛异常")
    void getFormationNotFound() {
        assertThatThrownBy(() -> controller.getFormation("nonexistent"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("POST /formations/{id}/positions 计算队形位置")
    void computePositions() {
        Map<String, Object> created = createFormation("直线队形", "LINE", 5, 10.0);
        String id = (String) created.get("id");

        ShowController.ComputePositionsRequest req = new ShowController.ComputePositionsRequest();
        req.droneCount = 5;

        Map<String, Object> result = controller.computePositions(id, req);
        assertThat(result.get("formationId")).isEqualTo(id);
        assertThat(result.get("droneCount")).isEqualTo(5);
        assertThat(result.get("positions")).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Double>> positions = (List<Map<String, Double>>) result.get("positions");
        assertThat(positions).hasSize(5);
    }

    @Test
    @DisplayName("POST /formations/{id}/positions 使用默认 droneCount")
    void computePositionsDefaultDroneCount() {
        Map<String, Object> created = createFormation("直线队形", "LINE", 3, 10.0);
        String id = (String) created.get("id");

        ShowController.ComputePositionsRequest req = new ShowController.ComputePositionsRequest();
        req.droneCount = 0; // 使用队形定义中的默认值

        Map<String, Object> result = controller.computePositions(id, req);
        assertThat(result.get("droneCount")).isEqualTo(3);
    }

    // ---- 表演任务管理 ----

    @Test
    @DisplayName("POST /tasks 创建表演任务")
    void createTaskSuccess() {
        Map<String, Object> formation = createFormation("直线队形", "LINE", 3, 10.0);
        String formationId = (String) formation.get("id");

        Map<String, Object> result = createTask("表演1", formationId, List.of(1, 2, 3));
        assertThat(result).containsKey("id");
        assertThat(result.get("name")).isEqualTo("表演1");
        assertThat(result.get("formationId")).isEqualTo(formationId);
        assertThat(result.get("status")).isEqualTo("CREATED");
        assertThat(result.get("droneSysids")).isEqualTo(List.of(1, 2, 3));
    }

    @Test
    @DisplayName("POST /tasks 队形不存在抛 BadRequestException")
    void createTaskFormationNotFound() {
        ShowController.CreateTaskRequest req = new ShowController.CreateTaskRequest();
        req.name = "表演1";
        req.formationId = "nonexistent";
        req.droneSysids = List.of(1, 2, 3);
        req.durationSec = 300;
        req.altitudeM = 50;
        req.centerLat = 39.90;
        req.centerLon = 116.40;

        assertThatThrownBy(() -> controller.createTask(req))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("POST /tasks name 为空抛 BadRequestException")
    void createTaskBlankName() {
        ShowController.CreateTaskRequest req = new ShowController.CreateTaskRequest();
        req.name = "";
        req.formationId = "some-id";
        req.droneSysids = List.of(1);
        req.durationSec = 300;
        req.altitudeM = 50;
        req.centerLat = 39.90;
        req.centerLon = 116.40;

        assertThatThrownBy(() -> controller.createTask(req))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("POST /tasks 无人机数量与队形不匹配抛 BadRequestException")
    void createTaskDroneCountMismatch() {
        Map<String, Object> formation = createFormation("直线队形", "LINE", 3, 10.0);
        String formationId = (String) formation.get("id");

        ShowController.CreateTaskRequest req = new ShowController.CreateTaskRequest();
        req.name = "表演1";
        req.formationId = formationId;
        req.droneSysids = List.of(1, 2); // 只有2架，队形需要3架
        req.durationSec = 300;
        req.altitudeM = 50;
        req.centerLat = 39.90;
        req.centerLon = 116.40;

        assertThatThrownBy(() -> controller.createTask(req))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("GET /tasks 列出所有任务")
    void listTasks() {
        Map<String, Object> formation = createFormation("直线队形", "LINE", 3, 10.0);
        String formationId = (String) formation.get("id");
        createTask("表演1", formationId, List.of(1, 2, 3));
        createTask("表演2", formationId, List.of(4, 5, 6));

        List<Map<String, Object>> tasks = controller.listTasks(null);
        assertThat(tasks).hasSize(2);
    }

    @Test
    @DisplayName("GET /tasks?status=CREATED 按状态筛选")
    void listTasksFilterByStatus() {
        Map<String, Object> formation = createFormation("直线队形", "LINE", 3, 10.0);
        String formationId = (String) formation.get("id");
        createTask("表演1", formationId, List.of(1, 2, 3));

        List<Map<String, Object>> created = controller.listTasks("CREATED");
        assertThat(created).hasSize(1);
        assertThat(created.get(0).get("status")).isEqualTo("CREATED");
    }

    @Test
    @DisplayName("GET /tasks?status=INVALID 非法状态抛 BadRequestException")
    void listTasksInvalidStatus() {
        assertThatThrownBy(() -> controller.listTasks("INVALID"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("GET /tasks/{id} 获取任务详情")
    void getTaskDetail() {
        Map<String, Object> formation = createFormation("直线队形", "LINE", 3, 10.0);
        String formationId = (String) formation.get("id");
        Map<String, Object> created = createTask("表演1", formationId, List.of(1, 2, 3));
        String id = (String) created.get("id");

        Map<String, Object> detail = controller.getTask(id);
        assertThat(detail.get("id")).isEqualTo(id);
        assertThat(detail).containsKey("startTime");
    }

    @Test
    @DisplayName("GET /tasks/{id} 不存在抛 NotFoundException")
    void getTaskNotFound() {
        assertThatThrownBy(() -> controller.getTask("nonexistent"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("POST /tasks/{id}/start 启动表演任务")
    void startTask() {
        Map<String, Object> formation = createFormation("直线队形", "LINE", 3, 10.0);
        String formationId = (String) formation.get("id");
        Map<String, Object> created = createTask("表演1", formationId, List.of(1, 2, 3));
        String id = (String) created.get("id");

        Map<String, Object> result = controller.startTask(id);
        assertThat(result.get("status")).isEqualTo("PERFORMING");
    }

    @Test
    @DisplayName("POST /tasks/{id}/start 不存在抛 NotFoundException")
    void startTaskNotFound() {
        assertThatThrownBy(() -> controller.startTask("nonexistent"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("POST /tasks/{id}/abort 中止表演任务")
    void abortTask() {
        Map<String, Object> formation = createFormation("直线队形", "LINE", 3, 10.0);
        String formationId = (String) formation.get("id");
        Map<String, Object> created = createTask("表演1", formationId, List.of(1, 2, 3));
        String id = (String) created.get("id");
        controller.startTask(id);

        Map<String, Object> result = controller.abortTask(id);
        assertThat(result.get("status")).isEqualTo("ABORTED");
    }

    @Test
    @DisplayName("POST /tasks/{id}/abort 不存在抛 NotFoundException")
    void abortTaskNotFound() {
        assertThatThrownBy(() -> controller.abortTask("nonexistent"))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- 动作序列 ----

    @Test
    @DisplayName("GET /tasks/{id}/actions 获取动作序列")
    void getActions() {
        Map<String, Object> formation = createFormation("直线队形", "LINE", 3, 10.0);
        String formationId = (String) formation.get("id");
        Map<String, Object> created = createTask("表演1", formationId, List.of(1, 2, 3));
        String id = (String) created.get("id");

        List<Map<String, Object>> actions = controller.getActions(id);
        assertThat(actions).isNotEmpty();
        // 应包含 TAKEOFF 和 LAND
        boolean hasTakeoff = actions.stream().anyMatch(a -> "TAKEOFF".equals(a.get("type")));
        boolean hasLand = actions.stream().anyMatch(a -> "LAND".equals(a.get("type")));
        assertThat(hasTakeoff).isTrue();
        assertThat(hasLand).isTrue();
        // 序号应从 0 递增
        for (int i = 0; i < actions.size(); i++) {
            assertThat(actions.get(i).get("seq")).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("GET /tasks/{id}/actions 包含 MOVE_TO_FORMATION")
    void getActionsContainsMoveToFormation() {
        Map<String, Object> formation = createFormation("直线队形", "LINE", 3, 10.0);
        String formationId = (String) formation.get("id");
        Map<String, Object> created = createTask("表演1", formationId, List.of(1, 2, 3));
        String id = (String) created.get("id");

        List<Map<String, Object>> actions = controller.getActions(id);
        boolean hasMove = actions.stream().anyMatch(a -> "MOVE_TO_FORMATION".equals(a.get("type")));
        assertThat(hasMove).isTrue();
    }

    @Test
    @DisplayName("GET /tasks/{id}/actions HEART 队形包含 COLOR_CHANGE")
    void getActionsHeartContainsColorChange() {
        Map<String, Object> formation = createFormation("心形队形", "HEART", 3, 10.0);
        String formationId = (String) formation.get("id");
        Map<String, Object> created = createTask("表演1", formationId, List.of(1, 2, 3));
        String id = (String) created.get("id");

        List<Map<String, Object>> actions = controller.getActions(id);
        boolean hasColorChange = actions.stream().anyMatch(a -> "COLOR_CHANGE".equals(a.get("type")));
        assertThat(hasColorChange).isTrue();
    }

    // ---- 音乐同步 ----

    @Test
    @DisplayName("POST /tasks/{id}/music-sync 配置音乐同步")
    void configureMusicSync() {
        Map<String, Object> formation = createFormation("直线队形", "LINE", 3, 10.0);
        String formationId = (String) formation.get("id");
        Map<String, Object> created = createTask("表演1", formationId, List.of(1, 2, 3));
        String id = (String) created.get("id");

        ShowController.MusicSyncRequest req = new ShowController.MusicSyncRequest();
        req.musicUrl = "https://example.com/music.mp3";
        req.bpm = 120;
        req.startTimeOffsetSec = 5.0;

        Map<String, Object> result = controller.configureMusicSync(id, req);
        assertThat(result.get("taskId")).isEqualTo(id);
        assertThat(result.get("musicUrl")).isEqualTo("https://example.com/music.mp3");
        assertThat(result.get("bpm")).isEqualTo(120.0);
        assertThat(result.get("startTimeOffsetSec")).isEqualTo(5.0);
        assertThat(result.get("beatDurationSec")).isEqualTo(0.5); // 60/120 = 0.5
        assertThat(result).containsKey("syncedBeatTimes");
    }

    @Test
    @DisplayName("POST /tasks/{id}/music-sync bpm 为 0 抛 BadRequestException")
    void configureMusicSyncZeroBpm() {
        Map<String, Object> formation = createFormation("直线队形", "LINE", 3, 10.0);
        String formationId = (String) formation.get("id");
        Map<String, Object> created = createTask("表演1", formationId, List.of(1, 2, 3));
        String id = (String) created.get("id");

        ShowController.MusicSyncRequest req = new ShowController.MusicSyncRequest();
        req.musicUrl = "https://example.com/music.mp3";
        req.bpm = 0;
        req.startTimeOffsetSec = 0;

        assertThatThrownBy(() -> controller.configureMusicSync(id, req))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("POST /tasks/{id}/music-sync musicUrl 为空抛 BadRequestException")
    void configureMusicSyncBlankUrl() {
        Map<String, Object> formation = createFormation("直线队形", "LINE", 3, 10.0);
        String formationId = (String) formation.get("id");
        Map<String, Object> created = createTask("表演1", formationId, List.of(1, 2, 3));
        String id = (String) created.get("id");

        ShowController.MusicSyncRequest req = new ShowController.MusicSyncRequest();
        req.musicUrl = "";
        req.bpm = 120;
        req.startTimeOffsetSec = 0;

        assertThatThrownBy(() -> controller.configureMusicSync(id, req))
                .isInstanceOf(BadRequestException.class);
    }

    // ---- 完整流程 ----

    @Test
    @DisplayName("完整表演流程：创建队形 → 创建任务 → 启动 → 中止")
    void fullWorkflow() {
        // 创建队形
        Map<String, Object> formation = createFormation("直线队形", "LINE", 3, 10.0);
        String formationId = (String) formation.get("id");

        // 创建任务
        Map<String, Object> task = createTask("表演1", formationId, List.of(1, 2, 3));
        String taskId = (String) task.get("id");
        assertThat(task.get("status")).isEqualTo("CREATED");

        // 启动
        Map<String, Object> started = controller.startTask(taskId);
        assertThat(started.get("status")).isEqualTo("PERFORMING");

        // 获取动作序列
        List<Map<String, Object>> actions = controller.getActions(taskId);
        assertThat(actions).isNotEmpty();

        // 中止
        Map<String, Object> aborted = controller.abortTask(taskId);
        assertThat(aborted.get("status")).isEqualTo("ABORTED");
    }

    // ---- 辅助方法 ----

    private Map<String, Object> createFormation(String name, String type,
                                                 int droneCount, double spacingM) {
        ShowController.CreateFormationRequest req = new ShowController.CreateFormationRequest();
        req.name = name;
        req.type = type;
        req.droneCount = droneCount;
        req.spacingM = spacingM;
        req.parameters = null;
        return controller.createFormation(req);
    }

    private Map<String, Object> createTask(String name, String formationId,
                                            List<Integer> droneSysids) {
        ShowController.CreateTaskRequest req = new ShowController.CreateTaskRequest();
        req.name = name;
        req.formationId = formationId;
        req.droneSysids = droneSysids;
        req.durationSec = 300;
        req.altitudeM = 50;
        req.centerLat = 39.90;
        req.centerLon = 116.40;
        return controller.createTask(req);
    }
}
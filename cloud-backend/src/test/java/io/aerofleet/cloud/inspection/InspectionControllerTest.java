package io.aerofleet.cloud.inspection;

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
 * {@link InspectionController} REST 端点单测（P1-1 无人机集群智能巡检）。
 * <p>
 * 直接实例化 Controller + Service（无 MockMvc / Spring 上下文），
 * 验证端点逻辑与异常处理。与 AlarmControllerTest 风格一致。
 */
@DisplayName("InspectionController REST 端点 (P1-1)")
class InspectionControllerTest {

    private InspectionTaskService taskService;
    private InspectionPresetFactory presetFactory;
    private InspectionController controller;

    @BeforeEach
    void setUp() {
        RoutePlannerService routePlanner = new RoutePlannerService();
        AnomalyDetectionService anomalyDetector = new AnomalyDetectionService();
        presetFactory = new InspectionPresetFactory();
        taskService = new InspectionTaskService(routePlanner, anomalyDetector, presetFactory);
        controller = new InspectionController(taskService, presetFactory);
    }

    private InspectionController.CreateTaskRequest createRequest(String templateId) {
        InspectionController.CreateTaskRequest req = new InspectionController.CreateTaskRequest();
        req.templateId = templateId;
        req.sysid = 1;
        req.startLat = 39.90;
        req.startLon = 116.40;
        req.area = null;
        return req;
    }

    @Test
    @DisplayName("GET /templates 列出 4 种预设模板")
    void listTemplatesReturnsFour() {
        List<Map<String, Object>> templates = controller.listTemplates();
        assertThat(templates).hasSize(4);
        assertThat(templates.get(0)).containsKey("id");
        assertThat(templates.get(0)).containsKey("name");
        assertThat(templates.get(0)).containsKey("industryType");
        assertThat(templates.get(0)).containsKey("routeType");
    }

    @Test
    @DisplayName("POST /tasks 创建巡检任务")
    void createTaskSuccess() {
        Map<String, Object> result = controller.createTask(createRequest("preset-power-line"));

        assertThat(result).containsKey("id");
        assertThat(result.get("templateId")).isEqualTo("preset-power-line");
        assertThat(result.get("status")).isEqualTo("PENDING");
        assertThat(result.get("assignedSysid")).isEqualTo(1);
        assertThat(result.get("waypointCount")).isNotNull();
    }

    @Test
    @DisplayName("POST /tasks 模板不存在抛 BadRequestException")
    void createTaskTemplateNotFound() {
        assertThatThrownBy(() -> controller.createTask(createRequest("nonexistent")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("POST /tasks templateId 为空抛 BadRequestException")
    void createTaskBlankTemplateId() {
        InspectionController.CreateTaskRequest req = createRequest("");
        assertThatThrownBy(() -> controller.createTask(req))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("POST /tasks 带圆形区域创建任务")
    void createTaskWithCircleArea() {
        InspectionController.CreateTaskRequest req = createRequest("preset-solar-farm");
        InspectionController.AreaBody area = new InspectionController.AreaBody();
        area.type = "circle";
        area.centerLat = 39.90;
        area.centerLon = 116.40;
        area.radiusM = 200.0;
        req.area = area;

        Map<String, Object> result = controller.createTask(req);
        assertThat(result.get("status")).isEqualTo("PENDING");
        assertThat(result.get("waypointCount")).isNotNull();
    }

    @Test
    @DisplayName("POST /tasks 带多边形区域创建任务")
    void createTaskWithPolygonArea() {
        InspectionController.CreateTaskRequest req = createRequest("preset-railway");
        InspectionController.AreaBody area = new InspectionController.AreaBody();
        area.type = "polygon";
        area.points = List.of(
                new double[]{39.89, 116.39},
                new double[]{39.89, 116.41},
                new double[]{39.91, 116.41},
                new double[]{39.91, 116.39});
        req.area = area;

        Map<String, Object> result = controller.createTask(req);
        assertThat(result.get("status")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("POST /tasks 非法区域类型抛 BadRequestException")
    void createTaskInvalidAreaType() {
        InspectionController.CreateTaskRequest req = createRequest("preset-power-line");
        InspectionController.AreaBody area = new InspectionController.AreaBody();
        area.type = "unknown";
        req.area = area;

        assertThatThrownBy(() -> controller.createTask(req))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("GET /tasks 列出所有任务")
    void listTasksReturnsAll() {
        controller.createTask(createRequest("preset-power-line"));
        controller.createTask(createRequest("preset-railway"));

        List<Map<String, Object>> tasks = controller.listTasks(null);
        assertThat(tasks).hasSize(2);
    }

    @Test
    @DisplayName("GET /tasks?status=PENDING 按状态筛选")
    void listTasksFilterByStatus() {
        controller.createTask(createRequest("preset-power-line"));
        controller.createTask(createRequest("preset-railway"));

        List<Map<String, Object>> pending = controller.listTasks("PENDING");
        assertThat(pending).hasSize(2);
        for (Map<String, Object> t : pending) {
            assertThat(t.get("status")).isEqualTo("PENDING");
        }
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
        Map<String, Object> created = controller.createTask(createRequest("preset-power-line"));
        String id = (String) created.get("id");

        Map<String, Object> detail = controller.getTask(id);
        assertThat(detail.get("id")).isEqualTo(id);
        assertThat(detail).containsKey("waypoints");
        assertThat(detail.get("waypoints")).isNotNull();
    }

    @Test
    @DisplayName("GET /tasks/{id} 不存在抛 NotFoundException")
    void getTaskNotFound() {
        assertThatThrownBy(() -> controller.getTask("nonexistent"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("POST /tasks/{id}/start 启动巡检任务")
    void startTask() {
        Map<String, Object> created = controller.createTask(createRequest("preset-power-line"));
        String id = (String) created.get("id");

        Map<String, Object> result = controller.startTask(id);
        assertThat(result.get("status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("POST /tasks/{id}/start 不存在抛 NotFoundException")
    void startTaskNotFound() {
        assertThatThrownBy(() -> controller.startTask("nonexistent"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("POST /tasks/{id}/abort 中止巡检任务")
    void abortTask() {
        Map<String, Object> created = controller.createTask(createRequest("preset-power-line"));
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

    @Test
    @DisplayName("GET /tasks/{id}/progress 查询进度")
    void getProgress() {
        Map<String, Object> created = controller.createTask(createRequest("preset-power-line"));
        String id = (String) created.get("id");

        Map<String, Object> progress = controller.getProgress(id);
        assertThat(progress.get("taskId")).isEqualTo(id);
        assertThat(progress.get("status")).isEqualTo("PENDING");
        assertThat(progress.get("progressPct")).isEqualTo(0.0);
        assertThat(progress).containsKey("waypointCount");
        assertThat(progress).containsKey("photosCaptured");
        assertThat(progress).containsKey("anomaliesFound");
    }

    @Test
    @DisplayName("GET /tasks/{id}/progress 不存在抛 NotFoundException")
    void getProgressNotFound() {
        assertThatThrownBy(() -> controller.getProgress("nonexistent"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("完整任务流程：创建 → 启动 → 中止")
    void fullWorkflowCreateStartAbort() {
        // 创建
        Map<String, Object> created = controller.createTask(createRequest("preset-power-line"));
        String id = (String) created.get("id");
        assertThat(created.get("status")).isEqualTo("PENDING");

        // 启动
        Map<String, Object> started = controller.startTask(id);
        assertThat(started.get("status")).isEqualTo("IN_PROGRESS");

        // 中止
        Map<String, Object> aborted = controller.abortTask(id);
        assertThat(aborted.get("status")).isEqualTo("ABORTED");

        // 进度查询
        Map<String, Object> progress = controller.getProgress(id);
        assertThat(progress.get("status")).isEqualTo("ABORTED");
    }

    @Test
    @DisplayName("模拟完成任务并生成报告")
    void simulateCompletionGeneratesReport() {
        Map<String, Object> created = controller.createTask(createRequest("preset-power-line"));
        String id = (String) created.get("id");

        InspectionReport report = taskService.simulateCompletion(id);
        assertThat(report.taskId()).isEqualTo(id);
        assertThat(report.totalPhotos()).isGreaterThan(0);
        assertThat(report.summary()).isNotBlank();
        assertThat(report.recommendations()).isNotEmpty();
    }
}
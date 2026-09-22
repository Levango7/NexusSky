package io.aerofleet.cloud.mapping;

import io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException;
import io.aerofleet.cloud.api.exception.ApiExceptionHandler.NotFoundException;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MappingController} REST 端点单测（P2-2 无人机航拍测绘）。
 * <p>
 * 直接实例化 Controller + Service（无 MockMvc / Spring 上下文），
 * 验证端点逻辑与异常处理。
 */
@DisplayName("MappingController REST 端点 (P2-2)")
class MappingControllerTest {

    private MappingRoutePlanner routePlanner;
    private PhotoCaptureService photoCaptureService;
    private MappingResultService resultService;
    private DeviceRegistry registry;
    private MappingController controller;
    private MappingTaskRepository taskRepository;
    private final Map<String, MappingTask> taskStore = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        routePlanner = new MappingRoutePlanner();
        photoCaptureService = new PhotoCaptureService();
        resultService = new MappingResultService();
        registry = new DeviceRegistry();
        taskStore.clear();
        taskRepository = Mockito.mock(MappingTaskRepository.class);
        Mockito.when(taskRepository.save(Mockito.any(MappingTask.class)))
                .thenAnswer(inv -> {
                    MappingTask t = inv.getArgument(0);
                    taskStore.put(t.getId(), t);
                    return t;
                });
        Mockito.when(taskRepository.findAll())
                .thenAnswer(inv -> new ArrayList<>(taskStore.values()));
        Mockito.when(taskRepository.findById(Mockito.anyString()))
                .thenAnswer(inv -> Optional.ofNullable(taskStore.get(inv.getArgument(0))));
        controller = new MappingController(routePlanner, photoCaptureService, resultService, registry, taskRepository);
    }

    private MappingController.CreateTaskRequest createRequest(String type) {
        MappingController.CreateTaskRequest req = new MappingController.CreateTaskRequest();
        req.name = "test-mapping";
        req.type = type;
        req.sysid = 1;
        req.altitudeM = 100.0;
        req.overlapPct = 80.0;
        req.sidelapPct = 60.0;
        req.cameraAngleDeg = 0.0;
        req.area = null;
        return req;
    }

    // ========== 创建任务 ==========

    @Test
    @DisplayName("POST /tasks 创建正射影像测绘任务")
    void createOrthophotoTask() {
        Map<String, Object> result = controller.createTask(createRequest("ORTHO_PHOTO"));

        assertThat(result).containsKey("id");
        assertThat(result.get("type")).isEqualTo("ORTHO_PHOTO");
        assertThat(result.get("status")).isEqualTo("PLANNING");
        assertThat(result.get("assignedSysid")).isEqualTo(1);
        assertThat(result).containsKey("waypointCount");
        assertThat(result).containsKey("gsdCm");
    }

    @Test
    @DisplayName("POST /tasks 创建 DEM 测绘任务")
    void createDemTask() {
        Map<String, Object> result = controller.createTask(createRequest("DEM"));

        assertThat(result.get("type")).isEqualTo("DEM");
        assertThat(result.get("status")).isEqualTo("PLANNING");
    }

    @Test
    @DisplayName("POST /tasks 创建三维建模测绘任务")
    void create3DModelTask() {
        Map<String, Object> result = controller.createTask(createRequest("THREE_D_MODEL"));

        assertThat(result.get("type")).isEqualTo("THREE_D_MODEL");
        assertThat(result.get("status")).isEqualTo("PLANNING");
    }

    @Test
    @DisplayName("POST /tasks 创建混合测绘任务")
    void createMixedTask() {
        Map<String, Object> result = controller.createTask(createRequest("MIXED"));

        assertThat(result.get("type")).isEqualTo("MIXED");
        assertThat(result.get("status")).isEqualTo("PLANNING");
    }

    @Test
    @DisplayName("POST /tasks 带圆形区域创建任务")
    void createTaskWithCircleArea() {
        MappingController.CreateTaskRequest req = createRequest("ORTHO_PHOTO");
        MappingController.AreaBody area = new MappingController.AreaBody();
        area.type = "circle";
        area.centerLat = 39.90;
        area.centerLon = 116.40;
        area.radiusM = 200.0;
        req.area = area;

        Map<String, Object> result = controller.createTask(req);
        assertThat(result.get("status")).isEqualTo("PLANNING");
        assertThat(result).containsKey("waypointCount");
    }

    @Test
    @DisplayName("POST /tasks 带多边形区域创建任务")
    void createTaskWithPolygonArea() {
        MappingController.CreateTaskRequest req = createRequest("ORTHO_PHOTO");
        MappingController.AreaBody area = new MappingController.AreaBody();
        area.type = "polygon";
        area.points = List.of(
                new double[]{39.89, 116.39},
                new double[]{39.89, 116.41},
                new double[]{39.91, 116.41},
                new double[]{39.91, 116.39});
        req.area = area;

        Map<String, Object> result = controller.createTask(req);
        assertThat(result.get("status")).isEqualTo("PLANNING");
    }

    @Test
    @DisplayName("POST /tasks name 为空抛 BadRequestException")
    void createTaskBlankName() {
        MappingController.CreateTaskRequest req = createRequest("ORTHO_PHOTO");
        req.name = "";
        assertThatThrownBy(() -> controller.createTask(req))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("POST /tasks type 为空抛 BadRequestException")
    void createTaskBlankType() {
        MappingController.CreateTaskRequest req = createRequest("ORTHO_PHOTO");
        req.type = "";
        assertThatThrownBy(() -> controller.createTask(req))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("POST /tasks 非法测绘类型抛 BadRequestException")
    void createTaskInvalidType() {
        MappingController.CreateTaskRequest req = createRequest("INVALID_TYPE");
        assertThatThrownBy(() -> controller.createTask(req))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("POST /tasks 非法区域类型抛 BadRequestException")
    void createTaskInvalidAreaType() {
        MappingController.CreateTaskRequest req = createRequest("ORTHO_PHOTO");
        MappingController.AreaBody area = new MappingController.AreaBody();
        area.type = "unknown";
        req.area = area;

        assertThatThrownBy(() -> controller.createTask(req))
                .isInstanceOf(BadRequestException.class);
    }

    // ========== 列出任务 ==========

    @Test
    @DisplayName("GET /tasks 列出所有任务")
    void listTasksReturnsAll() {
        controller.createTask(createRequest("ORTHO_PHOTO"));
        controller.createTask(createRequest("DEM"));

        List<Map<String, Object>> tasks = controller.listTasks(null);
        assertThat(tasks).hasSize(2);
    }

    @Test
    @DisplayName("GET /tasks?status=PLANNING 按状态筛选")
    void listTasksFilterByStatus() {
        controller.createTask(createRequest("ORTHO_PHOTO"));
        controller.createTask(createRequest("DEM"));

        List<Map<String, Object>> planning = controller.listTasks("PLANNING");
        assertThat(planning).hasSize(2);
        for (Map<String, Object> t : planning) {
            assertThat(t.get("status")).isEqualTo("PLANNING");
        }
    }

    @Test
    @DisplayName("GET /tasks?status=INVALID 非法状态抛 BadRequestException")
    void listTasksInvalidStatus() {
        assertThatThrownBy(() -> controller.listTasks("INVALID"))
                .isInstanceOf(BadRequestException.class);
    }

    // ========== 任务详情 ==========

    @Test
    @DisplayName("GET /tasks/{id} 获取任务详情")
    void getTaskDetail() {
        Map<String, Object> created = controller.createTask(createRequest("ORTHO_PHOTO"));
        String id = (String) created.get("id");

        Map<String, Object> detail = controller.getTask(id);
        assertThat(detail.get("id")).isEqualTo(id);
        assertThat(detail).containsKey("waypoints");
        assertThat(detail).containsKey("area");
    }

    @Test
    @DisplayName("GET /tasks/{id} 不存在抛 NotFoundException")
    void getTaskNotFound() {
        assertThatThrownBy(() -> controller.getTask("nonexistent"))
                .isInstanceOf(NotFoundException.class);
    }

    // ========== 启动/中止 ==========

    @Test
    @DisplayName("POST /tasks/{id}/start 启动测绘任务")
    void startTask() {
        Map<String, Object> created = controller.createTask(createRequest("ORTHO_PHOTO"));
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
    @DisplayName("POST /tasks/{id}/abort 中止测绘任务")
    void abortTask() {
        Map<String, Object> created = controller.createTask(createRequest("ORTHO_PHOTO"));
        String id = (String) created.get("id");

        Map<String, Object> result = controller.abortTask(id);
        assertThat(result.get("status")).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("POST /tasks/{id}/abort 不存在抛 NotFoundException")
    void abortTaskNotFound() {
        assertThatThrownBy(() -> controller.abortTask("nonexistent"))
                .isInstanceOf(NotFoundException.class);
    }

    // ========== 航线规划 ==========

    @Test
    @DisplayName("GET /tasks/{id}/waypoints 获取航线规划")
    void getWaypoints() {
        Map<String, Object> created = controller.createTask(createRequest("ORTHO_PHOTO"));
        String id = (String) created.get("id");

        List<MappingWaypoint> wps = controller.getWaypoints(id);
        assertThat(wps).isNotEmpty();
    }

    @Test
    @DisplayName("GET /tasks/{id}/waypoints 不存在抛 NotFoundException")
    void getWaypointsNotFound() {
        assertThatThrownBy(() -> controller.getWaypoints("nonexistent"))
                .isInstanceOf(NotFoundException.class);
    }

    // ========== 照片 ==========

    @Test
    @DisplayName("GET /tasks/{id}/photos 获取采集照片（初始为空）")
    void getPhotosInitiallyEmpty() {
        Map<String, Object> created = controller.createTask(createRequest("ORTHO_PHOTO"));
        String id = (String) created.get("id");

        List<CapturedPhoto> photos = controller.getPhotos(id);
        assertThat(photos).isEmpty();
    }

    @Test
    @DisplayName("GET /tasks/{id}/photos 不存在抛 NotFoundException")
    void getPhotosNotFound() {
        assertThatThrownBy(() -> controller.getPhotos("nonexistent"))
                .isInstanceOf(NotFoundException.class);
    }

    // ========== 成果生成 ==========

    @Test
    @DisplayName("POST /tasks/{id}/process 触发正射影像成果生成")
    void processOrthophotoTask() {
        Map<String, Object> created = controller.createTask(createRequest("ORTHO_PHOTO"));
        String id = (String) created.get("id");
        controller.startTask(id);

        Map<String, Object> result = controller.processTask(id);
        assertThat(result.get("status")).isEqualTo("COMPLETED");
        assertThat(result.get("resultsCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /tasks/{id}/process 触发 DEM 成果生成")
    void processDemTask() {
        Map<String, Object> created = controller.createTask(createRequest("DEM"));
        String id = (String) created.get("id");
        controller.startTask(id);

        Map<String, Object> result = controller.processTask(id);
        assertThat(result.get("status")).isEqualTo("COMPLETED");
        assertThat(result.get("resultsCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /tasks/{id}/process 触发三维模型成果生成")
    void process3DModelTask() {
        Map<String, Object> created = controller.createTask(createRequest("THREE_D_MODEL"));
        String id = (String) created.get("id");
        controller.startTask(id);

        Map<String, Object> result = controller.processTask(id);
        assertThat(result.get("status")).isEqualTo("COMPLETED");
        assertThat(result.get("resultsCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /tasks/{id}/process 触发混合成果生成（3种）")
    void processMixedTask() {
        Map<String, Object> created = controller.createTask(createRequest("MIXED"));
        String id = (String) created.get("id");
        controller.startTask(id);

        Map<String, Object> result = controller.processTask(id);
        assertThat(result.get("status")).isEqualTo("COMPLETED");
        assertThat(result.get("resultsCount")).isEqualTo(3);
    }

    @Test
    @DisplayName("POST /tasks/{id}/process 不存在抛 NotFoundException")
    void processTaskNotFound() {
        assertThatThrownBy(() -> controller.processTask("nonexistent"))
                .isInstanceOf(NotFoundException.class);
    }

    // ========== 成果查询 ==========

    @Test
    @DisplayName("GET /tasks/{id}/result 获取测绘成果")
    void getTaskResult() {
        Map<String, Object> created = controller.createTask(createRequest("ORTHO_PHOTO"));
        String id = (String) created.get("id");
        controller.startTask(id);
        controller.processTask(id);

        List<MappingResult> results = controller.getTaskResult(id);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getType()).isEqualTo(MappingType.ORTHO_PHOTO);
        assertThat(results.get(0).getStatus()).isEqualTo(MappingResult.Status.COMPLETED);
    }

    @Test
    @DisplayName("GET /results 列出所有测绘成果")
    void listResults() {
        controller.createTask(createRequest("ORTHO_PHOTO"));
        controller.createTask(createRequest("DEM"));

        // 初始无成果
        assertThat(controller.listResults()).isEmpty();

        // 处理后应有成果
        // 需要通过 process 触发
    }

    @Test
    @DisplayName("GET /results/{id}/download 下载测绘成果")
    void downloadResult() {
        Map<String, Object> created = controller.createTask(createRequest("ORTHO_PHOTO"));
        String id = (String) created.get("id");
        controller.startTask(id);
        controller.processTask(id);

        List<MappingResult> results = controller.getTaskResult(id);
        String resultId = results.get(0).getId();

        Map<String, Object> download = controller.downloadResult(resultId);
        assertThat(download.get("id")).isEqualTo(resultId);
        assertThat(download.get("type")).isEqualTo("ORTHO_PHOTO");
        assertThat(download).containsKey("downloadUrl");
        assertThat(download).containsKey("fileSizeMB");
    }

    @Test
    @DisplayName("GET /results/{id}/download 不存在抛 NotFoundException")
    void downloadResultNotFound() {
        assertThatThrownBy(() -> controller.downloadResult("nonexistent"))
                .isInstanceOf(NotFoundException.class);
    }

    // ========== 完整流程 ==========

    @Test
    @DisplayName("完整任务流程：创建 → 启动 → 处理 → 获取成果")
    void fullWorkflow() {
        // 创建
        Map<String, Object> created = controller.createTask(createRequest("ORTHO_PHOTO"));
        String id = (String) created.get("id");
        assertThat(created.get("status")).isEqualTo("PLANNING");

        // 启动
        Map<String, Object> started = controller.startTask(id);
        assertThat(started.get("status")).isEqualTo("IN_PROGRESS");

        // 处理
        Map<String, Object> processed = controller.processTask(id);
        assertThat(processed.get("status")).isEqualTo("COMPLETED");

        // 获取成果
        List<MappingResult> results = controller.getTaskResult(id);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getOrthophotoUrl()).isNotNull();
    }
}
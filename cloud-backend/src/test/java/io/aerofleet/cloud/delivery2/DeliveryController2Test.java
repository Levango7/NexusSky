package io.aerofleet.cloud.delivery2;

import io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DeliveryController2} REST 端点单测。
 * <p>
 * 直接实例化 Controller（无 MockMvc / Spring 上下文），用 AssertJ 断言。
 */
@DisplayName("DeliveryController2 配送 REST API (P4-1)")
class DeliveryController2Test {

    private RouteOptimizer routeOptimizer;
    private LandingSiteSelector landingSiteSelector;
    private DeliveryStatusTracker statusTracker;
    private DeliveryController2 controller;

    @BeforeEach
    void setUp() {
        routeOptimizer = new RouteOptimizer();
        landingSiteSelector = new LandingSiteSelector();
        statusTracker = new DeliveryStatusTracker();
        controller = new DeliveryController2(routeOptimizer, landingSiteSelector, statusTracker);
    }

    private DeliveryTask2 newTask(DeliveryTask2.Type type, DeliveryTask2.Priority priority) {
        return new DeliveryTask2(null, type, DeliveryTask2.Status.PENDING,
                39.90, 116.40, 39.95, 116.45, "Receiver",
                new Payload("P1", 2.0, 0.01, Payload.Type.MEDICINE,
                        Payload.TemperatureRange.AMBIENT, false, "test payload"),
                1, priority);
    }

    // ------------------------------------------------------------------
    // POST /tasks — 创建配送任务
    // ------------------------------------------------------------------

    @Test
    @DisplayName("createTask 创建任务并分配 ID")
    void createTask_assignsId() {
        DeliveryTask2 task = newTask(DeliveryTask2.Type.EMERGENCY_SUPPLY, DeliveryTask2.Priority.HIGH);

        DeliveryTask2 created = controller.createTask(task);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getId()).startsWith("DT-");
        assertThat(created.getStatus()).isEqualTo(DeliveryTask2.Status.PENDING);
    }

    @Test
    @DisplayName("createTask 多次创建 ID 递增")
    void createTask_multipleIdsIncrement() {
        DeliveryTask2 t1 = controller.createTask(newTask(DeliveryTask2.Type.EMERGENCY_SUPPLY, DeliveryTask2.Priority.HIGH));
        DeliveryTask2 t2 = controller.createTask(newTask(DeliveryTask2.Type.MEDICAL_SAMPLE, DeliveryTask2.Priority.NORMAL));

        assertThat(t1.getId()).isNotEqualTo(t2.getId());
    }

    // ------------------------------------------------------------------
    // GET /tasks — 列出配送任务
    // ------------------------------------------------------------------

    @Test
    @DisplayName("listTasks 空时返回空列表")
    void listTasks_empty() {
        List<DeliveryTask2> tasks = controller.listTasks();

        assertThat(tasks).isEmpty();
    }

    @Test
    @DisplayName("listTasks 返回所有已创建任务")
    void listTasks_returnsAll() {
        controller.createTask(newTask(DeliveryTask2.Type.EMERGENCY_SUPPLY, DeliveryTask2.Priority.HIGH));
        controller.createTask(newTask(DeliveryTask2.Type.MEDICAL_SAMPLE, DeliveryTask2.Priority.NORMAL));

        List<DeliveryTask2> tasks = controller.listTasks();

        assertThat(tasks).hasSize(2);
    }

    // ------------------------------------------------------------------
    // GET /tasks/{id} — 获取任务详情
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getTask 不存在的 ID 抛 NotFoundException")
    void getTask_notFound() {
        assertThatThrownBy(() -> controller.getTask("DT-9999"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("DT-9999");
    }

    @Test
    @DisplayName("getTask 返回任务详情")
    void getTask_returnsDetail() {
        DeliveryTask2 created = controller.createTask(
                newTask(DeliveryTask2.Type.EMERGENCY_SUPPLY, DeliveryTask2.Priority.HIGH));

        DeliveryTask2 found = controller.getTask(created.getId());

        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(created.getId());
        assertThat(found.getType()).isEqualTo(DeliveryTask2.Type.EMERGENCY_SUPPLY);
    }

    // ------------------------------------------------------------------
    // POST /tasks/{id}/start — 启动配送
    // ------------------------------------------------------------------

    @Test
    @DisplayName("startTask 启动后状态变为 IN_PROGRESS")
    void startTask_changesStatus() {
        DeliveryTask2 created = controller.createTask(
                newTask(DeliveryTask2.Type.EMERGENCY_SUPPLY, DeliveryTask2.Priority.HIGH));

        DeliveryTask2 started = controller.startTask(created.getId());

        assertThat(started.getStatus()).isEqualTo(DeliveryTask2.Status.IN_PROGRESS);
        assertThat(started.getStartTime()).isNotNull();
    }

    @Test
    @DisplayName("startTask 不存在的 ID 抛 NotFoundException")
    void startTask_notFound() {
        assertThatThrownBy(() -> controller.startTask("DT-9999"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("startTask 非 PENDING 状态抛 BadRequestException")
    void startTask_notPending() {
        DeliveryTask2 created = controller.createTask(
                newTask(DeliveryTask2.Type.EMERGENCY_SUPPLY, DeliveryTask2.Priority.HIGH));
        controller.startTask(created.getId());

        assertThatThrownBy(() -> controller.startTask(created.getId()))
                .isInstanceOf(io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException.class);
    }

    // ------------------------------------------------------------------
    // POST /tasks/{id}/abort — 中止配送
    // ------------------------------------------------------------------

    @Test
    @DisplayName("abortTask 中止后状态变为 ABORTED")
    void abortTask_changesStatus() {
        DeliveryTask2 created = controller.createTask(
                newTask(DeliveryTask2.Type.EMERGENCY_SUPPLY, DeliveryTask2.Priority.HIGH));

        DeliveryTask2 aborted = controller.abortTask(created.getId());

        assertThat(aborted.getStatus()).isEqualTo(DeliveryTask2.Status.ABORTED);
    }

    @Test
    @DisplayName("abortTask 不存在的 ID 抛 NotFoundException")
    void abortTask_notFound() {
        assertThatThrownBy(() -> controller.abortTask("DT-9999"))
                .isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------
    // GET /tasks/{id}/route — 获取优化路线
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getRoute 返回优化路线")
    void getRoute_returnsRoute() {
        DeliveryTask2 created = controller.createTask(
                newTask(DeliveryTask2.Type.EMERGENCY_SUPPLY, DeliveryTask2.Priority.HIGH));

        OptimizedRoute route = controller.getRoute(created.getId());

        assertThat(route).isNotNull();
        assertThat(route.getTaskId()).isEqualTo(created.getId());
        assertThat(route.getWaypoints()).isNotEmpty();
    }

    @Test
    @DisplayName("getRoute 不存在的 ID 抛 NotFoundException")
    void getRoute_notFound() {
        assertThatThrownBy(() -> controller.getRoute("DT-9999"))
                .isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------
    // POST /tasks/{id}/deliver — 执行投放
    // ------------------------------------------------------------------

    @Test
    @DisplayName("deliver AIR_DROP 投放成功")
    void deliver_airDrop() {
        DeliveryTask2 created = controller.createTask(
                newTask(DeliveryTask2.Type.EMERGENCY_SUPPLY, DeliveryTask2.Priority.HIGH));
        controller.startTask(created.getId());

        DeliveryController2.DeliverRequest req = new DeliveryController2.DeliverRequest();
        req.method = DeliveryMethod.AIR_DROP;

        java.util.Map<String, Object> result = controller.deliver(created.getId(), req);

        assertThat(result).containsKey("taskId");
        assertThat(result.get("method")).isEqualTo("AIR_DROP");
        assertThat(result.get("status")).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("deliver LAND_DELIVER 选择并验证降落点")
    void deliver_landDeliver() {
        DeliveryTask2 created = controller.createTask(
                newTask(DeliveryTask2.Type.REGULAR_PARCEL, DeliveryTask2.Priority.NORMAL));
        controller.startTask(created.getId());

        DeliveryController2.DeliverRequest req = new DeliveryController2.DeliverRequest();
        req.method = DeliveryMethod.LAND_DELIVER;

        java.util.Map<String, Object> result = controller.deliver(created.getId(), req);

        assertThat(result.get("method")).isEqualTo("LAND_DELIVER");
        assertThat(result.get("status")).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("deliver 非 IN_PROGRESS 状态抛 BadRequestException")
    void deliver_notInProgress() {
        DeliveryTask2 created = controller.createTask(
                newTask(DeliveryTask2.Type.EMERGENCY_SUPPLY, DeliveryTask2.Priority.HIGH));

        DeliveryController2.DeliverRequest req = new DeliveryController2.DeliverRequest();
        req.method = DeliveryMethod.AIR_DROP;

        assertThatThrownBy(() -> controller.deliver(created.getId(), req))
                .isInstanceOf(io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException.class);
    }

    // ------------------------------------------------------------------
    // GET /tasks/{id}/status — 配送状态
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getStatus 返回配送状态")
    void getStatus_returnsStatus() {
        DeliveryTask2 created = controller.createTask(
                newTask(DeliveryTask2.Type.EMERGENCY_SUPPLY, DeliveryTask2.Priority.HIGH));

        DeliveryStatus status = controller.getStatus(created.getId());

        assertThat(status).isNotNull();
        assertThat(status.getTaskId()).isEqualTo(created.getId());
        assertThat(status.getPhase()).isEqualTo(DeliveryStatus.Phase.CREATED);
    }

    @Test
    @DisplayName("getStatus 不存在的 ID 抛 NotFoundException")
    void getStatus_notFound() {
        assertThatThrownBy(() -> controller.getStatus("DT-9999"))
                .isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------
    // POST /tasks/{id}/confirm — 确认签收
    // ------------------------------------------------------------------

    @Test
    @DisplayName("confirm 确认签收成功")
    void confirm_success() {
        DeliveryTask2 created = controller.createTask(
                newTask(DeliveryTask2.Type.EMERGENCY_SUPPLY, DeliveryTask2.Priority.HIGH));
        controller.startTask(created.getId());

        DeliveryController2.DeliverRequest req = new DeliveryController2.DeliverRequest();
        req.method = DeliveryMethod.AIR_DROP;
        controller.deliver(created.getId(), req);

        java.util.Map<String, Object> result = controller.confirm(created.getId());

        assertThat(result.get("confirmed")).isEqualTo(true);
    }

    @Test
    @DisplayName("confirm 非 DELIVERED 状态抛 BadRequestException")
    void confirm_notDelivered() {
        DeliveryTask2 created = controller.createTask(
                newTask(DeliveryTask2.Type.EMERGENCY_SUPPLY, DeliveryTask2.Priority.HIGH));

        assertThatThrownBy(() -> controller.confirm(created.getId()))
                .isInstanceOf(io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException.class);
    }

    // ------------------------------------------------------------------
    // GET /landing-sites — 搜索降落点
    // ------------------------------------------------------------------

    @Test
    @DisplayName("searchLandingSites 返回降落点列表")
    void searchLandingSites_returnsList() {
        List<LandingSite> sites = controller.searchLandingSites(39.9050, 116.4070, 5.0);

        assertThat(sites).isNotEmpty();
    }

    @Test
    @DisplayName("searchLandingSites 小半径可能返回空列表")
    void searchLandingSites_smallRadius() {
        List<LandingSite> sites = controller.searchLandingSites(0.0, 0.0, 1.0);

        assertThat(sites).isEmpty();
    }
}
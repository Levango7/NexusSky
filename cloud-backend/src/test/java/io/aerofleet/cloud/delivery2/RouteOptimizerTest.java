package io.aerofleet.cloud.delivery2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RouteOptimizer} 单测。
 * <p>
 * 测试路线优化（单目标/多目标）、Haversine 距离估算、飞行时间估算。
 */
@DisplayName("RouteOptimizer 配送路线优化 (P4-1)")
class RouteOptimizerTest {

    private RouteOptimizer optimizer;

    @BeforeEach
    void setUp() {
        optimizer = new RouteOptimizer();
    }

    // ------------------------------------------------------------------
    // 距离估算
    // ------------------------------------------------------------------

    @Test
    @DisplayName("estimateDistance 相同点距离为 0")
    void estimateDistance_samePoint_zero() {
        double dist = optimizer.estimateDistance(39.90, 116.40, 39.90, 116.40);
        assertThat(dist).isCloseTo(0.0, org.assertj.core.api.Assertions.within(0.001));
    }

    @Test
    @DisplayName("estimateDistance 北京到上海约 1067km")
    void estimateDistance_beijingToShanghai() {
        double dist = optimizer.estimateDistance(39.90, 116.40, 31.23, 121.47);
        assertThat(dist).isCloseTo(1067.0, org.assertj.core.api.Assertions.within(50.0));
    }

    @Test
    @DisplayName("estimateDistance 短距离精度验证")
    void estimateDistance_shortDistance() {
        // 相距约 1.1km 的两个点
        double dist = optimizer.estimateDistance(39.9000, 116.4000, 39.9100, 116.4000);
        assertThat(dist).isCloseTo(1.11, org.assertj.core.api.Assertions.within(0.1));
    }

    // ------------------------------------------------------------------
    // 时间估算
    // ------------------------------------------------------------------

    @Test
    @DisplayName("estimateTime 10km@15m/s 约 667 秒")
    void estimateTime_10km() {
        double time = optimizer.estimateTime(10.0, 15.0);
        assertThat(time).isCloseTo(666.67, org.assertj.core.api.Assertions.within(1.0));
    }

    @Test
    @DisplayName("estimateTime 零距离返回 0")
    void estimateTime_zeroDistance() {
        double time = optimizer.estimateTime(0, 15.0);
        assertThat(time).isEqualTo(0.0);
    }

    @Test
    @DisplayName("estimateTime 零速度返回 0")
    void estimateTime_zeroSpeed() {
        double time = optimizer.estimateTime(10.0, 0);
        assertThat(time).isEqualTo(0.0);
    }

    // ------------------------------------------------------------------
    // 单目标路线优化
    // ------------------------------------------------------------------

    @Test
    @DisplayName("optimizeRoute 单任务生成 PICKUP + DELIVER 航点")
    void optimizeRoute_singleTask() {
        DeliveryTask2 task = new DeliveryTask2("T1",
                DeliveryTask2.Type.EMERGENCY_SUPPLY, DeliveryTask2.Status.PENDING,
                39.90, 116.40, 39.95, 116.45, "Receiver-A",
                null, 1, DeliveryTask2.Priority.HIGH);

        OptimizedRoute route = optimizer.optimizeRoute(List.of(task));

        assertThat(route).isNotNull();
        assertThat(route.getWaypoints()).isNotEmpty();
        // 应包含 PICKUP 和 DELIVER 动作
        boolean hasPickup = route.getWaypoints().stream()
                .anyMatch(w -> w.getAction() == RouteWaypoint.Action.PICKUP);
        boolean hasDeliver = route.getWaypoints().stream()
                .anyMatch(w -> w.getAction() == RouteWaypoint.Action.DELIVER);
        assertThat(hasPickup).isTrue();
        assertThat(hasDeliver).isTrue();
        // 总距离应大于 0
        assertThat(route.getTotalDistanceKm()).isGreaterThan(0);
        // 预估时间应大于 0
        assertThat(route.getEstimatedTimeMin()).isGreaterThan(0);
    }

    @Test
    @DisplayName("optimizeRoute 单任务航点序号连续递增")
    void optimizeRoute_singleTask_seqIncrement() {
        DeliveryTask2 task = new DeliveryTask2("T1",
                DeliveryTask2.Type.MEDICAL_SAMPLE, DeliveryTask2.Status.PENDING,
                39.90, 116.40, 39.95, 116.45, "Receiver-B",
                null, 1, DeliveryTask2.Priority.NORMAL);

        OptimizedRoute route = optimizer.optimizeRoute(List.of(task));

        for (int i = 0; i < route.getWaypoints().size(); i++) {
            assertThat(route.getWaypoints().get(i).getSeq()).isEqualTo(i);
        }
    }

    // ------------------------------------------------------------------
    // 多目标路线优化
    // ------------------------------------------------------------------

    @Test
    @DisplayName("optimizeRoute 多任务按优先级排序")
    void optimizeRoute_multipleTasks_priorityOrder() {
        DeliveryTask2 lowTask = new DeliveryTask2("T-low",
                DeliveryTask2.Type.REGULAR_PARCEL, DeliveryTask2.Status.PENDING,
                39.90, 116.40, 39.91, 116.41, "Receiver-Low",
                null, 1, DeliveryTask2.Priority.LOW);
        DeliveryTask2 highTask = new DeliveryTask2("T-high",
                DeliveryTask2.Type.EMERGENCY_SUPPLY, DeliveryTask2.Status.PENDING,
                39.95, 116.45, 39.96, 116.46, "Receiver-High",
                null, 1, DeliveryTask2.Priority.HIGH);
        DeliveryTask2 normalTask = new DeliveryTask2("T-normal",
                DeliveryTask2.Type.MEDICAL_SAMPLE, DeliveryTask2.Status.PENDING,
                39.92, 116.42, 39.93, 116.43, "Receiver-Normal",
                null, 1, DeliveryTask2.Priority.NORMAL);

        OptimizedRoute route = optimizer.optimizeRoute(List.of(lowTask, highTask, normalTask));

        assertThat(route.getWaypoints()).isNotEmpty();
        // HIGH 优先级任务的 PICKUP 应在第一个 PICKUP 航点
        RouteWaypoint firstPickup = route.getWaypoints().stream()
                .filter(w -> w.getAction() == RouteWaypoint.Action.PICKUP)
                .findFirst().orElse(null);
        assertThat(firstPickup).isNotNull();
        assertThat(firstPickup.getTaskId()).isEqualTo("T-high");
    }

    @Test
    @DisplayName("optimizeRoute 多任务总距离大于单任务")
    void optimizeRoute_multipleTasks_moreDistance() {
        DeliveryTask2 task1 = new DeliveryTask2("T1",
                DeliveryTask2.Type.EMERGENCY_SUPPLY, DeliveryTask2.Status.PENDING,
                39.90, 116.40, 39.95, 116.45, "R1",
                null, 1, DeliveryTask2.Priority.HIGH);
        DeliveryTask2 task2 = new DeliveryTask2("T2",
                DeliveryTask2.Type.MEDICAL_SAMPLE, DeliveryTask2.Status.PENDING,
                39.80, 116.30, 39.85, 116.35, "R2",
                null, 1, DeliveryTask2.Priority.NORMAL);

        OptimizedRoute singleRoute = optimizer.optimizeRoute(List.of(task1));
        OptimizedRoute multiRoute = optimizer.optimizeRoute(List.of(task1, task2));

        assertThat(multiRoute.getTotalDistanceKm()).isGreaterThan(singleRoute.getTotalDistanceKm());
    }

    @Test
    @DisplayName("optimizeRoute 空列表返回空路线")
    void optimizeRoute_emptyList() {
        OptimizedRoute route = optimizer.optimizeRoute(new ArrayList<>());

        assertThat(route).isNotNull();
        assertThat(route.getWaypoints()).isEmpty();
        assertThat(route.getTotalDistanceKm()).isEqualTo(0);
    }

    @Test
    @DisplayName("optimizeRoute null 返回空路线")
    void optimizeRoute_null() {
        OptimizedRoute route = optimizer.optimizeRoute(null);

        assertThat(route).isNotNull();
        assertThat(route.getWaypoints()).isEmpty();
    }
}
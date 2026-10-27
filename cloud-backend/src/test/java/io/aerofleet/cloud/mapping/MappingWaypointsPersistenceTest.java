package io.aerofleet.cloud.mapping;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 测绘航点持久化单测（V34；审计 §2.1 第 4 条）。
 * <p>
 * 契约裁决的可执行版：**任务存在 ⇒ 200+真实航点（读穿）；列 NULL（从未规划）⇒ 空数组；
 * 任务不存在 ⇒ 404（由 requireTask 既有行为承担）**——三种状态各自唯一，不再重叠。
 */
@DisplayName("测绘航点持久化")
class MappingWaypointsPersistenceTest {

    private static List<MappingWaypoint> sampleWaypoints() {
        return List.of(
                MappingWaypoint.fly(1, 39.901, 116.301, 120.0, 90.0),
                MappingWaypoint.photo(2, 39.902, 116.302, 120.0, 90.0, 45.0, 7001),
                MappingWaypoint.photo(3, 39.903, 116.303, 130.0, 180.0, 45.0, 7002));
    }

    private MappingController controllerWith(MappingTaskRepository repo) {
        return new MappingController(mock(MappingRoutePlanner.class), mock(PhotoCaptureService.class),
                mock(MappingResultService.class), new DeviceRegistry(), repo);
    }

    @Test
    @DisplayName("Codec 往返逐字段保真（含 Action 枚举与 photoId）；坏 JSON 降级空列表")
    void codecRoundTripIsFaithful() {
        String json = MappingWaypointCodec.encode(sampleWaypoints());
        List<MappingWaypoint> restored = MappingWaypointCodec.decode(json);

        assertThat(restored).hasSize(3);
        MappingWaypoint w = restored.get(1);
        assertThat(w.seq).isEqualTo(2);
        assertThat(w.lat).isEqualTo(39.902);
        assertThat(w.lon).isEqualTo(116.302);
        assertThat(w.alt).isEqualTo(120.0);
        assertThat(w.headingDeg).isEqualTo(90.0);
        assertThat(w.cameraAngleDeg).isEqualTo(45.0);
        assertThat(w.action).isEqualTo(MappingWaypoint.Action.PHOTO);
        assertThat(w.photoId).isEqualTo(7001);
        assertThat(restored.get(0).action).isEqualTo(MappingWaypoint.Action.FLY);

        assertThat(MappingWaypointCodec.decode("{not json")).isEmpty();
        assertThat(MappingWaypointCodec.decode(null)).isEmpty();
        // 未知动作名：航点保留，动作降级 null，不炸整条航线
        List<MappingWaypoint> degraded = MappingWaypointCodec.decode(
                "[{\"seq\":1,\"lat\":1.0,\"lon\":2.0,\"action\":\"NOT_A_REAL_ACTION\"}]");
        assertThat(degraded).hasSize(1);
        assertThat(degraded.get(0).action).isNull();
    }

    @Test
    @DisplayName("读穿修 200+空数组：内存未命中时从任务行重建真实航点")
    void waypointsReadThroughAfterRestart() {
        MappingTask task = new MappingTask();
        ReflectionTestUtils.setField(task, "id", "T-1");
        task.setWaypoints(MappingWaypointCodec.encode(sampleWaypoints()));
        MappingTaskRepository repo = mock(MappingTaskRepository.class);
        when(repo.findById("T-1")).thenReturn(Optional.of(task));

        MappingController controller = controllerWith(repo);
        List<MappingWaypoint> waypoints = controller.getWaypoints("T-1");

        assertThat(waypoints).hasSize(3);
        assertThat(waypoints.get(2).lat).isEqualTo(39.903);
    }

    @Test
    @DisplayName("任务存在但从未规划（列 NULL）⇒ 空数组（与 404、与有航线三态唯一）")
    void neverPlannedTaskYieldsEmptyArray() {
        MappingTask task = new MappingTask();
        ReflectionTestUtils.setField(task, "id", "T-2");
        MappingTaskRepository repo = mock(MappingTaskRepository.class);
        when(repo.findById("T-2")).thenReturn(Optional.of(task));

        List<MappingWaypoint> waypoints = controllerWith(repo).getWaypoints("T-2");
        assertThat(waypoints).isEmpty();
    }

    @Test
    @DisplayName("内存命中优先（有缓存时零反序列化——热路径不读 JSON）")
    void memoryHitTakesPrecedence() throws Exception {
        MappingTask task = new MappingTask();
        ReflectionTestUtils.setField(task, "id", "T-3");
        task.setWaypoints(MappingWaypointCodec.encode(List.of(MappingWaypoint.fly(1, 1, 1, 1, 0))));
        MappingTaskRepository repo = mock(MappingTaskRepository.class);
        when(repo.findById("T-3")).thenReturn(Optional.of(task));
        MappingController controller = controllerWith(repo);

        // 塞内存缓存（与创建路径同款）
        java.lang.reflect.Field f = MappingController.class.getDeclaredField("waypointsByTask");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        var cache = (java.util.Map<String, List<MappingWaypoint>>) f.get(controller);
        cache.put("T-3", sampleWaypoints());

        assertThat(controller.getWaypoints("T-3")).hasSize(3);   // 内存三台机全在，不是库里那条
    }
}

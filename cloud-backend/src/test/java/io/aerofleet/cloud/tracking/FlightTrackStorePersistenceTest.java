package io.aerofleet.cloud.tracking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * FlightTrackStore 持久化模式单测（直接实例化，无 Spring 上下文）。
 * <p>
 * 验证纯内存模式（repository=null）、节流持久化、@PreDestroy 行为。
 * 持久化模式通过反射注入 mock repository。
 */
@DisplayName("FlightTrackStore 持久化模式测试")
class FlightTrackStorePersistenceTest {

    private FlightTrackStore store;

    @BeforeEach
    void setUp() {
        store = new FlightTrackStore();
    }

    // ===== 纯内存模式（repository=null）=====

    @Test
    @DisplayName("纯内存模式 addPoint 正常工作")
    void memoryMode_addPoint_works() {
        FlightTrackStore.TrackPoint point = FlightTrackStore.TrackPoint.of(1, 1000L, 30.0, 120.0, 50.0);

        store.addPoint(1, point);

        assertThat(store.pointCount(1)).isEqualTo(1);
        assertThat(store.getTrack(1)).hasSize(1);
    }

    @Test
    @DisplayName("纯内存模式 getTrack 返回轨迹列表")
    void memoryMode_getTrack_works() {
        FlightTrackStore.TrackPoint p1 = FlightTrackStore.TrackPoint.of(1, 1000L, 30.0, 120.0, 50.0);
        FlightTrackStore.TrackPoint p2 = FlightTrackStore.TrackPoint.of(1, 2000L, 30.1, 120.1, 51.0);

        store.addPoint(1, p1);
        store.addPoint(1, p2);

        List<FlightTrackStore.TrackPoint> track = store.getTrack(1);

        assertThat(track).hasSize(2);
        assertThat(track.get(0).timestampMs).isEqualTo(1000L);
        assertThat(track.get(1).timestampMs).isEqualTo(2000L);
    }

    @Test
    @DisplayName("纯内存模式 getLastKnown 返回最新轨迹点")
    void memoryMode_getLastKnown_works() {
        FlightTrackStore.TrackPoint p1 = FlightTrackStore.TrackPoint.of(1, 1000L, 30.0, 120.0, 50.0);
        FlightTrackStore.TrackPoint p2 = FlightTrackStore.TrackPoint.of(1, 2000L, 30.1, 120.1, 51.0);

        store.addPoint(1, p1);
        store.addPoint(1, p2);

        FlightTrackStore.TrackPoint last = store.getLastKnown(1);

        assertThat(last).isEqualTo(p2);
    }

    @Test
    @DisplayName("纯内存模式 getLastKnown 无轨迹返回 null")
    void memoryMode_getLastKnown_noTrack_returnsNull() {
        FlightTrackStore.TrackPoint last = store.getLastKnown(999);

        assertThat(last).isNull();
    }

    @Test
    @DisplayName("纯内存模式 getTrack 不存在的无人机返回空列表")
    void memoryMode_getTrack_notExists_returnsEmpty() {
        List<FlightTrackStore.TrackPoint> track = store.getTrack(999);

        assertThat(track).isEmpty();
    }

    // ===== 节流持久化 =====

    @Test
    @DisplayName("节流持久化 — 前 9 次 addPoint 不写数据库")
    void throttlePersistence_first9NoWrite() throws Exception {
        DroneLastKnownPositionRepository repo = mock(DroneLastKnownPositionRepository.class);
        injectRepository(repo);

        for (int i = 0; i < 9; i++) {
            FlightTrackStore.TrackPoint point = FlightTrackStore.TrackPoint.of(1, i * 1000L, 30.0, 120.0, 50.0);
            store.addPoint(1, point);
        }

        verify(repo, never()).save(any(DroneLastKnownPositionEntity.class));
    }

    @Test
    @DisplayName("节流持久化 — 第 10 次 addPoint 写一次数据库")
    void throttlePersistence_10thWritesOnce() throws Exception {
        DroneLastKnownPositionRepository repo = mock(DroneLastKnownPositionRepository.class);
        injectRepository(repo);

        for (int i = 0; i < 10; i++) {
            FlightTrackStore.TrackPoint point = FlightTrackStore.TrackPoint.of(1, i * 1000L, 30.0, 120.0, 50.0);
            store.addPoint(1, point);
        }

        verify(repo, times(1)).save(any(DroneLastKnownPositionEntity.class));
    }

    @Test
    @DisplayName("节流持久化 — 第 20 次 addPoint 写第二次数据库")
    void throttlePersistence_20thWritesSecond() throws Exception {
        DroneLastKnownPositionRepository repo = mock(DroneLastKnownPositionRepository.class);
        injectRepository(repo);

        for (int i = 0; i < 20; i++) {
            FlightTrackStore.TrackPoint point = FlightTrackStore.TrackPoint.of(1, i * 1000L, 30.0, 120.0, 50.0);
            store.addPoint(1, point);
        }

        verify(repo, times(2)).save(any(DroneLastKnownPositionEntity.class));
    }

    @Test
    @DisplayName("纯内存模式 addPoint 不抛异常（repository=null）")
    void memoryMode_addPoint_noException() {
        FlightTrackStore.TrackPoint point = FlightTrackStore.TrackPoint.of(1, 1000L, 30.0, 120.0, 50.0);

        store.addPoint(1, point);

        assertThat(store.pointCount(1)).isEqualTo(1);
    }

    // ===== @PreDestroy persistAllOnShutdown =====

    @Test
    @DisplayName("persistAllOnShutdown repository=null 时跳过不抛异常")
    void persistAllOnShutdown_nullRepo_noException() {
        // 先添加一些轨迹点
        FlightTrackStore.TrackPoint point = FlightTrackStore.TrackPoint.of(1, 1000L, 30.0, 120.0, 50.0);
        store.addPoint(1, point);

        // repository 为 null，应直接跳过
        store.persistAllOnShutdown();

        // 无异常即通过
        assertThat(store.pointCount(1)).isEqualTo(1);
    }

    @Test
    @DisplayName("persistAllOnShutdown repository!=null 时持久化所有无人机最后位置")
    void persistAllOnShutdown_withRepo_persistsAll() throws Exception {
        DroneLastKnownPositionRepository repo = mock(DroneLastKnownPositionRepository.class);
        injectRepository(repo);

        FlightTrackStore.TrackPoint p1 = FlightTrackStore.TrackPoint.of(1, 1000L, 30.0, 120.0, 50.0);
        FlightTrackStore.TrackPoint p2 = FlightTrackStore.TrackPoint.of(2, 2000L, 31.0, 121.0, 60.0);
        store.addPoint(1, p1);
        store.addPoint(2, p2);

        store.persistAllOnShutdown();

        verify(repo, times(2)).save(any(DroneLastKnownPositionEntity.class));
    }

    @Test
    @DisplayName("persistAllOnShutdown 无轨迹时不写数据库")
    void persistAllOnShutdown_noTracks_noWrite() throws Exception {
        DroneLastKnownPositionRepository repo = mock(DroneLastKnownPositionRepository.class);
        injectRepository(repo);

        store.persistAllOnShutdown();

        verify(repo, never()).save(any(DroneLastKnownPositionEntity.class));
    }

    // ===== 辅助方法 =====

    /**
     * 通过反射注入 mock repository 到 FlightTrackStore 的私有字段。
     */
    private void injectRepository(DroneLastKnownPositionRepository repo) throws Exception {
        Field field = FlightTrackStore.class.getDeclaredField("repository");
        field.setAccessible(true);
        field.set(store, repo);
    }
}
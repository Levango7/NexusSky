package io.aerofleet.cloud.tracking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FlightTrackStore} 单元测试：轨迹存储/查询/清理/容量限制/线程安全。
 * <p>
 * 直接实例化（无 Spring 上下文），用 AssertJ 断言。
 */
@DisplayName("FlightTrackStore 飞行轨迹存储")
class FlightTrackStoreTest {

    private FlightTrackStore newStore() {
        FlightTrackStore store = new FlightTrackStore();
        // 默认 @Value 未注入，显式设置默认容量
        store.setMaxPoints(3600);
        return store;
    }

    private FlightTrackStore.TrackPoint point(int sysid, long ts, double lat, double lon) {
        return new FlightTrackStore.TrackPoint(sysid, ts, lat, lon, 100.0,
                5.0, 0.0, 0.0, 90.0, 80.0);
    }

    // ------------------------------------------------------------------
    // 基础存储 / 查询
    // ------------------------------------------------------------------

    @Test
    @DisplayName("addPoint 单点写入后 getTrack 返回 1 条")
    void addPoint_single_getTrackReturnsOne() {
        FlightTrackStore store = newStore();
        FlightTrackStore.TrackPoint p = point(1, 1000L, 22.5, 113.9);

        store.addPoint(1, p);
        List<FlightTrackStore.TrackPoint> track = store.getTrack(1);

        assertThat(track).hasSize(1);
        assertThat(track.get(0)).isSameAs(p);
    }

    @Test
    @DisplayName("addPoint 多点写入保持时间顺序（升序）")
    void addPoint_multiple_preservesOrder() {
        FlightTrackStore store = newStore();
        store.addPoint(1, point(1, 1000L, 22.50, 113.90));
        store.addPoint(1, point(1, 2000L, 22.51, 113.91));
        store.addPoint(1, point(1, 3000L, 22.52, 113.92));

        List<FlightTrackStore.TrackPoint> track = store.getTrack(1);

        assertThat(track).hasSize(3);
        assertThat(track.get(0).timestampMs).isEqualTo(1000L);
        assertThat(track.get(2).timestampMs).isEqualTo(3000L);
    }

    @Test
    @DisplayName("addPoint 不同 sysid 独立存储")
    void addPoint_differentSysids_isolated() {
        FlightTrackStore store = newStore();
        store.addPoint(1, point(1, 1000L, 22.50, 113.90));
        store.addPoint(2, point(2, 2000L, 30.00, 120.00));
        store.addPoint(1, point(1, 3000L, 22.51, 113.91));

        assertThat(store.getTrack(1)).hasSize(2);
        assertThat(store.getTrack(2)).hasSize(1);
    }

    @Test
    @DisplayName("getTrack 未知 sysid 返回空列表")
    void getTrack_unknownSysid_returnsEmpty() {
        FlightTrackStore store = newStore();

        assertThat(store.getTrack(99)).isEmpty();
    }

    @Test
    @DisplayName("getTrack limit 截取最近 N 条")
    void getTrack_withLimit_returnsLastN() {
        FlightTrackStore store = newStore();
        for (int i = 0; i < 10; i++) {
            store.addPoint(1, point(1, i * 1000L, 22.0 + i * 0.01, 113.0));
        }

        List<FlightTrackStore.TrackPoint> last5 = store.getTrack(1, 5);

        assertThat(last5).hasSize(5);
        assertThat(last5.get(0).timestampMs).isEqualTo(5000L);
        assertThat(last5.get(4).timestampMs).isEqualTo(9000L);
    }

    @Test
    @DisplayName("getTrack limit=0 返回全部")
    void getTrack_limitZero_returnsAll() {
        FlightTrackStore store = newStore();
        for (int i = 0; i < 5; i++) {
            store.addPoint(1, point(1, i * 1000L, 22.0, 113.0));
        }

        assertThat(store.getTrack(1, 0)).hasSize(5);
    }

    @Test
    @DisplayName("getTrack limit 超过实际数量返回全部")
    void getTrack_limitExceedsSize_returnsAll() {
        FlightTrackStore store = newStore();
        store.addPoint(1, point(1, 1000L, 22.0, 113.0));
        store.addPoint(1, point(1, 2000L, 22.0, 113.0));

        assertThat(store.getTrack(1, 100)).hasSize(2);
    }

    // ------------------------------------------------------------------
    // 时间范围查询 / 历史回放
    // ------------------------------------------------------------------

    @Test
    @DisplayName("replay 按时间范围查询返回子集（升序）")
    void testReplayByTimeRange() {
        FlightTrackStore store = newStore();
        // 时间戳：1000, 2000, 3000, 4000, 5000
        for (int i = 1; i <= 5; i++) {
            store.addPoint(1, point(1, i * 1000L, 22.0 + i * 0.01, 113.0));
        }

        // 查询 [2000, 4000] 应返回 3 条（2000, 3000, 4000）
        List<FlightTrackStore.TrackPoint> replay = store.getTrack(1, 2000L, 4000L, 0);

        assertThat(replay).hasSize(3);
        assertThat(replay.get(0).timestampMs).isEqualTo(2000L);
        assertThat(replay.get(1).timestampMs).isEqualTo(3000L);
        assertThat(replay.get(2).timestampMs).isEqualTo(4000L);
    }

    @Test
    @DisplayName("replay from=0, to=0 查询全部")
    void testReplayAllTimeRange() {
        FlightTrackStore store = newStore();
        for (int i = 1; i <= 5; i++) {
            store.addPoint(1, point(1, i * 1000L, 22.0, 113.0));
        }

        List<FlightTrackStore.TrackPoint> replay = store.getTrack(1, 0L, 0L, 0);

        assertThat(replay).hasSize(5);
        // 验证升序
        for (int i = 0; i < replay.size() - 1; i++) {
            assertThat(replay.get(i).timestampMs).isLessThanOrEqualTo(replay.get(i + 1).timestampMs);
        }
    }

    @Test
    @DisplayName("replay limit 截断取时间最近的 N 条")
    void testReplayWithLimit() {
        FlightTrackStore store = newStore();
        // 时间戳：1000..5000
        for (int i = 1; i <= 5; i++) {
            store.addPoint(1, point(1, i * 1000L, 22.0, 113.0));
        }

        // 范围内 5 条，limit=2 应返回最近的 2 条（4000, 5000）
        List<FlightTrackStore.TrackPoint> replay = store.getTrack(1, 0L, 0L, 2);

        assertThat(replay).hasSize(2);
        assertThat(replay.get(0).timestampMs).isEqualTo(4000L);
        assertThat(replay.get(1).timestampMs).isEqualTo(5000L);
    }

    @Test
    @DisplayName("replay 时间范围内无点返回空列表")
    void testReplayEmptyRange() {
        FlightTrackStore store = newStore();
        store.addPoint(1, point(1, 1000L, 22.0, 113.0));
        store.addPoint(1, point(1, 2000L, 22.0, 113.0));

        // 查询 [5000, 6000] 范围内无点
        List<FlightTrackStore.TrackPoint> replay = store.getTrack(1, 5000L, 6000L, 0);

        assertThat(replay).isEmpty();
    }

    @Test
    @DisplayName("replay 边界条件：fromMs == timestampMs == toMs")
    void testReplayBoundary() {
        FlightTrackStore store = newStore();
        store.addPoint(1, point(1, 1000L, 22.0, 113.0));
        store.addPoint(1, point(1, 2000L, 22.1, 113.1));
        store.addPoint(1, point(1, 3000L, 22.2, 113.2));

        // from == to == 2000，应精确匹配 timestampMs=2000 的点
        List<FlightTrackStore.TrackPoint> replay = store.getTrack(1, 2000L, 2000L, 0);

        assertThat(replay).hasSize(1);
        assertThat(replay.get(0).timestampMs).isEqualTo(2000L);
        assertThat(replay.get(0).lat).isEqualTo(22.1);
    }

    // ------------------------------------------------------------------
    // 最后已知位置
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getLastKnown 返回最新轨迹点")
    void getLastKnown_returnsLatest() {
        FlightTrackStore store = newStore();
        store.addPoint(1, point(1, 1000L, 22.50, 113.90));
        FlightTrackStore.TrackPoint latest = point(1, 2000L, 22.51, 113.91);
        store.addPoint(1, latest);

        FlightTrackStore.TrackPoint result = store.getLastKnown(1);

        assertThat(result).isSameAs(latest);
    }

    @Test
    @DisplayName("getLastKnown 无轨迹返回 null")
    void getLastKnown_noTrack_returnsNull() {
        FlightTrackStore store = newStore();

        assertThat(store.getLastKnown(1)).isNull();
    }

    // ------------------------------------------------------------------
    // 容量限制
    // ------------------------------------------------------------------

    @Test
    @DisplayName("容量限制：超过 maxPoints 时丢弃最旧点")
    void capacity_overflow_dropsOldest() {
        FlightTrackStore store = newStore();
        store.setMaxPoints(3);
        store.addPoint(1, point(1, 1000L, 22.00, 113.00));
        store.addPoint(1, point(1, 2000L, 22.01, 113.01));
        store.addPoint(1, point(1, 3000L, 22.02, 113.02));
        store.addPoint(1, point(1, 4000L, 22.03, 113.03));

        List<FlightTrackStore.TrackPoint> track = store.getTrack(1);

        assertThat(track).hasSize(3);
        assertThat(track.get(0).timestampMs).isEqualTo(2000L);
        assertThat(track.get(2).timestampMs).isEqualTo(4000L);
    }

    @Test
    @DisplayName("容量限制：maxPoints=1 仅保留最新点")
    void capacity_one_keepsLatestOnly() {
        FlightTrackStore store = newStore();
        store.setMaxPoints(1);
        store.addPoint(1, point(1, 1000L, 22.00, 113.00));
        store.addPoint(1, point(1, 2000L, 22.01, 113.01));

        List<FlightTrackStore.TrackPoint> track = store.getTrack(1);

        assertThat(track).hasSize(1);
        assertThat(track.get(0).timestampMs).isEqualTo(2000L);
    }

    // ------------------------------------------------------------------
    // 清理
    // ------------------------------------------------------------------

    @Test
    @DisplayName("clearTrack 清除指定无人机轨迹")
    void clearTrack_removesTrack() {
        FlightTrackStore store = newStore();
        store.addPoint(1, point(1, 1000L, 22.0, 113.0));
        store.addPoint(2, point(2, 2000L, 30.0, 120.0));

        store.clearTrack(1);

        assertThat(store.getTrack(1)).isEmpty();
        assertThat(store.getTrack(2)).hasSize(1);
    }

    @Test
    @DisplayName("clearTrack 未记录的 sysid 不抛异常")
    void clearTrack_unknownSysid_noException() {
        FlightTrackStore store = newStore();
        store.clearTrack(99); // should not throw
        assertThat(store.getTrack(99)).isEmpty();
    }

    @Test
    @DisplayName("clearAll 清除所有轨迹")
    void clearAll_removesAll() {
        FlightTrackStore store = newStore();
        store.addPoint(1, point(1, 1000L, 22.0, 113.0));
        store.addPoint(2, point(2, 2000L, 30.0, 120.0));

        store.clearAll();

        assertThat(store.trackedDroneCount()).isZero();
        assertThat(store.getTrack(1)).isEmpty();
        assertThat(store.getTrack(2)).isEmpty();
    }

    // ------------------------------------------------------------------
    // 统计
    // ------------------------------------------------------------------

    @Test
    @DisplayName("trackedDroneCount 返回已记录无人机数量")
    void trackedDroneCount_returnsCount() {
        FlightTrackStore store = newStore();
        store.addPoint(1, point(1, 1000L, 22.0, 113.0));
        store.addPoint(2, point(2, 2000L, 30.0, 120.0));
        store.addPoint(3, point(3, 3000L, 40.0, 110.0));

        assertThat(store.trackedDroneCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("pointCount 返回指定无人机轨迹点数")
    void pointCount_returnsPointSize() {
        FlightTrackStore store = newStore();
        store.addPoint(1, point(1, 1000L, 22.0, 113.0));
        store.addPoint(1, point(1, 2000L, 22.1, 113.1));
        store.addPoint(1, point(1, 3000L, 22.2, 113.2));

        assertThat(store.pointCount(1)).isEqualTo(3);
        assertThat(store.pointCount(99)).isZero();
    }

    // ------------------------------------------------------------------
    // 线程安全
    // ------------------------------------------------------------------

    @Test
    @DisplayName("并发追加：多线程写入同一 sysid 不丢失不超容量")
    void concurrentAdd_threadSafe() throws InterruptedException {
        FlightTrackStore store = newStore();
        store.setMaxPoints(1000);
        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger sysid = new AtomicInteger(1);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    int id = sysid.get();
                    for (int i = 0; i < perThread; i++) {
                        store.addPoint(id, point(id, System.currentTimeMillis(),
                                22.0 + i * 0.001, 113.0));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        // 容量 1000，总写入 1600，最终应恰好 1000
        assertThat(store.pointCount(1)).isEqualTo(1000);
    }

    // ------------------------------------------------------------------
    // TrackPoint 工具方法
    // ------------------------------------------------------------------

    @Test
    @DisplayName("TrackPoint.groundSpeed 计算 vx/vy 合速度")
    void trackPoint_groundSpeed_computes() {
        FlightTrackStore.TrackPoint p = new FlightTrackStore.TrackPoint(
                1, 1000L, 22.0, 113.0, 100.0,
                3.0, 4.0, 0.0, 90.0, 80.0);

        assertThat(p.groundSpeed()).isEqualTo(5.0); // 3-4-5 triangle
    }

    @Test
    @DisplayName("TrackPoint.of 工厂方法：速度/航向/电量置默认值")
    void trackPoint_of_setsDefaults() {
        FlightTrackStore.TrackPoint p = FlightTrackStore.TrackPoint.of(1, 1000L, 22.0, 113.0, 50.0);

        assertThat(p.sysid).isEqualTo(1);
        assertThat(p.lat).isEqualTo(22.0);
        assertThat(p.alt).isEqualTo(50.0);
        assertThat(p.vx).isNaN();
        assertThat(p.heading).isNaN();
        assertThat(p.batteryPct).isEqualTo(-1.0);
    }
}
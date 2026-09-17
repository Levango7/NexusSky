package io.aerofleet.cloud.twin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ScenarioReplayService 场景回放单测（M13）。
 * <p>
 * 直接实例化 Service（无 Spring 上下文），覆盖快照记录/回放查询/历史容量上限。
 */
@DisplayName("ScenarioReplayService 场景回放 (M13)")
class ScenarioReplayServiceTest {

    private ScenarioReplayService service;

    @BeforeEach
    void setUp() {
        service = new ScenarioReplayService();
    }

    @Test
    @DisplayName("recordSnapshot 后 getHistorySize 反映记录数")
    void recordSnapshotIncrementsHistorySize() {
        assertThat(service.getHistorySize()).isZero();

        service.recordSnapshot(snapshotOf(1, 30.0, 120.0));
        assertThat(service.getHistorySize()).isEqualTo(1);

        service.recordSnapshot(snapshotOf(1, 30.001, 120.001));
        assertThat(service.getHistorySize()).isEqualTo(2);
    }

    @Test
    @DisplayName("getReplayData 返回所有历史快照")
    void getReplayDataReturnsAllSnapshots() {
        service.recordSnapshot(snapshotOf(1, 30.0, 120.0));
        service.recordSnapshot(snapshotOf(1, 30.001, 120.001));
        service.recordSnapshot(snapshotOf(2, 31.0, 121.0));

        List<Map<Integer, TwinState>> replay = service.getReplayData(0, Long.MAX_VALUE);
        assertThat(replay).hasSize(3);
    }

    @Test
    @DisplayName("getReplayData 空历史返回空列表")
    void getReplayDataEmptyHistory() {
        List<Map<Integer, TwinState>> replay = service.getReplayData(0, 1000);
        assertThat(replay).isEmpty();
    }

    @Test
    @DisplayName("recordSnapshot 超过 1800 上限后自动淘汰最旧快照")
    void recordSnapshotEvictsOldEntriesOverCapacity() {
        // 写入 1805 个快照
        for (int i = 0; i < 1805; i++) {
            service.recordSnapshot(snapshotOf(1, 30.0 + i * 0.0001, 120.0));
        }

        // 容量上限 1800
        assertThat(service.getHistorySize()).isEqualTo(1800);
    }

    @Test
    @DisplayName("recordSnapshot 保留最新而非最旧的快照")
    void recordSnapshotKeepsNewestSnapshots() {
        // 写入 1801 个快照，每个快照内 sysid=1 的 lat 不同
        for (int i = 0; i < 1801; i++) {
            service.recordSnapshot(snapshotOf(1, 30.0 + i, 120.0));
        }

        List<Map<Integer, TwinState>> replay = service.getReplayData(0, Long.MAX_VALUE);
        assertThat(replay).hasSize(1800);
        // 第一个保留的应该是 i=1（i=0 被淘汰）
        TwinState first = replay.get(0).get(1);
        assertThat(first.lat).isEqualTo(31.0); // 30.0 + 1
        // 最后一个保留的是 i=1800
        TwinState last = replay.get(replay.size() - 1).get(1);
        assertThat(last.lat).isEqualTo(1830.0); // 30.0 + 1800
    }

    @Test
    @DisplayName("recordSnapshot 多无人机快照整体保存")
    void recordSnapshotMultipleDronesInOneSnapshot() {
        Map<Integer, TwinState> snap = new LinkedHashMap<>();
        snap.put(1, new TwinState(1, 30.0, 120.0, 100, 0, 10, 80, 0, 0));
        snap.put(2, new TwinState(2, 31.0, 121.0, 200, 90, 15, 60, 0, 0));
        service.recordSnapshot(snap);

        List<Map<Integer, TwinState>> replay = service.getReplayData(0, Long.MAX_VALUE);
        assertThat(replay).hasSize(1);
        assertThat(replay.get(0)).hasSize(2);
        assertThat(replay.get(0).keySet()).containsExactlyInAnyOrder(1, 2);
    }

    /** 构造单无人机快照。 */
    private static Map<Integer, TwinState> snapshotOf(int sysid, double lat, double lon) {
        Map<Integer, TwinState> snap = new LinkedHashMap<>();
        snap.put(sysid, new TwinState(sysid, lat, lon, 100.0, 0, 10, 80, 0L, 0));
        return snap;
    }
}
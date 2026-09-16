package io.aerofleet.cloud.api;

import io.aerofleet.cloud.api.dto.MeshNodeSnapshot;
import io.aerofleet.mavlink.messages.MeshNeighborTableMsg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MeshTopologyService 拓扑快照服务单测（M5 应急 mesh，FR-27/28/29）。
 * <p>
 * 直接实例化 Service（无 Spring 上下文），覆盖上报/查询/版本递增/变化检测/链路合并。
 */
@DisplayName("MeshTopologyService 拓扑快照服务 (FR-27/28/29)")
class MeshTopologyServiceTest {

    private static MeshNeighborTableMsg reportOf(int sysid, int... neighborSysids) {
        List<MeshNeighborTableMsg.NeighborInfo> infos = new java.util.ArrayList<>();
        for (int n : neighborSysids) {
            infos.add(new MeshNeighborTableMsg.NeighborInfo(n, -60, 1));
        }
        return new MeshNeighborTableMsg(sysid, 0L, infos);
    }

    @Test
    @DisplayName("onNeighborTable 上报后 getSnapshot 返回非空且邻居正确")
    void onNeighborTableStoresSnapshot() {
        MeshTopologyService svc = new MeshTopologyService();
        svc.onNeighborTable(1, reportOf(1, 2, 3));

        MeshNodeSnapshot snap = svc.getSnapshot(1);
        assertThat(snap).isNotNull();
        assertThat(snap.sysid).isEqualTo(1);
        assertThat(snap.neighbors).hasSize(2);
        assertThat(snap.neighbors.get(0).sysid()).isEqualTo(2);
        assertThat(snap.neighbors.get(1).sysid()).isEqualTo(3);
    }

    @Test
    @DisplayName("getSnapshot 未上报的 sysid 返回 null")
    void getSnapshotReturnsNullForUnknown() {
        MeshTopologyService svc = new MeshTopologyService();
        assertThat(svc.getSnapshot(99)).isNull();
    }

    @Test
    @DisplayName("getAllSnapshots 按 sysid 排序（TreeMap）")
    void getAllSnapshotsSortedBySysid() {
        MeshTopologyService svc = new MeshTopologyService();
        svc.onNeighborTable(3, reportOf(3));
        svc.onNeighborTable(1, reportOf(1));
        svc.onNeighborTable(2, reportOf(2));

        Map<Integer, MeshNodeSnapshot> all = svc.getAllSnapshots();
        assertThat(all.keySet()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("currentVersion 每次上报递增")
    void currentVersionIncrements() {
        MeshTopologyService svc = new MeshTopologyService();
        assertThat(svc.currentVersion()).isZero();

        svc.onNeighborTable(1, reportOf(1, 2));
        assertThat(svc.currentVersion()).isEqualTo(1);

        svc.onNeighborTable(2, reportOf(2, 1));
        assertThat(svc.currentVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("detectChanges 首次调用产生 NODE_JOINED 与 LINK_UP 事件")
    void detectChangesNodeJoined() {
        MeshTopologyService svc = new MeshTopologyService();
        svc.onNeighborTable(1, reportOf(1, 2));

        List<MeshTopologyService.MeshTopologyEvent> events = svc.detectChanges();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo(MeshTopologyService.EventType.NODE_JOINED);
        assertThat(events.get(1).type()).isEqualTo(MeshTopologyService.EventType.LINK_UP);
    }

    @Test
    @DisplayName("detectChanges 邻居新增产生 LINK_UP，邻居移除产生 LINK_DOWN")
    void detectChangesLinkUpAndDown() {
        MeshTopologyService svc = new MeshTopologyService();
        svc.onNeighborTable(1, reportOf(1, 2, 3));
        svc.detectChanges(); // 基线

        // 邻居变为只有 2（移除 3，新增 4）
        svc.onNeighborTable(1, reportOf(1, 2, 4));
        List<MeshTopologyService.MeshTopologyEvent> events = svc.detectChanges();

        boolean hasLinkUp = events.stream()
                .anyMatch(e -> e.type() == MeshTopologyService.EventType.LINK_UP
                        && e.toSysid() == 4);
        boolean hasLinkDown = events.stream()
                .anyMatch(e -> e.type() == MeshTopologyService.EventType.LINK_DOWN
                        && e.toSysid() == 3);
        assertThat(hasLinkUp).isTrue();
        assertThat(hasLinkDown).isTrue();
    }

    @Test
    @DisplayName("getAllLinks 合并双向链路 A-B 与 B-A 为一条无向边")
    void getAllLinksMergesBidirectional() {
        MeshTopologyService svc = new MeshTopologyService();
        svc.onNeighborTable(1, reportOf(1, 2));
        svc.onNeighborTable(2, reportOf(2, 1));

        List<MeshNodeSnapshot.LinkDto> links = svc.getAllLinks();
        assertThat(links).hasSize(1);
        assertThat(links.get(0).from()).isEqualTo(1);
        assertThat(links.get(0).to()).isEqualTo(2);
    }

    @Test
    @DisplayName("链路质量序数正确映射为名称 EXCELLENT/GOOD/FAIR/POOR")
    void qualityNameMapping() {
        MeshTopologyService svc = new MeshTopologyService();
        List<MeshNeighborTableMsg.NeighborInfo> infos = List.of(
                new MeshNeighborTableMsg.NeighborInfo(2, -40, 0),
                new MeshNeighborTableMsg.NeighborInfo(3, -60, 1),
                new MeshNeighborTableMsg.NeighborInfo(4, -80, 2),
                new MeshNeighborTableMsg.NeighborInfo(5, -90, 3));
        svc.onNeighborTable(1, new MeshNeighborTableMsg(1, 0L, infos));

        MeshNodeSnapshot snap = svc.getSnapshot(1);
        assertThat(snap.neighbors.get(0).quality()).isEqualTo("EXCELLENT");
        assertThat(snap.neighbors.get(1).quality()).isEqualTo("GOOD");
        assertThat(snap.neighbors.get(2).quality()).isEqualTo("FAIR");
        assertThat(snap.neighbors.get(3).quality()).isEqualTo("POOR");
    }
}
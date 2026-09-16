package io.aerofleet.sim.mesh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NeighborTable 邻居表容器单测（M5 应急 mesh，FR-10/11）。
 */
@DisplayName("NeighborTable 邻居表 (FR-10/11)")
class NeighborTableTest {

    private static final InetSocketAddress ADDR_A = new InetSocketAddress("10.0.0.2", 14550);
    private static final InetSocketAddress ADDR_B = new InetSocketAddress("10.0.0.3", 14550);

    @Test
    @DisplayName("upsert 新增邻居后 get/size/contains 正确")
    void upsertNewNeighbor() {
        NeighborTable table = new NeighborTable();
        table.upsert(2, ADDR_A, 1000L, -50);

        assertThat(table.size()).isEqualTo(1);
        assertThat(table.contains(2)).isTrue();
        NeighborEntry e = table.get(2);
        assertThat(e.sysid).isEqualTo(2);
        assertThat(e.addr).isEqualTo(ADDR_A);
        assertThat(e.lastSeenMs).isEqualTo(1000L);
        assertThat(e.rssiDbm).isEqualTo(-50);
        assertThat(e.linkQuality).isEqualTo(LinkQuality.GOOD);
    }

    @Test
    @DisplayName("upsert 同地址刷新 lastSeen/rssi，sysid 不变")
    void upsertRefreshExisting() {
        NeighborTable table = new NeighborTable();
        table.upsert(2, ADDR_A, 1000L, -50);
        table.upsert(2, ADDR_A, 2000L, -80);

        NeighborEntry e = table.get(2);
        assertThat(table.size()).isEqualTo(1);
        assertThat(e.lastSeenMs).isEqualTo(2000L);
        assertThat(e.rssiDbm).isEqualTo(-80);
        assertThat(e.linkQuality).isEqualTo(LinkQuality.FAIR);
    }

    @Test
    @DisplayName("upsert 地址变更时重建条目")
    void upsertAddressChangeRecreates() {
        NeighborTable table = new NeighborTable();
        table.upsert(2, ADDR_A, 1000L, -50);
        table.upsert(2, ADDR_B, 3000L, -90);

        NeighborEntry e = table.get(2);
        assertThat(e.addr).isEqualTo(ADDR_B);
        assertThat(e.lastSeenMs).isEqualTo(3000L);
        assertThat(e.rssiDbm).isEqualTo(-90);
    }

    @Test
    @DisplayName("findExpired 返回超时邻居 sysid 列表")
    void findExpired() {
        NeighborTable table = new NeighborTable();
        table.upsert(2, ADDR_A, 1000L, -50);
        table.upsert(3, ADDR_B, 5000L, -60);

        // now=8000, timeout=5000 → 邻居 2 (1000) 超时，邻居 3 (5000) 未超时
        List<Integer> expired = table.findExpired(8000L, 5000L);
        assertThat(expired).containsExactly(2);
    }

    @Test
    @DisplayName("snapshot 返回不可变列表")
    void snapshotIsImmutable() {
        NeighborTable table = new NeighborTable();
        table.upsert(2, ADDR_A, 0L, -50);

        List<NeighborEntry> snap = table.snapshot();
        assertThat(snap).hasSize(1);
        assertThatThrownBy(() -> snap.add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("remove 移除指定邻居")
    void removeNeighbor() {
        NeighborTable table = new NeighborTable();
        table.upsert(2, ADDR_A, 0L, -50);
        table.upsert(3, ADDR_B, 0L, -60);

        table.remove(2);
        assertThat(table.size()).isEqualTo(1);
        assertThat(table.contains(2)).isFalse();
        assertThat(table.contains(3)).isTrue();
    }

    @Test
    @DisplayName("clear 清空全部邻居")
    void clearAll() {
        NeighborTable table = new NeighborTable();
        table.upsert(2, ADDR_A, 0L, -50);
        table.upsert(3, ADDR_B, 0L, -60);

        table.clear();
        assertThat(table.size()).isZero();
        assertThat(table.snapshot()).isEmpty();
    }
}
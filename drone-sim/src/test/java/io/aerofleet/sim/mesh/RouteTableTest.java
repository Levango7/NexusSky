package io.aerofleet.sim.mesh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RouteTable 路由表容器单测（M5 应急 mesh，FR-17/18/21）。
 * <p>
 * 覆盖 upsert/lookup/lookupAny/remove/removeByNextHop/findAffectedTargets/
 * promoteBackup/cleanupExpired/主备切换/路径数上限。
 */
@DisplayName("RouteTable 路由表 (FR-17/18/21)")
class RouteTableTest {

    private static final long LIFETIME = 60_000L;

    @Test
    @DisplayName("upsert 主路径后 lookup 返回该路由")
    void upsertAndLookup() {
        RouteTable table = new RouteTable(LIFETIME);
        long now = System.currentTimeMillis();
        table.upsert(5, 2, 1, 10.0, now, true);

        RouteEntry r = table.lookup(5);
        assertThat(r).isNotNull();
        assertThat(r.targetSysId).isEqualTo(5);
        assertThat(r.nextHop).isEqualTo(2);
        assertThat(r.hopCount).isEqualTo(1);
        assertThat(r.metric).isEqualTo(10.0);
        assertThat(r.isPrimary).isTrue();
    }

    @Test
    @DisplayName("更优 metric 的新主路径替换旧主，旧主降为备份")
    void upsertBetterMetricReplacesPrimary() {
        RouteTable table = new RouteTable(LIFETIME);
        long now = System.currentTimeMillis();
        table.upsert(5, 2, 1, 30.0, now, true);
        // 新 nextHop=3, metric=10 更优 → 新为主，旧降备份
        table.upsert(5, 3, 1, 10.0, now, true);

        RouteEntry primary = table.lookup(5);
        assertThat(primary.nextHop).isEqualTo(3);
        assertThat(primary.metric).isEqualTo(10.0);
        assertThat(primary.isPrimary).isTrue();
        // 旧主仍存在但降为备份
        assertThat(table.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("lookupAny 在无主路径时回退到备份")
    void lookupAnyFallsBackToBackup() {
        RouteTable table = new RouteTable(LIFETIME);
        long now = System.currentTimeMillis();
        // 插入一条非主路径
        table.upsert(5, 2, 1, 10.0, now, false);

        assertThat(table.lookup(5)).isNull();
        RouteEntry any = table.lookupAny(5);
        assertThat(any).isNotNull();
        assertThat(any.nextHop).isEqualTo(2);
    }

    @Test
    @DisplayName("removeByNextHop 删除所有以指定 nextHop 为下一跳的项")
    void removeByNextHop() {
        RouteTable table = new RouteTable(LIFETIME);
        long now = System.currentTimeMillis();
        table.upsert(5, 2, 1, 10.0, now, true);
        table.upsert(6, 2, 1, 20.0, now, true);
        table.upsert(7, 3, 1, 30.0, now, true);

        table.removeByNextHop(2);
        assertThat(table.lookup(5)).isNull();
        assertThat(table.lookup(6)).isNull();
        assertThat(table.lookup(7)).isNotNull();
    }

    @Test
    @DisplayName("findAffectedTargets 返回以指定 nextHop 为下一跳的所有 target")
    void findAffectedTargets() {
        RouteTable table = new RouteTable(LIFETIME);
        long now = System.currentTimeMillis();
        table.upsert(5, 2, 1, 10.0, now, true);
        table.upsert(6, 2, 1, 20.0, now, true);
        table.upsert(7, 3, 1, 30.0, now, true);

        List<Integer> targets = table.findAffectedTargets(2);
        assertThat(targets).containsExactlyInAnyOrder(5, 6);
    }

    @Test
    @DisplayName("promoteBackup 将最优备份提升为主，原主降为备份")
    void promoteBackup() {
        RouteTable table = new RouteTable(LIFETIME);
        long now = System.currentTimeMillis();
        table.upsert(5, 2, 1, 30.0, now, true);   // 主 via 2
        table.upsert(5, 3, 1, 20.0, now, false);  // 备 via 3

        assertThat(table.promoteBackup(5)).isTrue();
        RouteEntry primary = table.lookup(5);
        assertThat(primary.nextHop).isEqualTo(3);
        assertThat(primary.isPrimary).isTrue();
    }

    @Test
    @DisplayName("promoteBackup 无备份时返回 false")
    void promoteBackupNoBackupReturnsFalse() {
        RouteTable table = new RouteTable(LIFETIME);
        long now = System.currentTimeMillis();
        table.upsert(5, 2, 1, 10.0, now, true); // 仅主路径

        assertThat(table.promoteBackup(5)).isFalse();
    }

    @Test
    @DisplayName("cleanupExpired 清理过期项并返回被清理列表")
    void cleanupExpired() {
        // lifetime=0 → 立即过期
        RouteTable table = new RouteTable(0);
        long now = System.currentTimeMillis();
        table.upsert(5, 2, 1, 10.0, now, true);

        List<RouteEntry> removed = table.cleanupExpired(now + 1);
        assertThat(removed).hasSize(1);
        assertThat(table.size()).isZero();
    }

    @Test
    @DisplayName("到同一目标至多保留 2 条路径（metric 最小者优先）")
    void maxRoutesPerTargetLimit() {
        RouteTable table = new RouteTable(LIFETIME);
        long now = System.currentTimeMillis();
        table.upsert(5, 2, 1, 30.0, now, true);
        table.upsert(5, 3, 1, 20.0, now, false);
        table.upsert(5, 4, 1, 10.0, now, false);

        assertThat(table.size()).isEqualTo(RouteTable.MAX_ROUTES_PER_TARGET);
    }

    @Test
    @DisplayName("remove 删除指定目标全部路由")
    void removeTarget() {
        RouteTable table = new RouteTable(LIFETIME);
        long now = System.currentTimeMillis();
        table.upsert(5, 2, 1, 10.0, now, true);

        table.remove(5);
        assertThat(table.lookup(5)).isNull();
        assertThat(table.size()).isZero();
    }

    @Test
    @DisplayName("snapshot 返回所有路由项的不可变列表")
    void snapshotIsImmutable() {
        RouteTable table = new RouteTable(LIFETIME);
        long now = System.currentTimeMillis();
        table.upsert(5, 2, 1, 10.0, now, true);
        table.upsert(6, 3, 1, 20.0, now, true);

        List<RouteEntry> snap = table.snapshot();
        assertThat(snap).hasSize(2);
        assertThatThrownByImmutable(snap);
    }

    private static void assertThatThrownByImmutable(List<RouteEntry> snap) {
        try {
            snap.add(null);
            throw new AssertionError("snapshot should be immutable");
        } catch (UnsupportedOperationException expected) {
            // 预期：不可变
        }
    }
}
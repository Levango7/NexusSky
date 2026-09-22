package io.aerofleet.sim.mesh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MultiPathRouter 多路径冗余路由单测（灾害应急通讯组网，FR-17）。
 * <p>
 * 覆盖 addPrimaryRoute/addBackupRoute/switchToBackup/节点不相交验证/备用路径上限。
 */
@DisplayName("MultiPathRouter 多路径冗余路由 (FR-17)")
class MultiPathRouterTest {

    /** 创建 RouteEntry 辅助方法。 */
    private static RouteEntry route(int target, int nextHop, int hops, double metric, boolean primary) {
        long now = System.currentTimeMillis();
        return new RouteEntry(target, nextHop, hops, metric, now, now + 60000, primary);
    }

    @Test
    @DisplayName("addPrimaryRoute：添加主路径后 getPrimaryRoute 返回该路由")
    void addPrimaryRoute() {
        MultiPathRouter router = new MultiPathRouter();
        RouteEntry primary = route(5, 2, 1, 10.0, true);
        router.addPrimaryRoute(5, primary);

        RouteEntry result = router.getPrimaryRoute(5);
        assertThat(result).isNotNull();
        assertThat(result.targetSysId).isEqualTo(5);
        assertThat(result.nextHop).isEqualTo(2);
    }

    @Test
    @DisplayName("addPrimaryRoute：新主路径替换旧主，旧主降为备份")
    void newPrimaryDemotesOldPrimary() {
        MultiPathRouter router = new MultiPathRouter();
        router.addPrimaryRoute(5, route(5, 2, 1, 30.0, true));
        router.addPrimaryRoute(5, route(5, 3, 1, 10.0, true));

        // 新主路径 via 3
        RouteEntry primary = router.getPrimaryRoute(5);
        assertThat(primary.nextHop).isEqualTo(3);

        // 旧主路径 via 2 降为备份
        assertThat(router.backupCount(5)).isEqualTo(1);
    }

    @Test
    @DisplayName("addBackupRoute：添加备用路径")
    void addBackupRoute() {
        MultiPathRouter router = new MultiPathRouter();
        router.addPrimaryRoute(5, route(5, 2, 1, 10.0, true));

        // 添加备用路径（nextHop 不同 → 节点不相交）
        RouteEntry backup = route(5, 3, 2, 20.0, false);
        boolean added = router.addBackupRoute(5, backup);
        assertThat(added).isTrue();
        assertThat(router.backupCount(5)).isEqualTo(1);
    }

    @Test
    @DisplayName("addBackupRoute：节点不相交检查失败时不添加")
    void addBackupRouteNodeNotDisjoint() {
        MultiPathRouter router = new MultiPathRouter();
        // 主路径 via nextHop=2
        router.addPrimaryRoute(5, route(5, 2, 1, 10.0, true));

        // 备用路径也 via nextHop=2 → 节点相交，不应添加
        RouteEntry backup = route(5, 2, 2, 20.0, false);
        boolean added = router.addBackupRoute(5, backup);
        assertThat(added).isFalse();
        assertThat(router.backupCount(5)).isZero();
    }

    @Test
    @DisplayName("addBackupRoute：超过上限时不添加")
    void addBackupRouteExceedsMax() {
        MultiPathRouter router = new MultiPathRouter();
        router.addPrimaryRoute(5, route(5, 2, 1, 10.0, true));

        // 添加 2 条备用路径（达到上限）
        router.addBackupRoute(5, route(5, 3, 2, 20.0, false));
        router.addBackupRoute(5, route(5, 4, 2, 25.0, false));
        assertThat(router.backupCount(5)).isEqualTo(MultiPathRouter.MAX_BACKUP_ROUTES);

        // 第三条备用路径不应添加
        boolean added = router.addBackupRoute(5, route(5, 5, 3, 30.0, false));
        assertThat(added).isFalse();
        assertThat(router.backupCount(5)).isEqualTo(MultiPathRouter.MAX_BACKUP_ROUTES);
    }

    @Test
    @DisplayName("switchToBackup：主路径断裂后无缝切换到备用路径")
    void switchToBackup() {
        MultiPathRouter router = new MultiPathRouter();
        router.addPrimaryRoute(5, route(5, 2, 1, 10.0, true));
        router.addBackupRoute(5, route(5, 3, 2, 20.0, false));

        // 主路径断裂 → 切换到备用
        RouteEntry newPrimary = router.switchToBackup(5);
        assertThat(newPrimary).isNotNull();
        assertThat(newPrimary.nextHop).isEqualTo(3);
        assertThat(newPrimary.isPrimary).isTrue();

        // getPrimaryRoute 返回新主路径
        RouteEntry primary = router.getPrimaryRoute(5);
        assertThat(primary.nextHop).isEqualTo(3);
    }

    @Test
    @DisplayName("switchToBackup：无备用路径时返回 null")
    void switchToBackupNoBackup() {
        MultiPathRouter router = new MultiPathRouter();
        router.addPrimaryRoute(5, route(5, 2, 1, 10.0, true));

        RouteEntry result = router.switchToBackup(5);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("switchToBackup：选择 metric 最小的备用路径")
    void switchToBackupSelectsBestMetric() {
        MultiPathRouter router = new MultiPathRouter();
        router.addPrimaryRoute(5, route(5, 2, 1, 10.0, true));
        router.addBackupRoute(5, route(5, 3, 2, 30.0, false));
        router.addBackupRoute(5, route(5, 4, 2, 15.0, false));

        // 切换：应选择 metric=15 的路径（via 4）
        RouteEntry newPrimary = router.switchToBackup(5);
        assertThat(newPrimary).isNotNull();
        assertThat(newPrimary.nextHop).isEqualTo(4);
        assertThat(newPrimary.metric).isEqualTo(15.0);
    }

    @Test
    @DisplayName("getBackupRoutes：返回所有备用路径")
    void getBackupRoutes() {
        MultiPathRouter router = new MultiPathRouter();
        router.addPrimaryRoute(5, route(5, 2, 1, 10.0, true));
        router.addBackupRoute(5, route(5, 3, 2, 20.0, false));
        router.addBackupRoute(5, route(5, 4, 2, 25.0, false));

        var backups = router.getBackupRoutes(5);
        assertThat(backups).hasSize(2);
    }

    @Test
    @DisplayName("removeTarget：移除指定目标的所有路径")
    void removeTarget() {
        MultiPathRouter router = new MultiPathRouter();
        router.addPrimaryRoute(5, route(5, 2, 1, 10.0, true));
        router.addBackupRoute(5, route(5, 3, 2, 20.0, false));

        router.removeTarget(5);
        assertThat(router.getPrimaryRoute(5)).isNull();
        assertThat(router.getBackupRoutes(5)).isEmpty();
    }

    @Test
    @DisplayName("removeByNextHop：移除所有以指定节点为下一跳的路径")
    void removeByNextHop() {
        MultiPathRouter router = new MultiPathRouter();
        router.addPrimaryRoute(5, route(5, 2, 1, 10.0, true));
        router.addPrimaryRoute(6, route(6, 2, 1, 10.0, true));
        router.addPrimaryRoute(7, route(7, 3, 1, 10.0, true));

        router.removeByNextHop(2);
        assertThat(router.getPrimaryRoute(5)).isNull();
        assertThat(router.getPrimaryRoute(6)).isNull();
        assertThat(router.getPrimaryRoute(7)).isNotNull();
    }

    @Test
    @DisplayName("addBackupRoute 无主路径时直接成为主路径")
    void addBackupNoPrimaryBecomesPrimary() {
        MultiPathRouter router = new MultiPathRouter();
        RouteEntry route = route(5, 2, 1, 10.0, false);
        boolean added = router.addBackupRoute(5, route);

        assertThat(added).isTrue();
        // 无主路径时备用直接成为主
        RouteEntry primary = router.getPrimaryRoute(5);
        assertThat(primary).isNotNull();
    }

    @Test
    @DisplayName("size：路径总数统计")
    void size() {
        MultiPathRouter router = new MultiPathRouter();
        router.addPrimaryRoute(5, route(5, 2, 1, 10.0, true));
        router.addBackupRoute(5, route(5, 3, 2, 20.0, false));
        router.addPrimaryRoute(6, route(6, 4, 1, 10.0, true));

        assertThat(router.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("clear：清空所有路径")
    void clear() {
        MultiPathRouter router = new MultiPathRouter();
        router.addPrimaryRoute(5, route(5, 2, 1, 10.0, true));
        router.addBackupRoute(5, route(5, 3, 2, 20.0, false));

        router.clear();
        assertThat(router.size()).isZero();
        assertThat(router.getPrimaryRoute(5)).isNull();
    }

    @Test
    @DisplayName("PathEntry isNodeDisjoint：中间节点不相交检查")
    void pathEntryNodeDisjoint() {
        Set<Integer> nodes1 = new HashSet<>();
        nodes1.add(2);
        nodes1.add(3);
        Set<Integer> nodes2 = new HashSet<>();
        nodes2.add(4);
        nodes2.add(5);

        MultiPathRouter.PathEntry pe1 = new MultiPathRouter.PathEntry(
                route(5, 2, 1, 10.0, true),
                MultiPathRouter.PathStatus.PRIMARY,
                nodes1);
        MultiPathRouter.PathEntry pe2 = new MultiPathRouter.PathEntry(
                route(5, 4, 2, 20.0, false),
                MultiPathRouter.PathStatus.BACKUP,
                nodes2);

        assertThat(pe1.isNodeDisjoint(pe2)).isTrue();

        // 添加相交节点
        Set<Integer> nodes3 = new HashSet<>();
        nodes3.add(3);
        nodes3.add(4);
        MultiPathRouter.PathEntry pe3 = new MultiPathRouter.PathEntry(
                route(5, 4, 2, 20.0, false),
                MultiPathRouter.PathStatus.BACKUP,
                nodes3);
        assertThat(pe1.isNodeDisjoint(pe3)).isFalse();
    }
}
package io.aerofleet.sim.mesh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DisasterZoneManager 灾区通信隔离单测（FR-04）。
 * <p>
 * 覆盖灾区创建/解散/合并/分裂、节点入区判定、区内通信隔离、
 * 跨区通信仅经卫星/HAPS中继、GeoBoundary边界判定。
 */
@DisplayName("DisasterZoneManager 灾区通信隔离 (FR-04)")
class DisasterZoneManagerTest {

    // ===== 辅助方法 =====

    /** 创建矩形地理边界。 */
    private static GeoBoundary rectangularBoundary(double minLat, double minLon,
                                                    double maxLat, double maxLon) {
        return new GeoBoundary(List.of(
                new GeoBoundary.GeoPoint(minLat, minLon),
                new GeoBoundary.GeoPoint(maxLat, minLon),
                new GeoBoundary.GeoPoint(maxLat, maxLon),
                new GeoBoundary.GeoPoint(minLat, maxLon)
        ));
    }

    /** 创建成员集合。 */
    private static Set<Integer> members(int... sysids) {
        Set<Integer> set = new HashSet<>();
        for (int s : sysids) {
            set.add(s);
        }
        return set;
    }

    // ===== GeoBoundary 测试 =====

    @Nested
    @DisplayName("GeoBoundary 地理边界")
    class GeoBoundaryTest {

        @Test
        @DisplayName("contains 点在矩形边界内返回 true")
        void containsInside() {
            GeoBoundary boundary = rectangularBoundary(30.0, 120.0, 31.0, 121.0);
            assertThat(boundary.contains(30.5, 120.5)).isTrue();
        }

        @Test
        @DisplayName("contains 点在矩形边界外返回 false")
        void containsOutside() {
            GeoBoundary boundary = rectangularBoundary(30.0, 120.0, 31.0, 121.0);
            assertThat(boundary.contains(32.0, 122.0)).isFalse();
        }

        @Test
        @DisplayName("contains 点在边界顶点上返回 true")
        void containsOnVertex() {
            GeoBoundary boundary = rectangularBoundary(30.0, 120.0, 31.0, 121.0);
            assertThat(boundary.contains(30.0, 120.0)).isTrue();
        }

        @Test
        @DisplayName("顶点数 < 3 抛出 IllegalArgumentException")
        void lessThan3VerticesThrows() {
            assertThatThrownBy(() -> new GeoBoundary(List.of(
                    new GeoBoundary.GeoPoint(30.0, 120.0),
                    new GeoBoundary.GeoPoint(31.0, 121.0)
            ))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ===== DisasterZone 数据模型测试 =====

    @Nested
    @DisplayName("DisasterZone 灾区数据模型")
    class DisasterZoneModelTest {

        @Test
        @DisplayName("灾区创建：zoneId/boundary/members/status 正确")
        void zoneCreation() {
            GeoBoundary boundary = rectangularBoundary(30.0, 120.0, 31.0, 121.0);
            DisasterZone zone = new DisasterZone(1, boundary, members(1, 2, 3),
                    1000L, DisasterZone.ZoneStatus.ACTIVE);

            assertThat(zone.zoneId).isEqualTo(1);
            assertThat(zone.boundary).isEqualTo(boundary);
            assertThat(zone.members).containsExactlyInAnyOrder(1, 2, 3);
            assertThat(zone.createdAtMs).isEqualTo(1000L);
            assertThat(zone.status).isEqualTo(DisasterZone.ZoneStatus.ACTIVE);
            assertThat(zone.isActive()).isTrue();
            assertThat(zone.size()).isEqualTo(3);
            assertThat(zone.contains(1)).isTrue();
            assertThat(zone.contains(99)).isFalse();
        }

        @Test
        @DisplayName("addMember 返回包含新成员的新实例")
        void addMember() {
            GeoBoundary boundary = rectangularBoundary(30.0, 120.0, 31.0, 121.0);
            DisasterZone zone = new DisasterZone(1, boundary, members(1, 2),
                    1000L, DisasterZone.ZoneStatus.ACTIVE);

            DisasterZone updated = zone.addMember(3);
            assertThat(updated.members).contains(3);
            assertThat(updated.size()).isEqualTo(3);
            // 原实例不变
            assertThat(zone.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("removeMember 返回不包含该成员的新实例")
        void removeMember() {
            GeoBoundary boundary = rectangularBoundary(30.0, 120.0, 31.0, 121.0);
            DisasterZone zone = new DisasterZone(1, boundary, members(1, 2, 3),
                    1000L, DisasterZone.ZoneStatus.ACTIVE);

            DisasterZone updated = zone.removeMember(2);
            assertThat(updated.members).doesNotContain(2);
            assertThat(updated.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("withStatus 返回新状态的新实例")
        void withStatus() {
            GeoBoundary boundary = rectangularBoundary(30.0, 120.0, 31.0, 121.0);
            DisasterZone zone = new DisasterZone(1, boundary, members(1, 2),
                    1000L, DisasterZone.ZoneStatus.ACTIVE);

            DisasterZone recovered = zone.withStatus(DisasterZone.ZoneStatus.RECOVERED);
            assertThat(recovered.status).isEqualTo(DisasterZone.ZoneStatus.RECOVERED);
            assertThat(recovered.isActive()).isFalse();
            assertThat(zone.isActive()).isTrue();
        }
    }

    // ===== CrossZoneRelayPolicy 测试 =====

    @Nested
    @DisplayName("CrossZoneRelayPolicy 跨区中继策略")
    class CrossZoneRelayPolicyTest {

        @Test
        @DisplayName("默认策略：SATELLITE 和 HAPS 允许跨区转发")
        void defaultPolicyAllowsSatelliteAndHaps() {
            CrossZoneRelayPolicy policy = new CrossZoneRelayPolicy();
            assertThat(policy.isAllowed(CrossZoneRelayPolicy.RelayType.SATELLITE)).isTrue();
            assertThat(policy.isAllowed(CrossZoneRelayPolicy.RelayType.HAPS)).isTrue();
        }

        @Test
        @DisplayName("默认策略：WiFi/LoRa/LTE 拒绝跨区转发")
        void defaultPolicyRejectsGroundRelays() {
            CrossZoneRelayPolicy policy = new CrossZoneRelayPolicy();
            assertThat(policy.isRejected(CrossZoneRelayPolicy.RelayType.WIFI)).isTrue();
            assertThat(policy.isRejected(CrossZoneRelayPolicy.RelayType.LORA)).isTrue();
            assertThat(policy.isRejected(CrossZoneRelayPolicy.RelayType.LTE)).isTrue();
        }

        @Test
        @DisplayName("自定义策略：可指定允许的中继类型")
        void customPolicy() {
            CrossZoneRelayPolicy policy = new CrossZoneRelayPolicy(
                    Set.of(CrossZoneRelayPolicy.RelayType.SATELLITE));
            assertThat(policy.isAllowed(CrossZoneRelayPolicy.RelayType.SATELLITE)).isTrue();
            assertThat(policy.isAllowed(CrossZoneRelayPolicy.RelayType.HAPS)).isFalse();
        }
    }

    // ===== DisasterZoneManager 灾区管理测试 =====

    @Nested
    @DisplayName("DisasterZoneManager 灾区管理")
    class ZoneManagementTest {

        @Test
        @DisplayName("createZone 创建灾区并自动启用通信隔离")
        void createZone() {
            DisasterZoneManager manager = new DisasterZoneManager();
            GeoBoundary boundary = rectangularBoundary(30.0, 120.0, 31.0, 121.0);
            DisasterZone zone = manager.createZone(boundary, members(1, 2, 3));

            assertThat(zone.zoneId).isEqualTo(1);
            assertThat(zone.status).isEqualTo(DisasterZone.ZoneStatus.ACTIVE);
            assertThat(manager.isIsolationEnabled()).isTrue();
            assertThat(manager.zoneCount()).isEqualTo(1);
            assertThat(manager.activeZoneCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("createZone 多个灾区各自独立运行")
        void createMultipleZones() {
            DisasterZoneManager manager = new DisasterZoneManager();
            GeoBoundary boundary1 = rectangularBoundary(30.0, 120.0, 31.0, 121.0);
            GeoBoundary boundary2 = rectangularBoundary(35.0, 125.0, 36.0, 126.0);

            DisasterZone zone1 = manager.createZone(boundary1, members(1, 2, 3));
            DisasterZone zone2 = manager.createZone(boundary2, members(4, 5, 6));

            assertThat(zone1.zoneId).isEqualTo(1);
            assertThat(zone2.zoneId).isEqualTo(2);
            assertThat(manager.zoneCount()).isEqualTo(2);
            assertThat(manager.activeZoneCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("dissolveZone 解散灾区，状态置为 RECOVERED")
        void dissolveZone() {
            DisasterZoneManager manager = new DisasterZoneManager();
            GeoBoundary boundary = rectangularBoundary(30.0, 120.0, 31.0, 121.0);
            manager.createZone(boundary, members(1, 2, 3));

            boolean result = manager.dissolveZone(1);
            assertThat(result).isTrue();
            assertThat(manager.getZone(1).status).isEqualTo(DisasterZone.ZoneStatus.RECOVERED);
            assertThat(manager.activeZoneCount()).isEqualTo(0);
            // 节点映射已清除
            assertThat(manager.getZoneOfNode(1)).isEqualTo(-1);
        }

        @Test
        @DisplayName("dissolveZone 不存在的灾区返回 false")
        void dissolveNonExistentZone() {
            DisasterZoneManager manager = new DisasterZoneManager();
            assertThat(manager.dissolveZone(99)).isFalse();
        }
    }

    // ===== 节点入区判定测试 =====

    @Nested
    @DisplayName("节点入区判定")
    class NodeZoneAssignmentTest {

        @Test
        @DisplayName("findZoneByLocation 根据经纬度找到对应灾区")
        void findZoneByLocation() {
            DisasterZoneManager manager = new DisasterZoneManager();
            GeoBoundary boundary1 = rectangularBoundary(30.0, 120.0, 31.0, 121.0);
            GeoBoundary boundary2 = rectangularBoundary(35.0, 125.0, 36.0, 126.0);
            manager.createZone(boundary1, members(1, 2));
            manager.createZone(boundary2, members(3, 4));

            assertThat(manager.findZoneByLocation(30.5, 120.5)).isEqualTo(1);
            assertThat(manager.findZoneByLocation(35.5, 125.5)).isEqualTo(2);
            assertThat(manager.findZoneByLocation(40.0, 130.0)).isEqualTo(-1);
        }

        @Test
        @DisplayName("getZoneOfNode 查询节点所属灾区")
        void getZoneOfNode() {
            DisasterZoneManager manager = new DisasterZoneManager();
            GeoBoundary boundary = rectangularBoundary(30.0, 120.0, 31.0, 121.0);
            manager.createZone(boundary, members(1, 2, 3));

            assertThat(manager.getZoneOfNode(1)).isEqualTo(1);
            assertThat(manager.getZoneOfNode(2)).isEqualTo(1);
            assertThat(manager.getZoneOfNode(99)).isEqualTo(-1);
        }

        @Test
        @DisplayName("addNodeToZone 将节点加入指定灾区")
        void addNodeToZone() {
            DisasterZoneManager manager = new DisasterZoneManager();
            GeoBoundary boundary = rectangularBoundary(30.0, 120.0, 31.0, 121.0);
            manager.createZone(boundary, members(1, 2));

            boolean result = manager.addNodeToZone(3, 1);
            assertThat(result).isTrue();
            assertThat(manager.getZoneOfNode(3)).isEqualTo(1);
            assertThat(manager.getZoneMembers(1)).contains(3);
        }

        @Test
        @DisplayName("addNodeToZone 节点从其他灾区转移")
        void addNodeToZoneTransfer() {
            DisasterZoneManager manager = new DisasterZoneManager();
            GeoBoundary boundary1 = rectangularBoundary(30.0, 120.0, 31.0, 121.0);
            GeoBoundary boundary2 = rectangularBoundary(35.0, 125.0, 36.0, 126.0);
            manager.createZone(boundary1, members(1, 2));
            manager.createZone(boundary2, members(3, 4));

            // 将节点1从灾区1转移到灾区2
            manager.addNodeToZone(1, 2);
            assertThat(manager.getZoneOfNode(1)).isEqualTo(2);
            assertThat(manager.getZoneMembers(1)).doesNotContain(1);
            assertThat(manager.getZoneMembers(2)).contains(1);
        }

        @Test
        @DisplayName("removeNodeFromZone 将节点从灾区移除")
        void removeNodeFromZone() {
            DisasterZoneManager manager = new DisasterZoneManager();
            GeoBoundary boundary = rectangularBoundary(30.0, 120.0, 31.0, 121.0);
            manager.createZone(boundary, members(1, 2, 3));

            boolean result = manager.removeNodeFromZone(2);
            assertThat(result).isTrue();
            assertThat(manager.getZoneOfNode(2)).isEqualTo(-1);
            assertThat(manager.getZoneMembers(1)).doesNotContain(2);
        }
    }

    // ===== 通信隔离判定测试 =====

    @Nested
    @DisplayName("通信隔离判定")
    class CommunicationIsolationTest {

        @Test
        @DisplayName("isSameZone 同一灾区内节点返回 true")
        void isSameZone() {
            DisasterZoneManager manager = new DisasterZoneManager();
            GeoBoundary boundary = rectangularBoundary(30.0, 120.0, 31.0, 121.0);
            manager.createZone(boundary, members(1, 2, 3));

            assertThat(manager.isSameZone(1, 2)).isTrue();
            assertThat(manager.isSameZone(1, 3)).isTrue();
        }

        @Test
        @DisplayName("isSameZone 不同灾区节点返回 false")
        void isSameZoneDifferentZones() {
            DisasterZoneManager manager = new DisasterZoneManager();
            GeoBoundary boundary1 = rectangularBoundary(30.0, 120.0, 31.0, 121.0);
            GeoBoundary boundary2 = rectangularBoundary(35.0, 125.0, 36.0, 126.0);
            manager.createZone(boundary1, members(1, 2));
            manager.createZone(boundary2, members(3, 4));

            assertThat(manager.isSameZone(1, 3)).isFalse();
            assertThat(manager.isSameZone(2, 4)).isFalse();
        }

        @Test
        @DisplayName("isCrossZoneCommunication 跨灾区通信返回 true")
        void isCrossZoneCommunication() {
            DisasterZoneManager manager = new DisasterZoneManager();
            GeoBoundary boundary1 = rectangularBoundary(30.0, 120.0, 31.0, 121.0);
            GeoBoundary boundary2 = rectangularBoundary(35.0, 125.0, 36.0, 126.0);
            manager.createZone(boundary1, members(1, 2));
            manager.createZone(boundary2, members(3, 4));

            assertThat(manager.isCrossZoneCommunication(1, 3)).isTrue();
            assertThat(manager.isCrossZoneCommunication(1, 2)).isFalse();
        }

        @Test
        @DisplayName("isCrossZoneRelayAllowed 卫星/HAPS 允许跨区转发")
        void crossZoneRelayAllowed() {
            DisasterZoneManager manager = new DisasterZoneManager();
            assertThat(manager.isCrossZoneRelayAllowed(CrossZoneRelayPolicy.RelayType.SATELLITE)).isTrue();
            assertThat(manager.isCrossZoneRelayAllowed(CrossZoneRelayPolicy.RelayType.HAPS)).isTrue();
        }

        @Test
        @DisplayName("isCrossZoneRelayRejected WiFi/LoRa/LTE 拒绝跨区转发")
        void crossZoneRelayRejected() {
            DisasterZoneManager manager = new DisasterZoneManager();
            assertThat(manager.isCrossZoneRelayRejected(CrossZoneRelayPolicy.RelayType.WIFI)).isTrue();
            assertThat(manager.isCrossZoneRelayRejected(CrossZoneRelayPolicy.RelayType.LORA)).isTrue();
            assertThat(manager.isCrossZoneRelayRejected(CrossZoneRelayPolicy.RelayType.LTE)).isTrue();
        }
    }

    // ===== 灾区合并/分裂测试 =====

    @Nested
    @DisplayName("灾区合并与分裂")
    class ZoneMergeSplitTest {

        @Test
        @DisplayName("mergeZones 两个灾区合并为一个")
        void mergeZones() {
            DisasterZoneManager manager = new DisasterZoneManager();
            GeoBoundary boundary1 = rectangularBoundary(30.0, 120.0, 31.0, 121.0);
            GeoBoundary boundary2 = rectangularBoundary(35.0, 125.0, 36.0, 126.0);
            manager.createZone(boundary1, members(1, 2));
            manager.createZone(boundary2, members(3, 4));

            DisasterZone merged = manager.mergeZones(1, 2);
            assertThat(merged).isNotNull();
            assertThat(merged.zoneId).isEqualTo(2);
            assertThat(merged.members).containsExactlyInAnyOrder(1, 2, 3, 4);
            // 源灾区状态为 MERGED
            assertThat(manager.getZone(1).status).isEqualTo(DisasterZone.ZoneStatus.MERGED);
            // 节点映射已更新
            assertThat(manager.getZoneOfNode(1)).isEqualTo(2);
            assertThat(manager.getZoneOfNode(3)).isEqualTo(2);
        }

        @Test
        @DisplayName("mergeZones 任一灾区不存在返回 null")
        void mergeZonesNonExistent() {
            DisasterZoneManager manager = new DisasterZoneManager();
            GeoBoundary boundary = rectangularBoundary(30.0, 120.0, 31.0, 121.0);
            manager.createZone(boundary, members(1, 2));

            assertThat(manager.mergeZones(1, 99)).isNull();
            assertThat(manager.mergeZones(99, 1)).isNull();
        }

        @Test
        @DisplayName("splitZone 灾区分裂为两个")
        void splitZone() {
            DisasterZoneManager manager = new DisasterZoneManager();
            GeoBoundary boundary = rectangularBoundary(30.0, 120.0, 31.0, 121.0);
            manager.createZone(boundary, members(1, 2, 3, 4));

            GeoBoundary newBoundary = rectangularBoundary(32.0, 122.0, 33.0, 123.0);
            DisasterZone newZone = manager.splitZone(1, members(3, 4), newBoundary);

            assertThat(newZone).isNotNull();
            assertThat(newZone.zoneId).isEqualTo(2);
            assertThat(newZone.members).containsExactlyInAnyOrder(3, 4);
            assertThat(newZone.status).isEqualTo(DisasterZone.ZoneStatus.ACTIVE);
            // 原灾区保留剩余成员
            assertThat(manager.getZoneMembers(1)).containsExactlyInAnyOrder(1, 2);
            // 节点映射已更新
            assertThat(manager.getZoneOfNode(3)).isEqualTo(2);
            assertThat(manager.getZoneOfNode(1)).isEqualTo(1);
        }

        @Test
        @DisplayName("splitZone 不存在的灾区返回 null")
        void splitZoneNonExistent() {
            DisasterZoneManager manager = new DisasterZoneManager();
            GeoBoundary newBoundary = rectangularBoundary(32.0, 122.0, 33.0, 123.0);
            assertThat(manager.splitZone(99, members(1, 2), newBoundary)).isNull();
        }
    }

    // ===== 端到端验收场景测试 =====

    @Test
    @DisplayName("验收场景：两个独立灾区 mesh 网络 → 区内通信自主，跨区通信经卫星/HAPS")
    void acceptanceScenario() {
        DisasterZoneManager manager = new DisasterZoneManager();

        // 灾区A：30-31°N, 120-121°E，成员节点 1,2,3
        GeoBoundary boundaryA = rectangularBoundary(30.0, 120.0, 31.0, 121.0);
        DisasterZone zoneA = manager.createZone(boundaryA, members(1, 2, 3));

        // 灾区B：35-36°N, 125-126°E，成员节点 4,5,6
        GeoBoundary boundaryB = rectangularBoundary(35.0, 125.0, 36.0, 126.0);
        DisasterZone zoneB = manager.createZone(boundaryB, members(4, 5, 6));

        // 验收条件1：区内通信自主
        // 灾区A内节点1→2 通信，同一灾区内，无需跨区
        assertThat(manager.isSameZone(1, 2)).isTrue();
        assertThat(manager.isCrossZoneCommunication(1, 2)).isFalse();

        // 灾区B内节点4→5 通信，同一灾区内
        assertThat(manager.isSameZone(4, 5)).isTrue();
        assertThat(manager.isCrossZoneCommunication(4, 5)).isFalse();

        // 验收条件2：跨区通信经卫星/HAPS
        // 灾区A节点1 → 灾区B节点4，跨灾区通信
        assertThat(manager.isCrossZoneCommunication(1, 4)).isTrue();

        // 跨区通信仅允许 SATELLITE / HAPS
        assertThat(manager.isCrossZoneRelayAllowed(CrossZoneRelayPolicy.RelayType.SATELLITE)).isTrue();
        assertThat(manager.isCrossZoneRelayAllowed(CrossZoneRelayPolicy.RelayType.HAPS)).isTrue();
        // 拒绝 WiFi / LoRa / LTE 跨区转发
        assertThat(manager.isCrossZoneRelayRejected(CrossZoneRelayPolicy.RelayType.WIFI)).isTrue();
        assertThat(manager.isCrossZoneRelayRejected(CrossZoneRelayPolicy.RelayType.LORA)).isTrue();
        assertThat(manager.isCrossZoneRelayRejected(CrossZoneRelayPolicy.RelayType.LTE)).isTrue();
    }
}
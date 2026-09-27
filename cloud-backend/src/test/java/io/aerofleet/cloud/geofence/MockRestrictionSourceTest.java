package io.aerofleet.cloud.geofence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MockRestrictionSource} 模拟限飞区数据源测试。
 * <p>
 * 验证 fetch() 返回预设限飞区列表，且所有限飞区 fenceType 恒为 KEEP_OUT。
 * <p>
 * 直接实例化（无 Spring 上下文），用 AssertJ 断言。
 */
@DisplayName("MockRestrictionSource 模拟限飞区数据源")
class MockRestrictionSourceTest {

    @Test
    @DisplayName("testGetSourceId: sourceId 为 'mock'")
    void testGetSourceId() {
        MockRestrictionSource source = new MockRestrictionSource();

        assertThat(source.getSourceId()).isEqualTo("mock");
    }

    @Test
    @DisplayName("testFetchReturnsPresetZones: fetch() 返回预设限飞区列表")
    void testFetchReturnsPresetZones() throws Exception {
        MockRestrictionSource source = new MockRestrictionSource();

        List<RestrictionZone> zones = source.fetch();

        // 应返回 3 个预设限飞区
        assertThat(zones).hasSize(3);

        // 验证所有限飞区的 fenceType 恒为 KEEP_OUT
        for (RestrictionZone zone : zones) {
            assertThat(zone.getFenceType()).isEqualTo(FenceType.KEEP_OUT);
            assertThat(zone.getSource()).isEqualTo("mock");
        }
    }

    @Test
    @DisplayName("testFetchContainsBeijingCircle: 包含北京首都机场圆形禁飞区")
    void testFetchContainsBeijingCircle() throws Exception {
        MockRestrictionSource source = new MockRestrictionSource();

        List<RestrictionZone> zones = source.fetch();

        RestrictionZone beijing = zones.stream()
                .filter(z -> "BJ-PEK-001".equals(z.getZoneId()))
                .findFirst()
                .orElse(null);

        assertThat(beijing).isNotNull();
        assertThat(beijing.getName()).isEqualTo("北京首都机场禁飞区");
        assertThat(beijing.getType()).isEqualTo(GeofenceZone.Type.CIRCLE);
        assertThat(beijing.getCenterLat()).isCloseTo(40.0801, org.assertj.core.api.Assertions.within(0.0001));
        assertThat(beijing.getCenterLon()).isCloseTo(116.5846, org.assertj.core.api.Assertions.within(0.0001));
        assertThat(beijing.getRadiusM()).isEqualTo(5000.0);
    }

    @Test
    @DisplayName("testFetchContainsShanghaiCircle: 包含上海虹桥机场圆形禁飞区")
    void testFetchContainsShanghaiCircle() throws Exception {
        MockRestrictionSource source = new MockRestrictionSource();

        List<RestrictionZone> zones = source.fetch();

        RestrictionZone shanghai = zones.stream()
                .filter(z -> "SH-SHA-001".equals(z.getZoneId()))
                .findFirst()
                .orElse(null);

        assertThat(shanghai).isNotNull();
        assertThat(shanghai.getName()).isEqualTo("上海虹桥机场禁飞区");
        assertThat(shanghai.getType()).isEqualTo(GeofenceZone.Type.CIRCLE);
        assertThat(shanghai.getRadiusM()).isEqualTo(3000.0);
    }

    @Test
    @DisplayName("testFetchContainsShenzhenPolygon: 包含深圳城区多边形禁飞区")
    void testFetchContainsShenzhenPolygon() throws Exception {
        MockRestrictionSource source = new MockRestrictionSource();

        List<RestrictionZone> zones = source.fetch();

        RestrictionZone shenzhen = zones.stream()
                .filter(z -> "SZ-CITY-001".equals(z.getZoneId()))
                .findFirst()
                .orElse(null);

        assertThat(shenzhen).isNotNull();
        assertThat(shenzhen.getName()).isEqualTo("深圳城区禁飞区");
        assertThat(shenzhen.getType()).isEqualTo(GeofenceZone.Type.POLYGON);
        assertThat(shenzhen.getPoints()).hasSize(4);
    }

    @Test
    @DisplayName("testFetchReturnsNewListEachCall: 每次 fetch() 返回新列表实例")
    void testFetchReturnsNewListEachCall() throws Exception {
        MockRestrictionSource source = new MockRestrictionSource();

        List<RestrictionZone> first = source.fetch();
        List<RestrictionZone> second = source.fetch();

        assertThat(first).isNotSameAs(second);
        assertThat(first).hasSameSizeAs(second);
    }
}
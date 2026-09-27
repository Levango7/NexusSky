package io.aerofleet.cloud.geofence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GeofenceZone} 围栏语义扩展测试：验证 fenceType、proximityBufferM 默认值与显式赋值。
 * <p>
 * 直接实例化（无 Spring 上下文），用 AssertJ 断言。
 * 风格与 {@link GeofenceMonitorTest} 一致。
 */
@DisplayName("GeofenceZone 围栏语义扩展")
class GeofenceZoneTest {

    // ------------------------------------------------------------------
    // fenceType 默认值与显式赋值
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testFenceTypeDefaultKeepIn: 默认围栏类型为 KEEP_IN")
    void testFenceTypeDefaultKeepIn() {
        // 使用不带 fenceType 参数的 circleZone 工厂方法
        GeofenceZone zone = GeofenceZone.circleZone(1, "default-fence",
                22.5, 113.9, 500.0, GeofenceZone.Action.WARN);

        assertThat(zone.getFenceType()).isEqualTo(FenceType.KEEP_IN);
    }

    @Test
    @DisplayName("testFenceTypeExplicitKeepOut: 显式指定 KEEP_OUT 围栏类型")
    void testFenceTypeExplicitKeepOut() {
        GeofenceZone zone = GeofenceZone.circleZone(2, "keepout-fence",
                22.5, 113.9, 500.0, GeofenceZone.Action.WARN,
                FenceType.KEEP_OUT, 100);

        assertThat(zone.getFenceType()).isEqualTo(FenceType.KEEP_OUT);
    }

    // ------------------------------------------------------------------
    // proximityBufferM 默认值
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testProximityBufferDefault: proximityBufferM 默认值为 100")
    void testProximityBufferDefault() {
        // 圆形围栏默认 proximityBufferM
        GeofenceZone circle = GeofenceZone.circleZone(3, "circle-default",
                22.5, 113.9, 500.0, GeofenceZone.Action.WARN);
        assertThat(circle.getProximityBufferM()).isEqualTo(100);

        // 多边形围栏默认 proximityBufferM
        List<GeofenceZone.GeoPoint> pts = List.of(
                new GeofenceZone.GeoPoint(22.0, 113.0),
                new GeofenceZone.GeoPoint(22.0, 114.0),
                new GeofenceZone.GeoPoint(23.0, 114.0));
        GeofenceZone polygon = GeofenceZone.polygonZone(4, "polygon-default",
                pts, GeofenceZone.Action.WARN);
        assertThat(polygon.getProximityBufferM()).isEqualTo(100);
    }

    // ------------------------------------------------------------------
    // withEnabled 传递新字段
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testWithEnabledPreservesFields: withEnabled 保留所有原有字段")
    void testWithEnabledPreservesFields() {
        GeofenceZone original = GeofenceZone.circleZone(5, "keepout-zone",
                22.5, 113.9, 500.0, GeofenceZone.Action.LOCK_RTH,
                FenceType.KEEP_OUT, 200);

        // 原始围栏 enabled=true（默认）
        assertThat(original.isEnabled()).isTrue();

        // 禁用围栏
        GeofenceZone disabled = original.withEnabled(false);

        // enabled 状态已切换
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(original.isEnabled()).isTrue(); // 不可变性：原对象不变

        // 所有其他字段保持不变
        assertThat(disabled.getId()).isEqualTo(original.getId());
        assertThat(disabled.getName()).isEqualTo(original.getName());
        assertThat(disabled.getType()).isEqualTo(original.getType());
        assertThat(disabled.getCenterLat()).isEqualTo(original.getCenterLat());
        assertThat(disabled.getCenterLon()).isEqualTo(original.getCenterLon());
        assertThat(disabled.getRadiusM()).isEqualTo(original.getRadiusM());
        assertThat(disabled.getAction()).isEqualTo(original.getAction());
        assertThat(disabled.getFenceType()).isEqualTo(original.getFenceType());
        assertThat(disabled.getProximityBufferM()).isEqualTo(original.getProximityBufferM());
        assertThat(disabled.getCreatedAtMs()).isEqualTo(original.getCreatedAtMs());
    }

    @Test
    @DisplayName("testWithEnabledReenable: withEnabled 从禁用恢复为启用")
    void testWithEnabledReenable() {
        GeofenceZone zone = GeofenceZone.circleZone(6, "toggle-zone",
                22.5, 113.9, 500.0, GeofenceZone.Action.WARN,
                FenceType.KEEP_OUT, 150);

        GeofenceZone disabled = zone.withEnabled(false);
        assertThat(disabled.isEnabled()).isFalse();

        GeofenceZone reenabled = disabled.withEnabled(true);
        assertThat(reenabled.isEnabled()).isTrue();
        assertThat(reenabled.getFenceType()).isEqualTo(FenceType.KEEP_OUT);
        assertThat(reenabled.getProximityBufferM()).isEqualTo(150);
    }
}
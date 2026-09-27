package io.aerofleet.cloud.geofence;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GeofenceMonitor} KEEP_OUT 围栏语义与接近缓冲区告警测试。
 * <p>
 * 测试覆盖：
 * <ul>
 *   <li>KEEP_OUT 围栏的 ENTER/EXIT 事件（语义反转）</li>
 *   <li>KEEP_OUT 围栏首次检查的行为</li>
 *   <li>接近缓冲区 PROXIMITY/CLEAR 事件</li>
 *   <li>KEEP_IN 围栏回归验证（行为与升级前一致）</li>
 * </ul>
 * <p>
 * 直接实例化（无 Spring 上下文），用 AssertJ 断言。
 * 风格与 {@link GeofenceMonitorTest} 一致。
 */
@DisplayName("GeofenceMonitor KEEP_OUT 围栏语义与接近告警")
class GeofenceMonitorKeepOutTest {

    private GeofenceStore store;
    private DeviceRegistry registry;
    private GeofenceMonitor monitor;

    /** 1° 纬度 ≈ 111319.9m（WGS84），用于构造精确距离的测试点。 */
    private static final double METERS_PER_DEG_LAT = 111_319.9;

    @BeforeEach
    void setUp() {
        store = new GeofenceStore();
        registry = new DeviceRegistry();
        monitor = new GeofenceMonitor(store, registry);
    }

    /** KEEP_OUT 圆形围栏（中心 22.5,113.9；半径 500m；接近缓冲区 100m）。 */
    private GeofenceZone keepOutCircle() {
        return GeofenceZone.circleZone(10, "keepout-circle", 22.5, 113.9, 500.0,
                GeofenceZone.Action.WARN, FenceType.KEEP_OUT, 100);
    }

    /** KEEP_IN 圆形围栏（中心 22.5,113.9；半径 500m），用于回归验证。 */
    private GeofenceZone keepInCircle() {
        return GeofenceZone.circleZone(11, "keepin-circle", 22.5, 113.9, 500.0,
                GeofenceZone.Action.WARN);
    }

    // ------------------------------------------------------------------
    // KEEP_OUT 围栏 ENTER/EXIT 事件
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testKeepOutEnterFromOutside: keep-out 围栏从外进入 → ENTER 事件")
    void testKeepOutEnterFromOutside() {
        store.addZone(keepOutCircle());

        // 第一次：无人机在围栏外（距中心约 1km），首次在围栏外 → 无事件
        double outsideLat = 22.5 + 1000.0 / METERS_PER_DEG_LAT;
        List<GeofenceBreachEvent> firstCheck = monitor.checkPosition(1, outsideLat, 113.9);
        assertThat(firstCheck).isEmpty();

        // 第二次：无人机进入围栏内（中心点）→ outside→inside → ENTER 事件
        List<GeofenceBreachEvent> events = monitor.checkPosition(1, 22.5, 113.9);

        assertThat(events).hasSize(1);
        GeofenceBreachEvent ev = events.get(0);
        assertThat(ev.getBreachType()).isEqualTo(GeofenceBreachEvent.BreachType.ENTER);
        assertThat(ev.getSysid()).isEqualTo(1);
        assertThat(ev.getZoneId()).isEqualTo(10);
        assertThat(store.getBreachHistory()).hasSize(1);
    }

    @Test
    @DisplayName("testKeepOutExitFromInside: keep-out 围栏从内离开 → EXIT 事件")
    void testKeepOutExitFromInside() {
        store.addZone(keepOutCircle());

        // 第一次：无人机在围栏内（中心点），首次在围栏内 → ENTER 事件
        List<GeofenceBreachEvent> firstCheck = monitor.checkPosition(1, 22.5, 113.9);
        assertThat(firstCheck).hasSize(1);
        assertThat(firstCheck.get(0).getBreachType()).isEqualTo(GeofenceBreachEvent.BreachType.ENTER);

        // 第二次：无人机离开围栏（距中心约 1km）→ inside→outside → EXIT 事件
        double outsideLat = 22.5 + 1000.0 / METERS_PER_DEG_LAT;
        List<GeofenceBreachEvent> events = monitor.checkPosition(1, outsideLat, 113.9);

        assertThat(events).hasSize(1);
        GeofenceBreachEvent ev = events.get(0);
        assertThat(ev.getBreachType()).isEqualTo(GeofenceBreachEvent.BreachType.EXIT);
        assertThat(ev.getSysid()).isEqualTo(1);
        assertThat(ev.getZoneId()).isEqualTo(10);
    }

    @Test
    @DisplayName("testKeepOutFirstInside: keep-out 围栏首次在围栏内 → ENTER 事件")
    void testKeepOutFirstInside() {
        store.addZone(keepOutCircle());

        // 首次检查：无人机在围栏内 → ENTER 事件
        List<GeofenceBreachEvent> events = monitor.checkPosition(1, 22.5, 113.9);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getBreachType()).isEqualTo(GeofenceBreachEvent.BreachType.ENTER);
        assertThat(events.get(0).getZoneId()).isEqualTo(10);
    }

    @Test
    @DisplayName("testKeepOutFirstOutside: keep-out 围栏首次在围栏外 → 无事件")
    void testKeepOutFirstOutside() {
        store.addZone(keepOutCircle());

        // 首次检查：无人机在围栏外（距中心约 1km，远超接近缓冲区）→ 无事件
        double outsideLat = 22.5 + 1000.0 / METERS_PER_DEG_LAT;
        List<GeofenceBreachEvent> events = monitor.checkPosition(1, outsideLat, 113.9);

        assertThat(events).isEmpty();
        assertThat(store.getBreachHistory()).isEmpty();
    }

    // ------------------------------------------------------------------
    // KEEP_IN 围栏回归验证
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testKeepInRegression: keep-in 围栏行为与升级前一致")
    void testKeepInRegression() {
        store.addZone(keepInCircle());

        // 首次在围栏内 → 无事件（KEEP_IN 语义：在围栏内是正常状态）
        List<GeofenceBreachEvent> inside = monitor.checkPosition(1, 22.5, 113.9);
        assertThat(inside).isEmpty();

        // 从围栏内到围栏外 → EXIT 事件（越界告警）
        double outsideLat = 22.5 + 1000.0 / METERS_PER_DEG_LAT;
        List<GeofenceBreachEvent> exitEvents = monitor.checkPosition(1, outsideLat, 113.9);
        assertThat(exitEvents).hasSize(1);
        assertThat(exitEvents.get(0).getBreachType()).isEqualTo(GeofenceBreachEvent.BreachType.EXIT);

        // 从围栏外回到围栏内 → ENTER 事件（恢复通知）
        List<GeofenceBreachEvent> enterEvents = monitor.checkPosition(1, 22.5, 113.9);
        assertThat(enterEvents).hasSize(1);
        assertThat(enterEvents.get(0).getBreachType()).isEqualTo(GeofenceBreachEvent.BreachType.ENTER);
    }

    // ------------------------------------------------------------------
    // 接近缓冲区告警
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testProximityEnter: 距边界 < proximityBufferM → PROXIMITY 事件")
    void testProximityEnter() {
        store.addZone(keepOutCircle());

        // 首次在围栏外、远离边界（距中心约 1km）→ 无事件
        double farLat = 22.5 + 1000.0 / METERS_PER_DEG_LAT;
        monitor.checkPosition(1, farLat, 113.9);

        // 移动到接近缓冲区内（距中心 550m，边界外 50m < 100m proximityBufferM）
        double proximityLat = 22.5 + 550.0 / METERS_PER_DEG_LAT;
        List<GeofenceBreachEvent> events = monitor.checkPosition(1, proximityLat, 113.9);

        // 应生成 PROXIMITY 事件
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getBreachType()).isEqualTo(GeofenceBreachEvent.BreachType.PROXIMITY);
        assertThat(events.get(0).getZoneId()).isEqualTo(10);
    }

    @Test
    @DisplayName("testProximityNoEvent: 距边界 ≥ proximityBufferM → 无 PROXIMITY 事件")
    void testProximityNoEvent() {
        store.addZone(keepOutCircle());

        // 首次在围栏外、远离边界（距中心约 1km，边界外 500m >> 100m proximityBufferM）
        double farLat = 22.5 + 1000.0 / METERS_PER_DEG_LAT;
        List<GeofenceBreachEvent> events = monitor.checkPosition(1, farLat, 113.9);

        // 不应生成任何事件（首次在围栏外 + 不在接近缓冲区内）
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("testProximityClear: 从接近区离开 → CLEAR 事件")
    void testProximityClear() {
        store.addZone(keepOutCircle());

        // 先进入接近缓冲区（距中心 550m，边界外 50m < 100m）
        double proximityLat = 22.5 + 550.0 / METERS_PER_DEG_LAT;
        monitor.checkPosition(1, proximityLat, 113.9);

        // 远离到接近缓冲区外（距中心 700m，边界外 200m > 100m）
        double farLat = 22.5 + 700.0 / METERS_PER_DEG_LAT;
        List<GeofenceBreachEvent> events = monitor.checkPosition(1, farLat, 113.9);

        // 应生成 CLEAR 事件
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getBreachType()).isEqualTo(GeofenceBreachEvent.BreachType.CLEAR);
        assertThat(events.get(0).getZoneId()).isEqualTo(10);
    }

    @Test
    @DisplayName("testKeepInNoProximity: keep-in 围栏不触发 PROXIMITY 事件")
    void testKeepInNoProximity() {
        store.addZone(keepInCircle());

        // 在 KEEP_IN 围栏外、接近边界（距中心 550m）
        double proximityLat = 22.5 + 550.0 / METERS_PER_DEG_LAT;

        // 首次在围栏外 → EXIT 事件（KEEP_IN 语义），但不应有 PROXIMITY 事件
        List<GeofenceBreachEvent> events = monitor.checkPosition(1, proximityLat, 113.9);

        // KEEP_IN 围栏首次在围栏外 → EXIT 事件（1条），无 PROXIMITY 事件
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getBreachType()).isEqualTo(GeofenceBreachEvent.BreachType.EXIT);

        // 继续在围栏外接近边界 → 不应生成 PROXIMITY 事件
        List<GeofenceBreachEvent> events2 = monitor.checkPosition(1, proximityLat, 113.9);
        assertThat(events2).isEmpty(); // 状态未变，无新事件
    }
}
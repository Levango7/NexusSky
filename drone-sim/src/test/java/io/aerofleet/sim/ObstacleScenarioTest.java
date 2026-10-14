package io.aerofleet.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code obstacle:} 场景与 {@link UltrasonicSource#measureFromBearing} 的单位测试。
 *
 * <p>端到端那条在 {@link ObstacleSourceWiringTest}；这里钉的是两半各自的语义，
 * 便于把 e2e 失败时的问题定位到"场景没解析对"还是"传感器几何算错"。
 */
@DisplayName("obstacle 场景与超声波方位几何")
class ObstacleScenarioTest {

    // ===== ScenarioController：解析与时间线 =====

    @Test
    @DisplayName("obstacle 场景解析出距离与方位")
    void obstacleScenarioParses() {
        ScenarioController sc = new ScenarioController("obstacle:20:30:1.5:90");

        assertThat(sc.enabled()).isTrue();
        assertThat(sc.obstacleRangeM(25)).isEqualTo(1.5);
        assertThat(sc.obstacleBearingDeg(25)).isEqualTo(90.0);
    }

    @Test
    @DisplayName("obstacle 事件只在 [start, start+duration) 内生效")
    void obstacleRespectsTimeline() {
        ScenarioController sc = new ScenarioController("obstacle:20:30:1.5:0");

        assertThat(sc.obstacleRangeM(19.9)).as("起点之前应无事件").isNaN();
        assertThat(sc.obstacleRangeM(20.0)).isEqualTo(1.5);
        assertThat(sc.obstacleRangeM(49.9)).isEqualTo(1.5);
        assertThat(sc.obstacleRangeM(50.0)).as("终点之后应失效").isNaN();
    }

    @Test
    @DisplayName("没有 obstacle 事件时返回 NaN（而不是 0 —— 0 会被当成贴脸障碍）")
    void absentObstacleIsNaNNotZero() {
        ScenarioController sc = new ScenarioController("gps-loss:30:20");

        assertThat(sc.obstacleRangeM(35)).isNaN();
        assertThat(sc.obstacleBearingDeg(35)).isNaN();
        assertThat(new ScenarioController(null).obstacleRangeM(1.0)).isNaN();
    }

    @Test
    @DisplayName("默认参数：距离 2.0m、正前方 0°、时长 30s")
    void obstacleDefaults() {
        ScenarioController sc = new ScenarioController("obstacle:5");

        assertThat(sc.obstacleRangeM(6)).isEqualTo(2.0);
        assertThat(sc.obstacleBearingDeg(6)).isEqualTo(0.0);
        assertThat(sc.obstacleRangeM(40)).as("默认时长 30s，35s 时应已失效").isNaN();
    }

    @Test
    @DisplayName("obstacle 与其它场景可共存（逗号分隔）")
    void obstacleCoexistsWithOthers() {
        ScenarioController sc = new ScenarioController("gps-loss:30:20,obstacle:10:9999:1.0:0");

        assertThat(sc.gpsLost(35)).isTrue();
        assertThat(sc.obstacleRangeM(35)).isEqualTo(1.0);
        assertThat(sc.gpsLost(60)).as("gps-loss 只持续 20s").isFalse();
    }

    // ===== UltrasonicSource.measureFromBearing：传感器几何 =====

    @Test
    @DisplayName("正前方障碍只被前向传感器看到")
    void obstacleDeadAheadHitsOnlyFrontSensor() {
        UltrasonicSource us = new UltrasonicSource();
        us.measureFromBearing(0.3, 0.0);

        double[] r = us.lastReadings();
        assertThat(r).hasSize(4);
        assertThat(r[0]).as("前向（0°）应测到 0.3m").isBetween(0.29, 0.31);
        assertThat(r[1]).as("后向（180°）应读满量程").isEqualTo(us.maxRangeM());
        assertThat(r[2]).as("左向（90°）应读满量程").isEqualTo(us.maxRangeM());
        assertThat(r[3]).as("右向（270°）应读满量程").isEqualTo(us.maxRangeM());
    }

    @Test
    @DisplayName("方位 90° 命中左向传感器（DIRECTIONS_DEG = 前0/后180/左90/右270）")
    void bearingPicksCorrectSensor() {
        UltrasonicSource us = new UltrasonicSource();
        us.measureFromBearing(1.2, 90.0);

        double[] r = us.lastReadings();
        assertThat(r[2]).as("左向（索引 2）应测到 1.2m").isBetween(1.19, 1.21);
        assertThat(r[0]).isEqualTo(us.maxRangeM());
        assertThat(r[3]).isEqualTo(us.maxRangeM());
    }

    @Test
    @DisplayName("象限边界 45°：恰好在前向视野内（±45° 含边界）")
    void bearingAtFovEdgeStillVisible() {
        UltrasonicSource us = new UltrasonicSource();
        us.measureFromBearing(1.0, 45.0);

        double[] r = us.lastReadings();
        assertThat(r[0]).as("45° 恰好在前向 ±45° 视野边界上，应可见").isBetween(0.99, 1.01);
        // 注意：45° 同时也在左向(90°)的 45° 边界上 ⇒ 边界处两个传感器都看到。
        // 这是 ±45° 视野首尾相接铺满整圈的必然结果，不是 bug，见 measureFromBearing 的 javadoc。
        assertThat(r[2]).as("45° 同时是左向视野的边界，两侧都可见").isBetween(0.99, 1.01);
    }

    @Test
    @DisplayName("方位 46°：前向已不可见，改由左向看到（视野交接点）")
    void bearingJustPastEdgeMovesToNextSensor() {
        UltrasonicSource us = new UltrasonicSource();
        us.measureFromBearing(1.0, 46.0);

        double[] r = us.lastReadings();
        // 与 0° 相差 46° > 45°，前向应当不再看到
        assertThat(r[0]).as("46° 超出前向 ±45° 视野").isEqualTo(us.maxRangeM());
        // 与左向(90°)相差 44° ≤ 45°，左向看到
        assertThat(r[2]).as("46° 进入左向视野").isBetween(0.99, 1.01);
    }

    @Test
    @DisplayName("任一方位都恰好有一个传感器看到（±45° 视野铺满整圈，无死角）")
    void everyBearingIsCoveredExactlyOnce() {
        UltrasonicSource us = new UltrasonicSource();
        for (double bearing = 0; bearing < 360; bearing += 7.5) {
            us.measureFromBearing(1.0, bearing);
            double[] r = us.lastReadings();
            long seen = 0L;
            for (double d : r) {
                if (d < us.maxRangeM()) {
                    seen++;
                }
            }
            assertThat(seen)
                    .as("方位 %s° 应恰好被 1 个传感器看到（45°/135°/225°/315° 边界处为 2 个）",
                            bearing)
                    .isBetween(1L, 2L);
        }
    }

    @Test
    @DisplayName("距离超出量程：所有传感器读满量程（等价于无障碍）")
    void rangeBeyondMaxRangeSeesNothing() {
        UltrasonicSource us = new UltrasonicSource();
        us.measureFromBearing(us.maxRangeM() + 5.0, 0.0);

        for (double d : us.lastReadings()) {
            assertThat(d).as("超出量程应读满量程").isEqualTo(us.maxRangeM());
        }
        assertThat(us.nearestObstacle().distance())
                .as("满量程读数不被判为障碍").isEqualTo(Double.MAX_VALUE);
    }

    @Test
    @DisplayName("方位角跨 0/360 环绕：-10° 等价于 350°")
    void bearingWrapsAround() {
        UltrasonicSource us = new UltrasonicSource();
        us.measureFromBearing(0.5, -10.0);

        double[] r = us.lastReadings();
        assertThat(r[0]).as("-10° 与 0° 只差 10°，前向应可见").isBetween(0.49, 0.51);
    }

    @Test
    @DisplayName("measureFromBearing 会写入 lastReadings —— 这是接入生产链路的关键")
    void measureFromBearingFeedsLastReadings() {
        UltrasonicSource us = new UltrasonicSource();
        assertThat(us.lastReadings()).as("初始应为 null（detect() 便会走 null 早退分支）").isNull();

        us.measureFromBearing(0.3, 0.0);

        assertThat(us.lastReadings()).isNotNull();
        assertThat(us.nearestObstacle().distance())
                .as("喂过之后 nearestObstacle 才可能返回真实距离")
                .isBetween(0.29, 0.31);
    }

    @Test
    @DisplayName("非法入参直接拒绝，不静默产出坏读数")
    void rejectsBadInput() {
        UltrasonicSource us = new UltrasonicSource();

        assertThatThrownBy(() -> us.measureFromBearing(Double.NaN, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> us.measureFromBearing(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> us.measureFromBearing(1.0, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
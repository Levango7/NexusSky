package io.aerofleet.sim.mesh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AdaptiveRouteMetric 自适应路由度量单测（灾害应急通讯组网，FR-16）。
 * <p>
 * 覆盖 6 维加权度量计算、正常/灾害模式权重切换、搜救/测绘场景权重细分。
 */
@DisplayName("AdaptiveRouteMetric 自适应度量 (FR-16)")
class AdaptiveRouteMetricTest {

    @Test
    @DisplayName("正常模式：默认权重为 NORMAL_W1~W6")
    void normalModeDefaultWeights() {
        AdaptiveRouteMetric metric = new AdaptiveRouteMetric();
        assertThat(metric.isDisasterMode()).isFalse();
        assertThat(metric.getW1()).isEqualTo(AdaptiveRouteMetric.NORMAL_W1);
        assertThat(metric.getW2()).isEqualTo(AdaptiveRouteMetric.NORMAL_W2);
        assertThat(metric.getW3()).isEqualTo(AdaptiveRouteMetric.NORMAL_W3);
        assertThat(metric.getW4()).isEqualTo(AdaptiveRouteMetric.NORMAL_W4);
        assertThat(metric.getW5()).isEqualTo(AdaptiveRouteMetric.NORMAL_W5);
        assertThat(metric.getW6()).isEqualTo(AdaptiveRouteMetric.NORMAL_W6);
    }

    @Test
    @DisplayName("灾害模式：权重切换为 DISASTER_W1~W6")
    void disasterModeWeightSwitch() {
        AdaptiveRouteMetric metric = new AdaptiveRouteMetric();
        metric.setDisasterMode(true);
        assertThat(metric.isDisasterMode()).isTrue();
        assertThat(metric.getW1()).isEqualTo(AdaptiveRouteMetric.DISASTER_W1);
        assertThat(metric.getW2()).isEqualTo(AdaptiveRouteMetric.DISASTER_W2);
        assertThat(metric.getW3()).isEqualTo(AdaptiveRouteMetric.DISASTER_W3);
        assertThat(metric.getW4()).isEqualTo(AdaptiveRouteMetric.DISASTER_W4);
        assertThat(metric.getW5()).isEqualTo(AdaptiveRouteMetric.DISASTER_W5);
        assertThat(metric.getW6()).isEqualTo(AdaptiveRouteMetric.DISASTER_W6);
    }

    @Test
    @DisplayName("灾害模式搜救场景：W3(延迟)和W5(稳定性)权重大于正常模式")
    void searchAndRescueWeightsHigher() {
        AdaptiveRouteMetric metric = new AdaptiveRouteMetric();
        metric.setDisasterScenario(AdaptiveRouteMetric.DisasterScenario.SEARCH_AND_RESCUE);
        metric.setDisasterMode(true);

        // 搜救场景下延迟和稳定性权重大幅增大
        assertThat(metric.getW3()).isGreaterThan(AdaptiveRouteMetric.NORMAL_W3);
        assertThat(metric.getW5()).isGreaterThan(AdaptiveRouteMetric.NORMAL_W5);
    }

    @Test
    @DisplayName("灾害模式测绘场景：W2(RSSI/带宽)权重仍偏重")
    void mappingDisasterWeights() {
        AdaptiveRouteMetric metric = new AdaptiveRouteMetric();
        metric.setDisasterScenario(AdaptiveRouteMetric.DisasterScenario.MAPPING);
        metric.setDisasterMode(true);

        // 测绘灾害模式下 RSSI 权重仍较大
        assertThat(metric.getW2()).isGreaterThan(AdaptiveRouteMetric.DISASTER_W2);
    }

    @Test
    @DisplayName("calculateMetric：跳数越多度量值越大")
    void moreHopsHigherMetric() {
        AdaptiveRouteMetric metric = new AdaptiveRouteMetric();
        double metric1Hop = metric.calculateMetric(1, -50, 100, 5, 0.8, 80, false);
        double metric5Hops = metric.calculateMetric(5, -50, 100, 5, 0.8, 80, false);
        assertThat(metric5Hops).isGreaterThan(metric1Hop);
    }

    @Test
    @DisplayName("calculateMetric：RSSI 越高度量值越小（路径越优）")
    void higherRssiLowerMetric() {
        AdaptiveRouteMetric metric = new AdaptiveRouteMetric();
        double metricPoorRssi = metric.calculateMetric(1, -100, 100, 5, 0.8, 80, false);
        double metricGoodRssi = metric.calculateMetric(1, -40, 100, 5, 0.8, 80, false);
        assertThat(metricGoodRssi).isLessThan(metricPoorRssi);
    }

    @Test
    @DisplayName("calculateMetric：延迟越低度量值越小")
    void lowerDelayLowerMetric() {
        AdaptiveRouteMetric metric = new AdaptiveRouteMetric();
        double metricHighDelay = metric.calculateMetric(1, -50, 2000, 5, 0.8, 80, false);
        double metricLowDelay = metric.calculateMetric(1, -50, 50, 5, 0.8, 80, false);
        assertThat(metricLowDelay).isLessThan(metricHighDelay);
    }

    @Test
    @DisplayName("calculateMetric：链路稳定性越高度量值越小")
    void higherStabilityLowerMetric() {
        AdaptiveRouteMetric metric = new AdaptiveRouteMetric();
        double metricLowStability = metric.calculateMetric(1, -50, 100, 5, 0.3, 80, false);
        double metricHighStability = metric.calculateMetric(1, -50, 100, 5, 0.9, 80, false);
        assertThat(metricHighStability).isLessThan(metricLowStability);
    }

    @Test
    @DisplayName("calculateMetric：电量越高度量值越小")
    void higherBatteryLowerMetric() {
        AdaptiveRouteMetric metric = new AdaptiveRouteMetric();
        double metricLowBattery = metric.calculateMetric(1, -50, 100, 5, 0.8, 20, false);
        double metricHighBattery = metric.calculateMetric(1, -50, 100, 5, 0.8, 90, false);
        assertThat(metricHighBattery).isLessThan(metricLowBattery);
    }

    @Test
    @DisplayName("calculateMetric：地形衰减越高度量值越大")
    void higherTerrainAttenuationHigherMetric() {
        AdaptiveRouteMetric metric = new AdaptiveRouteMetric();
        double metricLowTerrain = metric.calculateMetric(1, -50, 100, 2, 0.8, 80, false);
        double metricHighTerrain = metric.calculateMetric(1, -50, 100, 25, 0.8, 80, false);
        assertThat(metricHighTerrain).isGreaterThan(metricLowTerrain);
    }

    @Test
    @DisplayName("灾害模式下延迟差异对度量影响更大（W3增大）")
    void disasterModeDelayMoreImpact() {
        AdaptiveRouteMetric metric = new AdaptiveRouteMetric();
        // 正常模式：延迟从 100 到 2000 的度量差
        double normalLow = metric.calculateMetric(1, -50, 100, 5, 0.8, 80, false);
        double normalHigh = metric.calculateMetric(1, -50, 2000, 5, 0.8, 80, false);
        double normalDiff = normalHigh - normalLow;

        // 灾害模式：延迟从 100 到 2000 的度量差
        double disasterLow = metric.calculateMetric(1, -50, 100, 5, 0.8, 80, true);
        double disasterHigh = metric.calculateMetric(1, -50, 2000, 5, 0.8, 80, true);
        double disasterDiff = disasterHigh - disasterLow;

        // 灾害模式下延迟差异影响更大
        assertThat(disasterDiff).isGreaterThan(normalDiff);
    }

    @Test
    @DisplayName("calculateMetric(RouteEntry, boolean) 使用默认参数计算")
    void calculateMetricFromRouteEntry() {
        AdaptiveRouteMetric metric = new AdaptiveRouteMetric();
        long now = System.currentTimeMillis();
        RouteEntry route = new RouteEntry(5, 2, 3, 10.0, now, now + 60000, true);

        double result = metric.calculateMetric(route, false);
        assertThat(result).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("切回正常模式后权重恢复")
    void switchBackToNormalMode() {
        AdaptiveRouteMetric metric = new AdaptiveRouteMetric();
        metric.setDisasterMode(true);
        assertThat(metric.getW3()).isEqualTo(AdaptiveRouteMetric.DISASTER_W3);

        metric.setDisasterMode(false);
        assertThat(metric.getW3()).isEqualTo(AdaptiveRouteMetric.NORMAL_W3);
    }
}
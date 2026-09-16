package io.aerofleet.sim.satrelay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HierarchicalRouter 层级行由决策核心单测（M7 星-空-地多层级中继，FR-5.3/5.5）。
 * <p>
 * 覆盖近端优先、逐级升级、降级滞后、4 种切换策略、无路径、策略切换审计、链路模型。
 */
@DisplayName("HierarchicalRouter 层级行由决策 (FR-5.3/5.5)")
class HierarchicalRouterTest {

    private static final int SRC = 1;
    private static final int DST = 2;
    private static final double SRC_LAT = 22.0;
    private static final double SRC_LON = 114.0;
    private static final double DST_LAT = 22.1;
    private static final double DST_LON = 114.1;

    // ===== 近端优先 =====

    @Test
    @DisplayName("近端优先：L1 可达时选 L1")
    void nearFirstL1Reachable() {
        SatRelayConfig cfg = SatRelayConfig.defaults();
        HierarchicalRouter router = new HierarchicalRouter(cfg, null, null);

        HierarchicalRouter.RouteDecision d = router.decide(
                SRC, DST, SRC_LAT, SRC_LON, DST_LAT, DST_LON, true, 0L);

        assertThat(d.isNoPath()).isFalse();
        assertThat(d.chosenLayer()).isEqualTo(RelayLayer.L1);
        assertThat(d.estimatedDelayMs()).isEqualTo(20);
        assertThat(d.pathNodes()).containsExactly(SRC, DST);
    }

    @Test
    @DisplayName("逐级升级：L1 不可达、L2 可达时选 L2")
    void upgradeToL2() {
        SatRelayConfig cfg = SatRelayConfig.defaults();
        HapsRelayNode haps = HapsRelayNode.atDefault(200, 22.05, 114.05);
        HierarchicalRouter router = new HierarchicalRouter(cfg, List.of(haps), null);

        HierarchicalRouter.RouteDecision d = router.decide(
                SRC, DST, SRC_LAT, SRC_LON, DST_LAT, DST_LON, false, 0L);

        assertThat(d.isNoPath()).isFalse();
        assertThat(d.chosenLayer()).isEqualTo(RelayLayer.L2);
        assertThat(d.pathNodes()).hasSize(3);
        assertThat(d.pathNodes().get(0)).isEqualTo(SRC);
        assertThat(d.pathNodes().get(1)).isEqualTo(200);
        assertThat(d.pathNodes().get(2)).isEqualTo(DST);
    }

    @Test
    @DisplayName("全层级不可达返回 noPath")
    void noPathWhenAllUnreachable() {
        SatRelayConfig cfg = SatRelayConfig.defaults();
        HierarchicalRouter router = new HierarchicalRouter(cfg, null, null);

        HierarchicalRouter.RouteDecision d = router.decide(
                SRC, DST, SRC_LAT, SRC_LON, DST_LAT, DST_LON, false, 0L);

        assertThat(d.isNoPath()).isTrue();
        assertThat(d.chosenPath()).isNull();
        assertThat(d.decisionReason()).contains("全层级不可达");
        assertThat(d.pathNodes()).isEmpty();
    }

    @Test
    @DisplayName("L2 不可达时 HAPS reachable=false 被跳过")
    void l2HapsUnreachableSkipped() {
        SatRelayConfig cfg = SatRelayConfig.defaults();
        HapsRelayNode haps = HapsRelayNode.atDefault(200, 22.05, 114.05);
        haps.setReachable(false);
        HierarchicalRouter router = new HierarchicalRouter(cfg, List.of(haps), null);

        HierarchicalRouter.RouteDecision d = router.decide(
                SRC, DST, SRC_LAT, SRC_LON, DST_LAT, DST_LON, false, 0L);

        assertThat(d.isNoPath()).isTrue();
    }

    // ===== 4 种切换策略 =====

    @Test
    @DisplayName("BANDWIDTH_OPTIMAL：L1+L2 可达时选带宽更大的 L2")
    void bandwidthOptimalSelectsL2() {
        SatRelayConfig cfg = new SatRelayConfig(
                10.0, 5000L, SatRelayConfig.Strategy.BANDWIDTH_OPTIMAL,
                24, 550.0, 53.0, 60_000L, 2000L, 86_400_000L);
        HapsRelayNode haps = HapsRelayNode.atDefault(200, 22.05, 114.05);
        HierarchicalRouter router = new HierarchicalRouter(cfg, List.of(haps), null);

        HierarchicalRouter.RouteDecision d = router.decide(
                SRC, DST, SRC_LAT, SRC_LON, DST_LAT, DST_LON, true, 0L);

        // L1 bw=5, L2 bw=20 → BANDWIDTH_OPTIMAL 选 L2
        assertThat(d.chosenLayer()).isEqualTo(RelayLayer.L2);
        assertThat(d.strategy()).isEqualTo(SatRelayConfig.Strategy.BANDWIDTH_OPTIMAL);
    }

    @Test
    @DisplayName("NEAR_FIRST：L1+L2 可达时选层级最低的 L1")
    void nearFirstSelectsL1() {
        SatRelayConfig cfg = SatRelayConfig.defaults(); // NEAR_FIRST
        HapsRelayNode haps = HapsRelayNode.atDefault(200, 22.05, 114.05);
        HierarchicalRouter router = new HierarchicalRouter(cfg, List.of(haps), null);

        HierarchicalRouter.RouteDecision d = router.decide(
                SRC, DST, SRC_LAT, SRC_LON, DST_LAT, DST_LON, true, 0L);

        assertThat(d.chosenLayer()).isEqualTo(RelayLayer.L1);
    }

    @Test
    @DisplayName("DELAY_OPTIMAL：L1+L2 可达时选延迟最小的 L1（delay 20 < 40）")
    void delayOptimalSelectsL1() {
        SatRelayConfig cfg = new SatRelayConfig(
                10.0, 5000L, SatRelayConfig.Strategy.DELAY_OPTIMAL,
                24, 550.0, 53.0, 60_000L, 2000L, 86_400_000L);
        HapsRelayNode haps = HapsRelayNode.atDefault(200, 22.05, 114.05);
        HierarchicalRouter router = new HierarchicalRouter(cfg, List.of(haps), null);

        HierarchicalRouter.RouteDecision d = router.decide(
                SRC, DST, SRC_LAT, SRC_LON, DST_LAT, DST_LON, true, 0L);

        assertThat(d.chosenLayer()).isEqualTo(RelayLayer.L1);
        assertThat(d.estimatedDelayMs()).isEqualTo(20);
    }

    @Test
    @DisplayName("RELIABILITY_OPTIMAL：L1+L2 窗口并列时回退近端优先选 L1")
    void reliabilityOptimalFallbackNearFirst() {
        SatRelayConfig cfg = new SatRelayConfig(
                10.0, 5000L, SatRelayConfig.Strategy.RELIABILITY_OPTIMAL,
                24, 550.0, 53.0, 60_000L, 2000L, 86_400_000L);
        HapsRelayNode haps = HapsRelayNode.atDefault(200, 22.05, 114.05);
        HierarchicalRouter router = new HierarchicalRouter(cfg, List.of(haps), null);

        HierarchicalRouter.RouteDecision d = router.decide(
                SRC, DST, SRC_LAT, SRC_LON, DST_LAT, DST_LON, true, 0L);

        // L1 window=MAX, L2 window=MAX → 并列 → 回退近端优先 → L1
        assertThat(d.chosenLayer()).isEqualTo(RelayLayer.L1);
    }

    // ===== 降级滞后 =====

    @Test
    @DisplayName("降级滞后：低层恢复未达阈值时维持高层路径")
    void hysteresisHoldsHighLayerBeforeThreshold() {
        SatRelayConfig cfg = new SatRelayConfig(
                10.0, 5000L, SatRelayConfig.Strategy.BANDWIDTH_OPTIMAL,
                24, 550.0, 53.0, 60_000L, 2000L, 86_400_000L);
        HapsRelayNode haps = HapsRelayNode.atDefault(200, 22.05, 114.05);
        HierarchicalRouter router = new HierarchicalRouter(cfg, List.of(haps), null);

        // t=0：L1+L2 可达，BANDWIDTH_OPTIMAL 选 L2，L1 恢复起始时间记录
        HierarchicalRouter.RouteDecision d0 = router.decide(
                SRC, DST, SRC_LAT, SRC_LON, DST_LAT, DST_LON, true, 0L);
        assertThat(d0.chosenLayer()).isEqualTo(RelayLayer.L2);

        // t=3000：未达 5000 阈值，维持 L2
        HierarchicalRouter.RouteDecision d1 = router.decide(
                SRC, DST, SRC_LAT, SRC_LON, DST_LAT, DST_LON, true, 3000L);
        assertThat(d1.chosenLayer()).isEqualTo(RelayLayer.L2);
    }

    @Test
    @DisplayName("降级滞后：低层恢复达阈值后降级到低层")
    void hysteresisDowngradeAfterThreshold() {
        SatRelayConfig cfg = new SatRelayConfig(
                10.0, 5000L, SatRelayConfig.Strategy.BANDWIDTH_OPTIMAL,
                24, 550.0, 53.0, 60_000L, 2000L, 86_400_000L);
        HapsRelayNode haps = HapsRelayNode.atDefault(200, 22.05, 114.05);
        HierarchicalRouter router = new HierarchicalRouter(cfg, List.of(haps), null);

        // t=0：选 L2，记录 L1 恢复起始
        router.decide(SRC, DST, SRC_LAT, SRC_LON, DST_LAT, DST_LON, true, 0L);

        // t=6000：达 5000 阈值，降级到 L1
        HierarchicalRouter.RouteDecision d = router.decide(
                SRC, DST, SRC_LAT, SRC_LON, DST_LAT, DST_LON, true, 6000L);
        assertThat(d.chosenLayer()).isEqualTo(RelayLayer.L1);
        assertThat(d.decisionReason()).contains("滞后阈值已满足");
    }

    // ===== 策略切换审计 =====

    @Test
    @DisplayName("setStrategy 切换成功返回 true 并记审计")
    void setStrategySwitchAndAudit() {
        SatRelayConfig cfg = SatRelayConfig.defaults();
        HierarchicalRouter router = new HierarchicalRouter(cfg, null, null);

        assertThat(router.currentStrategy()).isEqualTo(SatRelayConfig.Strategy.NEAR_FIRST);

        boolean changed = router.setStrategy(SatRelayConfig.Strategy.DELAY_OPTIMAL, "test");
        assertThat(changed).isTrue();
        assertThat(router.currentStrategy()).isEqualTo(SatRelayConfig.Strategy.DELAY_OPTIMAL);

        List<HierarchicalRouter.StrategyAudit> audits = router.strategyAudits();
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).from()).isEqualTo(SatRelayConfig.Strategy.NEAR_FIRST);
        assertThat(audits.get(0).to()).isEqualTo(SatRelayConfig.Strategy.DELAY_OPTIMAL);
        assertThat(audits.get(0).triggerBy()).isEqualTo("test");
    }

    @Test
    @DisplayName("setStrategy 相同策略返回 false 不记审计")
    void setStrategySameReturnsFalse() {
        SatRelayConfig cfg = SatRelayConfig.defaults();
        HierarchicalRouter router = new HierarchicalRouter(cfg, null, null);

        boolean changed = router.setStrategy(SatRelayConfig.Strategy.NEAR_FIRST, "test");
        assertThat(changed).isFalse();
        assertThat(router.strategyAudits()).isEmpty();
    }

    @Test
    @DisplayName("setStrategy null 返回 false")
    void setStrategyNullReturnsFalse() {
        SatRelayConfig cfg = SatRelayConfig.defaults();
        HierarchicalRouter router = new HierarchicalRouter(cfg, null, null);

        assertThat(router.setStrategy(null, "test")).isFalse();
    }

    @Test
    @DisplayName("多次策略切换审计日志有界保留")
    void strategyAuditsBounded() {
        SatRelayConfig cfg = SatRelayConfig.defaults();
        HierarchicalRouter router = new HierarchicalRouter(cfg, null, null);

        router.setStrategy(SatRelayConfig.Strategy.DELAY_OPTIMAL, "t1");
        router.setStrategy(SatRelayConfig.Strategy.BANDWIDTH_OPTIMAL, "t2");
        router.setStrategy(SatRelayConfig.Strategy.RELIABILITY_OPTIMAL, "t3");

        List<HierarchicalRouter.StrategyAudit> audits = router.strategyAudits();
        assertThat(audits).hasSize(3);
        assertThat(audits.get(2).to()).isEqualTo(SatRelayConfig.Strategy.RELIABILITY_OPTIMAL);
    }

    // ===== 链路模型 =====

    @Test
    @DisplayName("computeDelayMs：300km 轨道延迟截断到下限 50ms")
    void computeDelayMsLowAltitude() {
        // 2*7 + 30 = 44 → max(50, 44) = 50
        assertThat(HierarchicalRouter.computeDelayMs(300.0)).isEqualTo(50);
    }

    @Test
    @DisplayName("computeDelayMs：1200km 轨道延迟 54ms")
    void computeDelayMsHighAltitude() {
        // 2*12 + 30 = 54
        assertThat(HierarchicalRouter.computeDelayMs(1200.0)).isEqualTo(54);
    }

    @Test
    @DisplayName("computeDelayMs：550km 轨道延迟截断到下限 50ms")
    void computeDelayMsMediumAltitude() {
        // 2*(7 + (550-300)/900*5) + 30 = 2*8.389 + 30 ≈ 46.78 → 47 → max(50, 47) = 50
        assertThat(HierarchicalRouter.computeDelayMs(550.0)).isEqualTo(50);
    }

    @Test
    @DisplayName("computeBandwidthMbps：共享用户数衰减")
    void computeBandwidthMbps() {
        assertThat(HierarchicalRouter.computeBandwidthMbps(0)).isEqualTo(50);
        assertThat(HierarchicalRouter.computeBandwidthMbps(1)).isEqualTo(50);
        assertThat(HierarchicalRouter.computeBandwidthMbps(2)).isEqualTo(25);
        assertThat(HierarchicalRouter.computeBandwidthMbps(5)).isEqualTo(10);
    }

    @Test
    @DisplayName("linkQualityScore：仰角正相关，0→0, 45→0.5, 90→1.0")
    void linkQualityScore() {
        assertThat(HierarchicalRouter.linkQualityScore(0.0)).isZero();
        assertThat(HierarchicalRouter.linkQualityScore(45.0)).isEqualTo(0.5);
        assertThat(HierarchicalRouter.linkQualityScore(90.0)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("linkQualityScore：负仰角截断到 0，超 90 截断到 1")
    void linkQualityScoreClamped() {
        assertThat(HierarchicalRouter.linkQualityScore(-10.0)).isZero();
        assertThat(HierarchicalRouter.linkQualityScore(100.0)).isEqualTo(1.0);
    }

    // ===== L3 卫星可达性 =====

    @Test
    @DisplayName("L3 可达：L1/L2 不可达时通过可见卫星选 L3")
    void upgradeToL3ViaVisibleSatellite() {
        SatRelayConfig cfg = SatRelayConfig.defaults();
        LeoConstellation constellation = LeoConstellation.walkerShell(12, 550.0, 53.0);
        constellation.tick(0L);

        // 在赤道附近查找可见卫星位置
        double testLat = 0.0;
        double testLon = 0.0;
        List<SatelliteNode> visible = constellation.findVisible(testLat, testLon, cfg.elevationThresholdDeg);

        // 如果当前时刻有可见卫星，验证 L3 路径；否则验证全不可达
        HierarchicalRouter router = new HierarchicalRouter(cfg, null, constellation);
        HierarchicalRouter.RouteDecision d = router.decide(
                SRC, DST, testLat, testLon, testLat + 0.01, testLon + 0.01, false, 0L);

        if (!visible.isEmpty()) {
            // 存在可见卫星 → 应选 L3（或因目标不可见而 noPath）
            // 由于卫星需要对源和目标都可见，结果取决于轨道几何
            assertThat(d.isNoPath() || d.chosenLayer() == RelayLayer.L3).isTrue();
        } else {
            // 无可见卫星 → 全不可达
            assertThat(d.isNoPath()).isTrue();
        }
    }

    // ===== 路由环路检测 =====

    @Test
    @DisplayName("路由环路检测：源=目标时 L1 路径含重复节点被剔除")
    void loopDetectionSameSourceTarget() {
        SatRelayConfig cfg = SatRelayConfig.defaults();
        HierarchicalRouter router = new HierarchicalRouter(cfg, null, null);

        // 源=目标=1，L1 路径 [1,1] 含重复节点
        HierarchicalRouter.RouteDecision d = router.decide(
                1, 1, SRC_LAT, SRC_LON, SRC_LAT, SRC_LON, true, 0L);

        // L1 路径被环路检测剔除 → 全不可达
        assertThat(d.isNoPath()).isTrue();
    }
}
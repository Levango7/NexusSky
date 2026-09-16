package io.aerofleet.sim.satrelay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SatRelayConfig 星-空-地中继配置单测（M7，数据约束 6.7）。
 */
@DisplayName("SatRelayConfig 中继配置 (DC-6.7)")
class SatRelayConfigTest {

    @Test
    @DisplayName("defaults 返回预期默认值")
    void defaultsValues() {
        SatRelayConfig cfg = SatRelayConfig.defaults();
        assertThat(cfg.elevationThresholdDeg).isEqualTo(10.0);
        assertThat(cfg.hysteresisThresholdMs).isEqualTo(5000L);
        assertThat(cfg.strategy).isEqualTo(SatRelayConfig.Strategy.NEAR_FIRST);
        assertThat(cfg.constellationSize).isEqualTo(24);
        assertThat(cfg.orbitAltitudeKm).isEqualTo(550.0);
        assertThat(cfg.inclinationDeg).isEqualTo(53.0);
        assertThat(cfg.linkWindowScanStepMs).isEqualTo(60_000L);
        assertThat(cfg.satLinkReportIntervalMs).isEqualTo(2000L);
        assertThat(cfg.passScheduleHorizonMs).isEqualTo(86_400_000L);
    }

    @Test
    @DisplayName("合法自定义参数构造成功")
    void validCustomConstruction() {
        SatRelayConfig cfg = new SatRelayConfig(
                15.0, 3000L, SatRelayConfig.Strategy.DELAY_OPTIMAL,
                48, 600.0, 97.0,
                30_000L, 1000L, 43_200_000L);
        assertThat(cfg.elevationThresholdDeg).isEqualTo(15.0);
        assertThat(cfg.strategy).isEqualTo(SatRelayConfig.Strategy.DELAY_OPTIMAL);
        assertThat(cfg.constellationSize).isEqualTo(48);
    }

    @Test
    @DisplayName("仰角阈值越界拒绝：<=0 或 >=90")
    void elevationThresholdOutOfRange() {
        assertThatThrownBy(() -> new SatRelayConfig(
                0.0, 5000L, SatRelayConfig.Strategy.NEAR_FIRST,
                24, 550.0, 53.0, 60_000L, 2000L, 86_400_000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SatRelayConfig(
                90.0, 5000L, SatRelayConfig.Strategy.NEAR_FIRST,
                24, 550.0, 53.0, 60_000L, 2000L, 86_400_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("滞后阈值 <=0 拒绝")
    void hysteresisThresholdNonPositive() {
        assertThatThrownBy(() -> new SatRelayConfig(
                10.0, 0L, SatRelayConfig.Strategy.NEAR_FIRST,
                24, 550.0, 53.0, 60_000L, 2000L, 86_400_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("strategy 为 null 拒绝")
    void nullStrategyRejected() {
        assertThatThrownBy(() -> new SatRelayConfig(
                10.0, 5000L, null,
                24, 550.0, 53.0, 60_000L, 2000L, 86_400_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("星座规模越界拒绝：<10 或 >100")
    void constellationSizeOutOfRange() {
        assertThatThrownBy(() -> new SatRelayConfig(
                10.0, 5000L, SatRelayConfig.Strategy.NEAR_FIRST,
                9, 550.0, 53.0, 60_000L, 2000L, 86_400_000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SatRelayConfig(
                10.0, 5000L, SatRelayConfig.Strategy.NEAR_FIRST,
                101, 550.0, 53.0, 60_000L, 2000L, 86_400_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("轨道高度越界拒绝：<300 或 >1200")
    void orbitAltitudeOutOfRange() {
        assertThatThrownBy(() -> new SatRelayConfig(
                10.0, 5000L, SatRelayConfig.Strategy.NEAR_FIRST,
                24, 299.0, 53.0, 60_000L, 2000L, 86_400_000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SatRelayConfig(
                10.0, 5000L, SatRelayConfig.Strategy.NEAR_FIRST,
                24, 1201.0, 53.0, 60_000L, 2000L, 86_400_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Strategy.fromCode 正确映射 4 种策略")
    void strategyFromCode() {
        assertThat(SatRelayConfig.Strategy.fromCode(0)).isEqualTo(SatRelayConfig.Strategy.NEAR_FIRST);
        assertThat(SatRelayConfig.Strategy.fromCode(1)).isEqualTo(SatRelayConfig.Strategy.DELAY_OPTIMAL);
        assertThat(SatRelayConfig.Strategy.fromCode(2)).isEqualTo(SatRelayConfig.Strategy.BANDWIDTH_OPTIMAL);
        assertThat(SatRelayConfig.Strategy.fromCode(3)).isEqualTo(SatRelayConfig.Strategy.RELIABILITY_OPTIMAL);
    }

    @Test
    @DisplayName("Strategy.fromCode 非法 code 抛异常")
    void strategyFromCodeInvalid() {
        assertThatThrownBy(() -> SatRelayConfig.Strategy.fromCode(99))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Strategy.fromName 大小写不敏感")
    void strategyFromNameCaseInsensitive() {
        assertThat(SatRelayConfig.Strategy.fromName("near_first"))
                .isEqualTo(SatRelayConfig.Strategy.NEAR_FIRST);
        assertThat(SatRelayConfig.Strategy.fromName("DELAY_OPTIMAL"))
                .isEqualTo(SatRelayConfig.Strategy.DELAY_OPTIMAL);
    }

    @Test
    @DisplayName("Strategy.fromName 非法名称抛异常")
    void strategyFromNameInvalid() {
        assertThatThrownBy(() -> SatRelayConfig.Strategy.fromName("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("parse 解析 --key=value 格式参数")
    void parseEqualsFormat() {
        SatRelayConfig cfg = SatRelayConfig.parse(new String[]{
                "--sat-strategy=BANDWIDTH_OPTIMAL",
                "--sat-constellation-size=48",
                "--sat-orbit-altitude=600.0"
        });
        assertThat(cfg.strategy).isEqualTo(SatRelayConfig.Strategy.BANDWIDTH_OPTIMAL);
        assertThat(cfg.constellationSize).isEqualTo(48);
        assertThat(cfg.orbitAltitudeKm).isEqualTo(600.0);
    }

    @Test
    @DisplayName("parse 解析 --key value 空格分隔格式")
    void parseSpaceFormat() {
        SatRelayConfig cfg = SatRelayConfig.parse(new String[]{
                "--sat-strategy", "RELIABILITY_OPTIMAL",
                "--sat-elevation-threshold", "15.0"
        });
        assertThat(cfg.strategy).isEqualTo(SatRelayConfig.Strategy.RELIABILITY_OPTIMAL);
        assertThat(cfg.elevationThresholdDeg).isEqualTo(15.0);
    }

    @Test
    @DisplayName("parse 无 sat 参数时返回 defaults")
    void parseNoSatArgsReturnsDefaults() {
        SatRelayConfig cfg = SatRelayConfig.parse(new String[]{"--other-flag", "value"});
        SatRelayConfig defaults = SatRelayConfig.defaults();
        assertThat(cfg.strategy).isEqualTo(defaults.strategy);
        assertThat(cfg.constellationSize).isEqualTo(defaults.constellationSize);
    }

    @Test
    @DisplayName("parse 非法值回退到 defaults")
    void parseInvalidValueFallback() {
        SatRelayConfig cfg = SatRelayConfig.parse(new String[]{
                "--sat-constellation-size=5"
        });
        // 5 < MIN_SIZE(10)，构造失败回退 defaults
        assertThat(cfg.constellationSize).isEqualTo(24);
    }

    @Test
    @DisplayName("Strategy label 与 code 正确")
    void strategyLabelAndCode() {
        assertThat(SatRelayConfig.Strategy.NEAR_FIRST.code()).isEqualTo(0);
        assertThat(SatRelayConfig.Strategy.NEAR_FIRST.label()).isEqualTo("近端优先");
        assertThat(SatRelayConfig.Strategy.RELIABILITY_OPTIMAL.code()).isEqualTo(3);
    }

    @Test
    @DisplayName("toString 包含关键字段")
    void toStringContainsFields() {
        SatRelayConfig cfg = SatRelayConfig.defaults();
        String s = cfg.toString();
        assertThat(s).contains("NEAR_FIRST").contains("550.0km").contains("24");
    }
}
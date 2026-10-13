package io.aerofleet.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RealEnvSource} 的契约测试：把"这是 FR-29 / M0b 的**刻意边界**"钉成断言。
 *
 * <p><b>为什么要单独钉它</b>：SPI 可验证性门禁扫出本类"测试源码里零引用"——
 * 它是 {@link EnvironmentSource} 的真实数据源预留位，当前故意不实现。没有断言时，
 * 谁把它改成"返回一个看起来合理但其实是编造的环境状态"，编译与既有单测都不会响，
 * 而下游（阵风/能见度/凝露）会拿着假数据继续跑。
 *
 * <p><b>这条断言的作用是"到期提醒"</b>：真正接入气象站/HTTP API 时，本测试会转红，
 * 逼着实现者同时更新契约与把关（而不是静默把占位换成实现）。这与门禁"豁免到期自动回红"
 * 是同一个取向。
 */
@DisplayName("RealEnvSource：M0b 边界——两个方法都必须显式拒绝，绝不返回假环境")
class RealEnvSourceContractTest {

    @Test
    @DisplayName("snapshot() 抛 UnsupportedOperationException（不是返回一个看起来合理的假状态）")
    void snapshotRefuses() {
        assertThatThrownBy(() -> new RealEnvSource().snapshot())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("RealEnvSource not implemented in M0b");
    }

    @Test
    @DisplayName("evolve(dt) 抛 UnsupportedOperationException")
    void evolveRefuses() {
        assertThatThrownBy(() -> new RealEnvSource().evolve(1.0))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("RealEnvSource not implemented in M0b");
    }
}

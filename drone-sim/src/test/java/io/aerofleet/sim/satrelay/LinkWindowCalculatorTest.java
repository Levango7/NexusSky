package io.aerofleet.sim.satrelay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LinkWindowCalculator 可见窗口计算单测（M7 星-空-地多层级中继，FR-5.1）。
 */
@DisplayName("LinkWindowCalculator 可见窗口计算 (FR-5.1)")
class LinkWindowCalculatorTest {

    @Test
    @DisplayName("computeWindows 返回窗口列表，每个窗口 startMs < endMs")
    void windowsHaveValidTimeRange() {
        SatelliteNode sat = new SatelliteNode(1, 550.0, 53.0, 0.0, 0.0);
        List<LinkWindowCalculator.VisibilityWindow> windows =
                LinkWindowCalculator.computeWindows(sat, 1, 22.0, 114.0, 10.0,
                        0L, 3_600_000L, 60_000L);
        for (LinkWindowCalculator.VisibilityWindow w : windows) {
            assertThat(w.startMs()).isLessThan(w.endMs());
            assertThat(w.durationMs()).isPositive();
        }
    }

    @Test
    @DisplayName("computeWindows 空区间返回空列表")
    void emptyRangeReturnsEmpty() {
        SatelliteNode sat = new SatelliteNode(1, 550.0, 53.0, 0.0, 0.0);
        List<LinkWindowCalculator.VisibilityWindow> windows =
                LinkWindowCalculator.computeWindows(sat, 1, 22.0, 114.0, 10.0,
                        1000L, 500L, 60_000L);
        assertThat(windows).isEmpty();
    }

    @Test
    @DisplayName("computeWindows 零步进返回空列表")
    void zeroStepReturnsEmpty() {
        SatelliteNode sat = new SatelliteNode(1, 550.0, 53.0, 0.0, 0.0);
        List<LinkWindowCalculator.VisibilityWindow> windows =
                LinkWindowCalculator.computeWindows(sat, 1, 22.0, 114.0, 10.0,
                        0L, 3_600_000L, 0L);
        assertThat(windows).isEmpty();
    }

    @Test
    @DisplayName("checkTransition 检测过境切换")
    void checkTransition() {
        SatelliteNode sat = new SatelliteNode(1, 550.0, 53.0, 0.0, 0.0);
        sat.updatePosition(0L);
        LinkWindowCalculator.TransitionEvent event =
                LinkWindowCalculator.checkTransition(sat, 22.0, 114.0, 10.0, 0L);
        assertThat(event.satId()).isEqualTo(1);
        assertThat(event.type()).isIn(
                LinkWindowCalculator.TransitionType.ENTER_WINDOW,
                LinkWindowCalculator.TransitionType.NO_CHANGE);
    }

    @Test
    @DisplayName("checkTransition NO_CHANGE 当可见状态不变")
    void noChangeWhenStable() {
        SatelliteNode sat = new SatelliteNode(1, 550.0, 53.0, 0.0, 0.0);
        sat.updatePosition(0L);
        // 第一次检测
        LinkWindowCalculator.checkTransition(sat, 22.0, 114.0, 10.0, 0L);
        boolean wasVisible = sat.isVisible();
        // 同一时刻再次检测，应无变化
        LinkWindowCalculator.TransitionEvent event =
                LinkWindowCalculator.checkTransition(sat, 22.0, 114.0, 10.0, 0L);
        assertThat(event.type()).isEqualTo(LinkWindowCalculator.TransitionType.NO_CHANGE);
    }

    @Test
    @DisplayName("windowRemainingMs 不可见时返回 0")
    void windowRemainingWhenNotVisible() {
        SatelliteNode sat = new SatelliteNode(1, 550.0, 53.0, 0.0, 0.0);
        // 默认不可见
        long remaining = LinkWindowCalculator.windowRemainingMs(
                sat, 22.0, 114.0, 10.0, 0L, 1_200_000L, 60_000L);
        assertThat(remaining).isZero();
    }

    @Test
    @DisplayName("VisibilityWindow.contains 判定时刻是否在窗口内")
    void visibilityWindowContains() {
        LinkWindowCalculator.VisibilityWindow w =
                new LinkWindowCalculator.VisibilityWindow(1, 1, 1000L, 5000L, 60.0, 4000L);
        assertThat(w.contains(1000L)).isTrue();
        assertThat(w.contains(3000L)).isTrue();
        assertThat(w.contains(5000L)).isTrue();
        assertThat(w.contains(999L)).isFalse();
        assertThat(w.contains(5001L)).isFalse();
    }
}
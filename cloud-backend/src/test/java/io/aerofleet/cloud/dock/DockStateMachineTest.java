package io.aerofleet.cloud.dock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dock 状态机（spec R2）——迁移表全表覆盖：
 * 每条命令的合法态逐一验证、非法态逐一拒绝（不抽样）。
 */
@DisplayName("Dock 状态机 — 迁移全表")
class DockStateMachineTest {

    private final DockStateMachine m = new DockStateMachine();

    @Nested
    @DisplayName("OPEN_DOOR")
    class OpenDoor {
        @ParameterizedTest
        @EnumSource(value = DockState.class, names = {"IDLE", "CHARGING"})
        void allowedFromIdleOrCharging(DockState s) {
            assertThat(m.apply(s, DockCommand.OPEN_DOOR)).isEqualTo(DockState.OPENING);
        }

        @ParameterizedTest
        @EnumSource(value = DockState.class, names = {"OFFLINE", "OPENING", "OPEN", "CLOSING",
                "EXCHANGING", "FAULT", "MAINTENANCE"})
        void rejectedElsewhere(DockState s) {
            assertThatThrownBy(() -> m.apply(s, DockCommand.OPEN_DOOR))
                    .isInstanceOf(IllegalDockTransitionException.class)
                    .hasMessageContaining("door_open")
                    .hasMessageContaining(s.name());
        }
    }

    @Nested
    @DisplayName("CLOSE_DOOR")
    class CloseDoor {
        @Test
        void allowedOnlyFromOpen() {
            assertThat(m.apply(DockState.OPEN, DockCommand.CLOSE_DOOR)).isEqualTo(DockState.CLOSING);
        }

        @ParameterizedTest
        @EnumSource(value = DockState.class, names = {"OFFLINE", "IDLE", "OPENING", "CHARGING",
                "CLOSING", "EXCHANGING", "FAULT", "MAINTENANCE"})
        void rejectedElsewhere(DockState s) {
            assertThatThrownBy(() -> m.apply(s, DockCommand.CLOSE_DOOR))
                    .isInstanceOf(IllegalDockTransitionException.class);
        }
    }

    @Nested
    @DisplayName("SWAP_BATTERY 与 REBOOT")
    class SwapAndReboot {
        @Test
        void swapAllowedFromIdleAndCharging() {
            assertThat(m.apply(DockState.IDLE, DockCommand.SWAP_BATTERY)).isEqualTo(DockState.EXCHANGING);
            assertThat(m.apply(DockState.CHARGING, DockCommand.SWAP_BATTERY)).isEqualTo(DockState.EXCHANGING);
        }

        @Test
        void rebootAllowedFromFaultAndSteadyStates() {
            for (DockState s : new DockState[]{DockState.FAULT, DockState.MAINTENANCE,
                    DockState.IDLE, DockState.CHARGING, DockState.OPEN}) {
                assertThat(m.apply(s, DockCommand.REBOOT)).isNull(); // 状态不变
            }
        }

        @Test
        void rebootRejectedFromOfflineAndTransitional() {
            for (DockState s : new DockState[]{DockState.OFFLINE, DockState.OPENING,
                    DockState.CLOSING, DockState.EXCHANGING}) {
                assertThatThrownBy(() -> m.apply(s, DockCommand.REBOOT))
                        .isInstanceOf(IllegalDockTransitionException.class);
            }
        }
    }

    @Nested
    @DisplayName("OSD 自报状态落地规则")
    class OsdFollow {
        @Test
        void offlineCanFollowAnything() {
            assertThat(m.canFollowOsd(DockState.OFFLINE, DockState.IDLE)).isTrue();
            assertThat(m.canFollowOsd(DockState.OFFLINE, DockState.CHARGING)).isTrue();
        }

        @Test
        void transitionalOnlyAcceptsNaturalSuccessor() {
            assertThat(m.canFollowOsd(DockState.OPENING, DockState.OPEN)).isTrue();
            assertThat(m.canFollowOsd(DockState.OPENING, DockState.IDLE)).isFalse(); // 跳态被拒
            assertThat(m.canFollowOsd(DockState.CLOSING, DockState.IDLE)).isTrue();
            assertThat(m.canFollowOsd(DockState.CLOSING, DockState.CHARGING)).isTrue();
            assertThat(m.canFollowOsd(DockState.CLOSING, DockState.OPEN)).isFalse();
            assertThat(m.canFollowOsd(DockState.EXCHANGING, DockState.CHARGING)).isTrue();
            assertThat(m.canFollowOsd(DockState.EXCHANGING, DockState.OPEN)).isFalse();
        }

        @Test
        void faultAndMaintenanceNotOverriddenByOsd() {
            assertThat(m.canFollowOsd(DockState.FAULT, DockState.IDLE)).isFalse();
            assertThat(m.canFollowOsd(DockState.MAINTENANCE, DockState.IDLE)).isFalse();
        }

        @Test
        void steadyStatesFollowReality() {
            assertThat(m.canFollowOsd(DockState.IDLE, DockState.CHARGING)).isTrue();
            assertThat(m.canFollowOsd(DockState.CHARGING, DockState.IDLE)).isTrue();
        }

        @Test
        void sameStateIsNoop() {
            assertThat(m.canFollowOsd(DockState.IDLE, DockState.IDLE)).isFalse();
        }
    }
}
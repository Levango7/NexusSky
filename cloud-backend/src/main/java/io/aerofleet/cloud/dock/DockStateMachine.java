package io.aerofleet.cloud.dock;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Dock 状态机——迁移规则唯一裁决点（spec R2）。
 * <p>
 * REST 层、网关层、调度器都不得自行判断合法性，一律走本类；非法迁移抛
 * {@link IllegalDockTransitionException}（控制器映射 409 + 当前状态名）。
 * 迁移表为显式静态数据：新增命令/状态只改这里，测试全表覆盖。
 */
@Component
public class DockStateMachine {

    /** 命令 → 允许触发的当前状态集合。 */
    private static final Map<DockCommand, Set<DockState>> ALLOWED = Map.of(
            DockCommand.OPEN_DOOR, Set.of(DockState.IDLE, DockState.CHARGING),
            DockCommand.CLOSE_DOOR, Set.of(DockState.OPEN),
            DockCommand.SWAP_BATTERY, Set.of(DockState.IDLE, DockState.CHARGING),
            DockCommand.REBOOT, Set.of(DockState.FAULT, DockState.MAINTENANCE,
                    DockState.IDLE, DockState.CHARGING, DockState.OPEN));

    /** 命令 → 触发后的过渡态（REBOOT 不换状态，由服务层定时器回到 IDLE）。 */
    private static final Map<DockCommand, DockState> TARGET = Map.of(
            DockCommand.OPEN_DOOR, DockState.OPENING,
            DockCommand.CLOSE_DOOR, DockState.CLOSING,
            DockCommand.SWAP_BATTERY, DockState.EXCHANGING);

    /**
     * 裁决「当前状态能否执行该命令」，返回命令触发后的过渡态；
     * REBOOT 返回 null（状态不变，服务层负责 8s 后回 IDLE）。
     */
    public DockState apply(DockState current, DockCommand command) {
        Set<DockState> allowed = ALLOWED.get(command);
        if (allowed == null || !allowed.contains(current)) {
            throw new IllegalDockTransitionException(current, command);
        }
        return TARGET.get(command);
    }

    /** OSD 心跳自报状态是否可落地。
     *  <p>
     *  规则：过渡态只接受「向其自然后继」的确认（OPENING→OPEN、CLOSING→IDLE/CHARGING、
     *  EXCHANGING→CHARGING/IDLE），防止机巢乱报跳态把状态机带飞；FAULT 只能被 reboot
     *  清除（温度尖峰后自动"痊愈"会掩盖问题）；MAINTENANCE 是人工置入态，不能被 OSD 顶掉；
     *  稳态之间跟随实机（IDLE↔CHARGING 等）。 */
    public boolean canFollowOsd(DockState current, DockState reported) {
        if (current == reported) {
            return false;
        }
        if (current == DockState.FAULT || current == DockState.MAINTENANCE) {
            return false;
        }
        if (current == DockState.OFFLINE) {
            return true;
        }
        if (current.transitional()) {
            return switch (current) {
                case OPENING -> reported == DockState.OPEN;
                case CLOSING -> reported == DockState.IDLE || reported == DockState.CHARGING;
                case EXCHANGING -> reported == DockState.CHARGING || reported == DockState.IDLE;
                default -> false;
            };
        }
        return true;
    }
}

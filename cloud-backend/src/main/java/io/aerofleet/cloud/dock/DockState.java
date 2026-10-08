package io.aerofleet.cloud.dock;

/**
 * 机巢状态（F2，Dock 状态机唯一真值源）。
 * <p>
 * 与 DJI Cloud API dock_osd 的 mode_code 语义对齐过粒度：这里按本系统需要的
 * 最小集合建模， transitional 态（OPENING/OPEN/CLOSING/EXCHANGING）由命令触发进入、
 * 由机巢 OSD 心跳确认为稳态。
 */
public enum DockState {
    /** 无心跳（初始或心跳超时）。 */
    OFFLINE,
    /** 空闲（门关、可接单）。 */
    IDLE,
    /** 开门过渡态。 */
    OPENING,
    /** 门开（无人机可起降）。 */
    OPEN,
    /** 关门过渡态。 */
    CLOSING,
    /** 充电中（门关）。 */
    CHARGING,
    /** 换电过渡态。 */
    EXCHANGING,
    /** 故障（温度超临界、命令超时等）。仅 reboot 可清除。 */
    FAULT,
    /** 维护（人工置入，动作命令全部拒绝）。 */
    MAINTENANCE;

    /** 是否允许起降（门开）。 */
    public boolean doorOpen() {
        return this == OPEN;
    }

    /** 是否处于过渡态（命令进行中，不接受新动作命令）。 */
    public boolean transitional() {
        return this == OPENING || this == CLOSING || this == EXCHANGING;
    }
}

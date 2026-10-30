package io.aerofleet.sim;

import io.aerofleet.mavlink.enums.MavEnums;

/**
 * Flight state machine of the virtual drone:
 * INIT -> STANDBY -> ARMED -> MISSION -> RTL -> STANDBY,
 * plus HOLD (failsafe hover during GPS loss, recovers to the prior state)
 * and CRASHED (terminal, terrain collision - requires a reboot to leave).
 */
public enum FlightState {
    INIT("INIT"),
    STANDBY("STANDBY"),
    ARMED("ARMED"),
    MANUAL("MANUAL"),
    MISSION("MISSION"),
    RTL("RTL"),
    HOLD("HOLD"),
    CRASHED("CRASHED");

    public final String label;

    FlightState(String label) {
        this.label = label;
    }

    /** MAV_STATE heartbeat value for the current state. */
    public int mavState() {
        return switch (this) {
            case INIT -> MavEnums.MAV_STATE_UNINIT;
            case STANDBY -> MavEnums.MAV_STATE_STANDBY;
            case ARMED, MISSION, RTL, MANUAL -> MavEnums.MAV_STATE_ACTIVE;
            // Failsafe hover: still flying, but under an active warning.
            case HOLD -> MavEnums.MAV_STATE_CRITICAL;
            // Wrecked on the terrain: emergency until someone reboots.
            case CRASHED -> MavEnums.MAV_STATE_EMERGENCY;
        };
    }

    /**
     * heartbeat custom_mode：与真 PX4 同编码的打包字
     * {@code (main_mode << 16) | (sub_mode << 24)}（PX4 px4_custom_mode.h 联合体）。
     * <p>
     * 此前这里发的是裸 nav_state 值 0..22（历史口径），真 PX4 发打包字——两个
     * 口径并存的后果是后端按 sim 表解码真机恒 "UNKNOWN"（PX4 首验实测）。现在
     * sim 与真机同编码，后端只有一张打包字解码表（docs/sitl-integration.md §11）。
     * <p>
     * STANDBY/HOLD 都映射 AUTO_LOITER：真 PX4 无 RC 地面待命与悬停保持都是
     * nav_state=AUTO_LOITER（custom_mode=0x03040000，SITL 实测），"STANDBY"
     * 不是任何真实 PX4 模式，不再伪装。
     */
    public int px4NavState() {
        return switch (this) {
            case INIT -> MAIN_MANUAL;         // 预启动替身：与 MANUAL 同值
            case STANDBY -> MAIN_AUTO | SUB_AUTO_LOITER;   // 地面待命（真机同值）
            case ARMED -> MAIN_POSCTL;        // 解锁悬停
            case MANUAL -> MAIN_MANUAL;       // 手动操纵飞行
            case MISSION -> MAIN_AUTO | SUB_AUTO_MISSION;
            case RTL -> MAIN_AUTO | SUB_AUTO_RTL;
            case HOLD -> MAIN_AUTO | SUB_AUTO_LOITER;      // 失效保护悬停
            case CRASHED -> 0xFF0000;         // 非真实 PX4 模式：故意留无效主模式，
                                              // 后端显示 UNKNOWN（不伪装成某个可用模式）
        };
    }

    // PX4 打包字片段（px4_custom_mode.h：main_mode 位 16-23、sub_mode 位 24-31）
    private static final int MAIN_MANUAL = 1 << 16;
    private static final int MAIN_POSCTL = 3 << 16;
    private static final int MAIN_AUTO = 4 << 16;
    private static final int SUB_AUTO_MISSION = 4 << 24;
    private static final int SUB_AUTO_LOITER = 3 << 24;
    private static final int SUB_AUTO_RTL = 5 << 24;

    /** base_mode bits for the current state. */
    public int baseMode() {
        int mode = MavEnums.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED;
        switch (this) {
            case STANDBY -> mode |= MavEnums.MAV_MODE_FLAG_STABILIZE_ENABLED
                    | MavEnums.MAV_MODE_FLAG_MANUAL_INPUT_ENABLED;
            case ARMED -> mode |= MavEnums.MAV_MODE_FLAG_GUIDED_ENABLED
                    | MavEnums.MAV_MODE_FLAG_STABILIZE_ENABLED
                    | MavEnums.MAV_MODE_FLAG_MANUAL_INPUT_ENABLED
                    | MavEnums.MAV_MODE_FLAG_SAFETY_ARMED;
            case MISSION -> mode |= MavEnums.MAV_MODE_FLAG_AUTO_ENABLED
                    | MavEnums.MAV_MODE_FLAG_STABILIZE_ENABLED
                    | MavEnums.MAV_MODE_FLAG_GUIDED_ENABLED
                    | MavEnums.MAV_MODE_FLAG_SAFETY_ARMED;
            case RTL -> mode |= MavEnums.MAV_MODE_FLAG_AUTO_ENABLED
                    | MavEnums.MAV_MODE_FLAG_GUIDED_ENABLED
                    | MavEnums.MAV_MODE_FLAG_STABILIZE_ENABLED
                    | MavEnums.MAV_MODE_FLAG_SAFETY_ARMED;
            case HOLD -> mode |= MavEnums.MAV_MODE_FLAG_AUTO_ENABLED
                    | MavEnums.MAV_MODE_FLAG_GUIDED_ENABLED
                    | MavEnums.MAV_MODE_FLAG_STABILIZE_ENABLED
                    | MavEnums.MAV_MODE_FLAG_SAFETY_ARMED;
            case INIT -> {
                // bare minimum flags
            }
        }
        return mode;
    }

    public boolean armed() {
        return this == ARMED || this == MISSION || this == RTL || this == HOLD
                || this == MANUAL;
    }

    public boolean flying() {
        return this == MISSION || this == RTL || this == HOLD || this == MANUAL;
    }
}

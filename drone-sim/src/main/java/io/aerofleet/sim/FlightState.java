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

    /** PX4 main nav_state (heartbeat custom_mode) for the current state. */
    public int px4NavState() {
        return switch (this) {
            case INIT -> 0;          // MANUAL (pre-boot stand-in)
            case STANDBY -> 13;     // STANDBY
            case ARMED -> 2;        // POSITION (armed hover)
            case MANUAL -> 0;      // MANUAL (stick-controlled flight)
            case MISSION -> 3;      // AUTO MISSION
            case RTL -> 4;          // AUTO RTL
            case HOLD -> 15;       // AUTO HOLD (failsafe hover)
            case CRASHED -> 22;    // TERMINATION-like: not a real PX4 mode,
                                    // but a clearly invalid one on the GCS
        };
    }

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

package io.aerofleet.mavlink.enums;

/**
 * MAVLink 协议枚举常量（值取自官方 common.xml / minimal.xml，2026-09 版本）。
 * 骨架阶段只收录系统必需的子集，后续按需扩展。
 */
public final class MavEnums {

    private MavEnums() {
    }

    // ---- MAV_TYPE ----
    public static final int MAV_TYPE_GENERIC = 0;
    public static final int MAV_TYPE_QUADROTOR = 2;
    public static final int MAV_TYPE_HEXAROTOR = 13;

    // ---- MAV_AUTOPILOT ----
    public static final int MAV_AUTOPILOT_GENERIC = 0;
    public static final int MAV_AUTOPILOT_ARDUPILOTMEGA = 3;
    public static final int MAV_AUTOPILOT_PX4 = 12;

    // ---- MAV_MODE_FLAG（base_mode 位标志）----
    public static final int MAV_MODE_FLAG_CUSTOM_MODE_ENABLED = 1;
    public static final int MAV_MODE_FLAG_TEST_ENABLED = 2;
    public static final int MAV_MODE_FLAG_AUTO_ENABLED = 4;
    public static final int MAV_MODE_FLAG_GUIDED_ENABLED = 8;
    public static final int MAV_MODE_FLAG_STABILIZE_ENABLED = 16;
    public static final int MAV_MODE_FLAG_HIL_ENABLED = 32;
    public static final int MAV_MODE_FLAG_MANUAL_INPUT_ENABLED = 64;
    public static final int MAV_MODE_FLAG_SAFETY_ARMED = 128;

    // ---- MAV_STATE ----
    public static final int MAV_STATE_UNINIT = 0;
    public static final int MAV_STATE_BOOT = 1;
    public static final int MAV_STATE_CALIBRATING = 2;
    public static final int MAV_STATE_STANDBY = 3;
    public static final int MAV_STATE_ACTIVE = 4;
    public static final int MAV_STATE_CRITICAL = 5;
    public static final int MAV_STATE_EMERGENCY = 6;
    public static final int MAV_STATE_POWEROFF = 7;

    // ---- MAV_CMD ----
    public static final int MAV_CMD_NAV_WAYPOINT = 16;
    public static final int MAV_CMD_NAV_RETURN_TO_LAUNCH = 20;
    public static final int MAV_CMD_NAV_LAND = 21;
    public static final int MAV_CMD_NAV_TAKEOFF = 22;
    public static final int MAV_CMD_DO_SET_HOME = 179;
    public static final int MAV_CMD_DO_REPOSITION = 192;
    public static final int MAV_CMD_DO_SET_MODE = 176;
    /** MAV_CMD_DO_CHANGE_SPEED: param2=speed (m/s), -1=restore default. */
    public static final int MAV_CMD_DO_CHANGE_SPEED = 178;
    /** MAV_CMD_IMAGE_START_CAPTURE (camera protocol): take N photos. */
    public static final int MAV_CMD_IMAGE_START_CAPTURE = 2000;
    /** MAV_CMD_REQUEST_CAMERA_INFORMATION (camera protocol v2). */
    public static final int MAV_CMD_REQUEST_CAMERA_INFORMATION = 518;
    /** MAV_CMD_REQUEST_CAMERA_SETTINGS (camera protocol v2). */
    public static final int MAV_CMD_REQUEST_CAMERA_SETTINGS = 520;
    /** MAV_CMD_REQUEST_CAMERA_CAPTURE_STATUS (camera protocol v2). */
    public static final int MAV_CMD_REQUEST_CAMERA_CAPTURE_STATUS = 521;
    /** MAV_CMD_REQUEST_MESSAGE: param1 = message id to emit once. */
    public static final int MAV_CMD_REQUEST_MESSAGE = 512;
    /** MAV_CMD_SET_MESSAGE_INTERVAL: param1 = message id, param2 = interval us. */
    public static final int MAV_CMD_SET_MESSAGE_INTERVAL = 511;
    public static final int MAV_CMD_COMPONENT_ARM_DISARM = 400;
    public static final int MAV_CMD_MISSION_START = 300;

    // ---- MAV_FRAME ----
    public static final int MAV_FRAME_GLOBAL = 0;
    public static final int MAV_FRAME_LOCAL_NED = 1;
    public static final int MAV_FRAME_MISSION = 2;
    public static final int MAV_FRAME_GLOBAL_RELATIVE_ALT = 3;

    // ---- MAV_MISSION_RESULT ----
    public static final int MAV_MISSION_ACCEPTED = 0;
    public static final int MAV_MISSION_ERROR = 1;
    public static final int MAV_MISSION_UNSUPPORTED_FRAME = 2;
    public static final int MAV_MISSION_NO_SPACE = 4;
    public static final int MAV_MISSION_INVALID = 5;
    public static final int MAV_MISSION_DENIED = 14;
    public static final int MAV_MISSION_OPERATION_CANCELLED = 15;

    // ---- MAV_MISSION_TYPE ----
    public static final int MAV_MISSION_TYPE_MISSION = 0;

    // ---- MAV_SEVERITY（STATUSTEXT 级别）----
    public static final int MAV_SEVERITY_EMERGENCY = 0;
    public static final int MAV_SEVERITY_ALERT = 1;
    public static final int MAV_SEVERITY_CRITICAL = 2;
    public static final int MAV_SEVERITY_ERROR = 3;
    public static final int MAV_SEVERITY_WARNING = 4;
    public static final int MAV_SEVERITY_NOTICE = 5;
    public static final int MAV_SEVERITY_INFO = 6;
    public static final int MAV_SEVERITY_DEBUG = 7;

    // ---- MISSION_STATE（MISSION_CURRENT 扩展字段，官方枚举名为 MISSION_STATE_*）----
    public static final int MISSION_STATE_UNKNOWN = 0;
    public static final int MISSION_STATE_NO_MISSION = 1;
    public static final int MISSION_STATE_NOT_STARTED = 2;
    public static final int MISSION_STATE_ACTIVE = 3;
    public static final int MISSION_STATE_PAUSED = 4;
    public static final int MISSION_STATE_COMPLETE = 5;

    // ---- MAV_RESULT（COMMAND_ACK）----
    public static final int MAV_RESULT_ACCEPTED = 0;
    public static final int MAV_RESULT_TEMPORARILY_REJECTED = 1;
    public static final int MAV_RESULT_DENIED = 2;
    public static final int MAV_RESULT_UNSUPPORTED = 3;
    public static final int MAV_RESULT_FAILED = 4;
    public static final int MAV_RESULT_IN_PROGRESS = 5;

    // ---- MAV_GLOBAL_POSITION_INT 的 hdg 语义 ----
    public static final int HDG_UNKNOWN = 65535;
}

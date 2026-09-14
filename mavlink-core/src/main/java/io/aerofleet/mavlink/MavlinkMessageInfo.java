package io.aerofleet.mavlink;

import java.util.HashMap;
import java.util.Map;

/**
 * 常量表：各消息的长度与 CRC_EXTRA（对齐 MAVLink 官方 c_library_v2/common.xml 生成值）。
 * LEN 用官方定义的“完整长度”，消息发送按完整长度填满 payload；未知长度按 MIN_LEN 解析。
 */
public final class MavlinkMessageInfo {

    private static final class Info {
        final int length;
        final int crcExtra;

        Info(int length, int crcExtra) {
            this.length = length;
            this.crcExtra = crcExtra;
        }
    }

    private static final Map<Integer, Info> INFOS = new HashMap<>();

    static {
        // msgId -> (LEN, CRC_EXTRA)，数值提取自官方头文件（2026-09 版本）
        INFOS.put(0,   new Info(9, 50));      // HEARTBEAT
        INFOS.put(1,   new Info(43, 124));    // SYS_STATUS
        INFOS.put(2,   new Info(12, 137));   // SYSTEM_TIME
        INFOS.put(24,  new Info(52, 24));    // GPS_RAW_INT
        INFOS.put(30,  new Info(28, 39));    // ATTITUDE
        INFOS.put(33,  new Info(28, 104));   // GLOBAL_POSITION_INT
        INFOS.put(74,  new Info(20, 20));    // VFR_HUD
        INFOS.put(42,  new Info(18, 28));    // MISSION_CURRENT
        INFOS.put(43,  new Info(5, 230));   // MISSION_REQUEST (PX4 legacy)
        INFOS.put(44,  new Info(9, 221));    // MISSION_COUNT
        INFOS.put(47,  new Info(8, 153));    // MISSION_ACK
        INFOS.put(51,  new Info(5, 196));    // MISSION_REQUEST_INT
        INFOS.put(69,  new Info(26, 243));  // MANUAL_CONTROL
        INFOS.put(73,  new Info(38, 38));    // MISSION_ITEM_INT
        INFOS.put(259, new Info(237, 92)); // CAMERA_INFORMATION (min 235)
        INFOS.put(260, new Info(14, 146)); // CAMERA_SETTINGS (min 5)
        INFOS.put(262, new Info(23, 12));   // CAMERA_CAPTURE_STATUS (min 18)
        INFOS.put(263, new Info(255, 133)); // CAMERA_IMAGE_CAPTURED
        INFOS.put(271, new Info(53, 22));   // CAMERA_FOV_STATUS (min 52)
        INFOS.put(143, new Info(4, 132));   // MISSION_REQUEST_LIST
        INFOS.put(76,  new Info(33, 152));   // COMMAND_LONG
        INFOS.put(77,  new Info(10, 143));   // COMMAND_ACK
        INFOS.put(242, new Info(60, 104));   // HOME_POSITION
        INFOS.put(253, new Info(54, 83));    // STATUSTEXT
        INFOS.put(109, new Info(9, 88));     // RADIO_STATUS (crc_extra computed
                                            // per official message_checksum)
    }

    private MavlinkMessageInfo() {
    }

    public static boolean isKnown(int msgId) {
        return INFOS.containsKey(msgId);
    }

    public static int lengthOf(int msgId) {
        Info info = INFOS.get(msgId);
        return info != null ? info.length : -1;
    }

    public static int crcExtraOf(int msgId) {
        Info info = INFOS.get(msgId);
        if (info == null) {
            throw new MavlinkException("Unknown messageId " + msgId + ", no CRC_EXTRA available");
        }
        return info.crcExtra;
    }
}

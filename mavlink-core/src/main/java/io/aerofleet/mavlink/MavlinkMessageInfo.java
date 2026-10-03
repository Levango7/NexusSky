package io.aerofleet.mavlink;

import java.util.HashMap;
import java.util.Map;

/**
 * 常量表：各消息的长度与 CRC_EXTRA（对齐 MAVLink 官方 c_library_v2/common.xml 生成值）。
 * LEN 用官方定义的"完整长度"，消息发送按完整长度填满 payload；未知长度按 MIN_LEN 解析。
 *
 * msgId 0-511 的官方标准消息（0-271）使用固定数组 INFOS 直接索引（零开销）；
 * NexusSky 自定义扩展（私有区 30000-30099）与 OPEN_DRONE_ID_*（12900-12999）使用
 * EXTENDED_INFOS HashMap 扩展区。私有区选址依据官方治理规则：common.xml 拥有 300-10000
 * 分配带，私有方言应避开全部官方已分配区间（详见 docs/sitl-integration.md §7）。
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

    // 数组直接索引替代 HashMap：覆盖官方标准消息（msgId 0-271），512 为 2 的幂预留空间，
    // 消除 hash 计算与 Integer 装箱开销。
    private static final Info[] INFOS = new Info[512];

    // 扩展区：msgId ≥512 使用 HashMap，避免直接扩容数组至 30000+/13000+（内存浪费）。
    // 容纳 NexusSky 自定义扩展（私有区 30000-30099，2026-10 自 420-483 搬迁而来）与
    // MAVLink 官方 OPEN_DRONE_ID_* 消息族（msgId 12900-12999）。
    private static final Map<Integer, Info> EXTENDED_INFOS = new HashMap<>();

    static {
        // msgId -> (LEN, CRC_EXTRA)，数值提取自官方头文件（2026-09 版本）
        INFOS[0]   = new Info(9, 50);      // HEARTBEAT
        INFOS[1]   = new Info(43, 124);    // SYS_STATUS
        INFOS[2]   = new Info(12, 137);   // SYSTEM_TIME
        INFOS[24]  = new Info(52, 24);    // GPS_RAW_INT
        INFOS[30]  = new Info(28, 39);    // ATTITUDE
        INFOS[33]  = new Info(28, 104);   // GLOBAL_POSITION_INT
        INFOS[74]  = new Info(20, 20);    // VFR_HUD
        INFOS[42]  = new Info(18, 28);    // MISSION_CURRENT
        INFOS[43]  = new Info(3, 132);    // MISSION_REQUEST_LIST
        INFOS[44]  = new Info(9, 221);    // MISSION_COUNT
        INFOS[47]  = new Info(8, 153);    // MISSION_ACK
        INFOS[51]  = new Info(5, 196);    // MISSION_REQUEST_INT
        INFOS[69]  = new Info(26, 243);  // MANUAL_CONTROL
        INFOS[73]  = new Info(38, 38);    // MISSION_ITEM_INT
        INFOS[40]  = new Info(5, 230);    // MISSION_REQUEST (PX4 legacy)
        INFOS[259] = new Info(237, 92); // CAMERA_INFORMATION (min 235)
        INFOS[260] = new Info(14, 146); // CAMERA_SETTINGS (min 5)
        INFOS[262] = new Info(23, 12);   // CAMERA_CAPTURE_STATUS (min 18)
        INFOS[263] = new Info(255, 133); // CAMERA_IMAGE_CAPTURED
        INFOS[271] = new Info(53, 22);   // CAMERA_FOV_STATUS (min 52)
        // msgId=143 官方为 SCALED_PRESSURE3，本项目未实现，故不登记。
        // （此前误把 MISSION_REQUEST_LIST 登记在 143 上，而官方 143 是 SCALED_PRESSURE3，
        //   同时官方 MISSION_REQUEST_LIST 是 43、官方 MISSION_REQUEST 是 40 —— 三个 id 串了位。）
        INFOS[76]  = new Info(33, 152);   // COMMAND_LONG
        INFOS[77]  = new Info(10, 143);   // COMMAND_ACK
        INFOS[242] = new Info(60, 104);   // HOME_POSITION
        INFOS[253] = new Info(54, 83);    // STATUSTEXT
        INFOS[109] = new Info(9, 185);    // RADIO_STATUS（官方 CRC_EXTRA=185；
                                            // 原为 88，与 pymavlink 官方定义不符）
        EXTENDED_INFOS.put(30000, new Info(18, 72));   // LED_CONTROL (自定义扩展)
        // ---- NexusSky 自定义扩展消息（M0b 环境气象）----
        EXTENDED_INFOS.put(30002, new Info(13, 249));  // ENVIRONMENT_STATUS (crc_extra computed
                                              // per official message_checksum)
        EXTENDED_INFOS.put(30001, new Info(46, 230));  // ENVIRONMENT_ALERT (crc_extra computed
                                              // per official message_checksum)
        // ---- NexusSky 自定义扩展消息（M2 喷洒物流，msgId 30003-30006，FR-26~FR-29）----
        EXTENDED_INFOS.put(30003, new Info(12, 3));  // SPRAY_STATUS (crc_extra computed
                                              // per MavlinkCrc on msg name + fields)
        EXTENDED_INFOS.put(30004, new Info(6, 124));   // SPRAY_COMMAND
        EXTENDED_INFOS.put(30005, new Info(7, 166));   // GRIPPER_COMMAND
        EXTENDED_INFOS.put(30006, new Info(10, 153));   // PAYLOAD_STATUS
        // ---- NexusSky 自定义扩展消息（M3 感知成像增强，msgId 30010-30014）----
        EXTENDED_INFOS.put(30010, new Info(20, 65));   // OBSTACLE_REPORT
        EXTENDED_INFOS.put(30011, new Info(24, 100));   // MULTISPECTRAL_DATA
        EXTENDED_INFOS.put(30012, new Info(24, 255));   // THERMAL_DATA
        EXTENDED_INFOS.put(30013, new Info(20, 171));   // DEPTH_DATA
        EXTENDED_INFOS.put(30014, new Info(20, 100));   // VISION_DETECTION
        // ---- NexusSky 自定义扩展消息（M4 硬件抽象，msgId 30017-30021）----
        EXTENDED_INFOS.put(30017, new Info(20, 251));   // RADAR_SCAN
        EXTENDED_INFOS.put(30018, new Info(28, 152));   // RADAR_TARGET
        EXTENDED_INFOS.put(30019, new Info(24, 112));   // ROTOR_TELEMETRY
        EXTENDED_INFOS.put(30020, new Info(20, 153));   // LIDAR_DATA
        EXTENDED_INFOS.put(30021, new Info(41, 150));   // IMU_DATA
        // ---- NexusSky 自定义扩展消息（M5 应急 mesh 自愈组网，msgId 30030-30034）----
        EXTENDED_INFOS.put(30030, new Info(24, 193));   // MESH_HEARTBEAT
        EXTENDED_INFOS.put(30031, new Info(12, 54));   // MESH_ROUTE_REQUEST
        EXTENDED_INFOS.put(30032, new Info(10, 14));   // MESH_ROUTE_REPLY
        EXTENDED_INFOS.put(30033, new Info(4, 124));    // MESH_ROUTE_ERROR
        EXTENDED_INFOS.put(30034, new Info(-1, 113));   // MESH_NEIGHBOR_TABLE (可变长度，LEN=-1)
        // ---- NexusSky 自定义扩展消息（M6 移动基站载荷抽象，msgId 30035-30038）----
        EXTENDED_INFOS.put(30035, new Info(15, 21));   // CELL_TOWER_STATUS
        EXTENDED_INFOS.put(30036, new Info(7, 190));    // CELL_TOWER_CONFIG
        EXTENDED_INFOS.put(30037, new Info(5, 247));    // CELL_HANDOVER
        EXTENDED_INFOS.put(30038, new Info(12, 83));   // GROUND_TERMINAL_REGISTER
        // ---- NexusSky 自定义扩展消息（M7 星-空-地多层级中继，msgId 30039-30041）----
        EXTENDED_INFOS.put(30039, new Info(24, 152));   // SAT_LINK_STATUS
        EXTENDED_INFOS.put(30040, new Info(16, 155));   // SAT_PASS_SCHEDULE
        EXTENDED_INFOS.put(30041, new Info(34, 217));   // HIERARCHICAL_ROUTE_DECISION
        // ---- NexusSky 自定义扩展消息（M8 复杂地形适配，msgId 30042-30044）----
        EXTENDED_INFOS.put(30042, new Info(-1, 107));   // TERRAIN_TYPE_MAP (可变长度，LEN=-1)
        EXTENDED_INFOS.put(30043, new Info(-1, 21));   // TERRAIN_UPDATE (可变长度，LEN=-1)
        EXTENDED_INFOS.put(30044, new Info(-1, 61));   // FLIGHT_RESTRICTION (可变长度，LEN=-1)
        // ---- NexusSky 自定义扩展消息（M9 应急任务编排，msgId 30045-30047）----
        EXTENDED_INFOS.put(30045, new Info(25, 106));   // EMERGENCY_MISSION_PLAN
        EXTENDED_INFOS.put(30046, new Info(24, 229));   // COVERAGE_OPTIMIZATION
        EXTENDED_INFOS.put(30047, new Info(50, 208));   // EMERGENCY_PRIORITY
        // ---- NexusSky 自定义扩展消息（M10 多机协同任务分配，msgId 30048-30050）----
        EXTENDED_INFOS.put(30048, new Info(18, 144));   // TASK_ASSIGNMENT
        EXTENDED_INFOS.put(30049, new Info(12, 86));   // CONFLICT_ALERT
        EXTENDED_INFOS.put(30050, new Info(11, 104));   // TASK_STATUS
        // ---- NexusSky 自定义扩展消息（M11 自主决策，msgId 30051-30052）----
        EXTENDED_INFOS.put(30051, new Info(15, 104));   // DECISION_EVENT
        EXTENDED_INFOS.put(30052, new Info(20, 208));   // ADAPTIVE_PATH
        // ---- NexusSky 自定义扩展消息（M12 边缘计算与传感器融合，msgId 30053-30054）----
        EXTENDED_INFOS.put(30053, new Info(13, 46));   // EDGE_TASK_STATUS
        EXTENDED_INFOS.put(30054, new Info(24, 39));   // SENSOR_FUSION_DATA
        // ---- NexusSky 自定义扩展消息（M13 数字孪生与轨迹预测，msgId 30055-30056）----
        EXTENDED_INFOS.put(30055, new Info(28, 208));   // TWIN_STATE_SYNC
        EXTENDED_INFOS.put(30056, new Info(20, 139));   // PREDICTION_RESULT
        // ---- NexusSky 自定义扩展消息（M14 安防报警，msgId 30057-30059）----
        EXTENDED_INFOS.put(30057, new Info(68, 64));   // ALARM_TRIGGER
        EXTENDED_INFOS.put(30058, new Info(12, 200));   // ALARM_ACK
        EXTENDED_INFOS.put(30059, new Info(14, 184));   // SURVEILLANCE_STATUS
        // ---- NexusSky 自定义扩展消息（P2 灾害应急通讯组网，msgId 30060-30062）----
        EXTENDED_INFOS.put(30060, new Info(12, 121));   // QOS_ROUTE_DECISION
        EXTENDED_INFOS.put(30061, new Info(18, 241));   // CLUSTER_FORMATION
        EXTENDED_INFOS.put(30062, new Info(8, 137));    // DISASTER_MODE_STATUS
        // ---- NexusSky 自定义扩展消息（P3 灾害应急搜救信号，msgId=30063）----
        EXTENDED_INFOS.put(30063, new Info(7, 94));    // BUZZER_CONTROL
        // ---- C2 开放无人机标识（OPEN_DRONE_ID_*，msgId=12900-12915）----
        // OPEN_DRONE_ID 与上方 30000-30099 私有区同属 EXTENDED_INFOS 扩展区
        EXTENDED_INFOS.put(12900, new Info(22, 223));   // OPEN_DRONE_ID_BASIC_ID
        EXTENDED_INFOS.put(12901, new Info(36, 234));   // OPEN_DRONE_ID_LOCATION
        EXTENDED_INFOS.put(12903, new Info(24, 200));   // OPEN_DRONE_ID_SELF_ID
        EXTENDED_INFOS.put(12904, new Info(23, 233));   // OPEN_DRONE_ID_SYSTEM
        EXTENDED_INFOS.put(12905, new Info(21, 225));   // OPEN_DRONE_ID_OPERATOR_ID
        EXTENDED_INFOS.put(12915, new Info(252, 109));  // OPEN_DRONE_ID_MESSAGE_PACK
    }

    private MavlinkMessageInfo() {
    }

    public static boolean isKnown(int msgId) {
        if (msgId >= 0 && msgId < INFOS.length) {
            return INFOS[msgId] != null;
        }
        return EXTENDED_INFOS.containsKey(msgId);
    }

    public static int lengthOf(int msgId) {
        Info info = (msgId >= 0 && msgId < INFOS.length) ? INFOS[msgId] : EXTENDED_INFOS.get(msgId);
        return info != null ? info.length : -1;
    }

    public static int crcExtraOf(int msgId) {
        Info info = (msgId >= 0 && msgId < INFOS.length) ? INFOS[msgId] : EXTENDED_INFOS.get(msgId);
        if (info == null) {
            throw new MavlinkException("Unknown messageId " + msgId + ", no CRC_EXTRA available");
        }
        return info.crcExtra;
    }
}

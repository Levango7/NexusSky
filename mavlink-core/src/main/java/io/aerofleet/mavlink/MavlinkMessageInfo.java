package io.aerofleet.mavlink;

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

    // 数组直接索引替代 HashMap：msgId 范围 0-467，512 为 2 的幂预留扩展空间，
    // 消除 hash 计算与 Integer 装箱开销。
    private static final Info[] INFOS = new Info[512];

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
        INFOS[43]  = new Info(5, 230);   // MISSION_REQUEST (PX4 legacy)
        INFOS[44]  = new Info(9, 221);    // MISSION_COUNT
        INFOS[47]  = new Info(8, 153);    // MISSION_ACK
        INFOS[51]  = new Info(5, 196);    // MISSION_REQUEST_INT
        INFOS[69]  = new Info(26, 243);  // MANUAL_CONTROL
        INFOS[73]  = new Info(38, 38);    // MISSION_ITEM_INT
        INFOS[259] = new Info(237, 92); // CAMERA_INFORMATION (min 235)
        INFOS[260] = new Info(14, 146); // CAMERA_SETTINGS (min 5)
        INFOS[262] = new Info(23, 12);   // CAMERA_CAPTURE_STATUS (min 18)
        INFOS[263] = new Info(255, 133); // CAMERA_IMAGE_CAPTURED
        INFOS[271] = new Info(53, 22);   // CAMERA_FOV_STATUS (min 52)
        INFOS[143] = new Info(4, 132);   // MISSION_REQUEST_LIST
        INFOS[76]  = new Info(33, 152);   // COMMAND_LONG
        INFOS[77]  = new Info(10, 143);   // COMMAND_ACK
        INFOS[242] = new Info(60, 104);   // HOME_POSITION
        INFOS[253] = new Info(54, 83);    // STATUSTEXT
        INFOS[109] = new Info(9, 88);     // RADIO_STATUS (crc_extra computed
                                            // per official message_checksum)
        INFOS[420] = new Info(18, 233);   // LED_CONTROL_MSG (自定义扩展)
        // ---- NexusSky 自定义扩展消息（M0b 环境气象）----
        INFOS[422] = new Info(13, 36086);  // ENVIRONMENT_STATUS (crc_extra computed
                                              // per official message_checksum)
        INFOS[421] = new Info(46, 25705);  // ENVIRONMENT_ALERT (crc_extra computed
                                              // per official message_checksum)
        // ---- NexusSky 自定义扩展消息（M2 喷洒物流，msgId 423-426，FR-26~FR-29）----
        INFOS[423] = new Info(12, 58864);  // SPRAY_STATUS (crc_extra computed
                                              // per MavlinkCrc on msg name + fields)
        INFOS[424] = new Info(6, 52077);   // SPRAY_COMMAND
        INFOS[425] = new Info(7, 46389);   // GRIPPER_COMMAND
        INFOS[426] = new Info(10, 9268);   // PAYLOAD_STATUS
        // ---- NexusSky 自定义扩展消息（M3 感知成像增强，msgId 430-434）----
        INFOS[430] = new Info(20, 201);   // OBSTACLE_REPORT
        INFOS[431] = new Info(24, 202);   // MULTISPECTRAL_DATA
        INFOS[432] = new Info(24, 203);   // THERMAL_DATA
        INFOS[433] = new Info(20, 204);   // DEPTH_DATA
        INFOS[434] = new Info(20, 205);   // VISION_DETECTION
        // ---- NexusSky 自定义扩展消息（M4 硬件抽象，msgId 437-441）----
        INFOS[437] = new Info(20, 211);   // RADAR_SCAN
        INFOS[438] = new Info(28, 212);   // RADAR_TARGET
        INFOS[439] = new Info(24, 213);   // ROTOR_TELEMETRY
        INFOS[440] = new Info(20, 214);   // LIDAR_DATA
        INFOS[441] = new Info(41, 215);   // IMU_DATA
        // ---- NexusSky 自定义扩展消息（M5 应急 mesh 自愈组网，msgId 450-454）----
        INFOS[450] = new Info(24, 233);   // MESH_HEARTBEAT
        INFOS[451] = new Info(12, 234);   // MESH_ROUTE_REQUEST
        INFOS[452] = new Info(10, 235);   // MESH_ROUTE_REPLY
        INFOS[453] = new Info(4, 236);    // MESH_ROUTE_ERROR
        INFOS[454] = new Info(-1, 237);   // MESH_NEIGHBOR_TABLE (可变长度，LEN=-1)
        // ---- NexusSky 自定义扩展消息（M6 移动基站载荷抽象，msgId 455-458）----
        INFOS[455] = new Info(15, 245);   // CELL_TOWER_STATUS
        INFOS[456] = new Info(7, 246);    // CELL_TOWER_CONFIG
        INFOS[457] = new Info(5, 247);    // CELL_HANDOVER
        INFOS[458] = new Info(12, 248);   // GROUND_TERMINAL_REGISTER
        // ---- NexusSky 自定义扩展消息（M7 星-空-地多层级中继，msgId 459-461）----
        INFOS[459] = new Info(24, 238);   // SAT_LINK_STATUS
        INFOS[460] = new Info(16, 239);   // SAT_PASS_SCHEDULE
        INFOS[461] = new Info(34, 240);   // HIERARCHICAL_ROUTE_DECISION
        // ---- NexusSky 自定义扩展消息（M8 复杂地形适配，msgId 462-464）----
        INFOS[462] = new Info(-1, 242);   // TERRAIN_TYPE_MAP (可变长度，LEN=-1)
        INFOS[463] = new Info(-1, 243);   // TERRAIN_UPDATE (可变长度，LEN=-1)
        INFOS[464] = new Info(-1, 244);   // FLIGHT_RESTRICTION (可变长度，LEN=-1)
        // ---- NexusSky 自定义扩展消息（M9 应急任务编排，msgId 465-467）----
        INFOS[465] = new Info(25, 249);   // EMERGENCY_MISSION_PLAN
        INFOS[466] = new Info(24, 250);   // COVERAGE_OPTIMIZATION
        INFOS[467] = new Info(50, 251);   // EMERGENCY_PRIORITY
        // ---- NexusSky 自定义扩展消息（M10 多机协同任务分配，msgId 468-470）----
        INFOS[468] = new Info(18, 252);   // TASK_ASSIGNMENT
        INFOS[469] = new Info(12, 253);   // CONFLICT_ALERT
        INFOS[470] = new Info(11, 254);   // TASK_STATUS
        // ---- NexusSky 自定义扩展消息（M11 自主决策，msgId 471-472）----
        INFOS[471] = new Info(15, 255);   // DECISION_EVENT
        INFOS[472] = new Info(20, 256);   // ADAPTIVE_PATH
        // ---- NexusSky 自定义扩展消息（M12 边缘计算与传感器融合，msgId 473-474）----
        INFOS[473] = new Info(13, 257);   // EDGE_TASK_STATUS
        INFOS[474] = new Info(24, 258);   // SENSOR_FUSION_DATA
        // ---- NexusSky 自定义扩展消息（M13 数字孪生与轨迹预测，msgId 475-476）----
        INFOS[475] = new Info(28, 259);   // TWIN_STATE_SYNC
        INFOS[476] = new Info(20, 260);   // PREDICTION_RESULT
        // ---- NexusSky 自定义扩展消息（M14 安防报警，msgId 477-479）----
        INFOS[477] = new Info(68, 261);   // ALARM_TRIGGER
        INFOS[478] = new Info(12, 262);   // ALARM_ACK
        INFOS[479] = new Info(14, 263);   // SURVEILLANCE_STATUS
        // ---- NexusSky 自定义扩展消息（P2 灾害应急通讯组网，msgId 480-482）----
        INFOS[480] = new Info(12, 264);   // QOS_ROUTE_DECISION
        INFOS[481] = new Info(18, 265);   // CLUSTER_FORMATION
        INFOS[482] = new Info(8, 266);    // DISASTER_MODE_STATUS
        // ---- NexusSky 自定义扩展消息（P3 灾害应急搜救信号，msgId=483）----
        INFOS[483] = new Info(7, 267);    // BUZZER_CONTROL
    }

    private MavlinkMessageInfo() {
    }

    public static boolean isKnown(int msgId) {
        return msgId >= 0 && msgId < INFOS.length && INFOS[msgId] != null;
    }

    public static int lengthOf(int msgId) {
        Info info = (msgId >= 0 && msgId < INFOS.length) ? INFOS[msgId] : null;
        return info != null ? info.length : -1;
    }

    public static int crcExtraOf(int msgId) {
        Info info = (msgId >= 0 && msgId < INFOS.length) ? INFOS[msgId] : null;
        if (info == null) {
            throw new MavlinkException("Unknown messageId " + msgId + ", no CRC_EXTRA available");
        }
        return info.crcExtra;
    }
}

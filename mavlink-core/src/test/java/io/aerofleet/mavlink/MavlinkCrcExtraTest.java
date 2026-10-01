package io.aerofleet.mavlink;

import io.aerofleet.mavlink.MavlinkMessageChecksum.Field;
import io.aerofleet.mavlink.MavlinkMessageChecksum.FieldType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.aerofleet.mavlink.MavlinkMessageChecksum.FieldType.CHAR;
import static io.aerofleet.mavlink.MavlinkMessageChecksum.FieldType.FLOAT;
import static io.aerofleet.mavlink.MavlinkMessageChecksum.FieldType.INT16;
import static io.aerofleet.mavlink.MavlinkMessageChecksum.FieldType.INT32;
import static io.aerofleet.mavlink.MavlinkMessageChecksum.FieldType.INT8;
import static io.aerofleet.mavlink.MavlinkMessageChecksum.FieldType.UINT16;
import static io.aerofleet.mavlink.MavlinkMessageChecksum.FieldType.UINT32;
import static io.aerofleet.mavlink.MavlinkMessageChecksum.FieldType.UINT64;
import static io.aerofleet.mavlink.MavlinkMessageChecksum.FieldType.UINT8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自定义消息 CRC_EXTRA 的一致性守卫。
 * <p>
 * 背景：CRC_EXTRA 是消息「签名」的函数（见 {@link MavlinkMessageChecksum}）。若填一个
 * 与字段定义无关的常数，两份字段布局完全不同的实现只要抄同一个数就能通过帧 CRC 校验，
 * 该机制形同虚设——本仓库此前的自定义消息正是手写序数（201..267）。
 * <p>
 * 本测试把每个自定义消息的字段签名（从 Javadoc 布局表 + {@code encode()} 偏移提取）钉在
 * 下方 {@link #SIGNATURES} 里，用 {@link MavlinkMessageChecksum} 独立重算，再与
 * {@link MavlinkMessageInfo} 的线上常量比对。**改了字段却没重算 CRC_EXTRA，构建会红。**
 * <p>
 * SIGNATURES 由 {@code scripts/mavlink-crc-extra-gen.py --emit-java} 生成，勿手改。
 * 该生成器自身已被双重验证：用本仓库算法重算 23 条标准 MAVLink 消息与 pymavlink 官方值
 * 逐条相等（证明算法实现正确），且 51 条自定义消息与 pymavlink {@code message_checksum}
 * 重算结果全部一致（证明字段提取正确）。
 */
class MavlinkCrcExtraTest {

    // 生成器输出的短别名，纯粹为了 SIGNATURES 可读
    private static Field F(FieldType t, String name) {
        return new Field(t, name, 0);
    }

    private static Field F(FieldType t, String name, int arrayLength) {
        return new Field(t, name, arrayLength);
    }

    private static final FieldType U8 = UINT8;
    private static final FieldType I8 = INT8;
    private static final FieldType U16 = UINT16;
    private static final FieldType I16 = INT16;
    private static final FieldType U32 = UINT32;
    private static final FieldType I32 = INT32;
    private static final FieldType U64 = UINT64;
    private static final FieldType F32 = FLOAT;
    private static final FieldType CH = CHAR;

    /** {@code {msgId, 消息名, 字段签名}}；由 scripts/mavlink-crc-extra-gen.py --emit-java 生成。 */
    private static final Object[][] SIGNATURES = {
        {420, "LED_CONTROL", new Field[]{F(U8, "colorR"), F(U8, "colorG"), F(U8, "colorB"), F(U8, "pattern"), F(U8, "brightness"), F(U8, "freq"), F(U8, "flags"), F(U32, "phaseStartUs"), F(U8, "targetSystem"), F(U8, "targetComp"), F(U8, "reserved"), F(U8, "ledIndex"), F(U16, "transitionMs"), F(U8, "reserved2")}},
        {421, "ENVIRONMENT_ALERT", new Field[]{F(U8, "alertType"), F(U8, "severity"), F(I16, "value"), F(I16, "threshold"), F(CH, "text", 40)}},
        {422, "ENVIRONMENT_STATUS", new Field[]{F(U8, "humidity"), F(U8, "weather"), F(U8, "rainRate"), F(I16, "temperature"), F(U16, "windSpeed"), F(U16, "windDirection"), F(I16, "gust"), F(U16, "visibility")}},
        {423, "SPRAY_STATUS", new Field[]{F(U8, "enabled"), F(U16, "rate"), F(U16, "remainingChemical"), F(U8, "coveragePercent"), F(U8, "lowChemical"), F(I16, "driftOffsetAngle"), F(U8, "flowCorrectionPercent"), F(U8, "reserved1"), F(U8, "reserved2")}},
        {424, "SPRAY_COMMAND", new Field[]{F(U8, "command"), F(U16, "targetRate"), F(U16, "sprayWidth"), F(U8, "reserved")}},
        {425, "GRIPPER_COMMAND", new Field[]{F(U8, "command"), F(U8, "payloadId"), F(U16, "payloadWeight"), F(U16, "payloadVolume"), F(U8, "reserved")}},
        {426, "PAYLOAD_STATUS", new Field[]{F(U8, "gripperState"), F(U16, "currentPayloadWeight"), F(U16, "currentPayloadVolume"), F(U8, "remainingSites"), F(U8, "currentSiteIndex"), F(U16, "dropAccuracyCm"), F(U8, "reserved")}},
        {430, "OBSTACLE_REPORT", new Field[]{F(F32, "distance"), F(F32, "direction"), F(U32, "timestamp"), F(U8, "threat"), F(U8, "type"), F(U8, "sysid"), F(U8, "reserved"), F(U16, "reserved2"), F(U8, "reserved3", 2)}},
        {431, "MULTISPECTRAL_DATA", new Field[]{F(F32, "ndviMean"), F(F32, "ndviMin"), F(F32, "ndviMax"), F(F32, "vegetationCoverage"), F(U32, "timestamp"), F(U8, "sysid"), F(U8, "reserved"), F(U16, "reserved2")}},
        {432, "THERMAL_DATA", new Field[]{F(F32, "tempMean"), F(F32, "tempMin"), F(F32, "tempMax"), F(F32, "tempStdDev"), F(U32, "timestamp"), F(U8, "hotspotCount"), F(U8, "sysid"), F(U16, "reserved")}},
        {433, "DEPTH_DATA", new Field[]{F(F32, "nearestDistance"), F(F32, "nearestDirection"), F(F32, "pointCloudDensity"), F(U32, "pointCount"), F(U8, "sysid"), F(U8, "reserved"), F(U16, "reserved2")}},
        {434, "VISION_DETECTION", new Field[]{F(F32, "u"), F(F32, "v"), F(F32, "confidence"), F(U8, "kind"), F(U8, "trackId"), F(U8, "sysid"), F(U32, "timestamp"), F(U8, "reserved")}},
        {437, "RADAR_SCAN", new Field[]{F(U8, "mode"), F(F32, "beamAzim"), F(F32, "beamElev"), F(U16, "scanPeriodMs"), F(U8, "targetCount"), F(U8, "sysid"), F(U32, "timestamp"), F(U8, "reserved"), F(U16, "reserved2")}},
        {438, "RADAR_TARGET", new Field[]{F(U16, "targetId"), F(F32, "distance"), F(F32, "azimDeg"), F(F32, "elevDeg"), F(F32, "radialVelocity"), F(F32, "rcs"), F(U8, "trackState"), F(U8, "sysid"), F(U32, "timestamp")}},
        {439, "ROTOR_TELEMETRY", new Field[]{F(U8, "rotorIndex"), F(F32, "rpm"), F(F32, "thrust"), F(F32, "power"), F(F32, "totalThrust"), F(F32, "totalPower"), F(U8, "sysid"), F(U16, "reserved")}},
        {440, "LIDAR_DATA", new Field[]{F(F32, "nearestDistance"), F(U32, "pointCount"), F(F32, "density"), F(F32, "avgIntensity"), F(U8, "sysid"), F(U8, "reserved"), F(U16, "reserved2")}},
        {441, "IMU_DATA", new Field[]{F(F32, "accelX"), F(F32, "accelY"), F(F32, "accelZ"), F(F32, "gyroX"), F(F32, "gyroY"), F(F32, "gyroZ"), F(F32, "magX"), F(F32, "magY"), F(F32, "magZ"), F(F32, "tempC"), F(U8, "sysid")}},
        {450, "MESH_HEARTBEAT", new Field[]{F(U8, "sysid"), F(I32, "lat"), F(I32, "lon"), F(I32, "alt"), F(U8, "batteryPercent"), F(U8, "neighborCount"), F(U32, "timestamp"), F(U8, "reserved"), F(U32, "reserved2")}},
        {451, "MESH_ROUTE_REQUEST", new Field[]{F(U8, "sourceSysid"), F(U8, "targetSysid"), F(U16, "broadcastId"), F(U8, "hopCount"), F(U16, "originMetric"), F(U32, "timestamp"), F(U8, "reserved")}},
        {452, "MESH_ROUTE_REPLY", new Field[]{F(U8, "sourceSysid"), F(U8, "targetSysid"), F(U8, "hopCount"), F(U16, "metric"), F(U32, "timestamp"), F(U8, "reserved")}},
        {453, "MESH_ROUTE_ERROR", new Field[]{F(U8, "unreachableSysid"), F(U8, "hopCount"), F(U16, "timestamp")}},
        {454, "MESH_NEIGHBOR_TABLE", new Field[]{F(U8, "sysid"), F(U8, "neighborCount"), F(U32, "timestamp"), F(U8, "neighborsSysid", 255), F(I8, "neighborsRssi", 255), F(U8, "neighborsLinkQuality", 255), F(U8, "neighborsReserved", 255)}},
        {455, "CELL_TOWER_STATUS", new Field[]{F(U8, "sysid"), F(U8, "cellType"), F(I32, "centerLat"), F(I32, "centerLon"), F(U16, "coverageRadiusM"), F(U16, "connectedTerminals"), F(U8, "capacityUtilization")}},
        {456, "CELL_TOWER_CONFIG", new Field[]{F(U8, "sysid"), F(U8, "cellType"), F(I8, "txPowerDbm"), F(U16, "maxTerminals"), F(U16, "frequencyChannel")}},
        {457, "CELL_HANDOVER", new Field[]{F(U16, "terminalId"), F(U8, "fromSysid"), F(U8, "toSysid"), F(U8, "handoverReason")}},
        {458, "GROUND_TERMINAL_REGISTER", new Field[]{F(U16, "terminalId"), F(U8, "terminalType"), F(I32, "gpsLat"), F(I32, "gpsLon"), F(U8, "requestedSysid")}},
        {459, "SAT_LINK_STATUS", new Field[]{F(U16, "satId"), F(U8, "visible"), F(U8, "elevationDeg"), F(U16, "azimuthDeg"), F(U16, "delayMs"), F(U8, "bandwidthMbps"), F(U32, "windowEndMs"), F(U8, "sharedUsers"), F(U32, "timestamp"), F(U8, "simFlag"), F(U8, "reserved"), F(U32, "reserved2")}},
        {460, "SAT_PASS_SCHEDULE", new Field[]{F(U16, "satId"), F(U32, "passStartMs"), F(U32, "passEndMs"), F(U8, "maxElevationDeg"), F(U8, "groundPointId"), F(U32, "timestamp")}},
        {461, "HIERARCHICAL_ROUTE_DECISION", new Field[]{F(U8, "sourceLayer"), F(U8, "targetLayer"), F(U8, "chosenLayer"), F(U16, "estimatedDelayMs"), F(U8, "pathHopCount"), F(U8, "pathNodes", 8), F(U8, "strategy"), F(U8, "reasonLen"), F(CH, "reasonChars", 14), F(U32, "timestamp")}},
        {462, "TERRAIN_TYPE_MAP", new Field[]{F(I32, "mapOriginLat"), F(I32, "mapOriginLon"), F(U16, "gridResolution"), F(U16, "mapWidth"), F(U16, "mapHeight"), F(U8, "gridCells", 255)}},
        {463, "TERRAIN_UPDATE", new Field[]{F(U32, "terrainVersion"), F(U8, "changeReason"), F(U16, "affectedCount"), F(U16, "reserved"), F(U16, "affectedCellsGridIndex", 255), F(U8, "affectedCellsNewTerrainType", 255), F(U8, "affectedCellsReserved", 255)}},
        {464, "FLIGHT_RESTRICTION", new Field[]{F(U8, "restrictionType"), F(F32, "limitValue"), F(U8, "vertexCount"), F(U16, "reserved"), F(I32, "areaLat", 255), F(I32, "areaLon", 255)}},
        {465, "EMERGENCY_MISSION_PLAN", new Field[]{F(U32, "planId"), F(U8, "scenarioType"), F(U8, "phase"), F(U8, "phaseStatus"), F(I32, "disasterCenterLat"), F(I32, "disasterCenterLon"), F(U16, "disasterRadius"), F(U8, "droneCount"), F(U8, "coverageRate"), F(U8, "connectRate"), F(U8, "priority"), F(U32, "timestamp")}},
        {466, "COVERAGE_OPTIMIZATION", new Field[]{F(U32, "planId"), F(U8, "droneId"), F(I32, "targetLat"), F(I32, "targetLon"), F(I16, "targetAlt"), F(U8, "cellType"), F(U8, "relayRole"), F(U8, "txPower"), F(U8, "expectedCoverage"), F(U8, "batteryBudget"), F(U32, "timestamp")}},
        {467, "EMERGENCY_PRIORITY", new Field[]{F(U32, "planId"), F(U32, "taskId"), F(U8, "priority"), F(U8, "action"), F(U32, "preemptedTaskId"), F(CH, "reason", 32), F(U32, "timestamp")}},
        {468, "TASK_ASSIGNMENT", new Field[]{F(U32, "taskId"), F(I32, "targetLat"), F(I32, "targetLon"), F(I16, "targetAlt"), F(U8, "sysId"), F(U8, "taskType"), F(U8, "priority"), F(U8, "assignedSysId")}},
        {469, "CONFLICT_ALERT", new Field[]{F(F32, "minDistance"), F(F32, "timeToConflict"), F(U8, "sysId"), F(U8, "conflictType"), F(U8, "conflictingSysId"), F(U8, "severity")}},
        {470, "TASK_STATUS", new Field[]{F(U32, "taskId"), F(U32, "timestamp"), F(U8, "sysId"), F(U8, "status"), F(U8, "progressPercent")}},
        {471, "DECISION_EVENT", new Field[]{F(F32, "triggerValue"), F(F32, "confidence"), F(U32, "timestamp"), F(U8, "sysId"), F(U8, "decisionType"), F(U8, "reason")}},
        {472, "ADAPTIVE_PATH", new Field[]{F(I32, "newLat"), F(I32, "newLon"), F(F32, "windSpeed"), F(U16, "originalWaypointSeq"), F(I16, "newAlt"), F(U16, "windDirection"), F(U8, "sysId"), F(U8, "adjustmentReason")}},
        {473, "EDGE_TASK_STATUS", new Field[]{F(U32, "edgeTaskId"), F(U32, "processingTimeMs"), F(U16, "resultSize"), F(U8, "sysId"), F(U8, "taskType"), F(U8, "status")}},
        {474, "SENSOR_FUSION_DATA", new Field[]{F(I32, "fusedLat"), F(I32, "fusedLon"), F(I32, "fusedAlt"), F(F32, "fusedVelocity"), F(F32, "accuracy"), F(U16, "fusedHeading"), F(U8, "sysId"), F(U8, "sensorMask")}},
        {475, "TWIN_STATE_SYNC", new Field[]{F(I32, "twinLat"), F(I32, "twinLon"), F(I32, "twinAlt"), F(U32, "syncTimestamp"), F(F32, "twinVelocity"), F(F32, "driftMeters"), F(U16, "twinHeading"), F(U8, "sysId"), F(U8, "twinBattery")}},
        {476, "PREDICTION_RESULT", new Field[]{F(I32, "predictedLat"), F(I32, "predictedLon"), F(I32, "predictedAlt"), F(F32, "confidence"), F(U16, "predictionHorizonSec"), F(U8, "sysId"), F(U8, "trajectoryPoints")}},
        {477, "ALARM_TRIGGER", new Field[]{F(U32, "timestamp"), F(I32, "lat"), F(I32, "lon"), F(U16, "sourceDeviceId"), F(I16, "alt"), F(U8, "alarmType"), F(U8, "severity"), F(CH, "description", 50)}},
        {478, "ALARM_ACK", new Field[]{F(U32, "alarmId"), F(U32, "timestamp"), F(U16, "estimatedArrivalSec"), F(U8, "droneSysid"), F(U8, "ackResult")}},
        {479, "SURVEILLANCE_STATUS", new Field[]{F(U32, "lastEventMs"), F(U32, "uptimeSec"), F(U16, "deviceId"), F(U8, "deviceType"), F(U8, "status"), F(U8, "onlineCameras"), F(U8, "totalCameras")}},
        {480, "QOS_ROUTE_DECISION", new Field[]{F(U32, "timestamp"), F(U16, "bandwidthAlloc"), F(U8, "routeId"), F(U8, "priorityClass"), F(U8, "sourceSysid"), F(U8, "targetSysid"), F(U8, "reserved", 2)}},
        {481, "CLUSTER_FORMATION", new Field[]{F(U32, "timestamp"), F(U16, "clusterRadius"), F(U8, "clusterId"), F(U8, "clusterHead"), F(U8, "members", 8), F(U8, "memberCount"), F(U8, "reserved")}},
        {482, "DISASTER_MODE_STATUS", new Field[]{F(U32, "timestamp"), F(U8, "mode"), F(U8, "triggerReason"), F(U8, "affectedNodes"), F(U8, "recoveryRate")}},
        {483, "BUZZER_CONTROL", new Field[]{F(U8, "sysid"), F(U8, "on"), F(U8, "pattern"), F(U8, "volume"), F(U16, "durationSec"), F(U8, "reserved")}},
    };

    static Stream<Object[]> customMessages() {
        return Stream.of(SIGNATURES);
    }

    private static int expected(int msgId) {
        return MavlinkMessageInfo.crcExtraOf(msgId);
    }

    @ParameterizedTest(name = "{1} (msgId={0})")
    @MethodSource("customMessages")
    @DisplayName("常量表 CRC_EXTRA == 按字段签名重算值")
    void tableMatchesSignature(int msgId, String name, Field[] fields) {
        int computed = MavlinkMessageChecksum.crcExtra(name, fields);
        assertEquals(computed, expected(msgId),
                name + " (msgId=" + msgId + ") 的 CRC_EXTRA 常量与字段签名不符："
                        + "改了字段就必须用 scripts/mavlink-crc-extra-gen.py 重算并同步常量");
    }

    @ParameterizedTest(name = "{1} (msgId={0})")
    @MethodSource("customMessages")
    @DisplayName("消息类内的 CRC_EXTRA 常量与常量表一致")
    void messageClassConstantMatchesTable(int msgId, String name, Field[] fields) {
        int fromTable = expected(msgId);
        Integer fromClass = classConstant(msgId);
        if (fromClass != null) {
            assertEquals(fromTable, fromClass.intValue(),
                    name + " (msgId=" + msgId + ") 的消息类 CRC_EXTRA 常量与 MavlinkMessageInfo 不一致");
        }
    }

    @Test
    @DisplayName("签名字段一改，CRC_EXTRA 必变（这是 CRC_EXTRA 存在的理由）")
    void crcExtraIsSensitiveToFieldDefinition() {
        Field[] original = {
            F(F32, "distance"), F(F32, "direction"), F(U32, "timestamp"),
            F(U8, "threat"), F(U8, "type"), F(U8, "sysid"),
            F(U8, "reserved"), F(U16, "reserved2"), F(U8, "reserved3", 2),
        };
        int base = MavlinkMessageChecksum.crcExtra("OBSTACLE_REPORT", original);

        // 改字段类型：distance f32 -> uint32_t
        Field[] retyped = original.clone();
        retyped[0] = F(U32, "distance");
        assertNotEquals(base, MavlinkMessageChecksum.crcExtra("OBSTACLE_REPORT", retyped),
                "字段类型变化必须改变 CRC_EXTRA，否则两端字段定义不一致却能通过帧 CRC");

        // 改字段名：threat -> threatLevel
        Field[] renamed = original.clone();
        renamed[3] = F(U8, "threatLevel");
        assertNotEquals(base, MavlinkMessageChecksum.crcExtra("OBSTACLE_REPORT", renamed),
                "字段改名必须改变 CRC_EXTRA");

        // 改消息名：OBSTACLE_REPORT -> OBSTACLE_REPORT_V2
        assertNotEquals(base, MavlinkMessageChecksum.crcExtra("OBSTACLE_REPORT_V2", original),
                "消息改名必须改变 CRC_EXTRA");

        // 改数组长度：reserved3[2] -> reserved3[3]
        Field[] resized = original.clone();
        resized[8] = F(U8, "reserved3", 3);
        assertNotEquals(base, MavlinkMessageChecksum.crcExtra("OBSTACLE_REPORT", resized),
                "数组长度变化必须改变 CRC_EXTRA");
    }

    @Test
    @DisplayName("算法在标准消息上与 pymavlink 官方值一致（抽样自检）")
    void algorithmMatchesOfficialValues() {
        // 字段签名与期望值取自 pymavlink 官方 common.xml / minimal.xml 的 CRC_EXTRA 宏，
        // 用 scripts/mavlink-crc-extra-gen.py --pymavlink 逐条核对过；完整 23 条自检见该脚本。
        //
        // 注意 STATUSTEXT 只列了 severity + text[50]：id / chunk_seq 在官方定义里是
        // 扩展字段，官方算法只覆盖 base_fields，扩展字段不进 CRC。
        assertEquals(50, MavlinkMessageChecksum.crcExtra("HEARTBEAT",
                F(U32, "custom_mode"), F(U8, "type"), F(U8, "autopilot"),
                F(U8, "base_mode"), F(U8, "system_status"), F(U8, "mavlink_version")));
        assertEquals(83, MavlinkMessageChecksum.crcExtra("STATUSTEXT",
                F(U8, "severity"), F(CH, "text", 50)));
        assertEquals(185, MavlinkMessageChecksum.crcExtra("RADIO_STATUS",
                F(U16, "rxerrors"), F(U16, "fixed"), F(U8, "rssi"), F(U8, "remrssi"),
                F(U8, "txbuf"), F(U8, "noise"), F(U8, "remnoise")));
        assertEquals(20, MavlinkMessageChecksum.crcExtra("VFR_HUD",
                F(F32, "airspeed"), F(F32, "groundspeed"), F(F32, "alt"), F(F32, "climb"),
                F(I16, "heading"), F(U16, "throttle")));
        assertEquals(196, MavlinkMessageChecksum.crcExtra("MISSION_REQUEST_INT",
                F(U16, "seq"), F(U8, "target_system"), F(U8, "target_component")));
        assertEquals(24, MavlinkMessageChecksum.crcExtra("GPS_RAW_INT",
                F(U64, "time_usec"), F(I32, "lat"), F(I32, "lon"), F(I32, "alt"),
                F(U16, "eph"), F(U16, "epv"), F(U16, "vel"), F(U16, "cog"),
                F(U8, "fix_type"), F(U8, "satellites_visible")));
    }

    @Test
    @DisplayName("排序按 type_length 降序，且等宽字段保持声明顺序")
    void fieldOrderingFollowsOfficialRule() {
        Field[] in = {F(U8, "a"), F(F32, "b"), F(U8, "c"), F(I16, "d")};
        Field[] ordered = MavlinkMessageChecksum.orderFields(in);
        assertEquals("b", ordered[0].name(), "4 字节字段应排最前");
        assertEquals("d", ordered[1].name(), "2 字节字段次之");
        assertEquals("a", ordered[2].name(), "1 字节字段最后，且同宽保持声明顺序");
        assertEquals("c", ordered[3].name(), "同宽字段保持声明顺序，不得按名排序");
    }

    @Test
    @DisplayName("CRC_EXTRA 取值域为 0-255，撞值属 MAVLink 固有现象而非缺陷")
    void crcExtraIsEightBitAndCollisionsAreExpected() {
        java.util.Set<Integer> distinct = new java.util.HashSet<>();
        for (Object[] row : SIGNATURES) {
            int msgId = (Integer) row[0];
            int crc = expected(msgId);
            assertTrue(crc >= 0 && crc <= 255, msgId + " 的 CRC_EXTRA 越界: " + crc);
            distinct.add(crc);
        }
        // 不要求两两不同：CRC_EXTRA 只有 1 字节，官方消息本身就撞值
        // （如 MISSION_SET_CURRENT 与 MISSION_CURRENT 同为 28）。
        // 真正保证内容完整性的是帧 CRC 覆盖 payload，不是 CRC_EXTRA。
        assertTrue(distinct.size() > SIGNATURES.length / 2,
                "若大量重复，说明重算可能退化成常数，需检查字段签名是否被丢弃");
    }

    /** 反射取消息类里的 CRC_EXTRA 常量；类不存在或没有该常量时返回 null。 */
    private static Integer classConstant(int msgId) {
        String cls = switch (msgId) {
            case 420 -> "LedControlMsg";
            case 421 -> "EnvironmentAlert";
            case 422 -> "EnvironmentStatus";
            case 423 -> "SprayStatus";
            case 424 -> "SprayCommand";
            case 425 -> "GripperCommand";
            case 426 -> "PayloadStatus";
            case 430 -> "ObstacleReportMsg";
            case 431 -> "MultispectralDataMsg";
            case 432 -> "ThermalDataMsg";
            case 433 -> "DepthDataMsg";
            case 434 -> "VisionDetectionMsg";
            case 437 -> "RadarScanMsg";
            case 438 -> "RadarTargetMsg";
            case 439 -> "RotorTelemetryMsg";
            case 440 -> "LidarDataMsg";
            case 441 -> "ImuDataMsg";
            case 450 -> "MeshHeartbeatMsg";
            case 451 -> "MeshRouteRequestMsg";
            case 452 -> "MeshRouteReplyMsg";
            case 453 -> "MeshRouteErrorMsg";
            case 454 -> "MeshNeighborTableMsg";
            case 455 -> "CellTowerStatusMsg";
            case 456 -> "CellTowerConfigMsg";
            case 457 -> "CellHandoverMsg";
            case 458 -> "GroundTerminalRegisterMsg";
            case 459 -> "SatLinkStatusMsg";
            case 460 -> "SatPassScheduleMsg";
            case 461 -> "HierarchicalRouteDecisionMsg";
            case 462 -> "TerrainTypeMapMsg";
            case 463 -> "TerrainUpdateMsg";
            case 464 -> "FlightRestrictionMsg";
            case 465 -> "EmergencyMissionPlanMsg";
            case 466 -> "CoverageOptimizationMsg";
            case 467 -> "EmergencyPriorityMsg";
            case 468 -> "TaskAssignmentMsg";
            case 469 -> "ConflictAlertMsg";
            case 470 -> "TaskStatusMsg";
            case 471 -> "DecisionEventMsg";
            case 472 -> "AdaptivePathMsg";
            case 473 -> "EdgeTaskStatusMsg";
            case 474 -> "SensorFusionDataMsg";
            case 475 -> "TwinStateSyncMsg";
            case 476 -> "PredictionResultMsg";
            case 477 -> "AlarmTriggerMsg";
            case 478 -> "AlarmAckMsg";
            case 479 -> "SurveillanceStatusMsg";
            case 480 -> "QoSRouteDecisionMsg";
            case 481 -> "ClusterFormationMsg";
            case 482 -> "DisasterModeStatusMsg";
            case 483 -> "BuzzerControlMsg";
            default -> null;
        };
        if (cls == null) {
            return null;
        }
        try {
            Class<?> c = Class.forName("io.aerofleet.mavlink.messages." + cls);
            return (Integer) c.getField("CRC_EXTRA").get(null);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}

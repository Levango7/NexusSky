package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;

import io.aerofleet.mavlink.MavlinkMessageInfo;
import io.aerofleet.mavlink.MavlinkParser;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3 感知成像增强自定义消息编解码单测（FR-19/20/21/22/23）：
 * 430-434 encode→decode 往返一致、decode 分发、msgId 无冲突、字段单位精度。
 */
class PerceptionMsgTest {

    /** 构造 v2 帧 → 解析 → decode 往返。 */
    private static MavlinkMessage roundtrip(MavlinkMessage msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        MavlinkParser.ParseResult r = new MavlinkParser().parse(ByteBuffer.wrap(frame.encodeV2()));
        assertNotNull(r, "parser must accept the frame (CRC registered?)");
        return MavlinkMessage.decode(r.frame);
    }

    // ===== OBSTACLE_REPORT (430) =====

    @Test
    void obstacleReportRoundtrip() {
        ObstacleReportMsg orig = new ObstacleReportMsg(3.5f, 180.0f, 123456L, 4, 1, 2);
        ObstacleReportMsg back = (ObstacleReportMsg) roundtrip(orig);
        assertEquals(orig.distance, back.distance, 1e-6f);
        assertEquals(orig.direction, back.direction, 1e-6f);
        assertEquals(orig.timestamp, back.timestamp);
        assertEquals(orig.threat, back.threat);
        assertEquals(orig.type, back.type);
        assertEquals(orig.sysid, back.sysid);
    }

    @Test
    void obstacleReportFieldSemantics() {
        // threat=4 → CRITICAL, type=1 → STATIC_OBSTACLE
        ObstacleReportMsg msg = new ObstacleReportMsg(1.0f, 0f, 0L, 4, 1, 1);
        assertEquals(4, msg.threat, "threat ordinal 4 = CRITICAL");
        assertEquals(1, msg.type, "type ordinal 1 = static obstacle");
        assertEquals(20, ObstacleReportMsg.LEN, "payload length 20 bytes");
        assertEquals(430, ObstacleReportMsg.ID);
    }

    // ===== MULTISPECTRAL_DATA (431) =====

    @Test
    void multispectralDataRoundtrip() {
        MultispectralDataMsg orig = new MultispectralDataMsg(
                0.45f, -0.2f, 0.8f, 0.65f, 99999L, 3);
        MultispectralDataMsg back = (MultispectralDataMsg) roundtrip(orig);
        assertEquals(orig.ndviMean, back.ndviMean, 1e-6f);
        assertEquals(orig.ndviMin, back.ndviMin, 1e-6f);
        assertEquals(orig.ndviMax, back.ndviMax, 1e-6f);
        assertEquals(orig.vegetationCoverage, back.vegetationCoverage, 1e-6f);
        assertEquals(orig.timestamp, back.timestamp);
        assertEquals(orig.sysid, back.sysid);
    }

    @Test
    void multispectralDataFieldSemantics() {
        MultispectralDataMsg msg = new MultispectralDataMsg(0.5f, 0.1f, 0.9f, 0.7f, 0L, 1);
        assertTrue(msg.ndviMean >= -1 && msg.ndviMean <= 1, "NDVI in [-1,1]");
        assertTrue(msg.vegetationCoverage >= 0 && msg.vegetationCoverage <= 1,
                "coverage in [0,1]");
        assertEquals(24, MultispectralDataMsg.LEN);
        assertEquals(431, MultispectralDataMsg.ID);
    }

    // ===== THERMAL_DATA (432) =====

    @Test
    void thermalDataRoundtrip() {
        ThermalDataMsg orig = new ThermalDataMsg(
                25.5f, 10.0f, 85.0f, 12.3f, 88888L, 3, 2);
        ThermalDataMsg back = (ThermalDataMsg) roundtrip(orig);
        assertEquals(orig.tempMean, back.tempMean, 1e-6f);
        assertEquals(orig.tempMin, back.tempMin, 1e-6f);
        assertEquals(orig.tempMax, back.tempMax, 1e-6f);
        assertEquals(orig.tempStdDev, back.tempStdDev, 1e-6f);
        assertEquals(orig.timestamp, back.timestamp);
        assertEquals(orig.hotspotCount, back.hotspotCount);
        assertEquals(orig.sysid, back.sysid);
    }

    @Test
    void thermalDataFieldSemantics() {
        ThermalDataMsg msg = new ThermalDataMsg(30f, 5f, 90f, 15f, 0L, 2, 1);
        assertTrue(msg.tempMax >= msg.tempMin, "tempMax >= tempMin");
        assertEquals(24, ThermalDataMsg.LEN);
        assertEquals(432, ThermalDataMsg.ID);
    }

    // ===== DEPTH_DATA (433) =====

    @Test
    void depthDataRoundtrip() {
        DepthDataMsg orig = new DepthDataMsg(2.5f, 270.0f, 100.0f, 5000L, 1);
        DepthDataMsg back = (DepthDataMsg) roundtrip(orig);
        assertEquals(orig.nearestDistance, back.nearestDistance, 1e-6f);
        assertEquals(orig.nearestDirection, back.nearestDirection, 1e-6f);
        assertEquals(orig.pointCloudDensity, back.pointCloudDensity, 1e-6f);
        assertEquals(orig.pointCount, back.pointCount);
        assertEquals(orig.sysid, back.sysid);
    }

    @Test
    void depthDataFieldSemantics() {
        DepthDataMsg msg = new DepthDataMsg(5.0f, 90f, 50f, 1000L, 1);
        assertTrue(msg.nearestDistance >= 0, "distance non-negative");
        assertTrue(msg.nearestDirection >= 0 && msg.nearestDirection < 360,
                "direction in [0,360)");
        assertEquals(20, DepthDataMsg.LEN);
        assertEquals(433, DepthDataMsg.ID);
    }

    // ===== VISION_DETECTION (434) =====

    @Test
    void visionDetectionRoundtrip() {
        VisionDetectionMsg orig = new VisionDetectionMsg(
                320.0f, 240.0f, 0.92f, 1, 5, 2, 55555L);
        VisionDetectionMsg back = (VisionDetectionMsg) roundtrip(orig);
        assertEquals(orig.u, back.u, 1e-6f);
        assertEquals(orig.v, back.v, 1e-6f);
        assertEquals(orig.confidence, back.confidence, 1e-6f);
        assertEquals(orig.kind, back.kind);
        assertEquals(orig.trackId, back.trackId);
        assertEquals(orig.sysid, back.sysid);
        assertEquals(orig.timestamp, back.timestamp);
    }

    @Test
    void visionDetectionFieldSemantics() {
        VisionDetectionMsg msg = new VisionDetectionMsg(
                100f, 200f, 0.85f, 0, VisionDetectionMsg.TRACK_ID_NONE, 1, 0L);
        assertTrue(msg.confidence >= 0 && msg.confidence <= 1, "confidence in [0,1]");
        assertEquals(255, VisionDetectionMsg.TRACK_ID_NONE, "TRACK_ID_NONE = 0xFF");
        assertEquals(20, VisionDetectionMsg.LEN);
        assertEquals(434, VisionDetectionMsg.ID);
    }

    // ===== decode 分发注册 =====

    @Test
    void decodeDispatches430() {
        MavlinkMessage decoded = roundtrip(
                new ObstacleReportMsg(5f, 0f, 0L, 0, 0, 1));
        assertTrue(decoded instanceof ObstacleReportMsg,
                "msgId=430 should dispatch to ObstacleReportMsg");
    }

    @Test
    void decodeDispatches431() {
        MavlinkMessage decoded = roundtrip(
                new MultispectralDataMsg(0f, 0f, 0f, 0f, 0L, 1));
        assertTrue(decoded instanceof MultispectralDataMsg,
                "msgId=431 should dispatch to MultispectralDataMsg");
    }

    @Test
    void decodeDispatches432() {
        MavlinkMessage decoded = roundtrip(
                new ThermalDataMsg(0f, 0f, 0f, 0f, 0L, 0, 1));
        assertTrue(decoded instanceof ThermalDataMsg,
                "msgId=432 should dispatch to ThermalDataMsg");
    }

    @Test
    void decodeDispatches433() {
        MavlinkMessage decoded = roundtrip(
                new DepthDataMsg(0f, 0f, 0f, 0L, 1));
        assertTrue(decoded instanceof DepthDataMsg,
                "msgId=433 should dispatch to DepthDataMsg");
    }

    @Test
    void decodeDispatches434() {
        MavlinkMessage decoded = roundtrip(
                new VisionDetectionMsg(0f, 0f, 0f, 0, 0, 1, 0L));
        assertTrue(decoded instanceof VisionDetectionMsg,
                "msgId=434 should dispatch to VisionDetectionMsg");
    }

    // ===== msgId 注册与无冲突 =====

    @Test
    void msgIdsRegisteredInInfoTable() {
        assertTrue(MavlinkMessageInfo.isKnown(ObstacleReportMsg.ID));
        assertTrue(MavlinkMessageInfo.isKnown(MultispectralDataMsg.ID));
        assertTrue(MavlinkMessageInfo.isKnown(ThermalDataMsg.ID));
        assertTrue(MavlinkMessageInfo.isKnown(DepthDataMsg.ID));
        assertTrue(MavlinkMessageInfo.isKnown(VisionDetectionMsg.ID));
    }

    @Test
    void msgIdLengthsCorrect() {
        assertEquals(20, MavlinkMessageInfo.lengthOf(430));
        assertEquals(24, MavlinkMessageInfo.lengthOf(431));
        assertEquals(24, MavlinkMessageInfo.lengthOf(432));
        assertEquals(20, MavlinkMessageInfo.lengthOf(433));
        assertEquals(20, MavlinkMessageInfo.lengthOf(434));
    }

    @Test
    void msgIdNoConflictWithExistingOrM2() {
        int[] existing = {0, 1, 2, 24, 30, 33, 42, 43, 44, 47, 51, 69, 73, 74, 76, 77,
                109, 143, 242, 253, 259, 260, 262, 263, 271, 420, 421, 422, 423, 424, 425, 426};
        int[] m3Ids = {430, 431, 432, 433, 434};
        for (int existingId : existing) {
            for (int m3Id : m3Ids) {
                assertNotEquals(existingId, m3Id,
                        "M3 msgId " + m3Id + " must not conflict with existing " + existingId);
            }
        }
        // M3 内部无重复
        for (int i = 0; i < m3Ids.length; i++) {
            for (int j = i + 1; j < m3Ids.length; j++) {
                assertNotEquals(m3Ids[i], m3Ids[j], "M3 msgIds must be distinct");
            }
        }
    }

    @Test
    void msgIdDoesNotOverlapM2Range() {
        // M2 使用 423-426，M3 使用 430-434，中间 427-429 保留给 M2 扩展
        for (int m2Id = 423; m2Id <= 429; m2Id++) {
            for (int m3Id = 430; m3Id <= 434; m3Id++) {
                assertNotEquals(m2Id, m3Id, "M3 must not overlap M2 reserved range");
            }
        }
    }
}
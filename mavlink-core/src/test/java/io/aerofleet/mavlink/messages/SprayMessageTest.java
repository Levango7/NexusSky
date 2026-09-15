package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;

import io.aerofleet.mavlink.MavlinkMessageInfo;
import io.aerofleet.mavlink.MavlinkParser;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SprayStatus / SprayCommand / GripperCommand / PayloadStatus 消息编解码单测（FR-26~FR-29）：
 * 423-426 encode→decode 往返一致、decode 分发、msgId 无冲突、短 payload 容忍。
 */
class SprayMessageTest {

    /** 构造 v2 帧 → 解析 → decode 往返。 */
    private static MavlinkMessage roundtrip(MavlinkMessage msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        MavlinkParser.ParseResult r = new MavlinkParser().parse(ByteBuffer.wrap(frame.encodeV2()));
        assertNotNull(r, "parser must accept the frame (CRC registered?)");
        return MavlinkMessage.decode(r.frame);
    }

    // ---- FR-26 SprayStatus (423) 往返 ----

    @Test
    void sprayStatusEncodeDecodeRoundtrip() {
        SprayStatus orig = new SprayStatus(true, 150, 8000, 45, true, -300, 90);
        SprayStatus back = (SprayStatus) roundtrip(orig);
        assertEquals(orig.enabled, back.enabled);
        assertEquals(orig.rate, back.rate);
        assertEquals(orig.remainingChemical, back.remainingChemical);
        assertEquals(orig.coveragePercent, back.coveragePercent);
        assertEquals(orig.lowChemical, back.lowChemical);
        assertEquals(orig.driftOffsetAngle, back.driftOffsetAngle);
        assertEquals(orig.flowCorrectionPercent, back.flowCorrectionPercent);
    }

    @Test
    void sprayStatusFieldUnits() {
        // driftOffsetAngle 单位 cdeg（×100）
        SprayStatus msg = new SprayStatus(true, 150, 8000, 45, false, -300, 90);
        assertEquals(-3.0, msg.driftOffsetAngle / 100.0, 1e-9, "-300 cdeg = -3.0 deg");
    }

    // ---- FR-27 SprayCommand (424) 往返 ----

    @Test
    void sprayCommandEncodeDecodeRoundtrip() {
        SprayCommand orig = new SprayCommand(SprayCommand.COMMAND_SET_RATE, 200, 500);
        SprayCommand back = (SprayCommand) roundtrip(orig);
        assertEquals(orig.command, back.command);
        assertEquals(orig.targetRate, back.targetRate);
        assertEquals(orig.sprayWidth, back.sprayWidth);
    }

    @Test
    void sprayCommandAllEnumValues() {
        int[] cmds = {SprayCommand.COMMAND_ENABLE, SprayCommand.COMMAND_DISABLE,
                SprayCommand.COMMAND_SET_RATE, SprayCommand.COMMAND_EMERGENCY_STOP};
        for (int cmd : cmds) {
            SprayCommand orig = new SprayCommand(cmd, 100, 400);
            SprayCommand back = (SprayCommand) roundtrip(orig);
            assertEquals(cmd, back.command, "command enum " + cmd);
        }
    }

    @Test
    void sprayCommandFieldUnits() {
        // sprayWidth 单位 cm（×100）
        SprayCommand msg = new SprayCommand(0, 150, 500);
        assertEquals(5.0, msg.sprayWidth / 100.0, 1e-9, "500 cm = 5.0 m");
    }

    // ---- FR-28 GripperCommand (425) 往返 ----

    @Test
    void gripperCommandEncodeDecodeRoundtrip() {
        GripperCommand orig = new GripperCommand(GripperCommand.COMMAND_GRAB, 3, 2500, 150);
        GripperCommand back = (GripperCommand) roundtrip(orig);
        assertEquals(orig.command, back.command);
        assertEquals(orig.payloadId, back.payloadId);
        assertEquals(orig.payloadWeight, back.payloadWeight);
        assertEquals(orig.payloadVolume, back.payloadVolume);
    }

    @Test
    void gripperCommandAllEnumValues() {
        int[] cmds = {GripperCommand.COMMAND_GRAB, GripperCommand.COMMAND_RELEASE,
                GripperCommand.COMMAND_RESET};
        for (int cmd : cmds) {
            GripperCommand orig = new GripperCommand(cmd, 1, 1000, 50);
            GripperCommand back = (GripperCommand) roundtrip(orig);
            assertEquals(cmd, back.command, "command enum " + cmd);
        }
    }

    @Test
    void gripperCommandFieldUnits() {
        // payloadWeight 单位 cg（×10，克）→ 2500 cg = 250.0 g = 0.25 kg
        // payloadVolume 单位 cL（×10，厘升）→ 150 cL = 15.0 L
        GripperCommand msg = new GripperCommand(0, 1, 2500, 150);
        assertEquals(250.0, msg.payloadWeight / 10.0, 1e-9, "2500 cg = 250 g");
        assertEquals(15.0, msg.payloadVolume / 10.0, 1e-9, "150 cL = 15 L");
    }

    // ---- FR-29 PayloadStatus (426) 往返 ----

    @Test
    void payloadStatusEncodeDecodeRoundtrip() {
        PayloadStatus orig = new PayloadStatus(2, 2000, 100, 3, 1, 500);
        PayloadStatus back = (PayloadStatus) roundtrip(orig);
        assertEquals(orig.gripperState, back.gripperState);
        assertEquals(orig.currentPayloadWeight, back.currentPayloadWeight);
        assertEquals(orig.currentPayloadVolume, back.currentPayloadVolume);
        assertEquals(orig.remainingSites, back.remainingSites);
        assertEquals(orig.currentSiteIndex, back.currentSiteIndex);
        assertEquals(orig.dropAccuracyCm, back.dropAccuracyCm);
    }

    @Test
    void payloadStatusFieldUnits() {
        // currentPayloadWeight 单位 cg → 2000 cg = 200 g = 0.2 kg
        // currentPayloadVolume 单位 cL → 100 cL = 10 L
        // dropAccuracyCm 单位 cm → 500 cm = 5 m
        PayloadStatus msg = new PayloadStatus(2, 2000, 100, 3, 1, 500);
        assertEquals(200.0, msg.currentPayloadWeight / 10.0, 1e-9, "2000 cg = 200 g");
        assertEquals(10.0, msg.currentPayloadVolume / 10.0, 1e-9, "100 cL = 10 L");
        assertEquals(5.0, msg.dropAccuracyCm / 100.0, 1e-9, "500 cm = 5 m");
    }

    // ---- decode 分发 ----

    @Test
    void decodeDispatches423() {
        SprayStatus msg = new SprayStatus(true, 100, 5000, 50, false, 0, 100);
        MavlinkMessage decoded = roundtrip(msg);
        assertTrue(decoded instanceof SprayStatus, "msgId=423 → SprayStatus");
    }

    @Test
    void decodeDispatches424() {
        SprayCommand msg = new SprayCommand(0, 100, 400);
        MavlinkMessage decoded = roundtrip(msg);
        assertTrue(decoded instanceof SprayCommand, "msgId=424 → SprayCommand");
    }

    @Test
    void decodeDispatches425() {
        GripperCommand msg = new GripperCommand(0, 1, 1000, 50);
        MavlinkMessage decoded = roundtrip(msg);
        assertTrue(decoded instanceof GripperCommand, "msgId=425 → GripperCommand");
    }

    @Test
    void decodeDispatches426() {
        PayloadStatus msg = new PayloadStatus(0, 0, 0, 0, 0, 0);
        MavlinkMessage decoded = roundtrip(msg);
        assertTrue(decoded instanceof PayloadStatus, "msgId=426 → PayloadStatus");
    }

    // ---- msgId 无冲突 ----

    @Test
    void msgIdNoConflict() {
        assertTrue(MavlinkMessageInfo.isKnown(SprayStatus.ID));
        assertTrue(MavlinkMessageInfo.isKnown(SprayCommand.ID));
        assertTrue(MavlinkMessageInfo.isKnown(GripperCommand.ID));
        assertTrue(MavlinkMessageInfo.isKnown(PayloadStatus.ID));

        assertEquals(SprayStatus.LEN, MavlinkMessageInfo.lengthOf(SprayStatus.ID));
        assertEquals(SprayCommand.LEN, MavlinkMessageInfo.lengthOf(SprayCommand.ID));
        assertEquals(GripperCommand.LEN, MavlinkMessageInfo.lengthOf(GripperCommand.ID));
        assertEquals(PayloadStatus.LEN, MavlinkMessageInfo.lengthOf(PayloadStatus.ID));

        // 确认 423-426 互不冲突
        assertNotEquals(SprayStatus.ID, SprayCommand.ID);
        assertNotEquals(SprayStatus.ID, GripperCommand.ID);
        assertNotEquals(SprayStatus.ID, PayloadStatus.ID);
        assertNotEquals(SprayCommand.ID, GripperCommand.ID);
        assertNotEquals(SprayCommand.ID, PayloadStatus.ID);
        assertNotEquals(GripperCommand.ID, PayloadStatus.ID);

        // 确认不与既有消息冲突
        int[] existing = {0, 1, 2, 24, 30, 33, 42, 43, 44, 47, 51, 69, 73, 74, 76, 77,
                109, 143, 242, 253, 259, 260, 262, 263, 271, 420, 421, 422};
        int[] m2Ids = {SprayStatus.ID, SprayCommand.ID, GripperCommand.ID, PayloadStatus.ID};
        for (int existingId : existing) {
            for (int m2Id : m2Ids) {
                assertNotEquals(existingId, m2Id, "M2 msgId " + m2Id + " must not conflict with " + existingId);
            }
        }
    }

    // ---- 短 payload 容忍 ----

    /** 构造短 payload 帧（CRC 不重要，直接调 decode 不经过 parser）。 */
    private static MavlinkFrame shortFrame(int msgId, byte[] payload) {
        return new MavlinkFrame(payload.length, 0, 0, 0, 1, 1, msgId, payload, 0);
    }

    @Test
    void sprayStatusShortPayloadTolerated() {
        // 构造一个只有 1 字节的 payload → enabled 解码，其余字段默认 0
        SprayStatus msg = new SprayStatus(true, 0, 0, 0, false, 0, 0);
        byte[] fullPayload = msg.toFrame(1, 1, 0).getPayload();
        byte[] shortPayload = new byte[]{fullPayload[0]};
        SprayStatus decoded = SprayStatus.decode(shortFrame(SprayStatus.ID, shortPayload));
        assertTrue(decoded.enabled, "enabled should decode from 1-byte payload");
        assertEquals(0, decoded.rate, "rate should default to 0 when payload too short");
    }

    @Test
    void sprayCommandShortPayloadTolerated() {
        SprayCommand msg = new SprayCommand(0, 0, 0);
        byte[] shortPayload = new byte[]{msg.toFrame(1, 1, 0).getPayload()[0]};
        SprayCommand decoded = SprayCommand.decode(shortFrame(SprayCommand.ID, shortPayload));
        assertEquals(0, decoded.command);
        assertEquals(0, decoded.targetRate, "targetRate should default to 0");
    }

    @Test
    void gripperCommandShortPayloadTolerated() {
        GripperCommand msg = new GripperCommand(0, 0, 0, 0);
        byte[] shortPayload = new byte[]{msg.toFrame(1, 1, 0).getPayload()[0]};
        GripperCommand decoded = GripperCommand.decode(shortFrame(GripperCommand.ID, shortPayload));
        assertEquals(0, decoded.command);
        assertEquals(0, decoded.payloadId, "payloadId should default to 0");
    }

    @Test
    void payloadStatusShortPayloadTolerated() {
        PayloadStatus msg = new PayloadStatus(0, 0, 0, 0, 0, 0);
        byte[] shortPayload = new byte[]{msg.toFrame(1, 1, 0).getPayload()[0]};
        PayloadStatus decoded = PayloadStatus.decode(shortFrame(PayloadStatus.ID, shortPayload));
        assertEquals(0, decoded.gripperState);
        assertEquals(0, decoded.currentPayloadWeight, "weight should default to 0");
    }
}
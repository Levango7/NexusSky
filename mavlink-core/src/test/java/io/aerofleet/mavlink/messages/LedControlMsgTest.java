package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;

import io.aerofleet.mavlink.MavlinkMessageInfo;
import io.aerofleet.mavlink.MavlinkParser;
import io.aerofleet.mavlink.enums.LightPattern;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LedControlMsg 编解码单测（FR-10/12，tasks T11）：
 * msgId=420 往返一致、全灯效模式支持、phaseStartUs 大值精度、CRC_EXTRA 注册一致。
 */
class LedControlMsgTest {

    /** 构造 v2 帧 → 解析 → decode 往返。 */
    private static LedControlMsg roundtrip(LedControlMsg msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        MavlinkParser.ParseResult r = new MavlinkParser().parse(ByteBuffer.wrap(frame.encodeV2()));
        assertNotNull(r, "parser must accept the frame (CRC registered?)");
        MavlinkMessage decoded = MavlinkMessage.decode(r.frame);
        assertNotNull(decoded, "decode must dispatch msgId=420 to LedControlMsg");
        assertTrue(decoded instanceof LedControlMsg, "decoded should be LedControlMsg");
        return (LedControlMsg) decoded;
    }

    @Test
    void encodeDecodeRoundtrip() {
        LedControlMsg orig = new LedControlMsg(1, 1, 255, 0, 0, 2, 80, 5,
                true, true, 500_000L, 0, 200);
        LedControlMsg back = roundtrip(orig);
        assertEquals(orig.targetSystem, back.targetSystem);
        assertEquals(orig.targetComponent, back.targetComponent);
        assertEquals(orig.colorR, back.colorR);
        assertEquals(orig.colorG, back.colorG);
        assertEquals(orig.colorB, back.colorB);
        assertEquals(orig.pattern, back.pattern);
        assertEquals(orig.brightness, back.brightness);
        assertEquals(orig.freq, back.freq);
        assertEquals(orig.on, back.on);
        assertEquals(orig.sync, back.sync);
        assertEquals(orig.phaseStartUs, back.phaseStartUs);
        assertEquals(orig.ledIndex, back.ledIndex);
        assertEquals(orig.transitionMs, back.transitionMs);
    }

    @Test
    void allLightPatternsSupported() {
        for (LightPattern p : LightPattern.values()) {
            LedControlMsg orig = new LedControlMsg(1, 1, 255, 255, 255,
                    p.ordinal(), 100, 10, true, false, 0L, 0, 0);
            LedControlMsg back = roundtrip(orig);
            assertEquals(p.ordinal(), back.pattern,
                    "pattern " + p.name() + " should round-trip");
        }
    }

    @Test
    void phaseStartUsPreserved() {
        long big = 1_000_000L;
        LedControlMsg orig = new LedControlMsg(1, 1, 0, 0, 0, 0, 0, 0,
                true, false, big, 0, 0);
        LedControlMsg back = roundtrip(orig);
        assertEquals(big, back.phaseStartUs, "phaseStartUs=1_000_000 should be preserved");
    }

    @Test
    void msgIdIs420() {
        assertEquals(420, LedControlMsg.ID);
        LedControlMsg msg = new LedControlMsg(1, 1, 0, 0, 0, 0, 0, 0,
                false, false, 0L, 0, 0);
        assertEquals(420, msg.messageId());
    }

    @Test
    void crcExtraCorrect() {
        assertTrue(MavlinkMessageInfo.isKnown(LedControlMsg.ID),
                "msgId=420 should be registered");
        assertEquals(LedControlMsg.LEN, MavlinkMessageInfo.lengthOf(LedControlMsg.ID),
                "registered LEN should match LedControlMsg.LEN");
        assertEquals(LedControlMsg.CRC_EXTRA, MavlinkMessageInfo.crcExtraOf(LedControlMsg.ID),
                "registered CRC_EXTRA should match LedControlMsg.CRC_EXTRA");
    }
}
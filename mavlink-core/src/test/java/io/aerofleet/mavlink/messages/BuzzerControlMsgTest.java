package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkMessageInfo;
import io.aerofleet.mavlink.MavlinkParser;
import io.aerofleet.mavlink.enums.BuzzerPattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BuzzerControlMsg 编解码单测（千元级灾害应急搜救信号）：
 * msgId=483 往返一致、全蜂鸣器模式支持、CRC_EXTRA 注册一致。
 */
class BuzzerControlMsgTest {

    /** 构造 v2 帧 → 解析 → decode 往返。 */
    private static BuzzerControlMsg roundtrip(BuzzerControlMsg msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        MavlinkParser.ParseResult r = new MavlinkParser().parse(ByteBuffer.wrap(frame.encodeV2()));
        assertNotNull(r, "parser must accept the frame (CRC registered?)");
        MavlinkMessage decoded = MavlinkMessage.decode(r.frame);
        assertNotNull(decoded, "decode must dispatch msgId=483 to BuzzerControlMsg");
        assertTrue(decoded instanceof BuzzerControlMsg, "decoded should be BuzzerControlMsg");
        return (BuzzerControlMsg) decoded;
    }

    @Test
    @DisplayName("编解码往返一致")
    void encodeDecodeRoundtrip() {
        BuzzerControlMsg orig = new BuzzerControlMsg(2, true, 1, 8, 30);
        BuzzerControlMsg back = roundtrip(orig);
        assertEquals(orig.sysid, back.sysid, "sysid 应一致");
        assertEquals(orig.on, back.on, "on 应一致");
        assertEquals(orig.pattern, back.pattern, "pattern 应一致");
        assertEquals(orig.volume, back.volume, "volume 应一致");
        assertEquals(orig.durationSec, back.durationSec, "durationSec 应一致");
    }

    @Test
    @DisplayName("关状态编解码往返")
    void encodeDecodeRoundtrip_off() {
        BuzzerControlMsg orig = new BuzzerControlMsg(1, false, 0, 0, 0);
        BuzzerControlMsg back = roundtrip(orig);
        assertEquals(1, back.sysid, "sysid 应为 1");
        assertFalse(back.on, "on 应为 false");
        assertEquals(0, back.pattern, "pattern 应为 0");
        assertEquals(0, back.volume, "volume 应为 0");
        assertEquals(0, back.durationSec, "durationSec 应为 0");
    }

    @Test
    @DisplayName("全蜂鸣器模式支持")
    void allBuzzerPatternsSupported() {
        for (BuzzerPattern p : BuzzerPattern.values()) {
            BuzzerControlMsg orig = new BuzzerControlMsg(1, true, p.ordinal(), 5, 60);
            BuzzerControlMsg back = roundtrip(orig);
            assertEquals(p.ordinal(), back.pattern,
                    "pattern " + p.name() + " should round-trip");
        }
    }

    @Test
    @DisplayName("durationSec 大值精度保持")
    void durationSecPreserved() {
        int big = 65535;
        BuzzerControlMsg orig = new BuzzerControlMsg(1, true, 0, 10, big);
        BuzzerControlMsg back = roundtrip(orig);
        assertEquals(big, back.durationSec, "durationSec=65535 should be preserved");
    }

    @Test
    @DisplayName("msgId 为 483")
    void msgIdIs483() {
        assertEquals(483, BuzzerControlMsg.ID);
        BuzzerControlMsg msg = new BuzzerControlMsg(1, true, 0, 5, 10);
        assertEquals(483, msg.messageId());
    }

    @Test
    @DisplayName("CRC_EXTRA 注册一致")
    void crcExtraCorrect() {
        assertTrue(MavlinkMessageInfo.isKnown(BuzzerControlMsg.ID),
                "msgId=483 should be registered");
        assertEquals(BuzzerControlMsg.LEN, MavlinkMessageInfo.lengthOf(BuzzerControlMsg.ID),
                "registered LEN should match BuzzerControlMsg.LEN");
        assertEquals(BuzzerControlMsg.CRC_EXTRA, MavlinkMessageInfo.crcExtraOf(BuzzerControlMsg.ID),
                "registered CRC_EXTRA should match BuzzerControlMsg.CRC_EXTRA");
    }

    @Test
    @DisplayName("LEN 为 7")
    void lenIs7() {
        assertEquals(7, BuzzerControlMsg.LEN, "LEN 应为 7");
    }

    @Test
    @DisplayName("CRC_EXTRA 为 267")
    void crcExtraIs267() {
        assertEquals(267, BuzzerControlMsg.CRC_EXTRA, "CRC_EXTRA 应为 267");
    }

    @Test
    @DisplayName("encode 返回 7 字节 payload")
    void encode_returns7Bytes() {
        BuzzerControlMsg msg = new BuzzerControlMsg(1, true, 2, 7, 120);
        byte[] payload = msg.encode();
        assertEquals(7, payload.length, "payload 长度应为 7");
    }

    @Test
    @DisplayName("toString 包含关键字段")
    void toString_containsKeyFields() {
        BuzzerControlMsg msg = new BuzzerControlMsg(3, true, 1, 8, 30);
        String s = msg.toString();
        assertTrue(s.contains("sysid=3"), "toString 应包含 sysid");
        assertTrue(s.contains("on=true"), "toString 应包含 on");
        assertTrue(s.contains("pattern=1"), "toString 应包含 pattern");
        assertTrue(s.contains("volume=8"), "toString 应包含 volume");
        assertTrue(s.contains("durationSec=30"), "toString 应包含 durationSec");
    }
}
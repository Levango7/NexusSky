package io.aerofleet.linksim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SecurityImpairmentEngine 单元测试（C5-T17）：
 * 覆盖签名篡改（SIGNING_TAMPER）和签名剥离（SIGNING_UNSIGNED）两类安全损伤。
 */
class SecurityImpairmentTest {

    /**
     * 构造 MAVLink v2 HEARTBEAT 帧（msgId=0, payload=9 字节）。
     *
     * @param signed true=签名帧（INC=0x01, 总长 34B），false=非签名帧（INC=0x00, 总长 21B）
     * @return 帧字节数组
     */
    private static byte[] buildFrame(boolean signed) {
        int payloadLen = 9;
        int frameLen = signed ? (12 + payloadLen + 13) : (12 + payloadLen);
        byte[] frame = new byte[frameLen];
        frame[0] = (byte) 0xFD;                          // STX
        frame[1] = (byte) payloadLen;                    // LEN
        frame[2] = signed ? (byte) 0x01 : (byte) 0x00;   // INC (bit 0 = signing)
        frame[3] = (byte) 0x00;                          // COMPAT
        frame[4] = (byte) 0x00;                          // SEQ
        frame[5] = (byte) 0x01;                          // SID
        frame[6] = (byte) 0x01;                          // CID
        frame[7] = (byte) 0x00;                          // MSGID low (little-endian)
        frame[8] = (byte) 0x00;                          // MSGID mid
        frame[9] = (byte) 0x00;                          // MSGID high
        // payload bytes 10~18: all zeros
        // CRC bytes 19~20: 0x00, 0x00
        if (signed) {
            // signature: linkId(1B) + timestamp(6B) + signature(8B) = 13B
            for (int i = 21; i < frameLen; i++) {
                frame[i] = (byte) 0xAA;
            }
        }
        return frame;
    }

    // --- verdict 测试 ---

    @Test
    void tamperProfileReturnsTamperAction() {
        SecurityImpairmentEngine engine =
                new SecurityImpairmentEngine(LinkProfile.SIGNING_TAMPER, 1.0);
        byte[] frame = buildFrame(false);
        for (int i = 0; i < 100; i++) {
            assertEquals(SecurityImpairmentEngine.SecurityAction.TAMPER,
                    engine.verdict(frame),
                    "tamperRate=1.0 should always return TAMPER (iteration " + i + ")");
        }
    }

    @Test
    void tamperProfileRateZero() {
        SecurityImpairmentEngine engine =
                new SecurityImpairmentEngine(LinkProfile.SIGNING_TAMPER, 0.0);
        byte[] frame = buildFrame(false);
        for (int i = 0; i < 100; i++) {
            assertEquals(SecurityImpairmentEngine.SecurityAction.FORWARD,
                    engine.verdict(frame),
                    "tamperRate=0.0 should always return FORWARD (iteration " + i + ")");
        }
    }

    @Test
    void tamperProfileRateHalf() {
        SecurityImpairmentEngine engine =
                new SecurityImpairmentEngine(LinkProfile.SIGNING_TAMPER, 0.5);
        byte[] frame = buildFrame(false);
        int tamperCount = 0;
        int totalRuns = 1000;
        for (int i = 0; i < totalRuns; i++) {
            if (engine.verdict(frame) == SecurityImpairmentEngine.SecurityAction.TAMPER) {
                tamperCount++;
            }
        }
        assertTrue(tamperCount >= 400 && tamperCount <= 600,
                "tamperRate=0.5 should produce ~50% TAMPER, got " + tamperCount + "/" + totalRuns);
    }

    @Test
    void unsignedProfileStripsSignedFrame() {
        SecurityImpairmentEngine engine =
                new SecurityImpairmentEngine(LinkProfile.SIGNING_UNSIGNED, 0.0);
        byte[] signedFrame = buildFrame(true);
        assertEquals(SecurityImpairmentEngine.SecurityAction.STRIP_SIGNATURE,
                engine.verdict(signedFrame),
                "SIGNING_UNSIGNED + signed frame (INC bit 0 set) should return STRIP_SIGNATURE");
    }

    @Test
    void unsignedProfileForwardsUnsignedFrame() {
        SecurityImpairmentEngine engine =
                new SecurityImpairmentEngine(LinkProfile.SIGNING_UNSIGNED, 0.0);
        byte[] unsignedFrame = buildFrame(false);
        assertEquals(SecurityImpairmentEngine.SecurityAction.FORWARD,
                engine.verdict(unsignedFrame),
                "SIGNING_UNSIGNED + unsigned frame (INC bit 0 clear) should return FORWARD");
    }

    // --- applyTamper 测试 ---

    @Test
    void applyTamperModifiesPayloadOnly() {
        byte[] original = buildFrame(true);
        byte[] tampered = SecurityImpairmentEngine.applyTamper(original);

        // 头部字节 0~9 不变（STX/LEN/INC/COMPAT/SEQ/SID/CID/MSGID）
        for (int i = 0; i <= 9; i++) {
            assertEquals(original[i], tampered[i],
                    "Header byte " + i + " should not change after tamper");
        }

        // CRC + 签名区域不变（从 payload 结束位置到帧尾）
        int payloadLen = original[1] & 0xFF;
        int afterPayloadStart = 10 + payloadLen;
        for (int i = afterPayloadStart; i < original.length; i++) {
            assertEquals(original[i], tampered[i],
                    "Post-payload byte " + i + " should not change after tamper");
        }

        // payload 区域至少有一个字节发生变化
        boolean payloadChanged = false;
        for (int i = 10; i < 10 + payloadLen; i++) {
            if (original[i] != tampered[i]) {
                payloadChanged = true;
                break;
            }
        }
        assertTrue(payloadChanged, "At least one payload byte should differ after tamper");
    }

    @Test
    void applyTamperFlips1To3Bits() {
        byte[] original = buildFrame(true);
        byte[] tampered = SecurityImpairmentEngine.applyTamper(original);

        int payloadLen = original[1] & 0xFF;
        int bitDiffCount = 0;
        for (int i = 10; i < 10 + payloadLen; i++) {
            // byte 提升为 int 会符号扩展（0x80 → 0xFFFFFF80），必须先掩码再计数
            int xor = (original[i] ^ tampered[i]) & 0xFF;
            bitDiffCount += Integer.bitCount(xor);
        }
        assertTrue(bitDiffCount >= 1 && bitDiffCount <= 3,
                "Tamper should flip 1-3 bits in payload, got " + bitDiffCount);
    }

    // --- applyStripSignature 测试 ---

    @Test
    void applyStripSignatureClearsIncBit() {
        byte[] signedFrame = buildFrame(true);
        byte[] stripped = SecurityImpairmentEngine.applyStripSignature(signedFrame);

        // INC bit 0 应被清除
        assertEquals(0, stripped[2] & 0x01,
                "INC bit 0 should be 0 after strip signature");

        // 帧长度应 = 12 + LEN
        int payloadLen = signedFrame[1] & 0xFF;
        assertEquals(12 + payloadLen, stripped.length,
                "Stripped frame length should be 12+LEN = " + (12 + payloadLen));
    }

    @Test
    void applyStripSignatureOnUnsignedFrame() {
        byte[] unsignedFrame = buildFrame(false);
        byte[] stripped = SecurityImpairmentEngine.applyStripSignature(unsignedFrame);

        // 对已无签名的帧剥离 → 帧内容不变（INC 已为 0，长度已是 12+LEN）
        assertArrayEquals(unsignedFrame, stripped,
                "Stripping an unsigned frame should not change it");
    }

    // --- stats 测试 ---

    @Test
    void statsReportsAllCounts() {
        // 使用 SIGNING_UNSIGNED 画像：对签名帧产生 STRIP_SIGNATURE，对非签名帧产生 FORWARD
        SecurityImpairmentEngine engine =
                new SecurityImpairmentEngine(LinkProfile.SIGNING_UNSIGNED, 0.0);
        byte[] signedFrame = buildFrame(true);
        byte[] unsignedFrame = buildFrame(false);

        // 跑一轮：2 次剥离 + 3 次转发
        engine.verdict(signedFrame);
        engine.verdict(signedFrame);
        engine.verdict(unsignedFrame);
        engine.verdict(unsignedFrame);
        engine.verdict(unsignedFrame);

        String stats = engine.stats();
        assertTrue(stats.contains("fwd="), "Stats should contain fwd=");
        assertTrue(stats.contains("tampered="), "Stats should contain tampered=");
        assertTrue(stats.contains("stripped="), "Stats should contain stripped=");
        assertTrue(stats.contains("fwd=3"), "Stats should report fwd=3, got: " + stats);
        assertTrue(stats.contains("stripped=2"), "Stats should report stripped=2, got: " + stats);
        assertTrue(stats.contains("tampered=0"), "Stats should report tampered=0, got: " + stats);
    }
}
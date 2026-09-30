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
            // 官方签名块 13B = linkId(1B) + timestamp(6B) + signature(6B)
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
    void applyTamperModifiesSignatureBlockOnly() {
        byte[] original = buildFrame(true);
        byte[] tampered = SecurityImpairmentEngine.applyTamper(original);

        int payloadLen = original[1] & 0xFF;
        int sigBlockStart = 12 + payloadLen;      // linkId + timestamp + signature
        int signatureStart = sigBlockStart + 7;   // 只有最后 6 字节允许变化

        // 帧长不变（篡改不是截断）
        assertEquals(original.length, tampered.length, "Tamper must not change frame length");

        // CRC 覆盖区（头 + payload + CRC）与 linkId/timestamp 全部原样：
        // 这是本画像的意义——接收方必须能过 CRC，才会走下去验签并失败
        for (int i = 0; i < signatureStart; i++) {
            assertEquals(original[i], tampered[i],
                    "Byte " + i + " (CRC-covered region / linkId / timestamp) must be untouched");
        }

        boolean signatureChanged = false;
        for (int i = signatureStart; i < original.length; i++) {
            if (original[i] != tampered[i]) {
                signatureChanged = true;
                break;
            }
        }
        assertTrue(signatureChanged,
                "At least one signature byte must differ, otherwise the attack is a no-op");
    }

    @Test
    void applyTamperFlips1To3BitsInSignature() {
        byte[] original = buildFrame(true);
        byte[] tampered = SecurityImpairmentEngine.applyTamper(original);

        int payloadLen = original[1] & 0xFF;
        int signatureStart = 12 + payloadLen + 7;
        int bitDiffCount = 0;
        for (int i = signatureStart; i < original.length; i++) {
            // byte 提升为 int 会符号扩展（0x80 → 0xFFFFFF80），必须先掩码再计数
            int xor = (original[i] ^ tampered[i]) & 0xFF;
            bitDiffCount += Integer.bitCount(xor);
        }
        assertTrue(bitDiffCount >= 1 && bitDiffCount <= 3,
                "Tamper should flip 1-3 bits in the signature block, got " + bitDiffCount);
    }

    @Test
    void applyTamperLeavesUnsignedFrameUnchanged() {
        byte[] unsignedFrame = buildFrame(false);
        byte[] result = SecurityImpairmentEngine.applyTamper(unsignedFrame);

        // 未签名帧没有签名块可篡改；若去改 payload 就变成"CRC 破坏帧"，
        // 接收方会在解析阶段丢弃，模拟不出签名攻击——所以必须原样返回
        assertArrayEquals(unsignedFrame, result,
                "Unsigned frame must pass through applyTamper untouched");
    }

    // --- applyStripSignature 测试 ---

    @Test
    void applyStripSignatureProducesCrcValidUnsignedFrame() {
        byte[] signedFrame = buildFrame(true);
        byte[] stripped = SecurityImpairmentEngine.applyStripSignature(signedFrame);

        // 剥离必须产出"合法的未签名帧"：INC bit0 在 CRC 覆盖范围内，清掉它不重算 CRC
        // 就是一帧坏数据，接收方在解析阶段丢弃 → 永远测不到"未签名被拒"。
        int payloadLen = stripped[1] & 0xFF;
        int crcExtra = io.aerofleet.mavlink.MavlinkMessageInfo.crcExtraOf(0);  // HEARTBEAT
        int crc = io.aerofleet.mavlink.MavlinkCrc.init();
        for (int i = 1; i <= 9; i++) {
            crc = io.aerofleet.mavlink.MavlinkCrc.accumulate(crc, stripped[i]);
        }
        crc = io.aerofleet.mavlink.MavlinkCrc.accumulate(crc, stripped, 10, payloadLen);
        crc = io.aerofleet.mavlink.MavlinkCrc.accumulate(crc, crcExtra);

        int embedded = (stripped[10 + payloadLen] & 0xFF) | ((stripped[11 + payloadLen] & 0xFF) << 8);
        assertEquals(crc, embedded,
                "Stripped frame must carry the CRC recomputed for INC bit 0 cleared");

        // 且必须能被正常解析为未签名帧（接收方走得到验签分支的前提）
        io.aerofleet.mavlink.MavlinkFrame decoded = io.aerofleet.mavlink.MavlinkFrame.decodeV2(stripped);
        assertFalse(decoded.isSigned(), "Stripped frame must decode as an unsigned frame");
    }

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
package io.aerofleet.mavlink;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MavlinkFrame 签名帧格式单元测试（C5-T13）：
 * 1. 签名帧 encodeV2() 长度 = 25+LEN，INC bit 0 置位，签名数据追加在 CRC 后
 * 2. decodeV2(encodeV2()) 往返一致——linkId/timestamp/signature 字段正确恢复
 * 3. 非签名帧 decodeV2() 正确解析，linkId=0/timestamp=0/signature=null
 * 4. 非签名帧 encodeV2() 输出与修改前逐字节一致
 * 5. 签名帧 CRC 与 of() 计算结果一致（CRC 不含签名数据）
 * 6. 签名帧长度不足 25+LEN 时抛异常
 * 7. 签名帧 LEN 字段 = payload 长度（不含签名）
 */
class MavlinkFrameTest {

    private static final int TEST_MSG_ID = 0; // HEARTBEAT
    private static final int TEST_CRC_EXTRA = 50; // HEARTBEAT CRC_EXTRA
    private static final byte[] TEST_PAYLOAD = new byte[]{(byte)4, (byte)2, (byte)12, (byte)0, (byte)209, (byte)0, (byte)3, (byte)0, (byte)0};
    private static final int TEST_LINK_ID = 1;
    private static final long TEST_TIMESTAMP = 12345L;
    private static final byte[] TEST_SIGNATURE = new byte[]{
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08
    };

    // ========== 1. encodeV2SignedProducesCorrectFormat ==========

    @Test
    @DisplayName("签名帧 encodeV2() 长度 = 25+LEN，INC bit 0 置位，签名数据追加在 CRC 后")
    void encodeV2SignedProducesCorrectFormat() {
        MavlinkFrame frame = MavlinkFrame.ofSigned(
                1, 1, 0, TEST_MSG_ID, TEST_CRC_EXTRA, TEST_PAYLOAD,
                TEST_LINK_ID, TEST_TIMESTAMP, TEST_SIGNATURE);

        byte[] encoded = frame.encodeV2();
        int len = TEST_PAYLOAD.length;

        // 长度 = 12 (header) + LEN (payload) + 2 (CRC) + 13 (signature data) = 27 + LEN
        // 但任务描述说 25+LEN，让我验证：STX(1)+LEN(1)+INC(1)+COMPAT(1)+SEQ(1)+SID(1)+CID(1)+MSGID(3) = 10
        // + PAYLOAD(LEN) + CRC(2) + SIG(13) = 10 + LEN + 2 + 13 = 25 + LEN
        assertEquals(27 + len, encoded.length, "签名帧 encodeV2() 长度应为 27+LEN");

        // STX = 0xFD
        assertEquals(MavlinkFrame.STX_V2, encoded[0] & 0xFF, "STX 应为 0xFD");

        // INC bit 0 置位
        assertEquals(0x01, encoded[2] & 0xFF, "INC 应为 0x01（签名帧）");

        // 签名数据追加在 CRC 后
        int sigOffset = 12 + len; // CRC 后的偏移
        assertEquals(TEST_LINK_ID, encoded[sigOffset] & 0xFF, "linkId 字节应正确");

        // timestamp 6 字节大端
        long decodedTs = ((long) (encoded[sigOffset + 1] & 0xFF) << 40)
                | ((long) (encoded[sigOffset + 2] & 0xFF) << 32)
                | ((long) (encoded[sigOffset + 3] & 0xFF) << 24)
                | ((long) (encoded[sigOffset + 4] & 0xFF) << 16)
                | ((long) (encoded[sigOffset + 5] & 0xFF) << 8)
                | ((long) (encoded[sigOffset + 6] & 0xFF));
        assertEquals(TEST_TIMESTAMP, decodedTs, "timestamp 字节应正确");

        // signature 8 字节
        byte[] decodedSig = new byte[8];
        System.arraycopy(encoded, sigOffset + 7, decodedSig, 0, 8);
        assertArrayEquals(TEST_SIGNATURE, decodedSig, "signature 字节应正确");
    }

    // ========== 2. decodeV2ParsesSignedFrame ==========

    @Test
    @DisplayName("decodeV2(encodeV2()) 往返一致——linkId/timestamp/signature 字段正确恢复")
    void decodeV2ParsesSignedFrame() {
        MavlinkFrame original = MavlinkFrame.ofSigned(
                1, 1, 0, TEST_MSG_ID, TEST_CRC_EXTRA, TEST_PAYLOAD,
                TEST_LINK_ID, TEST_TIMESTAMP, TEST_SIGNATURE);

        byte[] encoded = original.encodeV2();
        MavlinkFrame decoded = MavlinkFrame.decodeV2(encoded);

        assertTrue(decoded.isSigned(), "解码后应为签名帧");
        assertEquals(TEST_LINK_ID, decoded.getLinkId(), "linkId 应正确恢复");
        assertEquals(TEST_TIMESTAMP, decoded.getTimestamp(), "timestamp 应正确恢复");
        assertArrayEquals(TEST_SIGNATURE, decoded.getSignature(), "signature 应正确恢复");

        // 其他字段也应一致
        assertEquals(original.getPayloadLength(), decoded.getPayloadLength(), "payloadLength 应一致");
        assertEquals(original.getIncompatibilityFlags(), decoded.getIncompatibilityFlags(), "INC 应一致");
        assertEquals(original.getSequence(), decoded.getSequence(), "sequence 应一致");
        assertEquals(original.getSystemId(), decoded.getSystemId(), "systemId 应一致");
        assertEquals(original.getComponentId(), decoded.getComponentId(), "componentId 应一致");
        assertEquals(original.getMessageId(), decoded.getMessageId(), "messageId 应一致");
        assertArrayEquals(original.getPayload(), decoded.getPayload(), "payload 应一致");
        assertEquals(original.getCrc(), decoded.getCrc(), "crc 应一致");
    }

    // ========== 3. decodeV2ParsesUnsignedFrame ==========

    @Test
    @DisplayName("非签名帧 decodeV2() 正确解析，linkId=0/timestamp=0/signature=null")
    void decodeV2ParsesUnsignedFrame() {
        MavlinkFrame original = MavlinkFrame.of(
                1, 1, 0, TEST_MSG_ID, TEST_CRC_EXTRA, TEST_PAYLOAD);

        byte[] encoded = original.encodeV2();
        MavlinkFrame decoded = MavlinkFrame.decodeV2(encoded);

        assertFalse(decoded.isSigned(), "解码后应为非签名帧");
        assertEquals(0, decoded.getLinkId(), "非签名帧 linkId 应为 0");
        assertEquals(0L, decoded.getTimestamp(), "非签名帧 timestamp 应为 0");
        assertNull(decoded.getSignature(), "非签名帧 signature 应为 null");

        // 其他字段也应一致
        assertEquals(original.getPayloadLength(), decoded.getPayloadLength(), "payloadLength 应一致");
        assertEquals(0, decoded.getIncompatibilityFlags(), "非签名帧 INC 应为 0");
        assertEquals(original.getSystemId(), decoded.getSystemId(), "systemId 应一致");
        assertEquals(original.getMessageId(), decoded.getMessageId(), "messageId 应一致");
        assertArrayEquals(original.getPayload(), decoded.getPayload(), "payload 应一致");
        assertEquals(original.getCrc(), decoded.getCrc(), "crc 应一致");
    }

    // ========== 4. encodeV2UnsignedBackwardCompatible ==========

    @Test
    @DisplayName("非签名帧 encodeV2() 输出与修改前逐字节一致")
    void encodeV2UnsignedBackwardCompatible() {
        MavlinkFrame frame = MavlinkFrame.of(
                1, 1, 0, TEST_MSG_ID, TEST_CRC_EXTRA, TEST_PAYLOAD);

        byte[] encoded = frame.encodeV2();
        int len = TEST_PAYLOAD.length;

        // 非签名帧长度 = 12 + LEN（不含签名数据）
        assertEquals(12 + len, encoded.length, "非签名帧长度应为 12+LEN");

        // STX = 0xFD
        assertEquals(MavlinkFrame.STX_V2, encoded[0] & 0xFF, "STX 应为 0xFD");

        // LEN = payload 长度
        assertEquals(len, encoded[1] & 0xFF, "LEN 应为 payload 长度");

        // INC = 0（非签名帧）
        assertEquals(0, encoded[2] & 0xFF, "INC 应为 0");

        // COMPAT = 0
        assertEquals(0, encoded[3] & 0xFF, "COMPAT 应为 0");

        // SEQ = 0
        assertEquals(0, encoded[4] & 0xFF, "SEQ 应为 0");

        // SID = 1
        assertEquals(1, encoded[5] & 0xFF, "SID 应为 1");

        // CID = 1
        assertEquals(1, encoded[6] & 0xFF, "CID 应为 1");

        // MSGID = 0 (3 bytes LE)
        assertEquals(0, encoded[7] & 0xFF, "MSGID[0] 应为 0");
        assertEquals(0, encoded[8] & 0xFF, "MSGID[1] 应为 0");
        assertEquals(0, encoded[9] & 0xFF, "MSGID[2] 应为 0");

        // PAYLOAD
        for (int i = 0; i < len; i++) {
            assertEquals(TEST_PAYLOAD[i], encoded[10 + i], "PAYLOAD[" + i + "] 应一致");
        }

        // CRC (2 bytes LE)
        int crc = (encoded[10 + len] & 0xFF) | ((encoded[11 + len] & 0xFF) << 8);
        assertEquals(frame.getCrc(), crc, "CRC 应一致");
    }

    // ========== 5. ofSignedCrcConsistent ==========

    @Test
    @DisplayName("签名帧 CRC 与 of() 计算结果一致（CRC 不含签名数据）")
    void ofSignedCrcConsistent() {
        // 构造两个签名帧，只有签名数据不同（linkId/timestamp/signature 不同），
        // 其他参数相同，CRC 应该一致（因为 CRC 不含签名数据）
        byte[] sig1 = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        byte[] sig2 = new byte[]{0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte)0x88};

        MavlinkFrame frame1 = MavlinkFrame.ofSigned(
                1, 1, 0, TEST_MSG_ID, TEST_CRC_EXTRA, TEST_PAYLOAD,
                1, 100L, sig1);
        MavlinkFrame frame2 = MavlinkFrame.ofSigned(
                1, 1, 0, TEST_MSG_ID, TEST_CRC_EXTRA, TEST_PAYLOAD,
                2, 200L, sig2);

        assertEquals(frame1.getCrc(), frame2.getCrc(),
                "签名数据不同的签名帧 CRC 应一致（CRC 不含签名数据）");

        // 签名帧 CRC 与非签名帧 CRC 不同（因为 INC 字段不同：0x01 vs 0x00）
        MavlinkFrame unsignedFrame = MavlinkFrame.of(
                1, 1, 0, TEST_MSG_ID, TEST_CRC_EXTRA, TEST_PAYLOAD);
        assertNotEquals(unsignedFrame.getCrc(), frame1.getCrc(),
                "签名帧 CRC 应与非签名帧不同（INC 字段影响 CRC）");
    }

    // ========== 6. decodeV2RejectsTruncatedSignature ==========

    @Test
    @DisplayName("签名帧长度不足 25+LEN 时抛异常")
    void decodeV2RejectsTruncatedSignature() {
        MavlinkFrame frame = MavlinkFrame.ofSigned(
                1, 1, 0, TEST_MSG_ID, TEST_CRC_EXTRA, TEST_PAYLOAD,
                TEST_LINK_ID, TEST_TIMESTAMP, TEST_SIGNATURE);

        byte[] encoded = frame.encodeV2();
        int len = TEST_PAYLOAD.length;

        // 截断掉签名数据部分（保留 12+LEN 字节 = header+payload+CRC）
        byte[] truncated = Arrays.copyOf(encoded, 12 + len);

        // INC bit 0 置位但长度不足，应抛异常
        assertThrows(IllegalArgumentException.class,
                () -> MavlinkFrame.decodeV2(truncated),
                "签名帧长度不足 25+LEN 时应抛 IllegalArgumentException");

        // 也测试截断一部分签名数据的情况
        byte[] partialTruncated = Arrays.copyOf(encoded, 12 + len + 6); // 只有 6 字节签名数据，需要 13
        assertThrows(IllegalArgumentException.class,
                () -> MavlinkFrame.decodeV2(partialTruncated),
                "签名帧签名数据不完整时应抛 IllegalArgumentException");
    }

    // ========== 7. lenFieldExcludesSignature ==========

    @Test
    @DisplayName("签名帧 LEN 字段 = payload 长度（不含签名）")
    void lenFieldExcludesSignature() {
        MavlinkFrame frame = MavlinkFrame.ofSigned(
                1, 1, 0, TEST_MSG_ID, TEST_CRC_EXTRA, TEST_PAYLOAD,
                TEST_LINK_ID, TEST_TIMESTAMP, TEST_SIGNATURE);

        byte[] encoded = frame.encodeV2();

        // LEN 字段 = payload 长度，不含签名数据
        assertEquals(TEST_PAYLOAD.length, encoded[1] & 0xFF,
                "LEN 字段应为 payload 长度（不含签名数据）");

        // 解码后 payloadLength 也应一致
        MavlinkFrame decoded = MavlinkFrame.decodeV2(encoded);
        assertEquals(TEST_PAYLOAD.length, decoded.getPayloadLength(),
                "解码后 payloadLength 应为 payload 长度（不含签名）");
    }
}
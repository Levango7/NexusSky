package io.aerofleet.mavlink;

import io.aerofleet.mavlink.messages.EnvironmentAlert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MavlinkFrame.decodeV2Verified() 强校验解码单元测试：
 * 1. 官方标准帧（HEARTBEAT msgId=0）CRC 强校验通过
 * 2. 搬迁带私有帧（EnvironmentAlert msgId=30001）通过——验证 30000+ 段经 EXTENDED_INFOS 查 CRC_EXTRA
 * 3. 篡改 payload 后 CRC 不一致，抛 MavlinkException 且消息含 expected/received 十六进制
 * 4. 签名帧强校验通过（CRC 不覆盖签名数据）
 * 5. 未注册 msgId（预留段 30099）抛 MavlinkException
 */
class MavlinkFrameVerifiedDecodeTest {

    /** 官方 HEARTBEAT（msgId=0, CRC_EXTRA=50, LEN=9）。 */
    private static final int HEARTBEAT_ID = 0;
    private static final int HEARTBEAT_CRC_EXTRA = 50;
    private static final byte[] HEARTBEAT_PAYLOAD = new byte[]{
            (byte) 4, (byte) 2, (byte) 12, (byte) 0, (byte) 209, (byte) 0, (byte) 3, (byte) 0, (byte) 0
    };

    private static final byte[] TEST_SIGNATURE = new byte[]{0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F};

    // ========== 1. verifiedDecodeAcceptsOfficialFrame ==========

    @Test
    @DisplayName("官方标准帧（HEARTBEAT）decodeV2Verified() 强校验通过")
    void verifiedDecodeAcceptsOfficialFrame() {
        MavlinkFrame frame = MavlinkFrame.of(
                255, 190, 7, HEARTBEAT_ID, HEARTBEAT_CRC_EXTRA, HEARTBEAT_PAYLOAD);

        MavlinkFrame decoded = MavlinkFrame.decodeV2Verified(frame.encodeV2());

        assertEquals(HEARTBEAT_ID, decoded.getMessageId(), "msgId 应为 HEARTBEAT(0)");
        assertEquals(255, decoded.getSystemId(), "systemId 应一致");
        assertEquals(190, decoded.getComponentId(), "componentId 应一致");
        assertEquals(7, decoded.getSequence(), "sequence 应一致");
        assertArrayEquals(HEARTBEAT_PAYLOAD, decoded.getPayload(), "payload 应一致");
        assertTrue(decoded.verifyChecksum(), "往返帧 verifyChecksum() 应为 true");
    }

    // ========== 2. verifiedDecodeAcceptsRelocatedBandFrame ==========

    @Test
    @DisplayName("搬迁带私有帧（EnvironmentAlert msgId=30001）强校验通过——EXTENDED_INFOS 查表路径")
    void verifiedDecodeAcceptsRelocatedBandFrame() {
        EnvironmentAlert alert = new EnvironmentAlert(2, 3, 500, 400, "crc verified");
        byte[] payload = alert.encode();
        int crcExtra = MavlinkMessageInfo.crcExtraOf(EnvironmentAlert.ID);

        MavlinkFrame frame = MavlinkFrame.of(
                1, 1, 0, EnvironmentAlert.ID, crcExtra, payload);
        MavlinkFrame decoded = MavlinkFrame.decodeV2Verified(frame.encodeV2());

        assertEquals(EnvironmentAlert.ID, decoded.getMessageId(),
                "msgId 应为搬迁后的 30000 段私有 id");
        assertArrayEquals(payload, decoded.getPayload(), "payload 应一致");
        assertTrue(decoded.verifyChecksum(), "搬迁带帧 verifyChecksum() 应为 true");
    }

    // ========== 3. verifiedDecodeRejectsTamperedPayload ==========

    @Test
    @DisplayName("篡改 payload 后 CRC 不一致，抛 MavlinkException 且消息含 expected/received")
    void verifiedDecodeRejectsTamperedPayload() {
        MavlinkFrame frame = MavlinkFrame.of(
                255, 190, 7, HEARTBEAT_ID, HEARTBEAT_CRC_EXTRA, HEARTBEAT_PAYLOAD);

        byte[] encoded = frame.encodeV2();
        encoded[10] ^= 0x55; // 篡改首个 payload 字节

        MavlinkException ex = assertThrows(MavlinkException.class,
                () -> MavlinkFrame.decodeV2Verified(encoded),
                "CRC 不一致时应抛 MavlinkException");
        assertTrue(ex.getMessage().contains("msgId=" + HEARTBEAT_ID),
                "异常消息应包含 msgId，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("expected=0x"),
                "异常消息应包含期望 CRC 十六进制，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("received=0x"),
                "异常消息应包含收到 CRC 十六进制，实际: " + ex.getMessage());
    }

    // ========== 4. verifiedDecodeAcceptsSignedFrame ==========

    @Test
    @DisplayName("签名帧强校验通过（CRC 不覆盖签名数据）")
    void verifiedDecodeAcceptsSignedFrame() {
        int crcExtra = MavlinkMessageInfo.crcExtraOf(EnvironmentAlert.ID);
        byte[] payload = new EnvironmentAlert(1, 2, 300, 200, "signed").encode();

        MavlinkFrame frame = MavlinkFrame.ofSigned(
                255, 190, 9, EnvironmentAlert.ID, crcExtra, payload,
                3, 987654321L, TEST_SIGNATURE);

        MavlinkFrame decoded = MavlinkFrame.decodeV2Verified(frame.encodeV2());

        assertTrue(decoded.isSigned(), "解码后应为签名帧");
        assertEquals(EnvironmentAlert.ID, decoded.getMessageId(), "msgId 应一致");
        assertEquals(3, decoded.getLinkId(), "linkId 应一致");
        assertEquals(987654321L, decoded.getTimestamp(), "timestamp 应一致");
        assertArrayEquals(TEST_SIGNATURE, decoded.getSignature(), "signature 应一致");
    }

    // ========== 5. verifiedDecodeRejectsUnregisteredMsgId ==========

    @Test
    @DisplayName("未注册 msgId（预留段 30099）抛 MavlinkException")
    void verifiedDecodeRejectsUnregisteredMsgId() {
        // 30099 属 30000-30099 私有段中未分配的预留部分，注册表中不存在
        MavlinkFrame frame = MavlinkFrame.of(
                1, 1, 0, 30099, 0, new byte[]{1, 2, 3});

        MavlinkException ex = assertThrows(MavlinkException.class,
                () -> MavlinkFrame.decodeV2Verified(frame.encodeV2()),
                "未注册 msgId 应抛 MavlinkException");
        assertTrue(ex.getMessage().contains("30099"),
                "异常消息应包含未注册的 msgId，实际: " + ex.getMessage());
    }
}

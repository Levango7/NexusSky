package io.aerofleet.mavlink.security;

import io.aerofleet.mavlink.messages.Heartbeat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MavlinkSigner 单元测试：验证 HMAC-SHA256 签名/验证流程及签名关闭时的行为。
 * 包含标准协议签名（MAVLink v2 signing）和简化签名（已废弃，向后兼容）两种模式。
 */
class MavlinkSignerTest {

    private static final String TEST_SECRET = "test-secret-key-12345";

    // ========== 标准协议签名（MAVLink v2 signing） ==========

    @Test
    @DisplayName("标准签名：同一 frameBytes + linkId + timestamp + key → 签名一致")
    void standardSignDeterministic() {
        MavlinkSigner signer = new MavlinkSigner(true, TEST_SECRET);
        byte[] frameBytes = new byte[]{(byte)0xFD, 0x09, 0x01, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00,
                (byte)4, (byte)2, (byte)12, (byte)0, (byte)209, (byte)0, (byte)3, (byte)0, (byte)0, 0x12, 0x34};

        byte[] sig1 = signer.sign(frameBytes, 1, 12345L);
        byte[] sig2 = signer.sign(frameBytes, 1, 12345L);

        assertNotNull(sig1, "签名结果不应为 null");
        assertEquals(MavlinkSigner.SIGNATURE_LENGTH, sig1.length, "签名长度应为 8 字节");
        assertArrayEquals(sig1, sig2, "相同输入应产生相同签名");
    }

    @Test
    @DisplayName("标准签名：frameBytes 任意字节变化 → 签名不同")
    void standardSignDiffersOnFrameChange() {
        MavlinkSigner signer = new MavlinkSigner(true, TEST_SECRET);
        byte[] frameBytes1 = new byte[]{(byte)0xFD, 0x09, 0x01, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00,
                (byte)4, (byte)2, (byte)12, (byte)0, (byte)209, (byte)0, (byte)3, (byte)0, (byte)0, 0x12, 0x34};
        byte[] frameBytes2 = frameBytes1.clone();
        frameBytes2[10] ^= 0xFF; // 修改一个字节

        byte[] sig1 = signer.sign(frameBytes1, 1, 12345L);
        byte[] sig2 = signer.sign(frameBytes2, 1, 12345L);

        assertFalse(java.util.Arrays.equals(sig1, sig2), "frameBytes 变化后签名应不同");
    }

    @Test
    @DisplayName("标准签名：linkId 变化 → 签名不同")
    void standardSignDiffersOnLinkIdChange() {
        MavlinkSigner signer = new MavlinkSigner(true, TEST_SECRET);
        byte[] frameBytes = new byte[]{(byte)0xFD, 0x09, 0x01, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00,
                (byte)4, (byte)2, (byte)12, (byte)0, (byte)209, (byte)0, (byte)3, (byte)0, (byte)0, 0x12, 0x34};

        byte[] sig1 = signer.sign(frameBytes, 1, 12345L);
        byte[] sig2 = signer.sign(frameBytes, 2, 12345L);

        assertFalse(java.util.Arrays.equals(sig1, sig2), "linkId 变化后签名应不同");
    }

    @Test
    @DisplayName("标准签名：timestamp 变化 → 签名不同")
    void standardSignDiffersOnTimestampChange() {
        MavlinkSigner signer = new MavlinkSigner(true, TEST_SECRET);
        byte[] frameBytes = new byte[]{(byte)0xFD, 0x09, 0x01, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00,
                (byte)4, (byte)2, (byte)12, (byte)0, (byte)209, (byte)0, (byte)3, (byte)0, (byte)0, 0x12, 0x34};

        byte[] sig1 = signer.sign(frameBytes, 1, 12345L);
        byte[] sig2 = signer.sign(frameBytes, 1, 12346L);

        assertFalse(java.util.Arrays.equals(sig1, sig2), "timestamp 变化后签名应不同");
    }

    @Test
    @DisplayName("标准验证：合法签名 → true")
    void standardVerifyCorrectSignature() {
        MavlinkSigner signer = new MavlinkSigner(true, TEST_SECRET);
        byte[] frameBytes = new byte[]{(byte)0xFD, 0x09, 0x01, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00,
                (byte)4, (byte)2, (byte)12, (byte)0, (byte)209, (byte)0, (byte)3, (byte)0, (byte)0, 0x12, 0x34};

        byte[] signature = signer.sign(frameBytes, 1, 12345L);
        assertNotNull(signature, "签名结果不应为 null");

        boolean valid = signer.verify(frameBytes, 1, 12345L, signature);
        assertTrue(valid, "合法签名应验证通过");
    }

    @Test
    @DisplayName("标准验证：signature 任意字节修改 → false")
    void standardVerifyTamperedSignature() {
        MavlinkSigner signer = new MavlinkSigner(true, TEST_SECRET);
        byte[] frameBytes = new byte[]{(byte)0xFD, 0x09, 0x01, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00,
                (byte)4, (byte)2, (byte)12, (byte)0, (byte)209, (byte)0, (byte)3, (byte)0, (byte)0, 0x12, 0x34};

        byte[] signature = signer.sign(frameBytes, 1, 12345L);
        assertNotNull(signature);

        // 篡改签名：翻转第一个字节
        byte[] tampered = signature.clone();
        tampered[0] ^= 0xFF;

        boolean valid = signer.verify(frameBytes, 1, 12345L, tampered);
        assertFalse(valid, "篡改后的签名应验证失败");
    }

    @Test
    @DisplayName("标准验证：signature=null → false")
    void standardVerifyNullSignature() {
        MavlinkSigner signer = new MavlinkSigner(true, TEST_SECRET);
        byte[] frameBytes = new byte[]{(byte)0xFD, 0x09, 0x01, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00,
                (byte)4, (byte)2, (byte)12, (byte)0, (byte)209, (byte)0, (byte)3, (byte)0, (byte)0, 0x12, 0x34};

        boolean valid = signer.verify(frameBytes, 1, 12345L, null);
        assertFalse(valid, "null 签名应验证失败");
    }

    @Test
    @DisplayName("标准验证：signature 长度≠8 → false")
    void standardVerifyWrongLengthSignature() {
        MavlinkSigner signer = new MavlinkSigner(true, TEST_SECRET);
        byte[] frameBytes = new byte[]{(byte)0xFD, 0x09, 0x01, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00,
                (byte)4, (byte)2, (byte)12, (byte)0, (byte)209, (byte)0, (byte)3, (byte)0, (byte)0, 0x12, 0x34};

        byte[] shortSig = new byte[4];
        boolean valid = signer.verify(frameBytes, 1, 12345L, shortSig);
        assertFalse(valid, "长度不足的签名应验证失败");

        byte[] longSig = new byte[16];
        valid = signer.verify(frameBytes, 1, 12345L, longSig);
        assertFalse(valid, "长度过长的签名应验证失败");
    }

    @Test
    @DisplayName("标准签名：enabled=false → sign() 返回 null")
    void disabledSignReturnsNull() {
        MavlinkSigner signer = new MavlinkSigner(false, TEST_SECRET);
        byte[] frameBytes = new byte[]{(byte)0xFD, 0x09, 0x01, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00,
                (byte)4, (byte)2, (byte)12, (byte)0, (byte)209, (byte)0, (byte)3, (byte)0, (byte)0, 0x12, 0x34};

        byte[] signature = signer.sign(frameBytes, 1, 12345L);
        assertNull(signature, "签名关闭时 sign 应返回 null");
    }

    @Test
    @DisplayName("标准验证：enabled=false → verify() 返回 true")
    void disabledVerifyAlwaysTrue() {
        MavlinkSigner signer = new MavlinkSigner(false, TEST_SECRET);
        byte[] frameBytes = new byte[]{(byte)0xFD, 0x09, 0x01, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00,
                (byte)4, (byte)2, (byte)12, (byte)0, (byte)209, (byte)0, (byte)3, (byte)0, (byte)0, 0x12, 0x34};

        boolean valid = signer.verify(frameBytes, 1, 12345L, null);
        assertTrue(valid, "签名关闭时 verify 应始终返回 true");

        byte[] fakeSig = new byte[8];
        valid = signer.verify(frameBytes, 1, 12345L, fakeSig);
        assertTrue(valid, "签名关闭时 verify 应始终返回 true");
    }

    // ========== 简化签名（已废弃，向后兼容） ==========

    @Test
    void signAndVerifyCorrectFlow() {
        MavlinkSigner signer = new MavlinkSigner(true, TEST_SECRET);
        int msgId = 0; // HEARTBEAT
        byte[] payload = new Heartbeat(4, 2, 12, 209, 3).encode();

        byte[] signature = signer.sign(msgId, payload);

        assertNotNull(signature, "签名结果不应为 null");
        assertEquals(MavlinkSigner.SIGNATURE_LENGTH, signature.length,
                "签名长度应为 8 字节");

        boolean valid = signer.verify(msgId, payload, signature);
        assertTrue(valid, "正确签名应验证通过");
    }

    @Test
    void verifyFailsWithTamperedSignature() {
        MavlinkSigner signer = new MavlinkSigner(true, TEST_SECRET);
        int msgId = 0;
        byte[] payload = new Heartbeat(4, 2, 12, 209, 3).encode();

        byte[] signature = signer.sign(msgId, payload);
        assertNotNull(signature);

        // 篡改签名：翻转第一个字节
        byte[] tampered = signature.clone();
        tampered[0] ^= 0xFF;

        boolean valid = signer.verify(msgId, payload, tampered);
        assertFalse(valid, "篡改后的签名应验证失败");
    }

    @Test
    void verifyFailsWithWrongPayload() {
        MavlinkSigner signer = new MavlinkSigner(true, TEST_SECRET);
        int msgId = 0;
        byte[] payload1 = new Heartbeat(4, 2, 12, 209, 3).encode();
        byte[] payload2 = new Heartbeat(0, 2, 12, 0, 4).encode();

        byte[] signature = signer.sign(msgId, payload1);

        boolean valid = signer.verify(msgId, payload2, signature);
        assertFalse(valid, "不同 payload 的签名应验证失败");
    }

    @Test
    void verifyFailsWithWrongMsgId() {
        MavlinkSigner signer = new MavlinkSigner(true, TEST_SECRET);
        byte[] payload = new Heartbeat(4, 2, 12, 209, 3).encode();

        byte[] signature = signer.sign(0, payload);

        boolean valid = signer.verify(1, payload, signature);
        assertFalse(valid, "不同 msgId 的签名应验证失败");
    }

    @Test
    void verifyFailsWithNullSignature() {
        MavlinkSigner signer = new MavlinkSigner(true, TEST_SECRET);
        byte[] payload = new Heartbeat(4, 2, 12, 209, 3).encode();

        // 签名启用但 signature 为 null 时，应抛出 IllegalStateException
        assertThrows(IllegalStateException.class,
                () -> signer.verify(0, payload, null),
                "null 签名在签名启用时应抛出 IllegalStateException");
    }

    @Test
    void verifyFailsWithShortSignature() {
        MavlinkSigner signer = new MavlinkSigner(true, TEST_SECRET);
        byte[] payload = new Heartbeat(4, 2, 12, 209, 3).encode();

        byte[] shortSig = new byte[4];
        boolean valid = signer.verify(0, payload, shortSig);
        assertFalse(valid, "长度不足的签名应验证失败");
    }

    @Test
    void signingDisabledReturnsNullSignature() {
        MavlinkSigner signer = new MavlinkSigner(false, TEST_SECRET);
        byte[] payload = new Heartbeat(4, 2, 12, 209, 3).encode();

        byte[] signature = signer.sign(0, payload);
        assertNull(signature, "签名关闭时 sign 应返回 null");
    }

    @Test
    void signingDisabledVerifyAlwaysPasses() {
        MavlinkSigner signer = new MavlinkSigner(false, TEST_SECRET);
        byte[] payload = new Heartbeat(4, 2, 12, 209, 3).encode();

        boolean valid = signer.verify(0, payload, null);
        assertTrue(valid, "签名关闭时 verify 应始终返回 true");

        byte[] fakeSig = new byte[8];
        valid = signer.verify(0, payload, fakeSig);
        assertTrue(valid, "签名关闭时 verify 应始终返回 true");
    }

    @Test
    void messageRoundTripWithoutSigning() {
        // 签名关闭时，消息编解码应正常工作
        MavlinkSigner signer = new MavlinkSigner(false, TEST_SECRET);
        Heartbeat hb = new Heartbeat(4, 2, 12, 209, 3);

        hb.signWith(signer);
        assertNull(hb.getSignature(), "签名关闭时 signature 应为 null");

        boolean valid = hb.verifySignature(signer);
        assertTrue(valid, "签名关闭时 verifySignature 应返回 true");
    }

    @Test
    void messageSignAndVerifyWithSigning() {
        MavlinkSigner signer = new MavlinkSigner(true, TEST_SECRET);
        Heartbeat hb = new Heartbeat(4, 2, 12, 209, 3);

        hb.signWith(signer);
        byte[] sig = hb.getSignature();
        assertNotNull(sig, "签名启用时 signature 不应为 null");
        assertEquals(MavlinkSigner.SIGNATURE_LENGTH, sig.length,
                "签名长度应为 8 字节");

        boolean valid = hb.verifySignature(signer);
        assertTrue(valid, "签名启用时正确签名应验证通过");
    }

    @Test
    void messageVerifyFailsWithTamperedSignature() {
        MavlinkSigner signer = new MavlinkSigner(true, TEST_SECRET);
        Heartbeat hb = new Heartbeat(4, 2, 12, 209, 3);

        hb.signWith(signer);
        byte[] sig = hb.getSignature();
        assertNotNull(sig);

        // 篡改签名
        sig[0] ^= 0xFF;

        boolean valid = hb.verifySignature(signer);
        assertFalse(valid, "篡改后的签名应验证失败");
    }

    @Test
    void signatureDeterministic() {
        MavlinkSigner signer = new MavlinkSigner(true, TEST_SECRET);
        byte[] payload = new Heartbeat(4, 2, 12, 209, 3).encode();

        byte[] sig1 = signer.sign(0, payload);
        byte[] sig2 = signer.sign(0, payload);

        assertArrayEquals(sig1, sig2, "相同输入应产生相同签名");
    }

    @Test
    void differentSecretKeysProduceDifferentSignatures() {
        MavlinkSigner signer1 = new MavlinkSigner(true, "key1");
        MavlinkSigner signer2 = new MavlinkSigner(true, "key2");
        byte[] payload = new Heartbeat(4, 2, 12, 209, 3).encode();

        byte[] sig1 = signer1.sign(0, payload);
        byte[] sig2 = signer2.sign(0, payload);

        assertFalse(java.util.Arrays.equals(sig1, sig2),
                "不同密钥应产生不同签名");
    }

    @Test
    void configBasedConstructor() {
        MavlinkSignatureConfig config = new MavlinkSignatureConfig();
        config.setEnabled(true);
        config.setSecretKey(TEST_SECRET);

        MavlinkSigner signer = new MavlinkSigner(config);
        assertTrue(signer.isEnabled(), "配置构造的签名器应启用");

        byte[] payload = new Heartbeat(4, 2, 12, 209, 3).encode();
        byte[] sig = signer.sign(0, payload);
        assertNotNull(sig, "配置构造的签名器应能正常签名");
        assertTrue(signer.verify(0, payload, sig), "配置构造的签名器应能正常验证");
    }
}
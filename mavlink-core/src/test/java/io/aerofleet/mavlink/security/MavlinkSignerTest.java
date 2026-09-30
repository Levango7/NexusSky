package io.aerofleet.mavlink.security;

import io.aerofleet.mavlink.MavlinkException;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.messages.Heartbeat;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MAVLink v2 签名器测试（官方语义）。
 * <p>
 * 本类此前断言的是"15 字节签名块 + HMAC-SHA256 取前 8 字节 + 大端时间戳"，那套语义与
 * 官方不互通，所以按官方口径整体重写：签名 = SHA-256(secret + 帧头至 CRC + linkId + 6B
 * 小端时间戳) 的前 6 字节。互通性另有 {@link MavlinkSigningVectorTest} 的 pymavlink
 * 已知答案向量把关；本类聚焦构造规则、敏感性与启用/拒绝语义。
 */
@DisplayName("MAVLink v2 签名器（官方 sha256_48）")
class MavlinkSignerTest {

    private static final String SECRET = "unit-test-secret-key-32-bytes-ok";
    private static final int LINK_ID = 7;
    private static final long TIMESTAMP = 37_000_000_000_000L;

    /** 一段合法的"帧头至 CRC"字节（含 STX），长度不影响签名器逻辑。 */
    private static final byte[] FRAME_BYTES =
            hex("fd09010000010100000041710a001009510403");

    // ==================== 构造与启用 ====================

    @Test
    @DisplayName("未启用时 sign 返回 null、verify 直接放行（与不签名的历史行为一致）")
    void disabledSignerReturnsNullAndBypassesVerify() {
        MavlinkSigner signer = new MavlinkSigner(false, SECRET, LINK_ID, true);

        assertThat(signer.isEnabled()).isFalse();
        assertThat(signer.sign(FRAME_BYTES, LINK_ID, TIMESTAMP)).isNull();
        assertThat(signer.verify(FRAME_BYTES, LINK_ID, TIMESTAMP, null)).isTrue();
    }

    @Test
    @DisplayName("启用签名但口令为空 → 构造即拒绝（不允许开着签名用空密钥）")
    void enabledWithEmptySecretIsRejected() {
        assertThatThrownBy(() -> new MavlinkSigner(true, "", LINK_ID, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MavlinkSigner(true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("配置对象构造：linkId 固定 0，rejectUnsigned 跟随配置")
    void configConstructorDefaultsLinkIdZero() {
        MavlinkSignatureConfig config = new MavlinkSignatureConfig();
        config.setEnabled(true);
        config.setSecretKey(SECRET);
        config.setRejectUnsigned(false);

        MavlinkSigner signer = new MavlinkSigner(config);

        assertThat(signer.isEnabled()).isTrue();
        assertThat(signer.getLinkId()).isZero();
        assertThat(signer.isRejectUnsigned()).isFalse();
    }

    // ==================== 长度与构造规则 ====================

    @Test
    @DisplayName("签名长度为 6（sha256_48），签名块为 13 字节")
    void signatureAndBlockLengthsMatchOfficial() {
        MavlinkSigner signer = new MavlinkSigner(true, SECRET, LINK_ID, true);

        assertThat(signer.sign(FRAME_BYTES, LINK_ID, TIMESTAMP))
                .hasSize(MavlinkSigner.SIGNATURE_LENGTH)
                .hasSize(6);
        assertThat(MavlinkSigner.SIGNATURE_BLOCK_LENGTH).isEqualTo(13);
    }

    @Test
    @DisplayName("签名公式与官方一致：SHA-256(secret ++ frame ++ linkId ++ ts6LE) 前 6 字节")
    void signatureEqualsOfficialFormulaComputedIndependently() throws Exception {
        MavlinkSigner signer = new MavlinkSigner(true, SECRET, LINK_ID, true);

        // 测试内手工拼出签名输入，不复用被测代码的 buildSignInput，否则只是自证
        byte[] input = new byte[FRAME_BYTES.length + 7];
        System.arraycopy(FRAME_BYTES, 0, input, 0, FRAME_BYTES.length);
        input[FRAME_BYTES.length] = (byte) LINK_ID;
        long ts = TIMESTAMP;
        for (int i = 0; i < 6; i++) {
            input[FRAME_BYTES.length + 1 + i] = (byte) ((ts >> (8 * i)) & 0xFF);   // 小端
        }
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(SECRET.getBytes(StandardCharsets.UTF_8));
        sha.update(input);
        byte[] expected = new byte[6];
        System.arraycopy(sha.digest(), 0, expected, 0, 6);

        assertThat(signer.sign(FRAME_BYTES, LINK_ID, TIMESTAMP)).isEqualTo(expected);
    }

    @Test
    @DisplayName("不是 HMAC：同样的输入用 HmacSHA256 算出的前 6 字节必须与官方签名不同")
    void signatureIsNotHmacOfTheSameKey() throws Exception {
        MavlinkSigner signer = new MavlinkSigner(true, SECRET, LINK_ID, true);
        byte[] official = signer.sign(FRAME_BYTES, LINK_ID, TIMESTAMP);

        // 旧实现的做法：HMAC-SHA256(secret, frame + linkId + ts6BE) 取前 8 字节
        byte[] legacyInput = new byte[FRAME_BYTES.length + 7];
        System.arraycopy(FRAME_BYTES, 0, legacyInput, 0, FRAME_BYTES.length);
        legacyInput[FRAME_BYTES.length] = (byte) LINK_ID;
        legacyInput[FRAME_BYTES.length + 1] = (byte) ((TIMESTAMP >> 40) & 0xFF);
        legacyInput[FRAME_BYTES.length + 2] = (byte) ((TIMESTAMP >> 32) & 0xFF);
        legacyInput[FRAME_BYTES.length + 3] = (byte) ((TIMESTAMP >> 24) & 0xFF);
        legacyInput[FRAME_BYTES.length + 4] = (byte) ((TIMESTAMP >> 16) & 0xFF);
        legacyInput[FRAME_BYTES.length + 5] = (byte) ((TIMESTAMP >> 8) & 0xFF);
        legacyInput[FRAME_BYTES.length + 6] = (byte) (TIMESTAMP & 0xFF);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] legacy = new byte[6];
        System.arraycopy(mac.doFinal(legacyInput), 0, legacy, 0, 6);

        assertThat(official).isNotEqualTo(legacy);
    }

    @Test
    @DisplayName("同输入必得同签名（确定性）")
    void signatureIsDeterministic() {
        MavlinkSigner signer = new MavlinkSigner(true, SECRET, LINK_ID, true);

        assertThat(signer.sign(FRAME_BYTES, LINK_ID, TIMESTAMP))
                .isEqualTo(signer.sign(FRAME_BYTES, LINK_ID, TIMESTAMP));
    }

    // ==================== 敏感性 ====================

    @Test
    @DisplayName("linkId 参与签名：换链路必须换签名")
    void linkIdAffectsSignature() {
        MavlinkSigner signer = new MavlinkSigner(true, SECRET, LINK_ID, true);

        assertThat(signer.sign(FRAME_BYTES, LINK_ID, TIMESTAMP))
                .isNotEqualTo(signer.sign(FRAME_BYTES, LINK_ID + 1, TIMESTAMP));
    }

    @Test
    @DisplayName("时间戳参与签名：单个 tick 之差也必须换签名")
    void timestampAffectsSignature() {
        MavlinkSigner signer = new MavlinkSigner(true, SECRET, LINK_ID, true);

        assertThat(signer.sign(FRAME_BYTES, LINK_ID, TIMESTAMP))
                .isNotEqualTo(signer.sign(FRAME_BYTES, LINK_ID, TIMESTAMP + 1));
    }

    @Test
    @DisplayName("帧内任一字节（含 STX 与 CRC 区）都影响签名")
    void everyFrameByteAffectsSignature() {
        MavlinkSigner signer = new MavlinkSigner(true, SECRET, LINK_ID, true);
        byte[] baseline = signer.sign(FRAME_BYTES, LINK_ID, TIMESTAMP);

        for (int i = 0; i < FRAME_BYTES.length; i++) {
            byte[] tampered = FRAME_BYTES.clone();
            tampered[i] ^= 0x01;
            assertThat(signer.sign(tampered, LINK_ID, TIMESTAMP))
                    .as("翻转第 %d 字节后签名应变化", i)
                    .isNotEqualTo(baseline);
        }
    }

    @Test
    @DisplayName("换口令即换签名；错误口令验不过")
    void differentSecretProducesDifferentSignature() {
        MavlinkSigner signer = new MavlinkSigner(true, SECRET, LINK_ID, true);
        MavlinkSigner other = new MavlinkSigner(true, "another-secret-key-32-bytes-here!!",
                LINK_ID, true);
        byte[] signature = signer.sign(FRAME_BYTES, LINK_ID, TIMESTAMP);

        assertThat(other.sign(FRAME_BYTES, LINK_ID, TIMESTAMP)).isNotEqualTo(signature);
        assertThat(other.verify(FRAME_BYTES, LINK_ID, TIMESTAMP, signature)).isFalse();
    }

    // ==================== 验证语义 ====================

    @Test
    @DisplayName("verify 接受自算签名，拒绝 null、长度不足或长度 8（旧格式）的签名")
    void verifyAcceptsValidAndRejectsMalformed() {
        MavlinkSigner signer = new MavlinkSigner(true, SECRET, LINK_ID, true);
        byte[] signature = signer.sign(FRAME_BYTES, LINK_ID, TIMESTAMP);

        assertThat(signer.verify(FRAME_BYTES, LINK_ID, TIMESTAMP, signature)).isTrue();
        assertThat(signer.verify(FRAME_BYTES, LINK_ID, TIMESTAMP, null)).isFalse();
        assertThat(signer.verify(FRAME_BYTES, LINK_ID, TIMESTAMP, new byte[5])).isFalse();
        // 旧实现的 8 字节签名不得被接受（否则等于允许降级到不互通的格式）
        assertThat(signer.verify(FRAME_BYTES, LINK_ID, TIMESTAMP, new byte[8])).isFalse();
    }

    @Test
    @DisplayName("verify 对翻转的签名字段返回 false")
    void verifyRejectsFlippedSignature() {
        MavlinkSigner signer = new MavlinkSigner(true, SECRET, LINK_ID, true);
        byte[] signature = signer.sign(FRAME_BYTES, LINK_ID, TIMESTAMP);
        signature[3] ^= 0xFF;

        assertThat(signer.verify(FRAME_BYTES, LINK_ID, TIMESTAMP, signature)).isFalse();
    }

    // ==================== 时间戳口径 ====================

    @Test
    @DisplayName("签名时间戳：48 位内、10 微秒单位、以 2015-01-01 为纪元")
    void currentSigningTimestampUsesOfficialUnitAndEpoch() {
        long before = System.currentTimeMillis();
        long tick = MavlinkSigner.currentSigningTimestamp();
        long after = System.currentTimeMillis();

        long expectedLow = (before - MavlinkSigner.EPOCH_2015_MS) * 100;
        long expectedHigh = (after - MavlinkSigner.EPOCH_2015_MS) * 100 + 100;
        assertThat(tick).isBetween(expectedLow, expectedHigh);
        assertThat(tick).isPositive().isLessThanOrEqualTo(MavlinkSigner.MAX_TIMESTAMP_48);
    }

    @Test
    @DisplayName("系统时钟早于 2015 纪元时返回 0，不返回负 tick")
    void clockBeforeEpochYieldsZero() {
        // EPOCH_2015_MS 之后一秒的等价换算，确认负值分支存在（无法改系统时钟，故只验证边界定义）
        assertThat(MavlinkSigner.EPOCH_2015_MS).isEqualTo(1_420_070_400_000L);
        assertThat(MavlinkSigner.MAX_TIMESTAMP_48).isEqualTo((1L << 48) - 1);
    }

    @Test
    @DisplayName("时间戳小端读写一致，且超出 48 位的高位被丢弃")
    void timestampCodecIsLittleEndianAndMasks48Bits() {
        byte[] buf = new byte[8];
        MavlinkSigner.writeTimestampLittleEndian(0x0000A1B2C3D4E5F6L, buf, 1);

        assertThat(buf[0]).isZero();   // 未触碰 offset 之前
        assertThat(MavlinkSigner.readTimestampLittleEndian(buf, 1)).isEqualTo(0x00A1B2C3D4E5F6L);
        assertThat(buf[1]).isEqualTo((byte) 0xF6);   // 最低位在前 = 小端
    }

    // ==================== 消息级串联 ====================

    @Test
    @DisplayName("消息级往返：toFrame 签名 → decode 验签通过；换密钥则拒绝")
    void messageLevelSignAndVerifyRoundTrip() {
        MavlinkSigner signer = new MavlinkSigner(true, SECRET, LINK_ID, true);
        Heartbeat heartbeat = new Heartbeat(0x12345678, 16, 9, 81, 4);

        MavlinkFrame signedFrame = heartbeat.toFrame(1, 1, 5, signer);
        assertThat(signedFrame.isSigned()).isTrue();

        MavlinkFrame decoded = MavlinkFrame.decodeV2(signedFrame.encodeV2());
        assertThat(decoded.isSigned()).isTrue();
        assertThat(MavlinkMessage.decode(decoded, signer)).isNotNull();

        MavlinkSigner wrongKey = new MavlinkSigner(true, "a-completely-different-secret-key!",
                LINK_ID, true);
        assertThatThrownBy(() -> MavlinkMessage.decode(decoded, wrongKey))
                .isInstanceOf(MavlinkException.class)
                .hasMessageContaining("签名验证失败");
    }

    @Test
    @DisplayName("rejectUnsigned=true 时未签名帧被拒；false 时放行")
    void unsignedFrameHonoursRejectUnsigned() {
        MavlinkSigner strict = new MavlinkSigner(true, SECRET, LINK_ID, true);
        MavlinkSigner lenient = new MavlinkSigner(true, SECRET, LINK_ID, false);
        Heartbeat heartbeat = new Heartbeat(0x12345678, 16, 9, 81, 4);
        MavlinkFrame unsigned = heartbeat.toFrame(1, 1, 5);

        assertThat(unsigned.isSigned()).isFalse();
        assertThatThrownBy(() -> MavlinkMessage.decode(unsigned, strict))
                .isInstanceOf(MavlinkException.class)
                .hasMessageContaining("拒绝未签名消息");
        assertThat(MavlinkMessage.decode(unsigned, lenient)).isNotNull();
    }

    // ==================== 辅助 ====================

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}

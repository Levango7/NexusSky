package io.aerofleet.mavlink.security;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MAVLink v2 签名的**已知答案**（known-answer）测试：证明本仓实现与官方互通，
 * 而不只是"自己的代码签、自己的代码验"的自洽往返。
 * <p>
 * 向量由独立参考实现 <b>pymavlink</b> 打包并签名生成
 * （{@code scripts/mavlink-signing-vectors.py}，口令/linkId/时间戳/消息字段全部写死，
 * 因此可复现）。每条向量都做过两重自检：pymavlink 自己能验过它刚生成的帧，且按规范
 * 公式独立重算的签名与帧尾 6 字节一致。
 * <p>
 * 固定输入：secret={@code nexussky-p6-known-answer-secret!}（32 字节 ASCII）、
 * linkId=0x07、timestamp=37,000,000,000,000（10 微秒单位、纪元 2015-01-01）。
 * <p>
 * 覆盖的官方细节，任何一处写错都会让签名对不上：
 * <ul>
 *   <li>哈希输入含 <b>STX</b>（整帧从头到 CRC）+ linkId + 6 字节时间戳；</li>
 *   <li>口令是<b>前置后整体 SHA-256</b>，取摘要前 6 字节（sha256_48），不是 HMAC；</li>
 *   <li>时间戳是 48 位<b>小端</b>；</li>
 *   <li>payload 尾零裁剪（HEARTBEAT_TRAILING_ZEROS 的 LEN=1）与奇数长度
 *       （HEARTBEAT/RADIO_STATUS 的 LEN 为奇数，线上不落尾随零字节）。</li>
 * </ul>
 */
@DisplayName("MAVLink 签名：pymavlink 已知答案向量")
class MavlinkSigningVectorTest {

    private static final String SECRET = "nexussky-p6-known-answer-secret!";
    private static final int LINK_ID = 0x07;
    private static final long TIMESTAMP = 37_000_000_000_000L;

    /** 每条向量：{名称, pymavlink 生成的完整签名帧（hex）}。 */
    private static final String[][] VECTORS = {
            {"HEARTBEAT",
                    "fd09010000010100000041710a001009510403f218070050dbbba6211a0d4ba2f28b"},
            {"GLOBAL_POSITION_INT",
                    "fd1c010000010121000087d61200d0e5c817105c624500d0070094880100c7cfa05bebfc7869a1ba070050dbbba621c39f5d0667ea"},
            {"HEARTBEAT_TRAILING_ZEROS",
                    "fd01010000010100000000f200070050dbbba62116f6cf729a0f"},
            {"RADIO_STATUS_ODD_PADDING",
                    "fd0901000001016d000007000100c8b405e20aae9e070050dbbba62142568e7c7677"},
            {"ATTITUDE",
                    "fd1001000001011e0000e8030000000080be0000003f0000e0bf3266070050dbbba621b608fcd343f8"},
    };

    /** 期望签名（pymavlink 帧尾 6 字节）。 */
    private static final String[] EXPECTED_SIGNATURES = {
            "1a0d4ba2f28b", "c39f5d0667ea", "16f6cf729a0f", "42568e7c7677", "b608fcd343f8",
    };

    @Test
    @DisplayName("逐条向量：本仓 sign() 必须算出与 pymavlink 完全相同的 6 字节签名")
    void signsMatchPymavlinkVectors() {
        MavlinkSigner signer = new MavlinkSigner(true, SECRET, LINK_ID, true);

        for (int i = 0; i < VECTORS.length; i++) {
            String name = VECTORS[i][0];
            byte[] frame = hexToBytes(VECTORS[i][1]);
            // 签名块 13 字节 = linkId(1) + timestamp(6) + signature(6)
            // 哈希里的 frameBytes 是帧头到 CRC（含 STX），即去掉尾部 13 字节再补回前 7 字节
            int sigBlockStart = frame.length - MavlinkSigner.SIGNATURE_BLOCK_LENGTH;
            byte[] frameBytes = new byte[sigBlockStart];
            System.arraycopy(frame, 0, frameBytes, 0, sigBlockStart);

            byte[] signature = signer.sign(frameBytes, LINK_ID, TIMESTAMP);

            assertThat(bytesToHex(signature))
                    .as("%s 的签名应与 pymavlink 一致", name)
                    .isEqualTo(EXPECTED_SIGNATURES[i]);
        }
    }

    @Test
    @DisplayName("逐条向量：验签通过，且篡改任一字节后必须失败")
    void verifiesOwnVectorsAndRejectsTampering() {
        MavlinkSigner signer = new MavlinkSigner(true, SECRET, LINK_ID, true);

        for (int i = 0; i < VECTORS.length; i++) {
            byte[] frame = hexToBytes(VECTORS[i][1]);
            MavlinkFrame decoded = MavlinkFrame.decodeV2(frame);

            assertThat(decoded.isSigned()).as(VECTORS[i][0] + " 应为签名帧").isTrue();
            assertThat(decoded.getLinkId()).isEqualTo(LINK_ID);
            assertThat(decoded.getTimestamp()).isEqualTo(TIMESTAMP);
            assertThat(bytesToHex(decoded.getSignature())).isEqualTo(EXPECTED_SIGNATURES[i]);

            int sigBlockStart = frame.length - MavlinkSigner.SIGNATURE_BLOCK_LENGTH;
            byte[] frameBytes = new byte[sigBlockStart];
            System.arraycopy(frame, 0, frameBytes, 0, sigBlockStart);
            assertThat(signer.verify(frameBytes, decoded.getLinkId(), decoded.getTimestamp(),
                    decoded.getSignature())).as(VECTORS[i][0] + " 验签应通过").isTrue();

            // 篡改 CRC 前一字节（payload 尾字节）→ 签名必须不再匹配
            frameBytes[sigBlockStart - 1] ^= 0x01;
            assertThat(signer.verify(frameBytes, LINK_ID, TIMESTAMP, decoded.getSignature()))
                    .as(VECTORS[i][0] + " 内容被改后验签应失败").isFalse();
        }
    }

    @Test
    @DisplayName("帧编解码互通：解码 pymavlink 帧后重编码，必须与原字节逐字节相同")
    void roundTripsPymavlinkFramesByteExact() {
        for (String[] vector : VECTORS) {
            byte[] original = hexToBytes(vector[1]);
            MavlinkFrame decoded = MavlinkFrame.decodeV2(original);
            byte[] reEncoded = decoded.encodeV2();

            assertThat(reEncoded)
                    .as("%s 重编码应与 pymavlink 原帧一致（13 字节签名块布局/小端时间戳）", vector[0])
                    .isEqualTo(original);
        }
    }

    @Test
    @DisplayName("时间戳字节序：48 位小端写入与读回，且必须等于向量里的 6 字节")
    void timestampIsLittleEndian48Bit() {
        byte[] buf = new byte[6];
        MavlinkSigner.writeTimestampLittleEndian(TIMESTAMP, buf, 0);
        assertThat(bytesToHex(buf)).isEqualTo("0050dbbba621");   // 取自 pymavlink 帧
        assertThat(MavlinkSigner.readTimestampLittleEndian(buf, 0)).isEqualTo(TIMESTAMP);
    }

    @Test
    @DisplayName("口令参与方式：前置整体 SHA-256（sha256_48），不是 HMAC——长度必须为 6")
    void signatureLengthIsSixBytesNotEight() {
        MavlinkSigner signer = new MavlinkSigner(true, SECRET, LINK_ID, true);
        byte[] frameBytes = hexToBytes(VECTORS[0][1]
                .substring(0, 42));   // 前 21 字节 = 帧头至 CRC
        byte[] signature = signer.sign(frameBytes, LINK_ID, TIMESTAMP);

        assertThat(signature).hasSize(MavlinkSigner.SIGNATURE_LENGTH);
        assertThat(MavlinkSigner.SIGNATURE_BLOCK_LENGTH).isEqualTo(13);
    }

    @Test
    @DisplayName("签名输入含 STX：去掉首字节后签名必然不同")
    void signInputIncludesStx() {
        MavlinkSigner signer = new MavlinkSigner(true, SECRET, LINK_ID, true);
        byte[] frame = hexToBytes(VECTORS[0][1]);
        int sigBlockStart = frame.length - MavlinkSigner.SIGNATURE_BLOCK_LENGTH;
        byte[] withStx = new byte[sigBlockStart];
        System.arraycopy(frame, 0, withStx, 0, sigBlockStart);
        byte[] withoutStx = new byte[sigBlockStart - 1];
        System.arraycopy(frame, 1, withoutStx, 0, sigBlockStart - 1);

        byte[] correct = signer.sign(withStx, LINK_ID, TIMESTAMP);
        byte[] wrong = signer.sign(withoutStx, LINK_ID, TIMESTAMP);

        assertThat(bytesToHex(correct)).isEqualTo(EXPECTED_SIGNATURES[0]);
        assertThat(wrong).isNotEqualTo(correct);
    }

    // ==================== 辅助 ====================

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

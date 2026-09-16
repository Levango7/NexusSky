package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * COVERAGE_OPTIMIZATION (msgId=466) 编解码单测（M9 应急任务编排）。
 */
@DisplayName("CoverageOptimizationMsg 编解码 (msgId=466)")
class CoverageOptimizationMsgTest {

    private static CoverageOptimizationMsg roundtrip(CoverageOptimizationMsg msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        return CoverageOptimizationMsg.decode(frame);
    }

    @Test
    @DisplayName("全字段 encode→decode 往返一致")
    void encodeDecodeRoundtrip() {
        CoverageOptimizationMsg orig = new CoverageOptimizationMsg(
                12345L, 7, 399000000, 1163000000, 100, 1, 2, 20, 15, 60, 1700000000L);
        CoverageOptimizationMsg back = roundtrip(orig);

        assertThat(back.planId).isEqualTo(12345L);
        assertThat(back.droneId).isEqualTo(7);
        assertThat(back.targetLat).isEqualTo(399000000);
        assertThat(back.targetLon).isEqualTo(1163000000);
        assertThat(back.targetAlt).isEqualTo(100);
        assertThat(back.cellType).isEqualTo(1);
        assertThat(back.relayRole).isEqualTo(2);
        assertThat(back.txPower).isEqualTo(20);
        assertThat(back.expectedCoverage).isEqualTo(15);
        assertThat(back.batteryBudget).isEqualTo(60);
        assertThat(back.timestamp).isEqualTo(1700000000L);
    }

    @Test
    @DisplayName("消息 ID=466、LEN=24、CRC_EXTRA=250")
    void messageIdAndConstants() {
        assertThat(CoverageOptimizationMsg.ID).isEqualTo(466);
        assertThat(CoverageOptimizationMsg.LEN).isEqualTo(24);
        assertThat(CoverageOptimizationMsg.CRC_EXTRA).isEqualTo(250);
    }

    @Test
    @DisplayName("encode 产出 24 字节 payload")
    void encodeLength() {
        CoverageOptimizationMsg msg = new CoverageOptimizationMsg(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThat(msg.encode()).hasSize(24);
        assertThat(msg.messageId()).isEqualTo(466);
    }

    @Test
    @DisplayName("短 payload 容忍解码：仅 5 字节时还原 planId+droneId，其余填 0")
    void decodeShortPayloadTolerant() {
        byte[] shortPayload = new byte[]{0x39, 0x30, 0x00, 0x00, 0x07};
        MavlinkFrame frame = new MavlinkFrame(5, 0, 0, 0, 1, 1, 466, shortPayload, 0);
        CoverageOptimizationMsg msg = CoverageOptimizationMsg.decode(frame);

        assertThat(msg.planId).isEqualTo(12345L);
        assertThat(msg.droneId).isEqualTo(7);
        assertThat(msg.targetLat).isZero();
        assertThat(msg.timestamp).isZero();
    }

    @Test
    @DisplayName("负高度（targetAlt 为负）正确编解码")
    void negativeAltitude() {
        CoverageOptimizationMsg orig = new CoverageOptimizationMsg(
                1L, 1, 0, 0, -50, 0, 0, 0, 0, 0, 0);
        CoverageOptimizationMsg back = roundtrip(orig);
        assertThat(back.targetAlt).isEqualTo(-50);
    }

    @Test
    @DisplayName("toString 包含关键字段")
    void toStringContainsFields() {
        CoverageOptimizationMsg msg = new CoverageOptimizationMsg(
                999L, 5, 100, 200, 80, 2, 1, 15, 20, 70, 0L);
        String s = msg.toString();
        assertThat(s).contains("planId=999", "drone=5", "cell=2", "relay=1", "cov=20%");
    }
}
package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SAT_PASS_SCHEDULE (msgId=460) 编解码单测（M7 星-空-地多层级中继，FR-5.1）。
 */
@DisplayName("SatPassScheduleMsg 编解码 (msgId=460)")
class SatPassScheduleMsgTest {

    private static SatPassScheduleMsg roundtrip(SatPassScheduleMsg msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        return SatPassScheduleMsg.decode(frame);
    }

    @Test
    @DisplayName("全字段 encode→decode 往返一致")
    void encodeDecodeRoundtrip() {
        SatPassScheduleMsg orig = new SatPassScheduleMsg(
                15, 1000000L, 1200000L, 78, 1, 123456789L);
        SatPassScheduleMsg back = roundtrip(orig);

        assertThat(back.satId).isEqualTo(15);
        assertThat(back.passStartMs).isEqualTo(1000000L);
        assertThat(back.passEndMs).isEqualTo(1200000L);
        assertThat(back.maxElevationDeg).isEqualTo(78);
        assertThat(back.groundPointId).isEqualTo(1);
        assertThat(back.timestamp).isEqualTo(123456789L);
    }

    @Test
    @DisplayName("消息 ID=460、LEN=16、CRC_EXTRA=239")
    void messageIdAndConstants() {
        assertThat(SatPassScheduleMsg.ID).isEqualTo(460);
        assertThat(SatPassScheduleMsg.LEN).isEqualTo(16);
        assertThat(SatPassScheduleMsg.CRC_EXTRA).isEqualTo(239);
    }

    @Test
    @DisplayName("encode 产出 16 字节 payload")
    void encodeLength() {
        SatPassScheduleMsg msg = new SatPassScheduleMsg(1, 0L, 1000L, 45, 0, 0L);
        assertThat(msg.encode()).hasSize(16);
        assertThat(msg.messageId()).isEqualTo(460);
    }

    @Test
    @DisplayName("durationMs 计算正确")
    void durationCalculation() {
        SatPassScheduleMsg msg = new SatPassScheduleMsg(1, 1000L, 5000L, 60, 0, 0L);
        assertThat(msg.durationMs()).isEqualTo(4000L);
    }

    @Test
    @DisplayName("短 payload 容忍解码")
    void decodeShortPayloadTolerant() {
        byte[] shortPayload = new byte[]{15, 0};
        MavlinkFrame frame = new MavlinkFrame(1, 0, 0, 0, 1, 1, 460, shortPayload, 0);
        SatPassScheduleMsg msg = SatPassScheduleMsg.decode(frame);

        assertThat(msg.satId).isEqualTo(15);
        assertThat(msg.passStartMs).isZero();
    }

    @Test
    @DisplayName("msgId 460 全局唯一")
    void msgIdGloballyUnique() {
        assertThat(SatPassScheduleMsg.ID).isNotIn(459, 461, 450, 454);
    }
}
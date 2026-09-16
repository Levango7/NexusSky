package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SAT_LINK_STATUS (msgId=459) 编解码单测（M7 星-空-地多层级中继，FR-5.4）。
 */
@DisplayName("SatLinkStatusMsg 编解码 (msgId=459)")
class SatLinkStatusMsgTest {

    private static SatLinkStatusMsg roundtrip(SatLinkStatusMsg msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        return SatLinkStatusMsg.decode(frame);
    }

    @Test
    @DisplayName("全字段 encode→decode 往返一致")
    void encodeDecodeRoundtrip() {
        SatLinkStatusMsg orig = SatLinkStatusMsg.simulated(
                42, 1, 65, 180, 150, 40, 999999L, 3, 123456789L);
        SatLinkStatusMsg back = roundtrip(orig);

        assertThat(back.satId).isEqualTo(42);
        assertThat(back.visible).isEqualTo(1);
        assertThat(back.elevationDeg).isEqualTo(65);
        assertThat(back.azimuthDeg).isEqualTo(180);
        assertThat(back.delayMs).isEqualTo(150);
        assertThat(back.bandwidthMbps).isEqualTo(40);
        assertThat(back.windowEndMs).isEqualTo(999999L);
        assertThat(back.sharedUsers).isEqualTo(3);
        assertThat(back.timestamp).isEqualTo(123456789L);
        assertThat(back.simFlag).isEqualTo(1);
    }

    @Test
    @DisplayName("消息 ID=459、LEN=24、CRC_EXTRA=238")
    void messageIdAndConstants() {
        assertThat(SatLinkStatusMsg.ID).isEqualTo(459);
        assertThat(SatLinkStatusMsg.LEN).isEqualTo(24);
        assertThat(SatLinkStatusMsg.CRC_EXTRA).isEqualTo(238);
    }

    @Test
    @DisplayName("encode 产出 24 字节 payload")
    void encodeLength() {
        SatLinkStatusMsg msg = SatLinkStatusMsg.simulated(1, 0, 0, 0, 50, 50, 0L, 0, 0L);
        assertThat(msg.encode()).hasSize(24);
        assertThat(msg.messageId()).isEqualTo(459);
    }

    @Test
    @DisplayName("短 payload 容忍解码：仅 2 字节时只还原 satId")
    void decodeShortPayloadTolerant() {
        byte[] shortPayload = new byte[]{42, 0};
        MavlinkFrame frame = new MavlinkFrame(1, 0, 0, 0, 1, 1, 459, shortPayload, 0);
        SatLinkStatusMsg msg = SatLinkStatusMsg.decode(frame);

        assertThat(msg.satId).isEqualTo(42);
        assertThat(msg.visible).isZero();
    }

    @Test
    @DisplayName("isVisible/isSimulated 便捷方法")
    void visibilityHelpers() {
        SatLinkStatusMsg visible = SatLinkStatusMsg.simulated(1, 1, 45, 90, 100, 30, 0L, 1, 0L);
        SatLinkStatusMsg hidden = SatLinkStatusMsg.simulated(1, 0, 0, 0, 0, 0, 0L, 0, 0L);

        assertThat(visible.isVisible()).isTrue();
        assertThat(visible.isSimulated()).isTrue();
        assertThat(hidden.isVisible()).isFalse();
        assertThat(hidden.isSimulated()).isTrue();
    }

    @Test
    @DisplayName("msgId 459 全局唯一：不与 450-454 冲突")
    void msgIdGloballyUnique() {
        assertThat(SatLinkStatusMsg.ID).isNotIn(450, 451, 452, 453, 454);
        assertThat(SatLinkStatusMsg.ID).isNotIn(420, 421, 422, 423, 424, 425, 426);
    }
}
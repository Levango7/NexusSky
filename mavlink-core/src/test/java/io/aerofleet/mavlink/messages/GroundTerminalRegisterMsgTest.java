package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GROUND_TERMINAL_REGISTER (msgId=458) 编解码单测（M6 移动基站载荷抽象，FR-MSG-05）。
 */
@DisplayName("GroundTerminalRegisterMsg 编解码 (msgId=458)")
class GroundTerminalRegisterMsgTest {

    private static GroundTerminalRegisterMsg roundtrip(GroundTerminalRegisterMsg msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        return GroundTerminalRegisterMsg.decode(frame);
    }

    @Test
    @DisplayName("全字段 encode→decode 往返一致")
    void encodeDecodeRoundtrip() {
        GroundTerminalRegisterMsg orig = new GroundTerminalRegisterMsg(
                5001, 0, 399000000, 1163000000, 3);
        GroundTerminalRegisterMsg back = roundtrip(orig);

        assertThat(back.terminalId).isEqualTo(5001);
        assertThat(back.terminalType).isEqualTo(0);
        assertThat(back.gpsLat).isEqualTo(399000000);
        assertThat(back.gpsLon).isEqualTo(1163000000);
        assertThat(back.requestedSysid).isEqualTo(3);
    }

    @Test
    @DisplayName("消息 ID=458、LEN=12、CRC_EXTRA=248")
    void messageIdAndConstants() {
        assertThat(GroundTerminalRegisterMsg.ID).isEqualTo(458);
        assertThat(GroundTerminalRegisterMsg.LEN).isEqualTo(12);
        assertThat(GroundTerminalRegisterMsg.CRC_EXTRA).isEqualTo(248);
    }

    @Test
    @DisplayName("encode 产出 12 字节 payload")
    void encodeLength() {
        GroundTerminalRegisterMsg msg = new GroundTerminalRegisterMsg(0, 0, 0, 0, 0);
        assertThat(msg.encode()).hasSize(12);
        assertThat(msg.messageId()).isEqualTo(458);
    }

    @Test
    @DisplayName("短 payload 容忍解码：仅 3 字节时还原 terminalId + terminalType")
    void decodeShortPayloadTolerant() {
        byte[] shortPayload = new byte[]{0x01, 0x00, 0x02};
        MavlinkFrame frame = new MavlinkFrame(3, 0, 0, 0, 1, 1, 458, shortPayload, 0);
        GroundTerminalRegisterMsg msg = GroundTerminalRegisterMsg.decode(frame);

        assertThat(msg.terminalId).isEqualTo(1);
        assertThat(msg.terminalType).isEqualTo(2);
        assertThat(msg.gpsLat).isZero();
        assertThat(msg.gpsLon).isZero();
        assertThat(msg.requestedSysid).isZero();
    }

    @Test
    @DisplayName("终端类型常量正确")
    void typeConstants() {
        assertThat(GroundTerminalRegisterMsg.TYPE_PHONE).isEqualTo(0);
        assertThat(GroundTerminalRegisterMsg.TYPE_WALKIE_TALKIE).isEqualTo(1);
        assertThat(GroundTerminalRegisterMsg.TYPE_SENSOR).isEqualTo(2);
    }
}
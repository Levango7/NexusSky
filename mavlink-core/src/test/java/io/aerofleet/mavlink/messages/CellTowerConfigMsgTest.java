package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CELL_TOWER_CONFIG (msgId=456) 编解码单测（M6 移动基站载荷抽象，FR-MSG-03）。
 */
@DisplayName("CellTowerConfigMsg 编解码 (msgId=456)")
class CellTowerConfigMsgTest {

    private static CellTowerConfigMsg roundtrip(CellTowerConfigMsg msg) {
        MavlinkFrame frame = msg.toFrame(255, 190, 0);
        return CellTowerConfigMsg.decode(frame);
    }

    @Test
    @DisplayName("全字段 encode→decode 往返一致")
    void encodeDecodeRoundtrip() {
        CellTowerConfigMsg orig = new CellTowerConfigMsg(3, 1, 20, 200, 36);
        CellTowerConfigMsg back = roundtrip(orig);

        assertThat(back.sysid).isEqualTo(3);
        assertThat(back.cellType).isEqualTo(1);
        assertThat(back.txPowerDbm).isEqualTo(20);
        assertThat(back.maxTerminals).isEqualTo(200);
        assertThat(back.frequencyChannel).isEqualTo(36);
    }

    @Test
    @DisplayName("消息 ID=456、LEN=7、CRC_EXTRA=246")
    void messageIdAndConstants() {
        assertThat(CellTowerConfigMsg.ID).isEqualTo(456);
        assertThat(CellTowerConfigMsg.LEN).isEqualTo(7);
        assertThat(CellTowerConfigMsg.CRC_EXTRA).isEqualTo(246);
    }

    @Test
    @DisplayName("encode 产出 7 字节 payload")
    void encodeLength() {
        CellTowerConfigMsg msg = new CellTowerConfigMsg(1, 0, 0, 0, 0);
        assertThat(msg.encode()).hasSize(7);
        assertThat(msg.messageId()).isEqualTo(456);
    }

    @Test
    @DisplayName("短 payload 容忍解码：仅 1 字节时只还原 sysid")
    void decodeShortPayloadTolerant() {
        byte[] shortPayload = new byte[]{7};
        MavlinkFrame frame = new MavlinkFrame(1, 0, 0, 0, 255, 190, 456, shortPayload, 0);
        CellTowerConfigMsg msg = CellTowerConfigMsg.decode(frame);

        assertThat(msg.sysid).isEqualTo(7);
        assertThat(msg.cellType).isZero();
        assertThat(msg.txPowerDbm).isZero();
        assertThat(msg.maxTerminals).isZero();
        assertThat(msg.frequencyChannel).isZero();
    }

    @Test
    @DisplayName("负 txPowerDbm 编解码正确")
    void negativeTxPowerRoundtrip() {
        CellTowerConfigMsg orig = new CellTowerConfigMsg(2, 2, -10, 1000, 1);
        CellTowerConfigMsg back = roundtrip(orig);
        assertThat(back.txPowerDbm).isEqualTo(-10);
    }
}
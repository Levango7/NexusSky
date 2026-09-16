package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CELL_HANDOVER (msgId=457) 编解码单测（M6 移动基站载荷抽象，FR-MSG-04）。
 */
@DisplayName("CellHandoverMsg 编解码 (msgId=457)")
class CellHandoverMsgTest {

    private static CellHandoverMsg roundtrip(CellHandoverMsg msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        return CellHandoverMsg.decode(frame);
    }

    @Test
    @DisplayName("全字段 encode→decode 往返一致")
    void encodeDecodeRoundtrip() {
        CellHandoverMsg orig = new CellHandoverMsg(1001, 3, 5, 1);
        CellHandoverMsg back = roundtrip(orig);

        assertThat(back.terminalId).isEqualTo(1001);
        assertThat(back.fromSysid).isEqualTo(3);
        assertThat(back.toSysid).isEqualTo(5);
        assertThat(back.handoverReason).isEqualTo(1);
    }

    @Test
    @DisplayName("消息 ID=457、LEN=5、CRC_EXTRA=247")
    void messageIdAndConstants() {
        assertThat(CellHandoverMsg.ID).isEqualTo(457);
        assertThat(CellHandoverMsg.LEN).isEqualTo(5);
        assertThat(CellHandoverMsg.CRC_EXTRA).isEqualTo(247);
    }

    @Test
    @DisplayName("encode 产出 5 字节 payload")
    void encodeLength() {
        CellHandoverMsg msg = new CellHandoverMsg(0, 0, 0, 0);
        assertThat(msg.encode()).hasSize(5);
        assertThat(msg.messageId()).isEqualTo(457);
    }

    @Test
    @DisplayName("短 payload 容忍解码：空 payload 时全部填 0")
    void decodeShortPayloadTolerant() {
        byte[] emptyPayload = new byte[]{};
        MavlinkFrame frame = new MavlinkFrame(0, 0, 0, 0, 1, 1, 457, emptyPayload, 0);
        CellHandoverMsg msg = CellHandoverMsg.decode(frame);

        assertThat(msg.terminalId).isZero();
        assertThat(msg.fromSysid).isZero();
        assertThat(msg.toSysid).isZero();
        assertThat(msg.handoverReason).isZero();
    }

    @Test
    @DisplayName("原因常量正确")
    void reasonConstants() {
        assertThat(CellHandoverMsg.REASON_SIGNAL_WEAK).isEqualTo(0);
        assertThat(CellHandoverMsg.REASON_LOAD_BALANCE).isEqualTo(1);
        assertThat(CellHandoverMsg.REASON_CELL_SHUTDOWN).isEqualTo(2);
    }
}
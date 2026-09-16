package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CELL_TOWER_STATUS (msgId=455) 编解码单测（M6 移动基站载荷抽象，FR-MSG-02）。
 */
@DisplayName("CellTowerStatusMsg 编解码 (msgId=455)")
class CellTowerStatusMsgTest {

    private static CellTowerStatusMsg roundtrip(CellTowerStatusMsg msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        return CellTowerStatusMsg.decode(frame);
    }

    @Test
    @DisplayName("全字段 encode→decode 往返一致")
    void encodeDecodeRoundtrip() {
        CellTowerStatusMsg orig = new CellTowerStatusMsg(
                3, 0, 399000000, 1163000000, 2000, 150, 75);
        CellTowerStatusMsg back = roundtrip(orig);

        assertThat(back.sysid).isEqualTo(3);
        assertThat(back.cellType).isEqualTo(0);
        assertThat(back.centerLat).isEqualTo(399000000);
        assertThat(back.centerLon).isEqualTo(1163000000);
        assertThat(back.coverageRadiusM).isEqualTo(2000);
        assertThat(back.connectedTerminals).isEqualTo(150);
        assertThat(back.capacityUtilization).isEqualTo(75);
    }

    @Test
    @DisplayName("消息 ID=455、LEN=15、CRC_EXTRA=245")
    void messageIdAndConstants() {
        assertThat(CellTowerStatusMsg.ID).isEqualTo(455);
        assertThat(CellTowerStatusMsg.LEN).isEqualTo(15);
        assertThat(CellTowerStatusMsg.CRC_EXTRA).isEqualTo(245);
    }

    @Test
    @DisplayName("encode 产出 15 字节 payload")
    void encodeLength() {
        CellTowerStatusMsg msg = new CellTowerStatusMsg(1, 0, 0, 0, 0, 0, 0);
        assertThat(msg.encode()).hasSize(15);
        assertThat(msg.messageId()).isEqualTo(455);
    }

    @Test
    @DisplayName("短 payload 容忍解码：仅 1 字节时只还原 sysid，其余填 0")
    void decodeShortPayloadTolerant() {
        byte[] shortPayload = new byte[]{42};
        MavlinkFrame frame = new MavlinkFrame(1, 0, 0, 0, 1, 1, 455, shortPayload, 0);
        CellTowerStatusMsg msg = CellTowerStatusMsg.decode(frame);

        assertThat(msg.sysid).isEqualTo(42);
        assertThat(msg.cellType).isZero();
        assertThat(msg.centerLat).isZero();
        assertThat(msg.centerLon).isZero();
        assertThat(msg.coverageRadiusM).isZero();
        assertThat(msg.connectedTerminals).isZero();
        assertThat(msg.capacityUtilization).isZero();
    }

    @Test
    @DisplayName("toString 包含关键字段")
    void toStringContainsFields() {
        CellTowerStatusMsg msg = new CellTowerStatusMsg(5, 1, 100, 200, 3000, 50, 25);
        String s = msg.toString();
        assertThat(s).contains("sysid=5", "cellType=1", "radius=3000m", "connected=50", "util=25%");
    }
}
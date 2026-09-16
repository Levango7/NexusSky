package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MESH_HEARTBEAT (msgId=450) 编解码单测（M5 应急 mesh，FR-09）。
 * <p>
 * 覆盖 encode→decode 往返一致、常量正确性、payload 长度、短 payload 容忍解码、toString。
 */
@DisplayName("MeshHeartbeatMsg 编解码 (msgId=450)")
class MeshHeartbeatMsgTest {

    /** toFrame → decode 往返。 */
    private static MeshHeartbeatMsg roundtrip(MeshHeartbeatMsg msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        return MeshHeartbeatMsg.decode(frame);
    }

    @Test
    @DisplayName("全字段 encode→decode 往返一致")
    void encodeDecodeRoundtrip() {
        MeshHeartbeatMsg orig = new MeshHeartbeatMsg(
                7, 400000000, 1160000000, 50000, 85, 3, 123456789L);
        MeshHeartbeatMsg back = roundtrip(orig);

        assertThat(back.sysid).isEqualTo(7);
        assertThat(back.lat).isEqualTo(400000000);
        assertThat(back.lon).isEqualTo(1160000000);
        assertThat(back.alt).isEqualTo(50000);
        assertThat(back.batteryPercent).isEqualTo(85);
        assertThat(back.neighborCount).isEqualTo(3);
        assertThat(back.timestamp).isEqualTo(123456789L);
    }

    @Test
    @DisplayName("消息 ID=450、LEN=24、CRC_EXTRA=233")
    void messageIdAndConstants() {
        assertThat(MeshHeartbeatMsg.ID).isEqualTo(450);
        assertThat(MeshHeartbeatMsg.LEN).isEqualTo(24);
        assertThat(MeshHeartbeatMsg.CRC_EXTRA).isEqualTo(233);
    }

    @Test
    @DisplayName("encode 产出 24 字节 payload")
    void encodeLength() {
        MeshHeartbeatMsg msg = new MeshHeartbeatMsg(1, 0, 0, 0, 100, 0, 0L);
        assertThat(msg.encode()).hasSize(24);
        assertThat(msg.messageId()).isEqualTo(450);
    }

    @Test
    @DisplayName("短 payload 容忍解码：仅 1 字节时只还原 sysid，其余填 0")
    void decodeShortPayloadTolerant() {
        byte[] shortPayload = new byte[]{42};
        MavlinkFrame frame = new MavlinkFrame(1, 0, 0, 0, 1, 1, 450, shortPayload, 0);
        MeshHeartbeatMsg msg = MeshHeartbeatMsg.decode(frame);

        assertThat(msg.sysid).isEqualTo(42);
        assertThat(msg.lat).isZero();
        assertThat(msg.lon).isZero();
        assertThat(msg.alt).isZero();
        assertThat(msg.batteryPercent).isZero();
        assertThat(msg.neighborCount).isZero();
        assertThat(msg.timestamp).isZero();
    }

    @Test
    @DisplayName("toString 包含关键字段")
    void toStringContainsFields() {
        MeshHeartbeatMsg msg = new MeshHeartbeatMsg(5, 100, 200, 300, 90, 2, 999L);
        String s = msg.toString();
        assertThat(s).contains("sysid=5", "battery=90%", "neighbors=2", "ts=999");
    }
}
package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MESH_ROUTE_ERROR (msgId=453) 编解码单测（M5 AODV-lite，FR-03/13/14）。
 * <p>
 * 重点验证 timestamp 截断为 u16（65536ms 回绕）。
 */
@DisplayName("MeshRouteErrorMsg 编解码 (msgId=453)")
class MeshRouteErrorMsgTest {

    private static MeshRouteErrorMsg roundtrip(MeshRouteErrorMsg msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        return MeshRouteErrorMsg.decode(frame);
    }

    @Test
    @DisplayName("全字段 encode→decode 往返一致")
    void encodeDecodeRoundtrip() {
        MeshRouteErrorMsg orig = new MeshRouteErrorMsg(12, 2, 30000L);
        MeshRouteErrorMsg back = roundtrip(orig);

        assertThat(back.unreachableSysid).isEqualTo(12);
        assertThat(back.hopCount).isEqualTo(2);
        assertThat(back.timestamp).isEqualTo(30000);
    }

    @Test
    @DisplayName("timestamp 截断为 u16：70000 → 4464（70000 & 0xFFFF）")
    void timestampTruncatedToU16() {
        MeshRouteErrorMsg msg = new MeshRouteErrorMsg(1, 0, 70000L);
        assertThat(msg.timestamp).isEqualTo(70000 & 0xFFFF);
        assertThat(msg.timestamp).isEqualTo(4464);

        // 往返后仍是截断值
        MeshRouteErrorMsg back = roundtrip(msg);
        assertThat(back.timestamp).isEqualTo(4464);
    }

    @Test
    @DisplayName("短 payload 容忍解码：仅 1 字节时还原 unreachable，其余填 0")
    void decodeShortPayloadTolerant() {
        byte[] shortPayload = new byte[]{44};
        MavlinkFrame frame = new MavlinkFrame(1, 0, 0, 0, 1, 1, 453, shortPayload, 0);
        MeshRouteErrorMsg msg = MeshRouteErrorMsg.decode(frame);

        assertThat(msg.unreachableSysid).isEqualTo(44);
        assertThat(msg.hopCount).isZero();
        assertThat(msg.timestamp).isZero();
    }

    @Test
    @DisplayName("消息 ID=453、LEN=4")
    void messageIdAndLen() {
        assertThat(MeshRouteErrorMsg.ID).isEqualTo(453);
        assertThat(MeshRouteErrorMsg.LEN).isEqualTo(4);
    }

    @Test
    @DisplayName("encode 产出 4 字节 payload")
    void encodeLength() {
        MeshRouteErrorMsg msg = new MeshRouteErrorMsg(1, 1, 100L);
        assertThat(msg.encode()).hasSize(4);
    }
}
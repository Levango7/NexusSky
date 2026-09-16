package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * MESH_ROUTE_REPLY (msgId=452) 编解码单测（M5 AODV-lite，FR-02/02a）。
 */
@DisplayName("MeshRouteReplyMsg 编解码 (msgId=452)")
class MeshRouteReplyMsgTest {

    private static MeshRouteReplyMsg roundtrip(MeshRouteReplyMsg msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        return MeshRouteReplyMsg.decode(frame);
    }

    @Test
    @DisplayName("全字段 encode→decode 往返一致")
    void encodeDecodeRoundtrip() {
        MeshRouteReplyMsg orig = new MeshRouteReplyMsg(5, 8, 3, 7.77, 888888L);
        MeshRouteReplyMsg back = roundtrip(orig);

        assertThat(back.sourceSysid).isEqualTo(5);
        assertThat(back.targetSysid).isEqualTo(8);
        assertThat(back.hopCount).isEqualTo(3);
        assertThat(back.metric()).isCloseTo(7.77, within(0.01));
        assertThat(back.timestamp).isEqualTo(888888L);
    }

    @Test
    @DisplayName("metric 内部 ×100 量化")
    void metricQuantization() {
        MeshRouteReplyMsg msg = new MeshRouteReplyMsg(1, 2, 0, 2.55, 0L);
        assertThat(msg.metricRaw).isEqualTo(255);
        assertThat(msg.metric()).isCloseTo(2.55, within(0.01));
    }

    @Test
    @DisplayName("metric 超上限钳制为 655.35")
    void metricClampToMax() {
        MeshRouteReplyMsg msg = new MeshRouteReplyMsg(1, 2, 0, 1000.0, 0L);
        assertThat(msg.metric()).isCloseTo(655.35, within(0.01));
    }

    @Test
    @DisplayName("短 payload 容忍解码：仅 1 字节时还原 source，其余填 0")
    void decodeShortPayloadTolerant() {
        byte[] shortPayload = new byte[]{33};
        MavlinkFrame frame = new MavlinkFrame(1, 0, 0, 0, 1, 1, 452, shortPayload, 0);
        MeshRouteReplyMsg msg = MeshRouteReplyMsg.decode(frame);

        assertThat(msg.sourceSysid).isEqualTo(33);
        assertThat(msg.targetSysid).isZero();
        assertThat(msg.hopCount).isZero();
        assertThat(msg.timestamp).isZero();
    }

    @Test
    @DisplayName("消息 ID=452、LEN=10")
    void messageIdAndLen() {
        assertThat(MeshRouteReplyMsg.ID).isEqualTo(452);
        assertThat(MeshRouteReplyMsg.LEN).isEqualTo(10);
    }
}
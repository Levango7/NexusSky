package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * MESH_ROUTE_REQUEST (msgId=451) 编解码单测（M5 AODV-lite，FR-01）。
 * <p>
 * 覆盖往返一致、metric 量化（×100）、metric 钳制、负值钳零、短 payload 容忍。
 */
@DisplayName("MeshRouteRequestMsg 编解码 (msgId=451)")
class MeshRouteRequestMsgTest {

    private static MeshRouteRequestMsg roundtrip(MeshRouteRequestMsg msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        return MeshRouteRequestMsg.decode(frame);
    }

    @Test
    @DisplayName("全字段 encode→decode 往返一致")
    void encodeDecodeRoundtrip() {
        MeshRouteRequestMsg orig = new MeshRouteRequestMsg(3, 9, 1000, 4, 12.34, 555555L);
        MeshRouteRequestMsg back = roundtrip(orig);

        assertThat(back.sourceSysid).isEqualTo(3);
        assertThat(back.targetSysid).isEqualTo(9);
        assertThat(back.broadcastId).isEqualTo(1000);
        assertThat(back.hopCount).isEqualTo(4);
        assertThat(back.originMetric()).isCloseTo(12.34, within(0.01));
        assertThat(back.timestamp).isEqualTo(555555L);
    }

    @Test
    @DisplayName("metric 内部 ×100 量化，精度 0.01")
    void metricQuantization() {
        MeshRouteRequestMsg msg = new MeshRouteRequestMsg(1, 2, 0, 0, 1.05, 0L);
        assertThat(msg.originMetricRaw).isEqualTo(105);
        assertThat(msg.originMetric()).isCloseTo(1.05, within(0.01));
    }

    @Test
    @DisplayName("metric 超过上限 655.35 钳制为 655.35")
    void metricClampToMax() {
        MeshRouteRequestMsg msg = new MeshRouteRequestMsg(1, 2, 0, 0, 999.99, 0L);
        assertThat(msg.originMetric()).isCloseTo(655.35, within(0.01));
        assertThat(msg.originMetricRaw).isEqualTo(65535);
    }

    @Test
    @DisplayName("负 metric 钳制为 0")
    void negativeMetricClampedToZero() {
        MeshRouteRequestMsg msg = new MeshRouteRequestMsg(1, 2, 0, 0, -5.0, 0L);
        assertThat(msg.originMetric()).isZero();
        assertThat(msg.originMetricRaw).isZero();
    }

    @Test
    @DisplayName("短 payload 容忍解码：仅 2 字节时还原 source/target，其余填 0")
    void decodeShortPayloadTolerant() {
        byte[] shortPayload = new byte[]{11, 22};
        MavlinkFrame frame = new MavlinkFrame(2, 0, 0, 0, 1, 1, 451, shortPayload, 0);
        MeshRouteRequestMsg msg = MeshRouteRequestMsg.decode(frame);

        assertThat(msg.sourceSysid).isEqualTo(11);
        assertThat(msg.targetSysid).isEqualTo(22);
        assertThat(msg.broadcastId).isZero();
        assertThat(msg.hopCount).isZero();
        assertThat(msg.timestamp).isZero();
    }

    @Test
    @DisplayName("消息 ID=451、LEN=12")
    void messageIdAndLen() {
        assertThat(MeshRouteRequestMsg.ID).isEqualTo(451);
        assertThat(MeshRouteRequestMsg.LEN).isEqualTo(12);
    }
}
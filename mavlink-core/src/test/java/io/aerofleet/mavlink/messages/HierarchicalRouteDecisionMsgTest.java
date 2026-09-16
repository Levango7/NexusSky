package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HIERARCHICAL_ROUTE_DECISION (msgId=461) 编解码单测（M7 星-空-地多层级中继，FR-5.3）。
 */
@DisplayName("HierarchicalRouteDecisionMsg 编解码 (msgId=461)")
class HierarchicalRouteDecisionMsgTest {

    private static HierarchicalRouteDecisionMsg roundtrip(HierarchicalRouteDecisionMsg msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        return HierarchicalRouteDecisionMsg.decode(frame);
    }

    @Test
    @DisplayName("全字段 encode→decode 往返一致")
    void encodeDecodeRoundtrip() {
        HierarchicalRouteDecisionMsg orig = new HierarchicalRouteDecisionMsg(
                0, 4, 3, 150, List.of(1, 42, 255),
                2, "L1/L2 N/A", 123456789L);
        HierarchicalRouteDecisionMsg back = roundtrip(orig);

        assertThat(back.sourceLayer).isEqualTo(0);
        assertThat(back.targetLayer).isEqualTo(4);
        assertThat(back.chosenLayer).isEqualTo(3);
        assertThat(back.estimatedDelayMs).isEqualTo(150);
        assertThat(back.pathNodes).containsExactly(1, 42, 255);
        assertThat(back.strategy).isEqualTo(2);
        assertThat(back.decisionReason).isEqualTo("L1/L2 N/A");
        assertThat(back.timestamp).isEqualTo(123456789L);
    }

    @Test
    @DisplayName("消息 ID=461、LEN=34、CRC_EXTRA=240")
    void messageIdAndConstants() {
        assertThat(HierarchicalRouteDecisionMsg.ID).isEqualTo(461);
        assertThat(HierarchicalRouteDecisionMsg.LEN).isEqualTo(34);
        assertThat(HierarchicalRouteDecisionMsg.CRC_EXTRA).isEqualTo(240);
    }

    @Test
    @DisplayName("encode 产出 34 字节 payload")
    void encodeLength() {
        HierarchicalRouteDecisionMsg msg = new HierarchicalRouteDecisionMsg(
                0, 4, 1, 20, List.of(1, 2), 0, "L1 可达", 0L);
        assertThat(msg.encode()).hasSize(34);
        assertThat(msg.messageId()).isEqualTo(461);
    }

    @Test
    @DisplayName("全不可达决策：chosenLayer=255, delay=65535")
    void noPathDecision() {
        HierarchicalRouteDecisionMsg msg = new HierarchicalRouteDecisionMsg(
                0, 4, HierarchicalRouteDecisionMsg.NO_PATH_LAYER,
                HierarchicalRouteDecisionMsg.NO_PATH_DELAY,
                List.of(), 0, "全层级不可达", 0L);
        assertThat(msg.isNoPath()).isTrue();
        assertThat(msg.chosenLayer).isEqualTo(255);
        assertThat(msg.estimatedDelayMs).isEqualTo(65535);
    }

    @Test
    @DisplayName("路径节点超过 8 跳时截断")
    void pathNodesTruncation() {
        HierarchicalRouteDecisionMsg msg = new HierarchicalRouteDecisionMsg(
                0, 4, 1, 20, List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
                0, "test", 0L);
        assertThat(msg.pathNodes).hasSize(8);
        assertThat(msg.pathHopCount).isEqualTo(8);
    }

    @Test
    @DisplayName("决策原因超过 14 字符时截断")
    void reasonTruncation() {
        String longReason = "这是一个非常长的决策原因字符串超过十四个字符";
        HierarchicalRouteDecisionMsg msg = new HierarchicalRouteDecisionMsg(
                0, 4, 1, 20, List.of(1), 0, longReason, 0L);
        HierarchicalRouteDecisionMsg back = roundtrip(msg);
        assertThat(back.decisionReason.length()).isLessThanOrEqualTo(14);
    }

    @Test
    @DisplayName("短 payload 容忍解码")
    void decodeShortPayloadTolerant() {
        byte[] shortPayload = new byte[]{0, 4, 1};
        MavlinkFrame frame = new MavlinkFrame(3, 0, 0, 0, 1, 1, 461, shortPayload, 0);
        HierarchicalRouteDecisionMsg msg = HierarchicalRouteDecisionMsg.decode(frame);

        assertThat(msg.sourceLayer).isEqualTo(0);
        assertThat(msg.targetLayer).isEqualTo(4);
        assertThat(msg.chosenLayer).isEqualTo(1);
        assertThat(msg.pathNodes).isEmpty();
    }

    @Test
    @DisplayName("msgId 461 全局唯一")
    void msgIdGloballyUnique() {
        assertThat(HierarchicalRouteDecisionMsg.ID).isNotIn(459, 460, 450, 454);
    }
}
package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EMERGENCY_PRIORITY (msgId=467) 编解码单测（M9 应急任务编排）。
 */
@DisplayName("EmergencyPriorityMsg 编解码 (msgId=467)")
class EmergencyPriorityMsgTest {

    private static EmergencyPriorityMsg roundtrip(EmergencyPriorityMsg msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        return EmergencyPriorityMsg.decode(frame);
    }

    @Test
    @DisplayName("全字段 encode→decode 往返一致")
    void encodeDecodeRoundtrip() {
        EmergencyPriorityMsg orig = new EmergencyPriorityMsg(
                12345L, 67890L, 1, 2, 11111L, "drone lost, replan", 1700000000L);
        EmergencyPriorityMsg back = roundtrip(orig);

        assertThat(back.planId).isEqualTo(12345L);
        assertThat(back.taskId).isEqualTo(67890L);
        assertThat(back.priority).isEqualTo(1);
        assertThat(back.action).isEqualTo(2);
        assertThat(back.preemptedTaskId).isEqualTo(11111L);
        assertThat(back.reason).isEqualTo("drone lost, replan");
        assertThat(back.timestamp).isEqualTo(1700000000L);
    }

    @Test
    @DisplayName("消息 ID=467、LEN=50、CRC_EXTRA=251")
    void messageIdAndConstants() {
        assertThat(EmergencyPriorityMsg.ID).isEqualTo(467);
        assertThat(EmergencyPriorityMsg.LEN).isEqualTo(50);
        assertThat(EmergencyPriorityMsg.CRC_EXTRA).isEqualTo(251);
    }

    @Test
    @DisplayName("encode 产出 50 字节 payload")
    void encodeLength() {
        EmergencyPriorityMsg msg = new EmergencyPriorityMsg(
                0, 0, 0, 0, 0, "", 0);
        assertThat(msg.encode()).hasSize(50);
        assertThat(msg.messageId()).isEqualTo(467);
    }

    @Test
    @DisplayName("reason 超长截断为 32 字节")
    void reasonTruncation() {
        String longReason = "这是一个非常非常非常非常非常非常非常非常非常非常长的原因描述字符串超过32字节";
        EmergencyPriorityMsg orig = new EmergencyPriorityMsg(
                1L, 1L, 1, 0, 0, longReason, 0);
        EmergencyPriorityMsg back = roundtrip(orig);
        // 解码后的 reason 最多 32 字符（UTF-8 字节截断）
        assertThat(back.reason.length()).isLessThanOrEqualTo(32);
    }

    @Test
    @DisplayName("reason 为空字符串正确编解码")
    void emptyReason() {
        EmergencyPriorityMsg orig = new EmergencyPriorityMsg(
                1L, 1L, 2, 3, 0, "", 0);
        EmergencyPriorityMsg back = roundtrip(orig);
        assertThat(back.reason).isEmpty();
    }

    @Test
    @DisplayName("preemptedTaskId=0 表示无抢占")
    void noPreemption() {
        EmergencyPriorityMsg orig = new EmergencyPriorityMsg(
                1L, 1L, 3, 0, 0, "new task", 0);
        EmergencyPriorityMsg back = roundtrip(orig);
        assertThat(back.preemptedTaskId).isZero();
    }

    @Test
    @DisplayName("短 payload 容忍解码：仅 9 字节时还原 planId+taskId+priority，其余填 0")
    void decodeShortPayloadTolerant() {
        byte[] shortPayload = new byte[]{
            0x39, 0x30, 0x00, 0x00,  // planId=12345
            0x32, 0x09, 0x01, 0x00,  // taskId=67890
            0x01                     // priority=1
        };
        MavlinkFrame frame = new MavlinkFrame(9, 0, 0, 0, 1, 1, 467, shortPayload, 0);
        EmergencyPriorityMsg msg = EmergencyPriorityMsg.decode(frame);

        assertThat(msg.planId).isEqualTo(12345L);
        assertThat(msg.taskId).isEqualTo(67890L);
        assertThat(msg.priority).isEqualTo(1);
        assertThat(msg.action).isZero();
        assertThat(msg.reason).isEmpty();
    }

    @Test
    @DisplayName("toString 包含关键字段")
    void toStringContainsFields() {
        EmergencyPriorityMsg msg = new EmergencyPriorityMsg(
                999L, 888L, 1, 2, 777L, "preempt", 0);
        String s = msg.toString();
        assertThat(s).contains("planId=999", "task=888", "pri=1", "action=2", "preempted=777");
    }
}
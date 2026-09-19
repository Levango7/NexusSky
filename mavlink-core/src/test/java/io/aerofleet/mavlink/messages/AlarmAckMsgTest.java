package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ALARM_ACK (msgId=478) 编解码单测（M14 安防报警）。
 */
@DisplayName("AlarmAckMsg 编解码 (msgId=478)")
class AlarmAckMsgTest {

    private static AlarmAckMsg roundtrip(AlarmAckMsg msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        return AlarmAckMsg.decode(frame);
    }

    @Test
    @DisplayName("消息 ID=478、LEN=12、CRC_EXTRA=262")
    void messageIdAndConstants() {
        assertThat(AlarmAckMsg.ID).isEqualTo(478);
        assertThat(AlarmAckMsg.LEN).isEqualTo(12);
        assertThat(AlarmAckMsg.CRC_EXTRA).isEqualTo(262);
    }

    @Test
    @DisplayName("全字段 encode→decode 往返一致")
    void encodeDecodeRoundtrip() {
        AlarmAckMsg orig = new AlarmAckMsg(
                123456789L, 1700000000L, 300, 7, 1);
        AlarmAckMsg back = roundtrip(orig);

        assertThat(back.alarmId).isEqualTo(123456789L);
        assertThat(back.timestamp).isEqualTo(1700000000L);
        assertThat(back.estimatedArrivalSec).isEqualTo(300);
        assertThat(back.droneSysid).isEqualTo(7);
        assertThat(back.ackResult).isEqualTo(1);
    }

    @Test
    @DisplayName("encode 产出 12 字节 payload")
    void encodeLength() {
        AlarmAckMsg msg = new AlarmAckMsg(0, 0, 0, 0, 0);
        assertThat(msg.encode()).hasSize(12);
        assertThat(msg.messageId()).isEqualTo(478);
    }

    @Test
    @DisplayName("各字段设置/获取：已开始响应")
    void fieldAccessStartedResponse() {
        AlarmAckMsg msg = new AlarmAckMsg(
                999L, 888L, 120, 3, 1);
        assertThat(msg.alarmId).isEqualTo(999L);
        assertThat(msg.droneSysid).isEqualTo(3);
        assertThat(msg.ackResult).isEqualTo(1);    // 已开始响应
        assertThat(msg.estimatedArrivalSec).isEqualTo(120);
    }

    @Test
    @DisplayName("边界值：alarmId/timestamp/estimatedArrivalSec/droneSysid/ackResult 最大最小")
    void boundaryValues() {
        // 最大值（ackResult 受语义范围约束：[0,3]）
        AlarmAckMsg maxMsg = new AlarmAckMsg(
                0xFFFFFFFFL, 0xFFFFFFFFL, 0xFFFF, 255, 3);
        AlarmAckMsg maxBack = roundtrip(maxMsg);
        assertThat(maxBack.alarmId).isEqualTo(0xFFFFFFFFL);
        assertThat(maxBack.timestamp).isEqualTo(0xFFFFFFFFL);
        assertThat(maxBack.estimatedArrivalSec).isEqualTo(0xFFFF);
        assertThat(maxBack.droneSysid).isEqualTo(255);
        assertThat(maxBack.ackResult).isEqualTo(3);

        // 最小值
        AlarmAckMsg minMsg = new AlarmAckMsg(0L, 0L, 0, 0, 0);
        AlarmAckMsg minBack = roundtrip(minMsg);
        assertThat(minBack.alarmId).isZero();
        assertThat(minBack.timestamp).isZero();
        assertThat(minBack.estimatedArrivalSec).isZero();
        assertThat(minBack.droneSysid).isZero();
        assertThat(minBack.ackResult).isZero();
    }

    @Test
    @DisplayName("确认结果语义：0=已收到, 1=已开始响应, 2=无法响应, 3=拒绝")
    void ackResultSemantics() {
        for (int result = 0; result <= 3; result++) {
            AlarmAckMsg orig = new AlarmAckMsg(1L, 1L, 60, 1, result);
            AlarmAckMsg back = roundtrip(orig);
            assertThat(back.ackResult).isEqualTo(result);
        }
    }

    @Test
    @DisplayName("短 payload 容忍解码：仅 5 字节时还原 alarmId+部分 timestamp，其余填 0")
    void decodeShortPayloadTolerant() {
        byte[] shortPayload = new byte[]{
            0x39, 0x30, 0x00, 0x00,  // alarmId=12345
            0x01                     // timestamp 低字节（不完整，不读）
        };
        MavlinkFrame frame = new MavlinkFrame(5, 0, 0, 0, 1, 1, 478, shortPayload, 0);
        AlarmAckMsg msg = AlarmAckMsg.decode(frame);

        assertThat(msg.alarmId).isEqualTo(12345L);
        assertThat(msg.timestamp).isZero();   // len <= 7，填 0
        assertThat(msg.estimatedArrivalSec).isZero();
        assertThat(msg.droneSysid).isZero();
        assertThat(msg.ackResult).isZero();
    }

    @Test
    @DisplayName("toString 包含关键字段")
    void toStringContainsFields() {
        AlarmAckMsg msg = new AlarmAckMsg(42L, 99L, 200, 5, 2);
        String s = msg.toString();
        assertThat(s).contains("alarmId=42", "drone=5", "result=2", "eta=200s");
    }
}
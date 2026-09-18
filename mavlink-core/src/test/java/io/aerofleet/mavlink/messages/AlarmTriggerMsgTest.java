package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ALARM_TRIGGER (msgId=477) 编解码单测（M14 安防报警）。
 */
@DisplayName("AlarmTriggerMsg 编解码 (msgId=477)")
class AlarmTriggerMsgTest {

    private static AlarmTriggerMsg roundtrip(AlarmTriggerMsg msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        return AlarmTriggerMsg.decode(frame);
    }

    @Test
    @DisplayName("消息 ID=477、LEN=68、CRC_EXTRA=261")
    void messageIdAndConstants() {
        assertThat(AlarmTriggerMsg.ID).isEqualTo(477);
        assertThat(AlarmTriggerMsg.LEN).isEqualTo(68);
        assertThat(AlarmTriggerMsg.CRC_EXTRA).isEqualTo(261);
    }

    @Test
    @DisplayName("全字段 encode→decode 往返一致")
    void encodeDecodeRoundtrip() {
        AlarmTriggerMsg orig = new AlarmTriggerMsg(
                1700000000L, 477123456, 1214736928, 1001,
                5000, 1, 2, "Intrusion at gate A");
        AlarmTriggerMsg back = roundtrip(orig);

        assertThat(back.timestamp).isEqualTo(1700000000L);
        assertThat(back.lat).isEqualTo(477123456);
        assertThat(back.lon).isEqualTo(1214736928);
        assertThat(back.sourceDeviceId).isEqualTo(1001);
        assertThat(back.alt).isEqualTo(5000);
        assertThat(back.alarmType).isEqualTo(1);
        assertThat(back.severity).isEqualTo(2);
        assertThat(back.description).isEqualTo("Intrusion at gate A");
    }

    @Test
    @DisplayName("encode 产出 68 字节 payload")
    void encodeLength() {
        AlarmTriggerMsg msg = new AlarmTriggerMsg(
                0, 0, 0, 0, 0, 0, 0, "");
        assertThat(msg.encode()).hasSize(68);
        assertThat(msg.messageId()).isEqualTo(477);
    }

    @Test
    @DisplayName("各字段设置/获取：火灾报警 + CRITICAL")
    void fieldAccessFireCritical() {
        AlarmTriggerMsg msg = new AlarmTriggerMsg(
                123456789L, 399000000, 116390000, 2002,
                12000, 2, 2, "Fire detected sector 3");
        assertThat(msg.alarmType).isEqualTo(2);   // 火灾
        assertThat(msg.severity).isEqualTo(2);    // CRITICAL
        assertThat(msg.sourceDeviceId).isEqualTo(2002);
        assertThat(msg.description).isEqualTo("Fire detected sector 3");
    }

    @Test
    @DisplayName("边界值：timestamp/lat/lon/alt/sourceDeviceId 最大最小")
    void boundaryValues() {
        // 最大值
        AlarmTriggerMsg maxMsg = new AlarmTriggerMsg(
                0xFFFFFFFFL, Integer.MAX_VALUE, Integer.MAX_VALUE, 0xFFFF,
                Short.MAX_VALUE, 255, 255, "max");
        AlarmTriggerMsg maxBack = roundtrip(maxMsg);
        assertThat(maxBack.timestamp).isEqualTo(0xFFFFFFFFL);
        assertThat(maxBack.lat).isEqualTo(Integer.MAX_VALUE);
        assertThat(maxBack.lon).isEqualTo(Integer.MAX_VALUE);
        assertThat(maxBack.sourceDeviceId).isEqualTo(0xFFFF);
        assertThat(maxBack.alt).isEqualTo(Short.MAX_VALUE);
        assertThat(maxBack.alarmType).isEqualTo(255);
        assertThat(maxBack.severity).isEqualTo(255);

        // 最小值（负数 lat/lon/alt）
        AlarmTriggerMsg minMsg = new AlarmTriggerMsg(
                0L, Integer.MIN_VALUE, Integer.MIN_VALUE, 0,
                Short.MIN_VALUE, 0, 0, "");
        AlarmTriggerMsg minBack = roundtrip(minMsg);
        assertThat(minBack.timestamp).isZero();
        assertThat(minBack.lat).isEqualTo(Integer.MIN_VALUE);
        assertThat(minBack.lon).isEqualTo(Integer.MIN_VALUE);
        assertThat(minBack.sourceDeviceId).isZero();
        assertThat(minBack.alt).isEqualTo(Short.MIN_VALUE);
        assertThat(minBack.alarmType).isZero();
        assertThat(minBack.severity).isZero();
    }

    @Test
    @DisplayName("description 超长截断为 50 字节")
    void descriptionTruncation() {
        String longDesc = "这是一个非常非常非常非常非常非常非常非常非常非常非常非常非常长的报警描述超过50字节限制";
        AlarmTriggerMsg orig = new AlarmTriggerMsg(
                1L, 0, 0, 0, 0, 0, 0, longDesc);
        AlarmTriggerMsg back = roundtrip(orig);
        assertThat(back.description.length()).isLessThanOrEqualTo(50);
    }

    @Test
    @DisplayName("description 为空字符串正确编解码")
    void emptyDescription() {
        AlarmTriggerMsg orig = new AlarmTriggerMsg(
                1L, 0, 0, 0, 0, 4, 0, "");
        AlarmTriggerMsg back = roundtrip(orig);
        assertThat(back.description).isEmpty();
    }

    @Test
    @DisplayName("短 payload 容忍解码：仅 8 字节时还原 timestamp+lat，其余填 0")
    void decodeShortPayloadTolerant() {
        byte[] shortPayload = new byte[]{
            0x00, 0x00, 0x00, 0x01,  // timestamp=0x01000000 (LE) = 16777216
            0x01, 0x00, 0x00, 0x00   // lat=1
        };
        MavlinkFrame frame = new MavlinkFrame(8, 0, 0, 0, 1, 1, 477, shortPayload, 0);
        AlarmTriggerMsg msg = AlarmTriggerMsg.decode(frame);

        assertThat(msg.timestamp).isEqualTo(16777216L);
        assertThat(msg.lat).isEqualTo(1);
        assertThat(msg.lon).isZero();
        assertThat(msg.sourceDeviceId).isZero();
        assertThat(msg.alt).isZero();
        assertThat(msg.alarmType).isZero();
        assertThat(msg.severity).isZero();
        assertThat(msg.description).isEmpty();
    }

    @Test
    @DisplayName("toString 包含关键字段")
    void toStringContainsFields() {
        AlarmTriggerMsg msg = new AlarmTriggerMsg(
                999L, 100, 200, 5, 300, 1, 2, "test alarm");
        String s = msg.toString();
        assertThat(s).contains("type=1", "sev=2", "srcDev=5", "desc='test alarm'");
    }
}
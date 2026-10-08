package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkMessageInfo;
import io.aerofleet.mavlink.PayloadCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ALARM_TRIGGER (msgId=30057) 编解码单测（M14 安防报警）。
 */
@DisplayName("AlarmTriggerMsg 编解码 (msgId=30057)")
class AlarmTriggerMsgTest {

    private static AlarmTriggerMsg roundtrip(AlarmTriggerMsg msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        return AlarmTriggerMsg.decode(frame);
    }

    @Test
    @DisplayName("消息 ID=30057、LEN=72、CRC_EXTRA=64（LEN 因尾部追加 alarmId 由 68→72）")
    void messageIdAndConstants() {
        assertThat(AlarmTriggerMsg.ID).isEqualTo(30057);
        assertThat(AlarmTriggerMsg.LEN).isEqualTo(72);
        // CRC_EXTRA 不随尾部追加而变：它只由 msgId 与字段名决定，不含字段顺序/长度
        assertThat(AlarmTriggerMsg.CRC_EXTRA).isEqualTo(64);
        assertThat(MavlinkMessageInfo.lengthOf(AlarmTriggerMsg.ID))
                .as("MavlinkMessageInfo 必须同步，否则帧长与常量表不一致")
                .isEqualTo(72);
    }

    @Test
    @DisplayName("全字段 encode→decode 往返一致（含 alarmId）")
    void encodeDecodeRoundtrip() {
        AlarmTriggerMsg orig = new AlarmTriggerMsg(
                1700000000L, 477123456, 1214736928, 1001,
                5000, 1, 2, "Intrusion at gate A", 0xDEADBEEFL);
        AlarmTriggerMsg back = roundtrip(orig);

        assertThat(back.timestamp).isEqualTo(1700000000L);
        assertThat(back.lat).isEqualTo(477123456);
        assertThat(back.lon).isEqualTo(1214736928);
        assertThat(back.sourceDeviceId).isEqualTo(1001);
        assertThat(back.alt).isEqualTo(5000);
        assertThat(back.alarmType).isEqualTo(1);
        assertThat(back.severity).isEqualTo(2);
        assertThat(back.description).isEqualTo("Intrusion at gate A");
        assertThat(back.alarmId)
                .as("alarmId 必须往返无损 —— 它是与 AlarmAckMsg 关联的唯一键")
                .isEqualTo(0xDEADBEEFL);
        assertThat(back.hasAlarmId()).isTrue();
    }

    @Test
    @DisplayName("alarmId 位于 payload 尾部偏移 68，不挤占 description")
    void alarmIdSitsAfterDescription() {
        // 追加式布局的关键不变量：description 仍在偏移 18、长度仍为 50，
        // alarmId 在 68。若有人日后把它插进头部，本测试会红。
        String desc = "0123456789";
        AlarmTriggerMsg msg = new AlarmTriggerMsg(
                0, 0, 0, 0, 0, 0, 0, desc, 0x11223344L);
        byte[] buf = msg.encode();

        assertThat(buf).hasSize(72);
        // description 仍在原偏移：偏移 18 起应能读回原串
        assertThat(PayloadCodec.chars(
                PayloadCodec.littleEndian(buf), 18, 50)).isEqualTo(desc);
        // alarmId 在 68
        assertThat(PayloadCodec.u32(PayloadCodec.littleEndian(buf), 68))
                .isEqualTo(0x11223344L);
    }

    @Test
    @DisplayName("向后兼容：新解码器读 68 字节旧帧 ⇒ alarmId=0 且其余字段无损")
    void decodeLegacyFrameWithoutAlarmId() {
        // 构造一个"旧格式"帧：前 68 字节按旧布局，尾部不存在
        byte[] legacy = PayloadCodec.alloc(68);
        PayloadCodec.putU32(legacy, 0, 1700000000L);
        PayloadCodec.putI32(legacy, 4, 477123456);
        PayloadCodec.putI32(legacy, 8, 1214736928);
        PayloadCodec.putU16(legacy, 12, 1001);
        PayloadCodec.putI16(legacy, 14, 5000);
        PayloadCodec.putU8(legacy, 16, 1);
        PayloadCodec.putU8(legacy, 17, 2);
        PayloadCodec.putChars(legacy, 18, "legacy frame", 50);

        MavlinkFrame frame = new MavlinkFrame(68, 0, 0, 0, 1, 1, AlarmTriggerMsg.ID, legacy, 0);
        AlarmTriggerMsg msg = AlarmTriggerMsg.decode(frame);

        assertThat(msg.alarmId)
                .as("旧帧无该字段 ⇒ 必须为 0（= 未携带），不得抛异常或读垃圾")
                .isZero();
        assertThat(msg.hasAlarmId()).isFalse();
        assertThat(msg.timestamp).isEqualTo(1700000000L);
        assertThat(msg.lat).isEqualTo(477123456);
        assertThat(msg.description).isEqualTo("legacy frame");
    }

    @Test
    @DisplayName("向前兼容：旧解码器读到 72 字节新帧时忽略尾部 4 字节")
    void oldDecoderIgnoresTrailingBytes() {
        // 用旧布局的读取方式（只读到 offset 67）解析新帧，验证既有字段仍可正确取出：
        // 这等价于"未升级的解码器"的行为，因为旧代码本就只按原偏移取值。
        AlarmTriggerMsg msg = new AlarmTriggerMsg(
                1700000000L, 477123456, 1214736928, 1001,
                5000, 1, 2, "new frame", 0xCAFEBABEL);
        byte[] buf = msg.encode();

        ByteBuffer b = PayloadCodec.littleEndian(buf);
        assertThat(PayloadCodec.u32(b, 0)).isEqualTo(1700000000L);
        assertThat(PayloadCodec.i32(b, 4)).isEqualTo(477123456);
        assertThat(PayloadCodec.u16(b, 12)).isEqualTo(1001);
        assertThat(PayloadCodec.u8(b, 16)).isEqualTo(1);
        assertThat(PayloadCodec.chars(b, 18, 50)).isEqualTo("new frame");
        // 旧解码器根本不会碰 offset 68 ⇒ 不受影响
    }

    @Test
    @DisplayName("alarmId=0 与 AlarmAckMsg 的 alarmId 一样表示'不可关联'")
    void zeroAlarmIdMeansNotCarried() {
        AlarmTriggerMsg noId = new AlarmTriggerMsg(
                1L, 0, 0, 0, 0, 0, 0, "");
        assertThat(noId.alarmId).isZero();
        assertThat(noId.hasAlarmId())
                .as("消费侧据此判断能否与 ack 关联")
                .isFalse();
    }

    @Test
    @DisplayName("旧构造器（无 alarmId）等价于 alarmId=0，保持源码兼容")
    void legacyConstructorDelegatesToZero() {
        AlarmTriggerMsg legacy = new AlarmTriggerMsg(
                1L, 2, 3, 4, 5, 0, 0, "x");
        assertThat(legacy.alarmId).isZero();
        assertThat(legacy.hasAlarmId()).isFalse();
    }

    @Test
    @DisplayName("encode 产出 72 字节 payload")
    void encodeLength() {
        AlarmTriggerMsg msg = new AlarmTriggerMsg(
                0, 0, 0, 0, 0, 0, 0, "");
        assertThat(msg.encode()).hasSize(72);
        assertThat(msg.messageId()).isEqualTo(30057);
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
        // 最大值（alarmType/severity 受语义范围约束：[0,4]/[0,2]）
        AlarmTriggerMsg maxMsg = new AlarmTriggerMsg(
                0xFFFFFFFFL, Integer.MAX_VALUE, Integer.MAX_VALUE, 0xFFFF,
                Short.MAX_VALUE, 4, 2, "max");
        AlarmTriggerMsg maxBack = roundtrip(maxMsg);
        assertThat(maxBack.timestamp).isEqualTo(0xFFFFFFFFL);
        assertThat(maxBack.lat).isEqualTo(Integer.MAX_VALUE);
        assertThat(maxBack.lon).isEqualTo(Integer.MAX_VALUE);
        assertThat(maxBack.sourceDeviceId).isEqualTo(0xFFFF);
        assertThat(maxBack.alt).isEqualTo(Short.MAX_VALUE);
        assertThat(maxBack.alarmType).isEqualTo(4);
        assertThat(maxBack.severity).isEqualTo(2);

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
        MavlinkFrame frame = new MavlinkFrame(8, 0, 0, 0, 1, 1, 30057, shortPayload, 0);
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
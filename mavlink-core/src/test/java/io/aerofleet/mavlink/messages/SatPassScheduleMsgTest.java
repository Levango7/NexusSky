package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SAT_PASS_SCHEDULE (msgId=30040) 编解码单测（M7 星-空-地多层级中继，FR-5.1）。
 */
@DisplayName("SatPassScheduleMsg 编解码 (msgId=30040)")
class SatPassScheduleMsgTest {

    private static SatPassScheduleMsg roundtrip(SatPassScheduleMsg msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        return SatPassScheduleMsg.decode(frame);
    }

    @Test
    @DisplayName("全字段 encode→decode 往返一致")
    void encodeDecodeRoundtrip() {
        SatPassScheduleMsg orig = new SatPassScheduleMsg(
                15, 1000000L, 1200000L, 78, 1, 123456789L);
        SatPassScheduleMsg back = roundtrip(orig);

        assertThat(back.satId).isEqualTo(15);
        assertThat(back.passStartMs).isEqualTo(1000000L);
        assertThat(back.passEndMs).isEqualTo(1200000L);
        assertThat(back.maxElevationDeg).isEqualTo(78);
        assertThat(back.groundPointId).isEqualTo(1);
        assertThat(back.timestamp).isEqualTo(123456789L);
    }

    @Test
    @DisplayName("消息 ID=30040、LEN=16、CRC_EXTRA=155")
    void messageIdAndConstants() {
        assertThat(SatPassScheduleMsg.ID).isEqualTo(30040);
        assertThat(SatPassScheduleMsg.LEN).isEqualTo(16);
        assertThat(SatPassScheduleMsg.CRC_EXTRA).isEqualTo(155);
    }

    @Test
    @DisplayName("encode 产出 16 字节 payload")
    void encodeLength() {
        SatPassScheduleMsg msg = new SatPassScheduleMsg(1, 0L, 1000L, 45, 0, 0L);
        assertThat(msg.encode()).hasSize(16);
        assertThat(msg.messageId()).isEqualTo(30040);
    }

    @Test
    @DisplayName("durationMs 计算正确")
    void durationCalculation() {
        SatPassScheduleMsg msg = new SatPassScheduleMsg(1, 1000L, 5000L, 60, 0, 0L);
        assertThat(msg.durationMs()).isEqualTo(4000L);
    }

    /**
     * 短 payload 容忍解码：<b>走完整生产路径</b>。
     * <p>
     * 旧用例用 {@code new MavlinkFrame(..., payloadLength=1, payload=new byte[2])} ——
     * LEN 字段与 payload 数组长度不一致，生产任何路径都产不出这种状态，
     * 于是首字段 {@code u16} 缺守卫被绿灯掩盖（真实 1 字节线上帧会抛
     * IndexOutOfBoundsException，收包侧只能丢帧）。
     */
    @Test
    @DisplayName("短 payload 容忍解码：真实线上帧 LEN=2 恰好还原 satId")
    void decodeShortPayloadTolerant_realWireFrame() {
        SatPassScheduleMsg msg = decodeOverWire(new byte[]{15, 0});

        assertThat(msg.satId).isEqualTo(15);
        assertThat(msg.passStartMs).isZero();
    }

    /**
     * LEN=1：satId 是 u16，首字段被截断。按本方法内其余 5 个字段一致的口径
     * （字段不完整即填 0，而不是拿半截字节凑值）应为 0，且不得抛异常。
     */
    @Test
    @DisplayName("半截 u16（LEN=1）容忍：satId 填 0 且不抛")
    void decodeTruncatedU16_fillsZero() {
        SatPassScheduleMsg msg = decodeOverWire(new byte[]{15});

        assertThat(msg.satId).isZero();
    }

    /** 空 payload：连首个 u16 都读不出来，仍必须容忍并填 0。 */
    @Test
    @DisplayName("零长度 payload 容忍：所有字段填 0")
    void decodeZeroPayloadTolerant() {
        SatPassScheduleMsg msg = decodeOverWire(new byte[0]);

        assertThat(msg.satId).isZero();
        assertThat(msg.passStartMs).isZero();
    }

    /**
     * 用生产同构的方式解码：{@code MavlinkFrame.of} 按给定 payload 的真实长度算 CRC，
     * {@code encodeV2()} 产出 LEN=截断长度的线上帧，{@code MavlinkParser} 逐帧校验 CRC，
     * 再交给被测的 decode —— 与收包侧代码路径一致。
     */
    private static SatPassScheduleMsg decodeOverWire(byte[] truncatedPayload) {
        byte[] wire = MavlinkFrame.of(1, 1, 0, SatPassScheduleMsg.ID,
                SatPassScheduleMsg.CRC_EXTRA, truncatedPayload).encodeV2();
        MavlinkParser.ParseResult parsed = new MavlinkParser().parse(ByteBuffer.wrap(wire));

        assertThat(parsed).as("截断帧应通过 CRC 校验并被解析").isNotNull();
        // 生产不变式：解析产出的帧 LEN 与 payload 数组长度必然一致
        assertThat(parsed.frame.getPayloadLength())
                .as("生产形态：payloadLength 必须等于 payload 数组长度")
                .isEqualTo(parsed.frame.getPayload().length);
        return SatPassScheduleMsg.decode(parsed.frame);
    }

    @Test
    @DisplayName("msgId 30040 全局唯一")
    void msgIdGloballyUnique() {
        assertThat(SatPassScheduleMsg.ID).isNotIn(30039, 30041, 30030, 30034);
    }
}
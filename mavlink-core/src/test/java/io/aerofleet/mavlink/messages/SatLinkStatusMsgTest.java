package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SAT_LINK_STATUS (msgId=30039) 编解码单测（M7 星-空-地多层级中继，FR-5.4）。
 */
@DisplayName("SatLinkStatusMsg 编解码 (msgId=30039)")
class SatLinkStatusMsgTest {

    private static SatLinkStatusMsg roundtrip(SatLinkStatusMsg msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        return SatLinkStatusMsg.decode(frame);
    }

    @Test
    @DisplayName("全字段 encode→decode 往返一致")
    void encodeDecodeRoundtrip() {
        SatLinkStatusMsg orig = SatLinkStatusMsg.simulated(
                42, 1, 65, 180, 150, 40, 999999L, 3, 123456789L);
        SatLinkStatusMsg back = roundtrip(orig);

        assertThat(back.satId).isEqualTo(42);
        assertThat(back.visible).isEqualTo(1);
        assertThat(back.elevationDeg).isEqualTo(65);
        assertThat(back.azimuthDeg).isEqualTo(180);
        assertThat(back.delayMs).isEqualTo(150);
        assertThat(back.bandwidthMbps).isEqualTo(40);
        assertThat(back.windowEndMs).isEqualTo(999999L);
        assertThat(back.sharedUsers).isEqualTo(3);
        assertThat(back.timestamp).isEqualTo(123456789L);
        assertThat(back.simFlag).isEqualTo(1);
    }

    @Test
    @DisplayName("消息 ID=30039、LEN=24、CRC_EXTRA=152")
    void messageIdAndConstants() {
        assertThat(SatLinkStatusMsg.ID).isEqualTo(30039);
        assertThat(SatLinkStatusMsg.LEN).isEqualTo(24);
        assertThat(SatLinkStatusMsg.CRC_EXTRA).isEqualTo(152);
    }

    @Test
    @DisplayName("encode 产出 24 字节 payload")
    void encodeLength() {
        SatLinkStatusMsg msg = SatLinkStatusMsg.simulated(1, 0, 0, 0, 50, 50, 0L, 0, 0L);
        assertThat(msg.encode()).hasSize(24);
        assertThat(msg.messageId()).isEqualTo(30039);
    }

    /**
     * 短 payload 容忍解码：<b>走完整生产路径</b>。
     * <p>
     * 这里刻意不用 {@code new MavlinkFrame(..., payloadLength=1, payload=new byte[2])} ——
     * 那是 LEN 字段与 payload 数组长度不一致的自相矛盾状态，{@code MavlinkParser} 恒有
     * {@code payload.length == payloadLen}，{@code MavlinkFrame.of} 也按 payload.length 定 LEN，
     * 所以生产任何路径都产不出它。旧测试正是在这个不可能的状态上绿灯，
     * 于是首字段 {@code u16} 缺守卫（真实 1 字节帧上抛 IndexOutOfBoundsException）没被发现。
     */
    @Test
    @DisplayName("短 payload 容忍解码：真实线上帧 LEN=2 恰好还原 satId")
    void decodeShortPayloadTolerant_realWireFrame() {
        SatLinkStatusMsg msg = decodeOverWire(new byte[]{42, 0});

        assertThat(msg.satId).isEqualTo(42);
        assertThat(msg.visible).isZero();
    }

    /**
     * LEN=1：satId 是 u16，首字段被截断。按本方法内其余 8 个字段一致的口径
     * （字段不完整即填 0，而不是拿半截字节凑值）应为 0，且不得抛异常。
     */
    @Test
    @DisplayName("半截 u16（LEN=1）容忍：satId 填 0 且不抛")
    void decodeTruncatedU16_fillsZero() {
        SatLinkStatusMsg msg = decodeOverWire(new byte[]{42});

        assertThat(msg.satId).isZero();
    }

    /** 空 payload：连首个 u16 都读不出来，仍必须容忍并填 0。 */
    @Test
    @DisplayName("零长度 payload 容忍：所有字段填 0")
    void decodeZeroPayloadTolerant() {
        SatLinkStatusMsg msg = decodeOverWire(new byte[0]);

        assertThat(msg.satId).isZero();
        assertThat(msg.visible).isZero();
    }

    /**
     * 用生产同构的方式解码：{@code MavlinkFrame.of} 按给定 payload 的真实长度算 CRC，
     * {@code encodeV2()} 产出 LEN=截断长度的线上帧，{@code MavlinkParser} 逐帧校验 CRC，
     * 再交给被测的 decode —— 与收包侧代码路径一致。
     */
    private static SatLinkStatusMsg decodeOverWire(byte[] truncatedPayload) {
        byte[] wire = MavlinkFrame.of(1, 1, 0, SatLinkStatusMsg.ID,
                SatLinkStatusMsg.CRC_EXTRA, truncatedPayload).encodeV2();
        MavlinkParser.ParseResult parsed = new MavlinkParser().parse(ByteBuffer.wrap(wire));

        assertThat(parsed).as("截断帧应通过 CRC 校验并被解析").isNotNull();
        // 生产不变式：解析产出的帧 LEN 与 payload 数组长度必然一致
        assertThat(parsed.frame.getPayloadLength())
                .as("生产形态：payloadLength 必须等于 payload 数组长度")
                .isEqualTo(parsed.frame.getPayload().length);
        return SatLinkStatusMsg.decode(parsed.frame);
    }

    @Test
    @DisplayName("isVisible/isSimulated 便捷方法")
    void visibilityHelpers() {
        SatLinkStatusMsg visible = SatLinkStatusMsg.simulated(1, 1, 45, 90, 100, 30, 0L, 1, 0L);
        SatLinkStatusMsg hidden = SatLinkStatusMsg.simulated(1, 0, 0, 0, 0, 0, 0L, 0, 0L);

        assertThat(visible.isVisible()).isTrue();
        assertThat(visible.isSimulated()).isTrue();
        assertThat(hidden.isVisible()).isFalse();
        assertThat(hidden.isSimulated()).isTrue();
    }

    @Test
    @DisplayName("msgId 30039 全局唯一：不与 30030-30034 冲突")
    void msgIdGloballyUnique() {
        assertThat(SatLinkStatusMsg.ID).isNotIn(30030, 30031, 30032, 30033, 30034);
        assertThat(SatLinkStatusMsg.ID).isNotIn(30000, 30001, 30002, 30003, 30004, 30005, 30006);
    }
}
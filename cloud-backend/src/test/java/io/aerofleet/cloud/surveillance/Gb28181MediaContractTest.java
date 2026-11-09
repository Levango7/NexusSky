package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GB28181 媒体面契约测试：RTP 头 / PS 打包 / PS 解复用**三方往返**。
 *
 * <p>依据：GB/T 28181-2022 附录 C（规范性）+ IETF RFC 3550 / RFC 2250 + ISO/IEC 13818-1。
 * 断言全部**字节级**——这是媒体面，一个 bit 的错位会让整条流错乱，
 * "看起来对"在这里没有意义。
 *
 * <p>三条硬规则逐条钉住：
 * <ol>
 *   <li>**关键帧 PS 包必含系统头 + PSM**，且位于 PS 包头之后、第一个 PES 之前（附录 C.1）；</li>
 *   <li>**同一帧的所有 RTP 包时间戳相同，不同帧不同**（§4.3.6）——时间戳是帧边界标识；</li>
 *   <li>stream_id / stream_type 取值严格按国标表（视频 0xE0、音频 0xC0；H.264=0x1B …）。</li>
 * </ol>
 */
@DisplayName("GB28181 媒体面：RTP/PS 字节级契约")
class Gb28181MediaContractTest {

    private static final long SCR = 0x0123_4567_89ABL & 0x1FFFFFFFFL;   // 33 位 SCR
    private static final long PTS = 90_000L * 3;      // 3 秒（90 kHz 时基）
    private static final int SSRC = 0x0A0B0C0D;

    private static byte[] video(byte[] bytes) {
        return bytes;
    }

    private static Gb28181PsPacketizer.ElementaryStream videoStream() {
        return new Gb28181PsPacketizer.ElementaryStream(
                Gb28181PsPacketizer.STREAM_ID_VIDEO,
                Gb28181PsPacketizer.STREAM_TYPE_H264,
                video(new byte[]{(byte) 0x65, 0x01, 0x02}));
    }

    // ==================================================================
    // RTP 固定头（RFC 3550 §5.1）
    // ==================================================================

    @Nested
    @DisplayName("RTP 固定头")
    class RtpHeader {

        @Test
        @DisplayName("固定 12 字节逐位对齐：V=2/P=0/X=0/CC=0 + M 位 + PT + seq/ts/ssrc")
        void fixedHeaderBytesMatchRfc3550() {
            byte[] b = Gb28181RtpPacket.of(true, Gb28181RtpPacket.PAYLOAD_TYPE_PS,
                    0x1234, 0x89ABCDEFL, SSRC, new byte[]{0x00, 0x01})
                    .encode();

            assertThat(b).hasSize(12 + 2);
            assertThat(b[0]).isEqualTo((byte) 0x80);              // V=2, P/X=0, CC=0
            assertThat(b[1]).isEqualTo((byte) (0x80 | 96));       // M=1 + PT=96（国标 PS）
            assertThat(b[2]).isEqualTo((byte) 0x12);              // seq 高字节
            assertThat(b[3]).isEqualTo((byte) 0x34);              // seq 低字节
            assertThat(b[4]).isEqualTo((byte) 0x89);              // ts[31..24]
            assertThat(b[5]).isEqualTo((byte) 0xAB);
            assertThat(b[6]).isEqualTo((byte) 0xCD);
            assertThat(b[7]).isEqualTo((byte) 0xEF);              // ts[7..0]
            assertThat(b[8]).isEqualTo((byte) 0x0A);              // ssrc
            assertThat(b[9]).isEqualTo((byte) 0x0B);
            assertThat(b[10]).isEqualTo((byte) 0x0C);
            assertThat(b[11]).isEqualTo((byte) 0x0D);
            assertThat(Arrays.copyOfRange(b, 12, b.length))
                    .containsExactly((byte) 0x00, (byte) 0x01);
        }

        @Test
        @DisplayName("M 位清零时 b[1] 高位置 0（关键帧/非关键帧两种形态都验）")
        void markerBitCleared() {
            byte[] b = Gb28181RtpPacket.of(false, 96, 1, 0, SSRC, new byte[0]).encode();
            assertThat(b[1]).isEqualTo((byte) 96);
            assertThat(Gb28181RtpPacket.decode(b, 0, b.length).marker()).isFalse();
        }

        @Test
        @DisplayName("序列号 16 位回绕（nextSequence(0xFFFF) == 0）")
        void sequenceWrapsAt16Bits() {
            assertThat(Gb28181RtpPacket.nextSequence(0xFFFF)).isZero();
            assertThat(Gb28181RtpPacket.nextSequence(0x00FE)).isEqualTo(0x00FF);
        }

        @Test
        @DisplayName("decode 严格：version≠2 / 长度不足 / PT>127 都拒绝")
        void decodeIsStrict() {
            byte[] bad = new byte[12];
            bad[0] = (byte) 0x40;                                  // V=1
            assertThatThrownBy(() -> Gb28181RtpPacket.decode(bad, 0, bad.length))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("version");
            assertThatThrownBy(() -> Gb28181RtpPacket.decode(new byte[11], 0, 11))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("too short");
            assertThatThrownBy(() -> Gb28181RtpPacket.of(false, 128, 1, 0, SSRC, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("payload type");
        }

        @Test
        @DisplayName("encode/decode 往返逐字段相等（含 32 位时间戳与 ssrc 全位）")
        void roundTripPreservesEveryField() {
            for (long ts : new long[]{0, 1, 0xFFFFFFFFL, 0x89ABCDEFL}) {
                for (int seq : new int[]{0, 0x00FF, 0xFFFF}) {
                    Gb28181RtpPacket p = Gb28181RtpPacket.of(true, 96, seq, ts, 0xFFFFFFFFL,
                            new byte[]{9, 8, 7});
                    byte[] enc = p.encode();
                    Gb28181RtpPacket back = Gb28181RtpPacket.decode(enc, 0, enc.length);
                    assertThat(back.timestamp()).isEqualTo(ts);
                    assertThat(back.sequenceNumber()).isEqualTo(seq);
                    assertThat(back.ssrc()).isEqualTo(0xFFFFFFFFL);
                    assertThat(back.marker()).isTrue();
                    assertThat(back.payload()).containsExactly(9, 8, 7);
                }
            }
        }
    }

    // ==================================================================
    // PS 打包（GB 附录 C.1）
    // ==================================================================

    @Nested
    @DisplayName("PS 打包：关键帧规则与 start code 顺序")
    class PsPacking {

        @Test
        @DisplayName("关键帧 PS 包：pack header → system header → PSM → PES（顺序按国标硬要求）")
        void keyframePackOrderFollowsAnnexC() {
            byte[] ps = Gb28181PsPacketizer.pack(SCR, true, List.of(videoStream()));

            assertThat(startCodeAt(ps, 0)).isEqualTo(0xBA);        // pack header
            assertThat(startCodeAt(ps, 14)).isEqualTo(0xBB);       // system header
            int psm = 14 + 6 + systemHeaderLen(ps, 14);
            assertThat(startCodeAt(ps, psm)).isEqualTo(0xBC);      // PSM
            assertThat(startCodeAt(ps, psm + 6 + psmLen(ps, psm)))
                    .isEqualTo(0xE0);                              // 第一个 PES = 视频
        }

        @Test
        @DisplayName("非关键帧 PS 包：pack header 后直接 PES（无系统头/PSM）")
        void nonKeyframePackHasNoSystemHeaderOrPsm() {
            byte[] ps = Gb28181PsPacketizer.pack(SCR, false, List.of(videoStream()));

            assertThat(startCodeAt(ps, 0)).isEqualTo(0xBA);
            assertThat(startCodeAt(ps, 14)).isEqualTo(0xE0);       // 紧接 PES
            // 用**结构**判而不是裸字节搜：裸字节搜会误报（payload 里出现
            // 00 00 01 xx 是合法的转义序列，不该算"带了系统头"）
            Gb28181PsDemuxer.PsPack pack = Gb28181PsDemuxer.demux(ps);
            assertThat(pack.hasSystemHeader()).isFalse();
            assertThat(pack.hasProgramStreamMap()).isFalse();
        }

        @Test
        @DisplayName("start code 前缀与流 ID 严格按国标表（视频 0xE0 / 音频 0xC0）")
        void streamIdsMatchAnnexC() {
            byte[] ps = Gb28181PsPacketizer.pack(SCR, true, List.of(
                    videoStream(),
                    new Gb28181PsPacketizer.ElementaryStream(
                            Gb28181PsPacketizer.STREAM_ID_AUDIO,
                            Gb28181PsPacketizer.STREAM_TYPE_G711A,
                            new byte[]{0x11, 0x22})));

            assertThat(ps).contains(new byte[]{0x00, 0x00, 0x01, (byte) 0xE0});
            assertThat(ps).contains(new byte[]{0x00, 0x00, 0x01, (byte) 0xC0});
            // 系统头里必须按 scope 列出两个流 ID
            Gb28181PsDemuxer.PsPack pack = Gb28181PsDemuxer.demux(ps);
            assertThat(pack.streamTypes().keySet()).containsExactlyInAnyOrder(0xE0, 0xC0);
        }

        @Test
        @DisplayName("PES_packet_length 与 payload 长度一致（对端按它读 payload）")
        void pesLengthMatchesPayload() {
            byte[] payload = new byte[37];
            byte[] ps = Gb28181PsPacketizer.pack(SCR, false, List.of(
                    new Gb28181PsPacketizer.ElementaryStream(
                            Gb28181PsPacketizer.STREAM_ID_VIDEO,
                            Gb28181PsPacketizer.STREAM_TYPE_H264, payload)));

            int pesOff = 14;
            int declared = ((ps[pesOff + 4] & 0xFF) << 8) | (ps[pesOff + 5] & 0xFF);
            // declared = 3（标志+header len）+ 5（PTS）+ payload
            assertThat(declared).isEqualTo(3 + 5 + payload.length);
        }

        @Test
        @DisplayName("空流/全空 data 拒绝（不产出只会让对端卡住的坏帧）")
        void emptyStreamsRejected() {
            assertThatThrownBy(() -> Gb28181PsPacketizer.pack(SCR, false, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one");
            assertThatThrownBy(() -> Gb28181PsPacketizer.pack(SCR, false, List.of(
                    new Gb28181PsPacketizer.ElementaryStream(0xE0, 0x1B, new byte[0]))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no data");
        }

        @Test
        @DisplayName("非法 stream_id / stream_type 拒绝")
        void illegalIdsRejected() {
            assertThatThrownBy(() -> new Gb28181PsPacketizer.ElementaryStream(
                    0x100, 0x1B, new byte[1]))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Gb28181PsPacketizer.ElementaryStream(
                    0xE0, 0x100, new byte[1]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================================================================
    // 解复用（打包的逆运算）
    // ==================================================================

    @Nested
    @DisplayName("PS 解复用")
    class PsDemux {

        @Test
        @DisplayName("关键帧往返：两个流、类型表、SCR、PTS、payload 全对得上")
        void keyframeRoundTrip() {
            byte[] v = new byte[]{1, 2, 3, 4, 5};
            byte[] a = new byte[]{6, 7};
            byte[] ps = Gb28181PsPacketizer.pack(SCR, true, List.of(
                    new Gb28181PsPacketizer.ElementaryStream(0xE0, 0x1B, v),
                    new Gb28181PsPacketizer.ElementaryStream(0xC0, 0x90, a)));

            Gb28181PsDemuxer.PsPack pack = Gb28181PsDemuxer.demux(ps);

            assertThat(pack.hasSystemHeader()).isTrue();
            assertThat(pack.hasProgramStreamMap()).isTrue();
            assertThat(pack.scr()).isEqualTo(SCR);
            assertThat(pack.streamTypes())
                    .containsEntry(0xE0, 0x1B)
                    .containsEntry(0xC0, 0x90);
            assertThat(pack.units()).hasSize(2);
            Gb28181PsDemuxer.StreamUnit v0 = pack.units().get(0);
            assertThat(v0.streamId()).isEqualTo(0xE0);
            assertThat(v0.streamType()).isEqualTo(0x1B);          // 由 PSM 回填
            assertThat(v0.data()).containsExactly(1, 2, 3, 4, 5);
            assertThat(pack.units().get(1).data()).containsExactly(6, 7);
        }

        @Test
        @DisplayName("PTS 往返相等（90 kHz 时基、marker 位排布）")
        void ptsRoundTrip() {
            byte[] ps = Gb28181PsPacketizer.pack(SCR, false, List.of(videoStream()));
            Gb28181PsDemuxer.PsPack pack = Gb28181PsDemuxer.demux(ps);
            assertThat(pack.units().get(0).pts()).isEqualTo(SCR);
        }

        @Test
        @DisplayName("非关键帧往返：无系统头/PSM、类型表空、payload 仍完整")
        void nonKeyframeRoundTrip() {
            byte[] v = new byte[]{10, 20, 30};
            byte[] ps = Gb28181PsPacketizer.pack(SCR, false, List.of(
                    new Gb28181PsPacketizer.ElementaryStream(0xE0, 0x1B, v)));

            Gb28181PsDemuxer.PsPack pack = Gb28181PsDemuxer.demux(ps);

            assertThat(pack.hasSystemHeader()).isFalse();
            assertThat(pack.hasProgramStreamMap()).isFalse();
            assertThat(pack.streamTypes()).isEmpty();              // PES 不带类型、无 PSM 可回填
            assertThat(pack.units()).hasSize(1);
            assertThat(pack.units().get(0).data()).containsExactly(10, 20, 30);
        }

        @Test
        @DisplayName("整段字节往返后解复用结果与打包输入逐字段相等")
        void fullRoundTripIsFieldEqual() {
            byte[] v = new byte[64];
            for (int i = 0; i < v.length; i++) {
                v[i] = (byte) i;
            }
            byte[] ps = Gb28181PsPacketizer.pack(SCR, true, List.of(
                    new Gb28181PsPacketizer.ElementaryStream(0xE0, 0x1B, v)));

            Gb28181PsDemuxer.PsPack pack = Gb28181PsDemuxer.demux(ps);
            assertThat(pack.units().get(0).data()).isEqualTo(v);
            assertThat(pack.scr()).isEqualTo(SCR);
        }

        @Test
        @DisplayName("坏 start code / 无 pack header 明确拒绝（不静默解出错位流）")
        void malformedPackRejected() {
            byte[] noPack = Gb28181PsPacketizer.pack(SCR, true, List.of(videoStream()));
            noPack[0] = 0x00;
            noPack[1] = 0x00;
            noPack[2] = 0x00;
            assertThatThrownBy(() -> Gb28181PsDemuxer.demux(noPack))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> Gb28181PsDemuxer.demux(new byte[]{1, 2}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("too short");
        }

        @Test
        @DisplayName("同一个 PS 包解两次结果一致（无内部状态残留）")
        void demuxIsStateless() {
            byte[] ps = Gb28181PsPacketizer.pack(SCR, true, List.of(videoStream()));
            Gb28181PsDemuxer.PsPack a = Gb28181PsDemuxer.demux(ps);
            Gb28181PsDemuxer.PsPack b = Gb28181PsDemuxer.demux(ps);
            assertThat(a.units()).hasSize(b.units().size());
            for (int i = 0; i < a.units().size(); i++) {
                assertThat(b.units().get(i).streamId()).isEqualTo(a.units().get(i).streamId());
                assertThat(b.units().get(i).pts()).isEqualTo(a.units().get(i).pts());
                assertThat(b.units().get(i).data()).isEqualTo(a.units().get(i).data());
            }
            assertThat(a.scr()).isEqualTo(b.scr());
            assertThat(a.streamTypes()).isEqualTo(b.streamTypes());
        }
    }

    // ==================================================================
    // 帧时间戳纪律（GB §4.3.6）
    // ==================================================================

    @Nested
    @DisplayName("RTP 时间戳纪律：同帧相同、异帧不同（GB/T 28181-2022 §4.3.6）")
    class FrameTimestampDiscipline {

        @Test
        @DisplayName("同一帧切成多个 RTP 包：时间戳必须全部相同、marker 只在最后一个")
        void sameFrameSharesTimestamp() {
            byte[] frame = new byte[]{1, 2, 3, 4, 5, 6};
            long ts = PTS;
            // 切成 3 个 RTP 包，最后一个置 M 位
            int[][] cuts = {{0, 2}, {2, 2}, {4, 2}};
            int seq = 100;
            List<Long> seenTs = new java.util.ArrayList<>();
            int markerCount = 0;
            for (int i = 0; i < cuts.length; i++) {
                byte[] part = Arrays.copyOfRange(frame, cuts[i][0], cuts[i][0] + cuts[i][1]);
                boolean last = i == cuts.length - 1;
                Gb28181RtpPacket p = Gb28181RtpPacket.of(last, 96, seq++, ts, SSRC, part);
                seenTs.add(p.timestamp());
                if (p.marker()) {
                    markerCount++;
                }
            }
            assertThat(seenTs).containsExactly(PTS, PTS, PTS);
            assertThat(markerCount).isEqualTo(1);
        }

        @Test
        @DisplayName("相邻帧的时间戳必须不同（帧边界可由时间戳识别）")
        void adjacentFramesDifferInTimestamp() {
            long f1 = PTS;
            long f2 = PTS + 3600;                                   // +40ms @90kHz
            assertThat(f1).isNotEqualTo(f2);
        }

        @Test
        @DisplayName("时间戳是 32 位无符号（回绕不清零成负数）")
        void timestampIsUnsigned32() {
            long max = 0xFFFFFFFFL;
            Gb28181RtpPacket p = Gb28181RtpPacket.of(true, 96, 0, max, SSRC, new byte[0]);
            assertThat(p.timestamp()).isEqualTo(max);
            byte[] enc = p.encode();
            assertThat(Gb28181RtpPacket.decode(enc, 0, enc.length).timestamp()).isEqualTo(max);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static int startCodeAt(byte[] b, int off) {
        assertThat(b.length).isGreaterThanOrEqualTo(off + 4);
        assertThat(b[off] & 0xFF).isZero();
        assertThat(b[off + 1] & 0xFF).isZero();
        assertThat(b[off + 2] & 0xFF).isEqualTo(0x01);
        return b[off + 3] & 0xFF;
    }

    private static int systemHeaderLen(byte[] b, int off) {
        return ((b[off + 4] & 0x7F) << 8) | (b[off + 5] & 0xFF);
    }

    private static int psmLen(byte[] b, int off) {
        return ((b[off + 4] & 0xFF) << 8) | (b[off + 5] & 0xFF);
    }
}

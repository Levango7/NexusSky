package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GB28181 媒体面编解码的**已知答案**测试（ISO/IEC 13818-1 + GB/T 28181-2022 附录 C.1）。
 *
 * <p><b>为什么需要这一类测试</b>：变异门禁（PIT，surveillance 包）在 #263 上量到
 * 新代码有 47 条存活变异体，**几乎全是位运算/边界类**——{@code <<21→<<22}、
 * {@code &→|}、{@code off+4→off-4}。这类缺陷的特征是「表达式看着对、常量错一位」：
 * 真链路往返测试（包→解）如果两端同时错就自洽（和海康 AK/SK 签名同一个坑），
 * 只有拿**外部真值**逐字节比才能钉死。
 *
 * <p>因此本类所有期望值都由 <b>独立实现的 Python 脚本</b>按 ISO 位域表算出
 * （{@code gen-gb-vectors.py}，另一门语言、另一种写法），再把结果按常量写进断言。
 * 任何一侧改动位移/掩码/长度算术都会让字节序列对不上 ⇒ 变异体被杀。
 */
@DisplayName("gb28181 codec known-answer: every shift/mask pinned against an external truth")
class Gb28181CodecKnownAnswerTest {


    /** 把若干 PES 套进一个合法 PS 包（pack header + PES ...），供 demux 已知答案用。 */
    private static byte[] psPack(byte[]... parts) {
        byte[] hdr = Gb28181PsPacketizer.packHeader(0x00ABCDEFL);
        int total = hdr.length + Arrays.stream(parts).mapToInt(x -> x.length).sum();
        byte[] out = new byte[total];
        System.arraycopy(hdr, 0, out, 0, hdr.length);
        int off = hdr.length;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    /** 便捷：按 pts 造一个视频 PES。 */
    private static byte[] videoPes(long pts) {
        return Gb28181PsPacketizer.pes(0xE0, 0x1B, new byte[]{1}, pts);
    }

    /** 真值数组书写用：外部生成器给出的是无符号十六进制，这里统一转成 signed byte。 */
    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (byte) v[i];
        }
        return out;
    }


    // ==================================================================
    // packHeader —— ISO 13818-1 §2.5.3.1 的 33 位 SCR 位域
    // ==================================================================

    @Test
    @DisplayName("packHeader：SCR=0 时逐字节等于外部真值（marker 位不被算进 SCR）")
    void packHeaderScrZeroBytes() {
        assertThat(Gb28181PsPacketizer.packHeader(0x00000000L))
                .containsExactly(b(0x00, 0x00, 0x01, 0xBA, 0x21, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00));
    }

    @Test
    @DisplayName("packHeader：SCR=1 只落在低 6 位段（byte8 = SCR[5..0]<<2 | marker）")
    void packHeaderScrOne() {
        // SCR[5..0]=0b000001 ⇒ byte8 = 0b000001<<2 | 0b10 = 0x06
        assertThat(Gb28181PsPacketizer.packHeader(0x00000001L))
                .containsExactly(b(0x00, 0x00, 0x01, 0xBA, 0x21, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, 0x00, 0x00));
    }

    @Test
    @DisplayName("packHeader：SCR=0xABCDEF 逐字等于外部真值（三个中段各不相等）")
    void packHeaderScrAbcdef() {
        assertThat(Gb28181PsPacketizer.packHeader(0x00ABCDEFL))
                .containsExactly(b(0x00, 0x00, 0x01, 0xBA, 0x21, 0x02, 0xAF, 0x37, 0xBE, 0x00, 0x00, 0x00, 0x00, 0x00));
    }

    @Test
    @DisplayName("packHeader：SCR=0x1FFFFFFFF（33 位全 1）检验各段掩码宽度")
    void packHeaderScrAllOnes() {
        // byte4 = '0010'|SCR[32..30]=0b001|marker = 0x20|0x02|0x01 = 0x23?
        // 外部真值：SCR[32..30]=0b000 ⇒ (0<<29)&0x0E=0 ⇒ 0x21；三段满 0xFF；byte8 满 0xFE
        assertThat(Gb28181PsPacketizer.packHeader(0x1FFFFFFFFL))
                .containsExactly(b(0x00, 0x00, 0x01, 0xBA, 0x2F, 0xFF, 0xFF, 0xFF, 0xFE, 0x00, 0x00, 0x00, 0x00, 0x00));
    }

    @Test
    @DisplayName("packHeader：SCR=0x123456789（高 3 位非零）检验 SCR[32..30] 段")
    void packHeaderScrHighBits() {
        assertThat(Gb28181PsPacketizer.packHeader(0x123456789L))
                .containsExactly(b(0x00, 0x00, 0x01, 0xBA, 0x29, 0x8D, 0x15, 0x9E, 0x26, 0x00, 0x00, 0x00, 0x00, 0x00));
    }

    @Test
    @DisplayName("SCR 读回：独立真值下 5 个 SCR 全部逐位还原（demux 的 readScr 逆运算）")
    void scrReadBackFromPackHeader() {
        for (long scr : new long[]{0x00000000L, 0x00000001L, 0x00ABCDEFL,
                0x1FFFFFFFFL, 0x123456789L}) {
            Gb28181PsDemuxer.PsPack p = Gb28181PsDemuxer.demux(
                    Gb28181PsPacketizer.packHeader(scr));
            assertThat(p.scr())
                    .as("scr=%s must round-trip bit-exactly", Long.toHexString(scr))
                    .isEqualTo(scr);
        }
    }

    // ==================================================================
    // PES + PTS —— ISO 13818-1 §2.5.3.4 的 PTS 位域
    // ==================================================================

    @Test
    @DisplayName("pes：pts=0 时 b9..b13 的 marker 位逐个等于外部真值")
    void pesZeroPts() {
        assertThat(Gb28181PsPacketizer.pes(0xE0, 0x1B, b(0x00), 0x0000000000L))
                .containsExactly(b(0x00, 0x00, 0x01, 0xE0, 0x00, 0x09, 0x80, 0x80, 0x05, 0x21, 0x00, 0x01, 0x00, 0x01, 0x00));
    }

    @Test
    @DisplayName("pes：pts=1 只落在低 7 位段（b13 = PTS[6..0]<<1 | marker）")
    void pesPtsOne() {
        // PTS[6..0]=0b0000001 ⇒ b13 = 0b0000001<<1 | 1 = 0x03
        assertThat(Gb28181PsPacketizer.pes(0xE0, 0x1B, b(0x11, 0x22, 0x33),
                        0x0000000001L))
                .containsExactly(b(0x00, 0x00, 0x01, 0xE0, 0x00, 0x0B, 0x80, 0x80, 0x05, 0x21, 0x00, 0x01, 0x00, 0x03, 0x11, 0x22, 0x33));
    }

    @Test
    @DisplayName("pes：pts=0xABCDEF 五个 PTS 段各不相等（错一位就对不上）")
    void pesPtsAbcdef() {
        assertThat(Gb28181PsPacketizer.pes(0xE0, 0x1B, b(0xAB, 0xCD),
                        0x00ABCDEFL))
                .containsExactly(b(0x00, 0x00, 0x01, 0xE0, 0x00, 0x0A, 0x80, 0x80, 0x05, 0x21, 0x02, 0xAF, 0x9B, 0xDF, 0xAB, 0xCD));
    }

    @Test
    @DisplayName("pes：pts=0x1FFFFFFFF 检验 b9 高 3 位与三个满字段")
    void pesPtsAllOnes() {
        // PTS[32..30]=0b111 ⇒ b9 = 0x21 | 0b111<<1 = 0x2F
        assertThat(Gb28181PsPacketizer.pes(0xE0, 0x1B, b(0x00),
                        0x1FFFFFFFFL))
                .containsExactly(b(0x00, 0x00, 0x01, 0xE0, 0x00, 0x09, 0x80, 0x80, 0x05, 0x2F, 0xFF, 0xFF, 0xFF, 0xFF, 0x00));
    }

    @Test
    @DisplayName("pes：音频流 streamId=0xC0 与不同 pts 组合（stream_id 不吃 streamType）")
    void pesAudioStreamId() {
        assertThat(Gb28181PsPacketizer.pes(0xC0, 0x90, b(0x55, 0x66),
                        0x0000012345L))
                .containsExactly(b(0x00, 0x00, 0x01, 0xC0, 0x00, 0x0A, 0x80, 0x80, 0x05, 0x21, 0x00, 0x05, 0x46, 0x8B, 0x55, 0x66));
    }

    @Test
    @DisplayName("PTS 读回：独立真值下 4 个 pts 逐位还原（demux 的 readPts 逆运算）")
    void ptsReadBack() {
        for (long pts : new long[]{0x0000000000L, 0x0000000001L, 0x00ABCDEFL,
                0x1FFFFFFFFL}) {
            Gb28181PsDemuxer.PsPack p = Gb28181PsDemuxer.demux(psPack(videoPes(pts)));
            assertThat(p.units().get(0).pts())
                    .as("pts=%s must round-trip bit-exactly", Long.toHexString(pts))
                    .isEqualTo(pts);
        }
    }

    @Test
    @DisplayName("PTS_only 标志未置位 ⇒ 读回 0（不抛：音频/扩展帧可能不带 PTS）")
    void ptsAbsentReturnsZero() {
        byte[] raw = b(0x00, 0x00, 0x01, 0xE0, 0x00, 0x09, 0x80, 0x00, 0x05,
                0x21, 0x00, 0x01, 0x00, 0x0B, 0x01);
        Gb28181PsDemuxer.PsPack p = Gb28181PsDemuxer.demux(psPack(raw));
        assertThat(p.units().get(0).pts()).isZero();
    }

    @Test
    @DisplayName("PES 长度越界 ⇒ 响亮失败（读半包会让后续整条流错位）")
    void pesOutOfBoundsRejected() {
        // pesLen 声明白送：声称 0x01FF 但数组只到 14
        byte[] raw = b(0x00, 0x00, 0x01, 0xE0, 0x00, 0x09, 0x80, 0x80, 0x05,
                0x21, 0x00, 0x01, 0x00, 0x01);
        assertThatThrownBy(() -> Gb28181PsDemuxer.demux(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of bounds");
    }

    // ==================================================================
    // systemHeader —— GB 附录 C.1「关键帧必带」
    // ==================================================================

    @Test
    @DisplayName("systemHeader([E0]) 逐字等于外部真值（audio_bound=0, video_bound=1）")
    void systemHeaderVideoOnly() {
        var s = new Gb28181PsPacketizer.ElementaryStream(0xE0, 0x1B, b(1));
        assertThat(Gb28181PsPacketizer.systemHeader(List.of(s)))
                .containsExactly(b(0x00, 0x00, 0x01, 0xBB, 0x00, 0x06, 0x00, 0x41, 0x80, 0xE0, 0x40, 0x01));
    }

    @Test
    @DisplayName("systemHeader([E0,C0,E0]) 去重后 audio_bound=1/video_bound=1（视频 0xE0 不再被算成音频）")
    void systemHeaderVideoAudioDedup() {
        var v = new Gb28181PsPacketizer.ElementaryStream(0xE0, 0x1B, b(1));
        var a = new Gb28181PsPacketizer.ElementaryStream(0xC0, 0x90, b(1));
        var v2 = new Gb28181PsPacketizer.ElementaryStream(0xE0, 0x1B, b(1));

        byte[] h = Gb28181PsPacketizer.systemHeader(List.of(v, a, v2));

        // 去重后 [E0,C0] ⇒ body = 6 + 3 + 3*2 = 15（len=0x09）
        assertThat(h).containsExactly(b(0x00, 0x00, 0x01, 0xBB, 0x00, 0x09, 0x01, 0x41, 0x80, 0xE0, 0x40, 0x01, 0xC0, 0x40, 0x01));
        assertThat(h).hasSize(15);
        // audio_bound 落在 byte6、video_bound 落在 byte7 低 6 位
        // ISO 13818-1：音频 = 0xC0..0xDF，视频 = 0xE0..0xEF
        assertThat(h[6] & 0xFF).isEqualTo(1);
        assertThat(h[7] & 0x3F).isEqualTo(1);
    }

    @Test
    @DisplayName("demux 认得系统头：hasSystemHeader=true 且不把它当成 PES")
    void demuxRecognisesSystemHeader() {
        var v = new Gb28181PsPacketizer.ElementaryStream(0xE0, 0x1B, b(1));
        byte[] ps = Gb28181PsPacketizer.pack(0x00ABCDEFL, true, List.of(v));
        Gb28181PsDemuxer.PsPack p = Gb28181PsDemuxer.demux(ps);

        assertThat(p.hasSystemHeader()).isTrue();
        assertThat(p.hasProgramStreamMap()).isTrue();
        assertThat(p.units()).hasSize(1);
        assertThat(p.units().get(0).streamId()).isEqualTo(0xE0);
        assertThat(p.units().get(0).streamType()).isEqualTo(0x1B);
    }

    // ==================================================================
    // programStreamMap —— 逐个基本流的 stream_type
    // ==================================================================

    @Test
    @DisplayName("PSM({E0:0x1B}) 逐字等于外部真值（es_map_length=4, psm_length=12）")
    void psmVideoOnly() {
        var v = new Gb28181PsPacketizer.ElementaryStream(0xE0, 0x1B, b(1));
        assertThat(Gb28181PsPacketizer.programStreamMap(List.of(v)))
                .containsExactly(b(0x00, 0x00, 0x01, 0xBC, 0x00, 0x0C, 0x81, 0x00, 0x00, 0x04, 0x1B, 0xE0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00));
    }

    @Test
    @DisplayName("PSM({E0:0x1B, C0:0x90}) 两条流按顺序给出 type/ Eid（音频 G.711A）")
    void psmVideoAudio() {
        var v = new Gb28181PsPacketizer.ElementaryStream(0xE0, 0x1B, b(1));
        var a = new Gb28181PsPacketizer.ElementaryStream(0xC0, 0x90, b(1));
        assertThat(Gb28181PsPacketizer.programStreamMap(List.of(v, a)))
                .containsExactly(b(0x00, 0x00, 0x01, 0xBC, 0x00, 0x10, 0x81, 0x00, 0x00, 0x08, 0x1B, 0xE0, 0x00, 0x00, 0x90, 0xC0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00));
    }

    @Test
    @DisplayName("demux 按 PSM 回填 stream_type；非关键帧（无 PSM）时留 0 而不是抛")
    void demuxBackfillsStreamTypeFromPsm() {
        var v = new Gb28181PsPacketizer.ElementaryStream(0xE0, 0x1B, b(1));
        byte[] withPsm = Gb28181PsPacketizer.pack(0x100L, true, List.of(v));
        byte[] withoutPsm = Gb28181PsPacketizer.pack(0x100L, false, List.of(v));

        assertThat(Gb28181PsDemuxer.demux(withPsm).units().get(0).streamType())
                .isEqualTo(0x1B);
        assertThat(Gb28181PsDemuxer.demux(withoutPsm).hasProgramStreamMap()).isFalse();
        assertThat(Gb28181PsDemuxer.demux(withoutPsm).units().get(0).streamType())
                .isZero();
    }

    // ==================================================================
    // RTP （RFC 3550 §5.1）
    // ==================================================================

    @Test
    @DisplayName("RTP encode：marker+PT+seq+ts+ssrc 逐字节等于外部真值")
    void rtpEncodeKnownBytes() {
        byte[] e = Gb28181RtpPacket.of(true, 96, 0x1234, 0x89ABCDEFL, 0x01020304L,
                b(1, 2, 3)).encode();
        assertThat(e).containsExactly(b(0x80, 0xE0, 0x12, 0x34, 0x89, 0xAB, 0xCD, 0xEF, 0x01, 0x02, 0x03, 0x04, 0x01, 0x02, 0x03));
    }

    @Test
    @DisplayName("RTP encode：M=0 时 PT=127 不溢出到 V 位（0x7F 不是 0xFF）")
    void rtpEncodeMarkerBitDoesNotLeakIntoVersion() {
        byte[] e = Gb28181RtpPacket.of(false, 127, 0xFFFF, 0L, 0xFFFFFFFFL,
                new byte[0]).encode();
        assertThat(e).containsExactly(b(0x80, 0x7F, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF));
    }

    @Test
    @DisplayName("RTP decode：seq/ts/ssrc 三个高位全不同的包逐字段还原")
    void rtpDecodeRoundTrip() {
        byte[] e = Gb28181RtpPacket.of(true, 96, 0x1234, 0x89ABCDEFL, 0x01020304L,
                b(1, 2, 3)).encode();
        byte[] back = new byte[e.length + 3];
        System.arraycopy(e, 0, back, 3, e.length);   // 偏移 3 起解
        Gb28181RtpPacket p = Gb28181RtpPacket.decode(back, 3, e.length);

        assertThat(p.payloadType()).isEqualTo(96);
        assertThat(p.sequenceNumber()).isEqualTo(0x1234);
        assertThat(p.timestamp()).isEqualTo(0x89ABCDEFL);
        assertThat(p.ssrc()).isEqualTo(0x01020304L);
        assertThat(p.marker()).isTrue();
        assertThat(p.payload()).containsExactly(b(1, 2, 3));
        assertThat(p.length()).isEqualTo(15);
    }

    @Test
    @DisplayName("RTP decode：CC=2 时头部是 12+8=20 字节（CSRC 参与长度计算）")
    void rtpDecodeCountsCsrcInHeaderLength() {
        // V=2, CC=2, M=0, PT=96 | ts | ssrc | CSRC[0..1]=8B | payload
        byte[] raw = b(0x82, 0x60, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x0A,
                0x11, 0x22, 0x33, 0x44,
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                0xAA, 0xBB);
        Gb28181RtpPacket p = Gb28181RtpPacket.decode(raw, 0, raw.length);

        assertThat(p.payloadType()).isEqualTo(96);
        assertThat(p.ssrc()).isEqualTo(0x11223344L);
        assertThat(p.payload()).containsExactly(b(0xAA, 0xBB));
        assertThat(p.payload()).hasSize(2);
    }

    @Test
    @DisplayName("RTP decode：version!=2 立即抛（媒体面上不返半成品）")
    void rtpDecodeRejectsBadVersion() {
        byte[] raw = new byte[12];
        raw[0] = 0x40;                                    // V=1
        assertThatThrownBy(() -> Gb28181RtpPacket.decode(raw, 0, 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    @Test
    @DisplayName("RTP decode：长度 < 12 立即抛；短于 CSRC 列表也抛")
    void rtpDecodeRejectsShortPackets() {
        byte[] eleven = new byte[11];
        assertThatThrownBy(() -> Gb28181RtpPacket.decode(eleven, 0, 11))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too short");

        byte[] claimsCsrc = b(0x81, 0x60, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThatThrownBy(() -> Gb28181RtpPacket.decode(claimsCsrc, 0, 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CSRC");
    }

    @Test
    @DisplayName("PT 边界：127 合法、128 抛（GB 附录 C.1 的 PT=96 在这区间内）")
    void rtpPayloadTypeBoundary() {
        assertThat(Gb28181RtpPacket.of(false, 127, 0, 0, 0, new byte[0]).payloadType())
                .isEqualTo(127);
        assertThatThrownBy(() -> Gb28181RtpPacket.of(false, 128, 0, 0, 0, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload type");
        assertThatThrownBy(() -> Gb28181RtpPacket.of(false, -1, 0, 0, 0, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("nextSequence：0xFFFF 回绕到 0（16 位序号，不回绕会串帧）")
    void nextSequenceWrapsAtSixteenBits() {
        assertThat(Gb28181RtpPacket.nextSequence(0xFFFE)).isEqualTo(0xFFFF);
        assertThat(Gb28181RtpPacket.nextSequence(0xFFFF)).isZero();
    }

    @Test
    @DisplayName("toString 可读且含全部判读字段（别在事故排查时只剩对象地址）")
    void rtpToStringCarriesDiagnosticFields() {
        String s = Gb28181RtpPacket.of(true, 96, 0x1234, 0x89ABCDEFL, 0x01020304L,
                b(1, 2, 3)).toString();
        assertThat(s).contains("pt=96", "seq=4660", "ts=", "ssrc=16909060", "m=true", "len=3");
    }

    // ==================================================================
    // demux 的拒绝路径
    // ==================================================================

    @Test
    @DisplayName("demux：长度不足 / 无 pack_start_code / 第二个 pack_start 各自抛")
    void demuxRejectsMalformedPacks() {
        assertThatThrownBy(() -> Gb28181PsDemuxer.demux(new byte[3]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too short");

        byte[] sysHdrOnly = b(0x00, 0x00, 0x01, 0xBB, 0x00, 0x06, 0x00, 0x00, 0x80,
                0xE0, 0x40, 0x01);
        assertThatThrownBy(() -> Gb28181PsDemuxer.demux(sysHdrOnly))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no pack_start_code");

        // 手工拼两个 pack header ⇒ 第二个 start code 必须被拒
        byte[] two = new byte[28];
        System.arraycopy(Gb28181PsPacketizer.packHeader(1L), 0, two, 0, 14);
        System.arraycopy(Gb28181PsPacketizer.packHeader(2L), 0, two, 14, 14);
        assertThatThrownBy(() -> Gb28181PsDemuxer.demux(two))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("second pack_start_code");
    }

    @Test
    @DisplayName("demux：偏移处无 start code ⇒ 报出偏移量（错位包要能定位）")
    void demuxReportsOffsetOfBadStartCode() {
        // pack header(14B) 之后本该是 00 00 01 XX，这里给错前缀 01 01 01 E0
        byte[] raw = b(0x00, 0x00, 0x01, 0xBA, 0x21, 0x00, 0x00, 0x00, 0x02,
                0x00, 0x00, 0x00, 0x00, 0x01,
                0x01, 0x01, 0x01, 0xE0);
        assertThatThrownBy(() -> Gb28181PsDemuxer.demux(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset 14");
    }

    // ==================================================================
    // MediaFrame / MediaSession 的可观测面（补齐 NO_COVERAGE 变异体）
    // ==================================================================

    @Test
    @DisplayName("MediaFrame：units()/keyframe()/toString() 三个取值面都走一遍")
    void mediaFrameAccessors() {
        var v = new Gb28181PsPacketizer.ElementaryStream(0xE0, 0x1B, b(1));
        byte[] ps = Gb28181PsPacketizer.pack(0x100L, true, List.of(v));
        Gb28181MediaFrame f = new Gb28181MediaFrame(0x11223344L, 9000L, 1, ps);

        assertThat(f.keyframe()).isTrue();
        assertThat(f.units()).hasSize(1);
        assertThat(f.ps()).isEqualTo(ps);
        assertThat(f.toString()).contains("ssrc=", "ts=9000", "packets=1", "psBytes=");
    }

    @Test
    @DisplayName("MediaFrame：非关键帧（无系统头/PSM）keyframe()=false")
    void mediaFrameNonKeyframe() {
        var v = new Gb28181PsPacketizer.ElementaryStream(0xE0, 0x1B, b(1));
        byte[] ps = Gb28181PsPacketizer.pack(0x100L, false, List.of(v));
        assertThat(new Gb28181MediaFrame(1L, 9000L, 1, ps).keyframe()).isFalse();
    }

    @Test
    @DisplayName("统计面：receivedCount/duplicateCount/outOfOrderCount/framesBuilt 初始为 0")
    void sessionCountersStartAtZero() throws IOException {
        var s = Gb28181MediaSession.open(0);
        try {
            assertThat(s.receivedCount()).isZero();
            assertThat(s.duplicateCount()).isZero();
            assertThat(s.outOfOrderCount()).isZero();
            assertThat(s.framesBuilt()).isZero();
            assertThat(s.frames()).isEmpty();
            assertThat(s.address().getPort()).isGreaterThan(0);
        } finally {
            s.close();
        }
    }

    @Test
    @DisplayName("close：句柄真的放开（socket 关闭 + 计数可再读）")
    void sessionCloseReleasesHandle() throws IOException {
        var s = Gb28181MediaSession.open(0);
        s.onFrame(f -> { });
        s.close();
        s.close();   // 幂等：重复 close 不炸
        assertThat(s.framesBuilt()).isZero();
    }

    @Test
    @DisplayName("onRtp：不同 (ssrc,ts) 的帧互不串键；同键重复包计入 duplicates")
    void onRtpKeysArePerStreamPerTimestamp() throws IOException {
        var s = Gb28181MediaSession.open(0);
        try {
            byte[] payload = Gb28181PsPacketizer.packHeader(0x100L);
            Gb28181RtpPacket a = Gb28181RtpPacket.of(true, 96, 10, 9000L, 0x1111L, payload);
            Gb28181RtpPacket b = Gb28181RtpPacket.of(true, 96, 10, 9000L, 0x2222L, payload);

            s.onRtp(a);
            s.onRtp(a);      // 同 ssrc+seq ⇒ 重复
            s.onRtp(b);      // 不同 ssrc ⇒ 新帧

            assertThat(s.receivedCount()).isEqualTo(3);
            assertThat(s.duplicateCount()).isEqualTo(1);
            assertThat(s.framesBuilt()).isEqualTo(2);
        } finally {
            s.close();
        }
    }

    @Test
    @DisplayName("onRtp：缺 pack header 的半个帧不交（demux 守卫，宁可丢帧）")
    void onRtpDoesNotDeliverHalfFrame() throws IOException {
        var s = Gb28181MediaSession.open(0);
        try {
            byte[] payload = b(0x00, 0x00, 0x01, 0xBA, 0x21, 0x00);
            s.onRtp(Gb28181RtpPacket.of(true, 96, 7, 4500L, 0x3333L, payload));
            assertThat(s.framesBuilt()).isZero();
            assertThat(s.frames()).isEmpty();
        } finally {
            s.close();
        }
    }

    @Test
    @DisplayName("onRtp：乱序的 marker 先到也不早组帧（pendingMarkerTs 守卫）")
    void onRtpWaitsForMarker() throws IOException {
        var s = Gb28181MediaSession.open(0);
        try {
            // 只有一个数据报、无 marker ⇒ 不该组帧
            s.onRtp(Gb28181RtpPacket.of(false, 96, 1, 100L, 0x4444L,
                    Gb28181PsPacketizer.packHeader(100L)));
            assertThat(s.framesBuilt()).isZero();
        } finally {
            s.close();
        }
    }

    @Test
    @DisplayName("帧观察者收到帧后才计数（E2E 断言依赖的顺序）")
    void frameObserverFiresAfterBuild() throws IOException {
        var s = Gb28181MediaSession.open(0);
        try {
            var seen = new CopyOnWriteArrayList<Gb28181MediaFrame>();
            s.onFrame(seen::add);
            s.onRtp(Gb28181RtpPacket.of(true, 96, 3, 300L, 0x5555L,
                    Gb28181PsPacketizer.packHeader(300L)));

            assertThat(s.framesBuilt()).isEqualTo(1);
            assertThat(seen).hasSize(1);
            assertThat(seen.get(0).timestamp()).isEqualTo(300L);
        } finally {
            s.close();
        }
    }

    // ==== 观察者扇出的故障隔离（见 Gb28181MediaSession#tryBuildFrame 的就地吞异常）====
    @Test
    @DisplayName("观察者抛异常不再冒泡出 onRtp（否则会被误记成坏包而误导排查）")
    void throwingObserverDoesNotKillSession() throws IOException {
        var s = Gb28181MediaSession.open(0);
        try {
            s.onFrame(f -> {
                throw new IllegalStateException("observer boom");
            });
            // 帧已组成（framesBuilt 先加），随后扇出给观察者
            assertThat(s.framesBuilt()).isZero();

            s.onRtp(Gb28181RtpPacket.of(true, 96, 4, 400L, 0x6666L,
                    Gb28181PsPacketizer.packHeader(400L)));

            assertThat(s.framesBuilt()).isEqualTo(1);
            assertThat(s.frames()).hasSize(1);
        } finally {
            s.close();
        }
    }

    @Test
    @DisplayName("观察者抛异常后会话仍能继续收流（第二个观察者照常收到）")
    void sessionKeepsReceivingAfterObserverFailure() throws IOException {
        var s = Gb28181MediaSession.open(0);
        try {
            var ok = new AtomicInteger();
            s.onFrame(f -> {
                throw new IllegalStateException("first observer boom");
            });
            s.onFrame(f -> ok.incrementAndGet());

            s.onRtp(Gb28181RtpPacket.of(true, 96, 5, 500L, 0x7777L,
                    Gb28181PsPacketizer.packHeader(500L)));
            s.onRtp(Gb28181RtpPacket.of(true, 96, 6, 600L, 0x7777L,
                    Gb28181PsPacketizer.packHeader(600L)));

            assertThat(s.framesBuilt()).isEqualTo(2);
            assertThat(ok.get()).isEqualTo(2);
        } finally {
            s.close();
        }
    }

    @Test
    @DisplayName("packHeader 长度恒 14、PES 头恒 14（长度算术被钉住）")
    void codecFixedHeaderLengths() {
        assertThat(Gb28181PsPacketizer.packHeader(0L)).hasSize(14);
        assertThat(Gb28181PsPacketizer.pes(0xE0, 0x1B, new byte[0], 0L)).hasSize(14);
        assertThat(Gb28181PsPacketizer.pes(0xE0, 0x1B, new byte[9], 0L)).hasSize(23);
        assertThat(Gb28181PsPacketizer.pack(0L, false,
                List.of(new Gb28181PsPacketizer.ElementaryStream(0xE0, 0x1B,
                        b(1, 2, 3))))).hasSize(14 + 14 + 3);
    }

    @Test
    @DisplayName("pack：空 streams / 空 units 立即抛（不产出空 PS 包）")
    void packRejectsEmptyInput() {
        assertThatThrownBy(() -> Gb28181PsPacketizer.pack(0L, false, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Gb28181PsPacketizer.pack(0L, false, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Gb28181PsPacketizer.pack(0L, false,
                List.of(new Gb28181PsPacketizer.ElementaryStream(0xE0, 0x1B, new byte[0]))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ElementaryStream：streamId/streamType 越界立即抛（0x100 不是 0xFF）")
    void elementaryStreamRangeCheck() {
        assertThatThrownBy(() -> new Gb28181PsPacketizer.ElementaryStream(0x100, 0x1B,
                b(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Gb28181PsPacketizer.ElementaryStream(0xE0, 0x100,
                b(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Gb28181PsPacketizer.ElementaryStream(-1, 0x1B,
                b(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================================================================
    // ⑨ 16 位长度字段的高字节（只在长度 >= 256 时可观测）
    // ==================================================================

    @Test
    @DisplayName("大 PES：负载 > 255 字节时 pesLen 高字节非零（<<8 与 >>8 在此分道）")
    void largePesHasNonZeroHighLengthByte() {
        byte[] data = new byte[300];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;                    // 内容可辨，便于断言不串位
        }
        byte[] pes = Gb28181PsPacketizer.pes(0xE0, 0x1B, data, 0x00ABCDEFL);

        // pesLen = 3 + 5 + 300 = 308 = 0x0134 ⇒ b4 = 0x01, b5 = 0x34
        assertThat(pes[4] & 0xFF).isEqualTo(0x01);
        assertThat(pes[5] & 0xFF).isEqualTo(0x34);
        assertThat(pes).hasSize(14 + 300);

        // 读回：长度与内容都还原（readPes 的高字节位运算被钉住）
        Gb28181PsDemuxer.PsPack pack = Gb28181PsDemuxer.demux(psPack(pes));
        assertThat(pack.units()).hasSize(1);
        assertThat(pack.units().get(0).data()).isEqualTo(data);
    }

    @Test
    @DisplayName("PES 正好到 buffer 末尾仍合法（payloadEnd == b.length 不是越界）")
    void pesEndingExactlyAtBufferEndIsLegal() {
        // psPack 把 PES 放在最后 ⇒ payloadEnd == ps.length
        byte[] ps = psPack(videoPes(0x00ABCDEFL));
        Gb28181PsDemuxer.PsPack pack = Gb28181PsDemuxer.demux(ps);

        assertThat(pack.units()).hasSize(1);
        assertThat(pack.units().get(0).pts()).isEqualTo(0x00ABCDEFL);
    }

    // ==================================================================
    // ⑩ ISO stream_id 区间端点（0xDF / 0xEF 是区间式判定的边界）
    // ==================================================================

    @Test
    @DisplayName("audio_bound/video_bound 只按 ISO 区间端点判定：0xDF 算音频、0xEF 算视频")
    void streamIdRangeEndpoints() {
        var maxAudio = new Gb28181PsPacketizer.ElementaryStream(0xDF, 0x90, b(1));
        var maxVideo = new Gb28181PsPacketizer.ElementaryStream(0xEF, 0x1B, b(1));
        var gapAudio = new Gb28181PsPacketizer.ElementaryStream(0xE0, 0x1B, b(1));

        byte[] h = Gb28181PsPacketizer.systemHeader(List.of(maxAudio, maxVideo, gapAudio));

        // byte6 = audio_bound = 1（只有 0xDF 落在 0xC0..0xDF）
        // byte7 低 6 位 = video_bound = 2（0xE0 与 0xEF 都落在 0xE0..0xEF）
        assertThat(h[6] & 0xFF).isEqualTo(1);
        assertThat(h[7] & 0x3F).isEqualTo(2);
        assertThat(h).hasSize(9 + 3 * 3);
    }

    @Test
    @DisplayName("PSM 的 CRC_32 恒为 0——由 byte[] 零初始化保证，不是空转循环的功劳")
    void psmCrcIsZeroByConstruction() {
        var v = new Gb28181PsPacketizer.ElementaryStream(0xE0, 0x1B, b(1));
        var a = new Gb28181PsPacketizer.ElementaryStream(0xC0, 0x90, b(1));

        byte[] psm = Gb28181PsPacketizer.programStreamMap(List.of(v, a));

        // 末 4 字节 = CRC_32，必须全 0（国标实时点播不解码它）
        assertThat(psm[psm.length - 1]).isZero();
        assertThat(psm[psm.length - 2]).isZero();
        assertThat(psm[psm.length - 3]).isZero();
        assertThat(psm[psm.length - 4]).isZero();
    }

    // ==================================================================
    // ⑪ frameKey / nextSeq 的键与回绕语义（私有方法经 onRtp 行为观测）
    // ==================================================================

    @Test
    @DisplayName("frameKey 碰撞测试：低 16 位时间戳撞上 ssrc 高 16 位时两帧不得被并成一帧")
    void frameKeyCollisionMustNotMergeFrames() throws IOException {
        var s = Gb28181MediaSession.open(0);
        try {
            // 正确语义：frameKey(A) = 0x1111_2328，frameKey(B) = 0x0000_2328 ⇒ 两个键
            // 若把 (ssrc<<16) 写成 (ssrc>>16)，两者都塌成 0x2328 ⇒ 后到的 marker 覆盖
            // 前者的待组标记，最终只交 1 帧而不是 2 帧。
            long tsB = 0x2328L;                  // = 9000 & 0xFFFF，正是 A 的低 16 位
            s.onRtp(Gb28181RtpPacket.of(true, 96, 1, 9000L, 0x1111L,
                    Gb28181PsPacketizer.packHeader(9000L)));
            s.onRtp(Gb28181RtpPacket.of(true, 96, 1, tsB, 0x0000L,
                    Gb28181PsPacketizer.packHeader(tsB)));
            s.onRtp(Gb28181RtpPacket.of(false, 96, 2, 9000L, 0x1111L,
                    Gb28181PsPacketizer.packHeader(9000L)));
            s.onRtp(Gb28181RtpPacket.of(false, 96, 2, tsB, 0x0000L,
                    Gb28181PsPacketizer.packHeader(tsB)));

            assertThat(s.framesBuilt()).isEqualTo(2);
        } finally {
            s.close();
        }
    }

    @Test
    @DisplayName("序号回绕：0xFFFF → 0x0000 仍是连续（不计乱序、帧不丢）")
    void sequenceWrapAroundStaysContiguous() throws IOException {
        var s = Gb28181MediaSession.open(0);
        try {
            // 一个 PS 包被切成 3 片；序号在 0xFFFF → 0x0000 处回绕
            byte[] onePack = psPack(videoPes(700L));
            int cut1 = onePack.length / 3;
            int cut2 = onePack.length * 2 / 3;
            s.onRtp(Gb28181RtpPacket.of(false, 96, 0xFFFE, 700L, 0xAAAA_BBBBL,
                    Arrays.copyOfRange(onePack, 0, cut1)));
            s.onRtp(Gb28181RtpPacket.of(false, 96, 0xFFFF, 700L, 0xAAAA_BBBBL,
                    Arrays.copyOfRange(onePack, cut1, cut2)));
            s.onRtp(Gb28181RtpPacket.of(true, 96, 0x0000, 700L, 0xAAAA_BBBBL,
                    Arrays.copyOfRange(onePack, cut2, onePack.length)));

            assertThat(s.outOfOrderCount()).isZero();
            assertThat(s.framesBuilt()).isEqualTo(1);
            assertThat(s.frames().get(0).packets()).isEqualTo(3);
            assertThat(s.frames().get(0).ps()).isEqualTo(onePack);
        } finally {
            s.close();
        }
    }

    @Test
    @DisplayName("序号跳号 ⇒ 计入乱序计数（last < 0 的首包不算乱序）")
    void sequenceGapCountsAsOutOfOrder() throws IOException {
        var s = Gb28181MediaSession.open(0);
        try {
            s.onRtp(Gb28181RtpPacket.of(false, 96, 10, 800L, 0xCCCCL,
                    Gb28181PsPacketizer.packHeader(800L)));
            assertThat(s.outOfOrderCount())
                    .as("首包 last < 0，不算乱序")
                    .isZero();

            s.onRtp(Gb28181RtpPacket.of(true, 96, 20, 800L, 0xCCCCL,
                    Gb28181PsPacketizer.packHeader(800L)));
            assertThat(s.outOfOrderCount()).isEqualTo(1);
        } finally {
            s.close();
        }
    }

    // ==================================================================
    // ⑫ close() 的收尾语义（uv：socket 真关、loop 真停）
    // ==================================================================

    @Test
    @DisplayName("close 后端口可被再次绑定（socket 真的关了，不是只置了个标志）")
    void closeReleasesThePort() throws Exception {
        try (var first = Gb28181MediaSession.open(0)) {
            int port = first.address().getPort();
            first.close();

            // 端口只可能因 socket 已 close 而可再绑
            try (var second = new java.net.DatagramSocket(port)) {
                assertThat(second.getLocalPort()).isEqualTo(port);
            }
        }
    }

    @Test
    @DisplayName("close 后 running=false、loop 已关（反射读取私有态）")
    void closeStopsTheLoopFlag() throws Exception {
        var s = Gb28181MediaSession.open(0);
        s.start();                     // 让 loop 真正被提交过一次
        s.close();
        s.close();

        java.lang.reflect.Field f = Gb28181MediaSession.class.getDeclaredField("running");
        f.setAccessible(true);
        assertThat(((java.util.concurrent.atomic.AtomicBoolean) f.get(s)).get()).isFalse();
        assertThat(s.framesBuilt()).isZero();
    }

    @Test
    @DisplayName("收流线程是 daemon：宿主进程退出时不会卡在 media 线程上")
    void receiveThreadIsDaemon() throws Exception {
        var s = Gb28181MediaSession.open(0);
        try {
            s.start();
            // 让它真跑起来：收流循环收到一个包即可确认线程已存在
            s.onRtp(Gb28181RtpPacket.of(true, 96, 1, 1_000L, 0xDDDDL,
                    Gb28181PsPacketizer.packHeader(1_000L)));

            Thread t = Thread.getAllStackTraces().keySet().stream()
                    .filter(x -> x.getName().startsWith("gb28181-media-"))
                    .findFirst()
                    .orElse(null);
            if (t != null) {                  // 线程尚未起来时不强断（交给 CI 平台）
                assertThat(t.isDaemon())
                        .as("非 daemon 的收流线程会让宿主进程无法退出")
                        .isTrue();
            }
            assertThat(s.framesBuilt()).isEqualTo(1);
        } finally {
            s.close();
        }
    }

    // ==================================================================
    // ⑬ 已组帧的包会从待组缓冲清理（bySeq 不留垃圾，防内存爬升）
    // ==================================================================

    @Test
    @DisplayName("组帧成功后待组缓冲被清空（长流下不会无限堆积）")
    void bySeqIsClearedAfterFrameBuilt() throws Exception {
        var s = Gb28181MediaSession.open(0);
        try {
            for (int i = 0; i < 5; i++) {
                s.onRtp(Gb28181RtpPacket.of(true, 96, i, (long) i * 1000, 0xEEEE_0001L,
                        Gb28181PsPacketizer.packHeader((long) i * 1000)));
            }
            assertThat(s.framesBuilt()).isEqualTo(5);

            java.lang.reflect.Field f = Gb28181MediaSession.class.getDeclaredField("bySeq");
            f.setAccessible(true);
            assertThat(((java.util.Map<?, ?>) f.get(s))).isEmpty();
        } finally {
            s.close();
        }
    }

    // ==================================================================
    // ⑭ 第二轮变异收口：16 位长度字段高字节 / 边界算子 / frameKey 碰撞
    // ==================================================================

    @Test
    @DisplayName("长度不足判定是 < 4 而不是 <= 4（4 字节输入应被 start code 拒绝）")
    void demuxTooShortBoundaryIsStrictlyLessThan() {
        // 4 字节全 0：不是 start code ⇒ 应报 start code 错，而不是"太短"
        byte[] four = b(0x00, 0x00, 0x00, 0x00);
        assertThatThrownBy(() -> Gb28181PsDemuxer.demux(four))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected start code at offset 0")
                .hasMessageNotContaining("too short");
    }

    @Test
    @DisplayName("PT=0 合法（边界算子是 < 0，不是 <= 0）")
    void rtpPayloadTypeZeroIsLegal() {
        assertThat(Gb28181RtpPacket.of(false, 0, 0, 0, 0, b(1)).payloadType()).isZero();
        assertThat(Gb28181RtpPacket.of(true, 1, 0, 0, 0, b(1)).payloadType()).isEqualTo(1);
    }

    @Test
    @DisplayName("PES 负载为 0 字节且正好到 buffer 末尾仍合法（不是越界）")
    void zeroLengthPesAtBufferEndIsLegal() {
        // 手工 PES：pesLen = 8（3 + 5 + 0），header_data_length = 5 ⇒ 负载 0 字节
        byte[] pes = b(0x00, 0x00, 0x01, 0xE0, 0x00, 0x08, 0x80, 0x80, 0x05,
                0x21, 0x00, 0x01, 0x00, 0x01);
        Gb28181PsDemuxer.PsPack pack = Gb28181PsDemuxer.demux(psPack(pes));

        assertThat(pack.units()).hasSize(1);
        assertThat(pack.units().get(0).data()).isEmpty();
    }

    @Test
    @DisplayName("85 条基本流：system header 与 PSM 的长度高字节才真被用上（<<8 与 >>8 在此分道）")
    void manyStreamsExerciseLengthHighByte() {
        List<Gb28181PsPacketizer.ElementaryStream> streams = new ArrayList<>();
        // 视频 0xE0 + 音频 0xC0..0xC2 + 一堆其它 id，总数 85（让 syshdr/psm 长度跨过 256）
        streams.add(new Gb28181PsPacketizer.ElementaryStream(0xE0, 0x1B, b(1)));
        for (int i = 0; i < 84; i++) {
            streams.add(new Gb28181PsPacketizer.ElementaryStream(i, 0x1B, b(1)));
        }

        byte[] syshdr = Gb28181PsPacketizer.systemHeader(streams);
        byte[] psm = Gb28181PsPacketizer.programStreamMap(streams);

        // system_header_length = body - 6 = (9 + 3*85) - 6 = 258 = 0x0102
        assertThat(syshdr[4] & 0xFF).isEqualTo(0x01);          // 高字节非零
        assertThat(syshdr[5] & 0xFF).isEqualTo(0x02);
        // es_map_length = 4 * 85 = 340 = 0x0154
        assertThat(psm[8] & 0xFF).isEqualTo(0x01);
        assertThat(psm[9] & 0xFF).isEqualTo(0x54);

        // 往返：解复用必须认得 85 条流（读到高字节才算数）
        byte[] ps = Gb28181PsPacketizer.pack(0x100L, true, streams);
        Gb28181PsDemuxer.PsPack pack = Gb28181PsDemuxer.demux(ps);
        assertThat(pack.streamTypes()).hasSize(85);
        assertThat(pack.hasSystemHeader()).isTrue();
        assertThat(pack.hasProgramStreamMap()).isTrue();
        assertThat(pack.units()).hasSize(85);
    }

    @Test
    @DisplayName("系统头长度字段若声称 >=256 但实体很短 ⇒ 解复用必须按字段跳过（不猜实体）")
    void systemHeaderLengthHighByteIsTrusted() {
        // 声称 system_header_length = 0x0100（256），实体只有 12 字节 ⇒ 按 256 跳过
        byte[] raw = psPack(b(
                0x00, 0x00, 0x01, 0xBB, 0x01, 0x00,      // len = 0x0100
                0x00, 0x40, 0x80, 0xE0, 0x40, 0x01),     // 实体内容 6 字节
                videoPes(0x00ABCDEFL));

        // 按 256 跳过 ⇒ 直接越过数组尾 ⇒ 只剩 pack header，正常返回
        Gb28181PsDemuxer.PsPack pack = Gb28181PsDemuxer.demux(raw);
        assertThat(pack.hasSystemHeader()).isTrue();
        assertThat(pack.units()).isEmpty();

        // 对照：把高字节抹掉（0x00）⇒ 只跳过 6+0，随即撞见非 start code 的实体字节
        byte[] lying = psPack(b(
                0x00, 0x00, 0x01, 0xBB, 0x00, 0x00,      // len = 0x0000
                0x00, 0x40, 0x80, 0xE0, 0x40, 0x01),
                videoPes(0x00ABCDEFL));
        assertThatThrownBy(() -> Gb28181PsDemuxer.demux(lying))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset 20");
    }

    @Test
    @DisplayName("PSM 的 es_map_length 高字节非零 ⇒ 逐条读出 64 个 stream_type（不是 0 个）")
    void psmEsMapLengthHighByteIsRead() {
        // 手工 PSM：es_map_length = 0x0100（256）⇒ 64 个 4 字节条目
        var raw = new java.io.ByteArrayOutputStream();
        raw.write(b(0x00, 0x00, 0x01, 0xBC, 0x01, 0x0C, 0x81, 0x00, 0x01, 0x00), 0, 10);
        for (int i = 0; i < 64; i++) {
            raw.write(b(0x1B, i, 0x00, 0x00), 0, 4);
        }
        raw.write(b(0x00, 0x00, 0x00, 0x00), 0, 4);        // CRC_32 = 0
        byte[] psm = raw.toByteArray();

        Gb28181PsDemuxer.PsPack pack = Gb28181PsDemuxer.demux(psPack(psm));

        assertThat(pack.hasProgramStreamMap()).isTrue();
        assertThat(pack.streamTypes()).hasSize(64);
        // 逐条 type 与 id 都对得上（错一位就张冠李戴）
        for (int i = 0; i < 64; i++) {
            assertThat(pack.streamTypes().get(i))
                    .as("stream id %d must map to H.264 (0x1B)", i)
                    .isEqualTo(0x1B);
        }
    }

    @Test
    @DisplayName("frameKey 碰撞：两个 marker 先到、各自数据包后到 ⇒ 必须交出 2 帧")
    void frameKeyCollisionWithBothMarkersFirst() throws IOException {
        var s = Gb28181MediaSession.open(0);
        try {
            long tsB = 0x2328L;      // = 9000 & 0xFFFF，正好是 A 的低 16 位
            long ssrcA = 0x1111L;
            long ssrcB = 0x0000L;

            // 每帧 3 片，marker 片只给前 5 字节（连 pack header 都不完整，
            // 自己一定解不动）。这一点是关键：若 marker 片自带完整 PS，帧会在
            // marker 到达瞬间被交出、pending 键当即删除——碰撞就永远显不出来了。
            byte[] packA = psPack(videoPes(9000L));
            byte[] packB = psPack(videoPes(tsB));
            int cA1 = 5, cA2 = 17;
            int cB1 = 5, cB2 = 17;

            // ① 两个 marker 先到（**按到达序，不按序号**）。marker 持该帧最大序号——
            //    RFC 3551 语义是"marker = 该帧最后一个包"，锚点排序据此还原。
            s.onRtp(Gb28181RtpPacket.of(true, 96, 3, 9000L, ssrcA,
                    Arrays.copyOfRange(packA, cA2, packA.length)));
            s.onRtp(Gb28181RtpPacket.of(true, 96, 3, tsB, ssrcB,
                    Arrays.copyOfRange(packB, cB2, packB.length)));
            // ② 各自的数据片后到（三片之后才凑齐一个完整 PS）
            s.onRtp(Gb28181RtpPacket.of(false, 96, 1, 9000L, ssrcA,
                    Arrays.copyOfRange(packA, 0, cA1)));
            s.onRtp(Gb28181RtpPacket.of(false, 96, 2, 9000L, ssrcA,
                    Arrays.copyOfRange(packA, cA1, cA2)));
            s.onRtp(Gb28181RtpPacket.of(false, 96, 1, tsB, ssrcB,
                    Arrays.copyOfRange(packB, 0, cB1)));
            s.onRtp(Gb28181RtpPacket.of(false, 96, 2, tsB, ssrcB,
                    Arrays.copyOfRange(packB, cB1, cB2)));

            // 若把 (ssrc<<16) 写成 (ssrc>>16)，两个键都塌成 0x2328 ⇒
            // 第二个 marker 覆盖第一个的待组标记 ⇒ 第一帧交出去时把键删掉 ⇒
            // 第二帧的键已不在 pending ⇒ 只交 1 帧。
            // 把 ^ 写成 & 同样让两个键都变成 0。
            assertThat(s.framesBuilt()).isEqualTo(2);
            assertThat(s.frames()).hasSize(2);
        } finally {
            s.close();
        }
    }

    @Test
    @DisplayName("首包序号为 0 时紧随跳号仍算乱序（边界是 last >= 0，不是 last > 0）")
    void outOfOrderBoundaryCountsLastZero() throws IOException {
        var s = Gb28181MediaSession.open(0);
        try {
            s.onRtp(Gb28181RtpPacket.of(false, 96, 0, 900L, 0x1234L,
                    Gb28181PsPacketizer.packHeader(900L)));
            assertThat(s.outOfOrderCount()).isZero();

            s.onRtp(Gb28181RtpPacket.of(true, 96, 5, 900L, 0x1234L,
                    Gb28181PsPacketizer.packHeader(900L)));
            // last = 0 也应参与比较（用 > 0 会漏掉这一次跳号）
            assertThat(s.outOfOrderCount()).isEqualTo(1);
        } finally {
            s.close();
        }
    }

    @Test
    @DisplayName("回绕排序比较器：6 片跨 0xFFFF → 0x0000 也必须逐字节还原（比较器一致性）")
    void wrapSafeSortHoldsForSixFragments() throws IOException {
        var s = Gb28181MediaSession.open(0);
        try {
            byte[] onePack = psPack(videoPes(0x00ABCDEFL));
            int n = 12;
            int base = onePack.length / n;
            // 12 片跨 0xFFFF → 0x0000：比较器一旦不自洽，排序结果必然错
            int[] seqs = new int[n];
            for (int i = 0; i < n; i++) {
                seqs[i] = (0x10000 - (n - 1) + i) & 0xFFFF;
            }
            assertThat(seqs[0]).isEqualTo(0xFFF5);
            assertThat(seqs[n - 1]).isZero();
            for (int i = 0; i < n; i++) {
                int from = i * base;
                int to = (i == n - 1) ? onePack.length : (i + 1) * base;
                s.onRtp(Gb28181RtpPacket.of(i == n - 1, 96, seqs[i], 0x7777L, 0x7777L,
                        Arrays.copyOfRange(onePack, from, to)));
            }
            assertThat(s.framesBuilt()).isEqualTo(1);
            assertThat(s.frames().get(0).ps()).isEqualTo(onePack);
            assertThat(s.outOfOrderCount()).isZero();
        } finally {
            s.close();
        }
    }

    @Test
    @DisplayName("锚点不回绕时 12 片也必须按序号升序还原（比较器两翼必须对称）")
    void sortOrdersTwelveFragmentsWithoutWrap() throws IOException {
        var s = Gb28181MediaSession.open(0);
        try {
            // 12 片、序号 0xFFF0..0xFFFB，marker 在 0xFFFB（锚点最大、不回绕）
            byte[] onePack = psPack(videoPes(0x00ABCDEFL));
            int n = 12;
            int base = onePack.length / n;
            int start = 0xFFF0;
            for (int i = 0; i < n; i++) {
                int from = i * base;
                int to = (i == n - 1) ? onePack.length : (i + 1) * base;
                s.onRtp(Gb28181RtpPacket.of(i == n - 1, 96, start + i, 0x8888L, 0x8888L,
                        Arrays.copyOfRange(onePack, from, to)));
            }
            assertThat(s.framesBuilt()).isEqualTo(1);
            assertThat(s.frames().get(0).ps()).isEqualTo(onePack);
            // 若比较器两翼不对称（一翼把减号写成加号），这里会整体逆序 ⇒ 解不动 ⇒ 0 帧
            assertThat(s.frames().get(0).packets()).isEqualTo(n);
        } finally {
            s.close();
        }
    }

    @Test
    @DisplayName("收流线程一定是 daemon（宿主退出不卡在 media 线程上）")
    void receiveThreadIsAlwaysDaemon() throws Exception {
        var s = Gb28181MediaSession.open(0);
        try {
            s.start();
            Thread t = Thread.getAllStackTraces().keySet().stream()
                    .filter(x -> x.getName().startsWith("gb28181-media-"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("media receive thread not found"));
            assertThat(t.isDaemon()).isTrue();
        } finally {
            s.close();
        }
    }

    @Test
    @DisplayName("国标常量：PT=96 / clock=90000 / 固定头 12（附录 C.1）")
    void gbConstants() {
        assertThat(Gb28181RtpPacket.PAYLOAD_TYPE_PS).isEqualTo(96);
        assertThat(Gb28181RtpPacket.CLOCK_RATE_PS).isEqualTo(90_000);
        assertThat(Gb28181RtpPacket.FIXED_HEADER_BYTES).isEqualTo(12);
        assertThat(Gb28181PsPacketizer.STREAM_ID_VIDEO).isEqualTo(0xE0);
        assertThat(Gb28181PsPacketizer.STREAM_ID_AUDIO).isEqualTo(0xC0);
        assertThat(Gb28181PsPacketizer.STREAM_TYPE_H264).isEqualTo(0x1B);
        assertThat(Gb28181PsPacketizer.STREAM_TYPE_H265).isEqualTo(0x24);
        assertThat(Gb28181PsPacketizer.STREAM_TYPE_G711A).isEqualTo(0x90);
        assertThat(Arrays.equals(b(0, 0, 1),
                new byte[]{0, 0, (byte) Gb28181PsPacketizer.PES_START_PREFIX & 0xFF})).isTrue();
    }

    @Test
    @DisplayName("AtomicInteger 计数器面：incrementAndGet 与 set 语义（回归防呆）")
    void atomicCounterSanity() {
        var c = new AtomicInteger(5);
        assertThat(c.incrementAndGet()).isEqualTo(6);
        c.set(-1);
        assertThat(c.get()).isEqualTo(-1);
    }
}

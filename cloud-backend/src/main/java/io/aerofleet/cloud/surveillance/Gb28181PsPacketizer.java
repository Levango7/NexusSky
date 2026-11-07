package io.aerofleet.cloud.surveillance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MPEG-2 Program Stream 打包器（GB/T 28181-2022 附录 C.1 规范性要求）。
 *
 * <p>国标对 PS 封装的**硬要求**（附录 C.1 原文）：
 * <blockquote>
 * 进行 PS 封装时，应将<b>每个视频帧封装为一个 PS 包</b>，且每个<b>关键帧的 PS 包中应包含
 * 系统头（System Header）和 PSM（Program Stream Map）</b>，系统头和 PSM 放置于 PS 包头之后、
 * 第一个 PES 包之前。视频非关键帧的 PS 包结构中一般不包含系统头和 PSM。
 * </blockquote>
 *
 * <p>本类按此实现：
 * <ol>
 *   <li>{@link #packHeader} —— PS 包起始码 {@code 00 00 01 BA} + SCR（系统时钟参考）
 *       + 复用速率。SCR 用于音视频同步（GB §4.3.6：由 RTP 时间戳提供同步信息，
 *       SCR 则按 ISO/IEC 13818-1 填写）；</li>
 *   <li>{@link #systemHeader} —— 仅关键帧带；描述 PS 包内码流种类（scope 里的
 *       {@code stream_id}：视频 0xE0 / 音频 0xC0）；</li>
 *   <li>{@link #programStreamMap} —— 仅关键帧带；逐个基本流给出
 *       {@code stream_type}（H.264=0x1B / H.265=0x24 / MPEG-4=0x10 / G.711A=0x90 …）；</li>
 *   <li>{@link #pes} —— PES 包头 {@code 00 00 01} + {@code stream_id} + 长度 + PTS/DTS。</li>
 * </ol>
 *
 * <p>数据结构依据 ISO/IEC 13818-1:2019（国标规范性引用），字段取值依据 GB/T 28181-2022 附录 C.1。
 */
public final class Gb28181PsPacketizer {

    /** PS 包起始码（ISO 13818-1 §2.5.3.1）。 */
    public static final int PACK_START_CODE = 0x000001BA;
    /** 系统头起始码。 */
    public static final int SYSTEM_HEADER_START_CODE = 0x000001BB;
    /** Program Stream Map 起始码。 */
    public static final int PSM_START_CODE = 0x000001BC;
    /** PES 起始码前缀。 */
    public static final int PES_START_PREFIX = 0x000001;

    /** 视频基本流 ID（GB 附录 C.1：视频流 ID = 0xE0）。 */
    public static final int STREAM_ID_VIDEO = 0xE0;
    /** 音频基本流 ID（GB 附录 C.1：音频流 ID = 0xC0）。 */
    public static final int STREAM_ID_AUDIO = 0xC0;

    /** PSM stream_type：H.264（GB 附录 C.1 b）。 */
    public static final int STREAM_TYPE_H264 = 0x1B;
    /** PSM stream_type：H.265（GB 附录 C.1 d）。 */
    public static final int STREAM_TYPE_H265 = 0x24;
    /** PSM stream_type：MPEG-4 视频（GB 附录 C.1 a）。 */
    public static final int STREAM_TYPE_MPEG4 = 0x10;
    /** PSM stream_type：SVAC 视频（GB 附录 C.1 c）。 */
    public static final int STREAM_TYPE_SVAC = 0x80;
    /** PSM stream_type：G.711 A 律（GB 附录 C.1 e）。 */
    public static final int STREAM_TYPE_G711A = 0x90;
    /** PSM stream_type：G.711 U 律（GB 附录 C.1 f）。 */
    public static final int STREAM_TYPE_G711U = 0x91;
    /** PSM stream_type：G.729（GB 附录 C.1 i）。 */
    public static final int STREAM_TYPE_G729 = 0x99;
    /** PSM stream_type：AAC（GB 附录 C.1 k）。 */
    public static final int STREAM_TYPE_AAC = 0x0F;

    /**
     * PS 复用时基：SCR 与 PTS 同为 90 kHz（与 RTP clock rate 一致）。
     * <p>ISO 13818-1 的 SCR 是 33 位、以 90 kHz 为单位、后缀 300 分频的 1/300 字段；
     * 本类按"90 kHz 时基 + 9 位扩展"写入，与国标实时点播的 90 kHz 时基对齐。
     */
    public static final int PS_CLOCK_HZ = 90_000;

    /** 一个待复用的基本流。 */
    public record ElementaryStream(int streamId, int streamType, byte[] units) {
        public ElementaryStream {
            if (streamId < 0 || streamId > 0xFF) {
                throw new IllegalArgumentException("stream id out of range: " + streamId);
            }
            if (streamType < 0 || streamType > 0xFF) {
                throw new IllegalArgumentException("stream type out of range: " + streamType);
            }
        }
    }

    private Gb28181PsPacketizer() {
    }

    // ------------------------------------------------------------------
    // PS 包
    // ------------------------------------------------------------------

    /**
     * 打一个 PS 包。
     *
     * <p>**顺序按国标硬要求**：pack header →（关键帧时）system header →（关键帧时）PSM
     * → 第一个 PES → 其余 PES。非关键帧：pack header → PES…（无系统头/PSM）。
     *
     * @param scr     系统时钟参考（90 kHz 时基）
     * @param keyframe 是否关键帧（决定是否带系统头 + PSM）
     * @param streams 基本流（可为多个：视频 + 音频）
     */
    public static byte[] pack(long scr, boolean keyframe, List<ElementaryStream> streams) {
        if (streams == null || streams.isEmpty()) {
            throw new IllegalArgumentException("ps pack needs at least one elementary stream");
        }
        for (ElementaryStream s : streams) {
            if (s.units() == null || s.units().length == 0) {
                throw new IllegalArgumentException(
                        "elementary stream 0x" + Integer.toHexString(s.streamId()) + " has no data");
            }
        }

        List<byte[]> parts = new ArrayList<>();
        parts.add(packHeader(scr));
        if (keyframe) {
            parts.add(systemHeader(streams));
            parts.add(programStreamMap(streams));
        }
        // 每个基本流一个 PES（GB：每个视频帧一个 PS 包 ⇒ 一个视频 PES）
        for (ElementaryStream s : streams) {
            parts.add(pes(s.streamId(), s.streamType(), s.units(), scr));
        }

        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    /**
     * PS 包起始码 + SCR + 复用速率（ISO/IEC 13818-1:2019 §2.5.3.1 pack_header()）。
     *
     * <p>SCR 为 **33 位**，按 ISO 布局分布在 byte4..byte8：
     * <pre>
     *  byte4 = '0010'(4 bit) | SCR[32..30] | marker(1)
     *  byte5 = SCR[29..22]
     *  byte6 = SCR[21..14]
     *  byte7 = SCR[13..6]
     *  byte8 = SCR[5..0] | marker(1) | 1 bit 备用
     * </pre>
     * byte9..13（SCR_ext / mux_rate / 保留 / stuffing_length）本仓<b>按最小国标实时点播形态填 0</b>：
     * SCR_ext 与 mux_rate 在实时点播的解复用侧不参与音视频同步（GB §4.3.6 把同步交给
     * RTP 时间戳），填 0 而不是假装算对——若将来要真算 CRC/mux_rate，两端一起改并由测试钉住。
     *
     * <p><b>为什么不用旧布局</b>：旧实现只编码 30 位（3 高位 + 27 低位）且掩码写成 34 位，
     * SCR[27..29] 整段丢失——往返测试（{@code ptsRoundTrip}/{@code keyframeRoundTrip}）
     * 当场抓出。SCR 是音视频同步的基准，丢 4 位等于把时间基准打偏。
     */
    static byte[] packHeader(long scr) {
        byte[] b = new byte[14];
        b[0] = 0x00;
        b[1] = 0x00;
        b[2] = 0x01;
        b[3] = (byte) 0xBA;
        long s = scr & 0x1FFFFFFFFL;                 // 33 位 SCR
        b[4] = (byte) (0x20 | (int) ((s >> 29) & 0x0E) | 0x01);
        b[5] = (byte) ((s >> 22) & 0xFF);
        b[6] = (byte) ((s >> 14) & 0xFF);
        b[7] = (byte) ((s >> 6) & 0xFF);
        b[8] = (byte) (((s & 0x3F) << 2) | 0x02);
        b[9] = 0x00;                                 // SCR_ext(9) + marker
        b[10] = 0x00;                                // mux_rate 高 6 位
        b[11] = 0x00;                                // mux_rate 中 8 位
        b[12] = 0x00;                                // mux_rate 低 7 位 + marker + reserved
        b[13] = (byte) 0x00;                         // reserved(5) + stuffing_length(3)=0
        return b;
    }

    /**
     * 系统头（ISO 13818-1 §2.5.3.5；GB 附录 C.1：描述 PS 包内码流种类）。
     *
     * <p>本类按"一个视频流 + 可选音频流"的最小国标形态输出，scope 里逐个给出
     * {@code stream_id}（视频 0xE0 / 音频 0xC0）与其 buffer 尺度。
     */
    static byte[] systemHeader(List<ElementaryStream> streams) {
        List<Integer> ids = new ArrayList<>();
        for (ElementaryStream s : streams) {
            int id = s.streamId();
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        // audio_bound(1 byte) + video_bound(1 byte) + packet_rate_restriction(1) + reserved(7)
        int audioBound = (int) ids.stream().filter(i -> i >= STREAM_ID_AUDIO).count();
        int videoBound = (int) ids.stream().filter(i -> i == STREAM_ID_VIDEO).count();
        // audio_bound(1) + flags/video_bound(1) + packet_rate_restriction(1) = 3 字节内容头，
        // 之后每个流 3 字节（stream_id + 2 字节 STD_buffer_size_scale）。
        // start code(4) + length(2) + 内容 ⇒ body = 6 + 3 + 3*n
        int body = 9 + 3 * ids.size();
        byte[] b = new byte[body];
        b[0] = 0x00;
        b[1] = 0x00;
        b[2] = 0x01;
        b[3] = (byte) SYSTEM_HEADER_START_CODE;
        int len = body - 6;
        b[4] = (byte) ((len >> 8) & 0x7F);
        b[5] = (byte) (len & 0xFF);
        b[6] = (byte) (audioBound & 0xFF);
        b[7] = (byte) (0x40 | (videoBound & 0x3F));   // '1'+fixed_flag+CSPS+PES_priv + video_bound(6)
        b[8] = (byte) 0x80;                            // packet_rate_restriction bit + reserved
        int off = 9;
        for (int id : ids) {
            b[off++] = (byte) id;
            // STD_buffer_size_scale（1 bit，视频置 1）+ 13 位尺度 + marker
            b[off++] = (byte) 0x40;
            b[off++] = (byte) 0x01;
        }
        return b;
    }

    /**
     * Program Stream Map（ISO 13818-1 §2.5.4；GB 附录 C.1：逐个基本流的 stream_type）。
     *
     * <p>本类输出最小可用 PSM：current_next=1、program_stream_map_length、
     * elementary_stream_map_length、PSM 版本、以及每个流的 {@code stream_type} +
     * elementary_stream_id + 描述长度 0（不带描述符——国标实时点播的最小形态）。
     */
    static byte[] programStreamMap(List<ElementaryStream> streams) {
        Map<Integer, Integer> byId = new LinkedHashMap<>();
        for (ElementaryStream s : streams) {
            byId.putIfAbsent(s.streamId(), s.streamType());
        }
        // 6 字节前缀（start code 4 + psm_length 2）之后的内容：
        //   current_next+version(1) + psm_version(1) + elementary_stream_map_length(2) = 4
        //   + 每个流 4 字节（stream_type + eid + es_info_length 2）
        //   + CRC_32(4)
        // psm_length = 上述内容总长；body = 6 + psm_length
        int esMapLen = 4 * byId.size();
        int psmLen = 4 + esMapLen + 4;
        int body = 6 + psmLen;

        byte[] b = new byte[body];
        b[0] = 0x00;
        b[1] = 0x00;
        b[2] = 0x01;
        b[3] = (byte) PSM_START_CODE;
        b[4] = (byte) ((psmLen >> 8) & 0xFF);
        b[5] = (byte) (psmLen & 0xFF);
        b[6] = (byte) 0x81;                        // current_next_indicator=1 + reserved
        b[7] = (byte) 0x00;                        // program_stream_map_version
        b[8] = (byte) ((esMapLen >> 8) & 0xFF);
        b[9] = (byte) (esMapLen & 0xFF);
        int off = 10;
        for (Map.Entry<Integer, Integer> e : byId.entrySet()) {
            b[off++] = (byte) (int) e.getValue();          // stream_type
            b[off++] = (byte) (int) e.getKey();           // elementary_stream_id
            b[off++] = 0x00;                              // ES_info_length 高 6 位
            b[off++] = 0x00;                              // ES_info_length 低 8 位
        }
        // CRC_32：本类填 0（ISO 13818-1 要求存在但国标实时点播不解码它；
        // 详见 Gb28181PsDemuxer 的对应注释——**填 0 而不是假装算对**）
        for (int i = body - 4; i < body; i++) {
            b[i] = 0x00;
        }
        return b;
    }

    /**
     * PES 包（ISO 13818-1 §2.5.3.4；stream_id 视频 0xE0 / 音频 0xC0）。
     *
     * <p>头 6 + 3 + 5 = 14 字节：{@code 00 00 01} + stream_id + PES_packet_length(2)
     * + {@code 10}（'10' + 标志位）+ PTS(5)。
     * PTS 只写 32 位以内的部分（90 kHz 时基、marker 位按 ISO 13818-1 排布），
     * DTS 不写（实时点播无 B 帧重排序需求时 PTS=DTS）。
     */
    static byte[] pes(int streamId, int streamType, byte[] data, long pts) {
        byte[] b = new byte[14 + data.length];
        b[0] = 0x00;
        b[1] = 0x00;
        b[2] = 0x01;
        b[3] = (byte) streamId;
        int pesLen = 3 + 5 + data.length;
        b[4] = (byte) ((pesLen >> 8) & 0xFF);
        b[5] = (byte) (pesLen & 0xFF);
        b[6] = (byte) 0x80;                        // '10' + 各标志位 0
        b[7] = (byte) 0x80;                        // PTS_only 标志
        b[8] = 5;                                  // PES_header_data_length = 5
        // 33 位 PTS：掩码必须是 0x1FFFFFFFFL（33 个 1）。
        // **这里曾写成 0x3FFFFFFFL（30 位），把 PTS 高 3 位整段抹掉** ——
        // 表现为时间戳永远小于 2^30、b9 的 PTS[32..30] 恒为 0，而.src 里
        // `p >>> 29 & 7` 的表达式看起来完全正确（字节码也对），
        // 只有把数值代进去才看得见。此类"表达式对、常量错"的缺陷，
        // 靠往返测试（keyframeRoundTrip / ptsRoundTrip）才抓得出来。
        long p = pts & 0x1FFFFFFFFL;
        // PTS[32..30] 显式落到 b9 的 bits 3..1（'0010' 占 bits7..4、marker 占 bit0）
        // 位域 value v：bit0=PTS[30]、bit2=PTS[32] ⇒ 写 `v<<1`，读回 `v<<30` 还原。
        // **这里曾写成 `(p>>>29)&7`**：`p>>>29` 是 PTS[32..29] 4 位，
        // `&7` 会把 PTS[32] 砍掉，等价于拿 PTS[31..29] 冒充 PTS[32..30]。
        int ptsTop3 = (int) ((p >>> 30) & 0x07);
        b[9] = (byte) (0x21 | (ptsTop3 << 1));
        b[10] = (byte) ((p >> 22) & 0xFF);                 // PTS[29..22]
        b[11] = (byte) ((((p >> 15) & 0x7F) << 1) | 0x01);  // PTS[21..15] 在 bits7..1，marker 在 bit0
        b[12] = (byte) ((p >> 7) & 0xFF);                  // PTS[14..7]
        b[13] = (byte) (((p & 0x7F) << 1) | 0x01);         // PTS[6..0] 在 bits7..1，marker 在 bit0
        System.arraycopy(data, 0, b, 14, data.length);
        return b;
    }
}

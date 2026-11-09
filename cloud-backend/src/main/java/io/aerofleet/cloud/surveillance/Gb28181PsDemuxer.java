package io.aerofleet.cloud.surveillance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MPEG-2 Program Stream 解复用器（云端收流侧，GB/T 28181-2022 附录 C.1 的逆操作）。
 *
 * <p>职责：把一个 RTP 负载（PS 包）拆回基本流单元 + 元数据，让上层能拿到
 * 「这一包里有什么流、什么类型、什么时间戳、多少字节」——**不解码 H.264**，
 * 也不假装能播（媒体播放属前端/生产阶段，见 README 的 E5 分层条款）。
 *
 * <p>与 {@link Gb28181PsPacketizer} 是**同一份契约的两端**：任何一端改动
 * （顺序、字段、关键帧规则）都会被 {@code Gb28181PsRoundTripTest} 当场抓到。
 *
 * <p>**不校验 CRC_32**：国标实时点播的最小 PSM 不带 CRC 语义使用，
 * 本类的打包器填 0、解复用器不校验——**校验一个恒 0 的字段只会造出假门禁**。
 * 若将来要按 ISO 13818-1 真算 CRC，两端一起改并由测试钉住。
 */
public final class Gb28181PsDemuxer {

    /** 解出的一个基本流单元（一个 PES 的载荷）。 */
    public record StreamUnit(int streamId, int streamType, long pts, byte[] data) {
    }

    /** 解出的 PS 包视图。 */
    public record PsPack(int packStartOffset, long scr, boolean hasSystemHeader,
                         boolean hasProgramStreamMap, Map<Integer, Integer> streamTypes,
                         List<StreamUnit> units) {
    }

    private Gb28181PsDemuxer() {
    }

    /**
     * 解一个 PS 包。
     *
     * <p>口径：从头按 start code 顺序走，遇到 {@code 00 00 01 BA} 记 pack header 与 SCR，
     * {@code BB} 记系统头，{@code BC} 记 PSM（逐个 stream_type），{@code E0-EF}/{@code C0-DF}
     * 记 PES 并把载荷收成一个 {@link StreamUnit}。**遇到未知 start code 即抛** ——
     * 媒体面上一处柔舌解析会让后续整条流错位，宁可不解。
     */
    public static PsPack demux(byte[] ps) {
        if (ps == null || ps.length < 4) {
            throw new IllegalArgumentException("ps pack too short");
        }
        int off = 0;
        long scr = -1;
        boolean hasSysHdr = false;
        boolean hasPsm = false;
        Map<Integer, Integer> streamTypes = new LinkedHashMap<>();
        List<StreamUnit> units = new ArrayList<>();
        int packStart = -1;

        while (off + 4 <= ps.length) {
            if ((ps[off] & 0xFF) != 0x00 || (ps[off + 1] & 0xFF) != 0x00
                    || (ps[off + 2] & 0xFF) != 0x01) {
                throw new IllegalArgumentException(
                        "expected start code at offset " + off + " in ps pack");
            }
            int code = ps[off + 3] & 0xFF;
            switch (code) {
                case 0xBA -> {
                    if (packStart >= 0) {
                        throw new IllegalArgumentException("second pack_start_code");
                    }
                    packStart = off;
                    scr = readScr(ps, off + 4);
                    off += 14;
                }
                case 0xBB -> {
                    hasSysHdr = true;
                    int len = ((ps[off + 4] & 0x7F) << 8) | (ps[off + 5] & 0xFF);
                    off += 6 + len;
                }
                case 0xBC -> {
                    hasPsm = true;
                    int len = ((ps[off + 4] & 0xFF) << 8) | (ps[off + 5] & 0xFF);
                    int esMapLen = ((ps[off + 8] & 0xFF) << 8) | (ps[off + 9] & 0xFF);
                    int p = off + 10;
                    int end = p + esMapLen;
                    while (p + 4 <= end) {
                        int type = ps[p] & 0xFF;
                        int id = ps[p + 1] & 0xFF;
                        streamTypes.put(id, type);
                        p += 4;
                    }
                    off += 6 + len;
                }
                default -> {
                    PesRead r = readPes(ps, off);
                    units.add(r.unit());
                    off = r.nextOffset();
                }
            }
        }
        if (packStart < 0) {
            throw new IllegalArgumentException("ps pack has no pack_start_code");
        }
        // PES 自己不带 stream_type（PSM 才有）——按 PSM 表回填，取不到留 0
        List<StreamUnit> typed = new ArrayList<>(units.size());
        for (StreamUnit u : units) {
            typed.add(new StreamUnit(u.streamId(), streamTypes.getOrDefault(u.streamId(), 0),
                    u.pts(), u.data()));
        }
        return new PsPack(packStart, scr, hasSysHdr, hasPsm,
                Map.copyOf(streamTypes), List.copyOf(typed));
    }

    /**
     * 读 33 位 SCR（与 {@link Gb28181PsPacketizer#packHeader} 的 ISO 13818-1 布局互为逆运算）。
     * <p>SCR_ext / mux_rate 不读（实时点播的音视频同步由 RTP 时间戳承担，见 GB §4.3.6）。
     */
    private static long readScr(byte[] b, int o) {
        return ((long) (b[o] & 0x0E) << 29)
                | ((long) (b[o + 1] & 0xFF) << 22)
                | ((long) (b[o + 2] & 0xFF) << 14)
                | ((long) (b[o + 3] & 0xFF) << 6)
                | ((b[o + 4] & 0xFC) >> 2);
    }

    /** 读一个 PES；stream_type 由调用方按 PSM 表格回填（PS 包里 PES 本身不带类型）。 */
    private static PesRead readPes(byte[] b, int o) {
        int streamId = b[o + 3] & 0xFF;
        int pesLen = ((b[o + 4] & 0xFF) << 8) | (b[o + 5] & 0xFF);
        int headerDataLen = b[o + 8] & 0xFF;
        int payloadStart = o + 9 + headerDataLen;
        int payloadEnd = o + 6 + pesLen;
        if (payloadEnd > b.length || payloadEnd < payloadStart) {
            throw new IllegalArgumentException("pes length out of bounds at offset " + o);
        }
        long pts = readPts(b, o);
        byte[] data = new byte[payloadEnd - payloadStart];
        System.arraycopy(b, payloadStart, data, 0, data.length);
        return new PesRead(new StreamUnit(streamId, 0, pts, data), payloadEnd);
    }

    /** 读 PTS（PTS_only 标志置位时；未置位返回 0 而不是抛——音频/扩展帧可能不带）。 */
    private static long readPts(byte[] b, int o) {
        if ((b[o + 7] & 0x80) == 0) {
            return 0L;
        }
        // 位域映射：b9 bits 3..1 = PTS[32..30]，读回需 `(b9&0x0E) << 29`
        // （b9 bit1→bit30、bit2→bit31、bit3→bit32）。
        // **这里曾写成 `<<30`**：高位翻倍，表现为解码值比期望值恰好多出一份
        // 高 3 位（expected + 2^30×v），往返测试 ptsRoundTrip 抓出。
        return ((long) (b[o + 9] & 0x0E) << 29)
                | ((long) (b[o + 10] & 0xFF) << 22)
                | ((long) (b[o + 11] & 0xFE) << 14)
                | ((long) (b[o + 12] & 0xFF) << 7)
                | ((b[o + 13] & 0xFE) >> 1);
    }

    /** PES 读取的中间载体（带 next 偏移；对外只暴露 {@link StreamUnit}）。 */
    private record PesRead(StreamUnit unit, int nextOffset) {
    }
}

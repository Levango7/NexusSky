package io.aerofleet.cloud.surveillance;

/**
 * 媒体面组出的一帧。
 *
 * @param ssrc    同步源标识
 * @param timestamp 90 kHz RTP 时间戳（GB/T 28181-2022 §4.3.6：同帧内所有包相同）
 * @param packets 组成本帧的 RTP 包数（一帧可被切成多个包）
 * @param ps      拼还原的 PS 包字节（每个视频帧一个 PS 包 —— 附录 C.1）
 */
record Gb28181MediaFrame(long ssrc, long timestamp, int packets, byte[] ps) {

    /** 解复用后的基本流单元（PS → PES）。 */
    java.util.List<Gb28181PsDemuxer.StreamUnit> units() {
        return Gb28181PsDemuxer.demux(ps).units();
    }

    /** 是否为关键帧（PS 含系统头 + PSM —— 附录 C.1 硬要求）。 */
    boolean keyframe() {
        Gb28181PsDemuxer.PsPack pack = Gb28181PsDemuxer.demux(ps);
        return pack.hasSystemHeader() && pack.hasProgramStreamMap();
    }

    @Override
    public String toString() {
        return "MediaFrame{ssrc=" + ssrc + ", ts=" + timestamp + ", packets=" + packets
                + ", psBytes=" + ps.length + '}';
    }
}

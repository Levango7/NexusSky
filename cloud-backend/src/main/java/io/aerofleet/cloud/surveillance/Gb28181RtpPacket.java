package io.aerofleet.cloud.surveillance;

/**
 * RTP 包（IETF RFC 3550，GB/T 28181-2022 附录 C 的封装外层）。
 *
 * <p>**字段与字节位置**（RFC 3550 §5.1，固定头 12 字节）：
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|X|  CC   |M|     PT      |        sequence number        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |               timestamp (32 bit, 90kHz clock per GB/T 28181)   |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |             synchronization source (SSRC) identifier          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |          contributing source (CSRC) identifiers ...           |
 * </pre>
 *
 * <p>**GB/T 28181-2022 §4.3.6 的硬要求**（本类负责让其可被断言）：
 * 「同一帧音视频数据包封装成的所有 RTP 数据包的 RTP 时间戳相同，且与不同帧音视频数据包的
 * RTP 数据包的 RTP 时间戳不同」——即**时间戳是帧边界标识**。见
 * {@link #assertFrameTimestampDiscipline} 的思路与
 * {@code Gb28181MediaSessionTest} 的对应断言。
 *
 * <p>国标实时点播参数（附录 C.1）：PT=96、encoding name={@code PS}、clock rate=90000 Hz。
 */
public final class Gb28181RtpPacket {

    /** 国标 PS 负载类型（GB/T 28181-2022 附录 C.1：payload type = 96）。 */
    public static final int PAYLOAD_TYPE_PS = 96;
    /** PS 流的时钟频率（附录 C.1：clock rate = 90 kHz）。 */
    public static final int CLOCK_RATE_PS = 90_000;
    /** RTP 固定头长度（RFC 3550 §5.1）。 */
    public static final int FIXED_HEADER_BYTES = 12;

    private final boolean marker;
    private final int payloadType;
    private final int sequenceNumber;
    private final long timestamp;
    private final long ssrc;
    private final byte[] payload;

    private Gb28181RtpPacket(boolean marker, int payloadType, int sequenceNumber,
                             long timestamp, long ssrc, byte[] payload) {
        this.marker = marker;
        this.payloadType = payloadType;
        this.sequenceNumber = sequenceNumber & 0xFFFF;
        this.timestamp = timestamp & 0xFFFFFFFFL;
        this.ssrc = ssrc & 0xFFFFFFFFL;
        this.payload = payload == null ? new byte[0] : payload;
    }

    // ------------------------------------------------------------------
    // 构造
    // ------------------------------------------------------------------

    /**
     * 造一个 RTP 包。
     *
     * @param marker         M 位：帧结束标志（RFC 3550 §5.1 对视频即"一帧的最后一个包"）
     * @param payloadType    负载类型；国标 PS 用 {@link #PAYLOAD_TYPE_PS}
     * @param sequenceNumber 序号（16 位回绕）；调用方负责单调递增
     * @param timestamp      90kHz 时间戳；**同一帧内必须相同、不同帧必须不同**（GB §4.3.6）
     * @param ssrc          同步源标识；国标由 SIP 域的 {@code y=} 规则产生（附录 G 注16）
     */
    public static Gb28181RtpPacket of(boolean marker, int payloadType, int sequenceNumber,
                                      long timestamp, long ssrc, byte[] payload) {
        if (payloadType < 0 || payloadType > 127) {
            throw new IllegalArgumentException("rtp payload type out of range: " + payloadType);
        }
        return new Gb28181RtpPacket(marker, payloadType, sequenceNumber, timestamp, ssrc, payload);
    }

    /** 序列号递增 1（16 位回绕）——供流式发送方逐包调用。 */
    public static int nextSequence(int seq) {
        return (seq + 1) & 0xFFFF;
    }

    // ------------------------------------------------------------------
    // 序列化 / 解析
    // ------------------------------------------------------------------

    /** 渲染为 RTP 字节（固定头 + 负载，无 CSRC、无扩展——国标实时点播不需要）。 */
    public byte[] encode() {
        byte[] out = new byte[FIXED_HEADER_BYTES + payload.length];
        out[0] = (byte) 0x80;                       // V=2, P=0, X=0, CC=0
        out[1] = (byte) (((marker ? 1 : 0) << 7) | (payloadType & 0x7F));
        out[2] = (byte) ((sequenceNumber >> 8) & 0xFF);
        out[3] = (byte) (sequenceNumber & 0xFF);
        out[4] = (byte) ((timestamp >> 24) & 0xFF);
        out[5] = (byte) ((timestamp >> 16) & 0xFF);
        out[6] = (byte) ((timestamp >> 8) & 0xFF);
        out[7] = (byte) (timestamp & 0xFF);
        out[8] = (byte) ((ssrc >> 24) & 0xFF);
        out[9] = (byte) ((ssrc >> 16) & 0xFF);
        out[10] = (byte) ((ssrc >> 8) & 0xFF);
        out[11] = (byte) (ssrc & 0xFF);
        System.arraycopy(payload, 0, out, FIXED_HEADER_BYTES, payload.length);
        return out;
    }

    /**
     * 解析 RTP 字节。
     *
     * <p>**严格**：version 必须为 2、PT ≤ 127、长度至少 12 字节——不满足即抛，
     * 不返回"尽量解析"的半成品（媒体面上一个错位的包会污染整条流）。
     */
    public static Gb28181RtpPacket decode(byte[] data, int offset, int length) {
        if (data == null || length < FIXED_HEADER_BYTES) {
            throw new IllegalArgumentException(
                    "rtp packet too short: " + length + " < " + FIXED_HEADER_BYTES);
        }
        int version = (data[offset] >> 6) & 0x03;
        if (version != 2) {
            throw new IllegalArgumentException("rtp version != 2: " + version);
        }
        int cc = data[offset] & 0x0F;
        boolean marker = ((data[offset + 1] >> 7) & 0x01) == 1;
        int pt = data[offset + 1] & 0x7F;
        int seq = ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
        long ts = ((long) (data[offset + 4] & 0xFF) << 24)
                | ((long) (data[offset + 5] & 0xFF) << 16)
                | ((long) (data[offset + 6] & 0xFF) << 8)
                | (long) (data[offset + 7] & 0xFF);
        long s = ((long) (data[offset + 8] & 0xFF) << 24)
                | ((long) (data[offset + 9] & 0xFF) << 16)
                | ((long) (data[offset + 10] & 0xFF) << 8)
                | (long) (data[offset + 11] & 0xFF);
        int headerLen = FIXED_HEADER_BYTES + 4 * cc;
        if (length < headerLen) {
            throw new IllegalArgumentException(
                    "rtp packet shorter than CSRC list: " + length + " < " + headerLen);
        }
        byte[] pl = new byte[length - headerLen];
        System.arraycopy(data, offset + headerLen, pl, 0, pl.length);
        return new Gb28181RtpPacket(marker, pt, seq, ts, s, pl);
    }

    // ------------------------------------------------------------------
    // 取值
    // ------------------------------------------------------------------

    public boolean marker() {
        return marker;
    }

    public int payloadType() {
        return payloadType;
    }

    public int sequenceNumber() {
        return sequenceNumber;
    }

    /** @return 32 位时间戳（90 kHz 时基） */
    public long timestamp() {
        return timestamp;
    }

    public long ssrc() {
        return ssrc;
    }

    public byte[] payload() {
        return payload;
    }

    public int length() {
        return FIXED_HEADER_BYTES + payload.length;
    }

    @Override
    public String toString() {
        return "Rtp{pt=" + payloadType + ", seq=" + sequenceNumber + ", ts=" + timestamp
                + ", ssrc=" + ssrc + ", m=" + marker + ", len=" + payload.length + '}';
    }
}

package io.aerofleet.sim.comm;

import io.aerofleet.sim.SimLog;

import java.util.HashMap;
import java.util.Map;

/**
 * LoRa 传输适配层：MAVLink 帧分片/重组 + LoRa 参数配置。
 * <p>
 * 用于丐版无人机的低成本远距离通信（SX1278 433MHz）。
 * 大帧自动分片为 LoRa 载荷片段（~50 bytes），接收端重组。
 * <p>
 * 分片协议：每个分片头 = [frameId(1) | totalFragments(1) | fragIndex(1) | payload(<=maxPayload-3)]。
 * <p>
 * 线程安全：重组缓冲非线程安全，调用方需自行同步；分片操作无状态，可并发调用。
 */
public final class LoRaTransport {

    /** 分片头长度：frameId(1) + totalFragments(1) + fragIndex(1) */
    private static final int FRAG_HEADER_BYTES = 3;

    /** 默认最大 LoRa 载荷（SX1278 433MHz SF7 典型值，字节） */
    private static final int DEFAULT_MAX_PAYLOAD = 50;

    /** SF 下限（含） */
    private static final int SF_MIN = 7;
    /** SF 上限（含） */
    private static final int SF_MAX = 12;
    /** SF7 基础空口延迟（毫秒） */
    private static final long LATENCY_SF7_MS = 50L;
    /** SF12 基础空口延迟（毫秒） */
    private static final long LATENCY_SF12_MS = 2000L;

    // LoRa 物理层参数
    private final double frequencyMHz;    // 433.0
    private final int spreadingFactor;    // 7-12
    private final int bandwidthKHz;       // 125
    private final int codingRate;         // 4/5 = 5, 4/8 = 8
    private final int txPowerDbm;         // 20
    private final int maxPayloadBytes;    // 50

    /** 重组缓冲：frameId -> 分片数组（按 fragIndex 槽位存放） */
    private final Map<Integer, byte[][]> reassemblyBuffer = new HashMap<>();

    /**
     * 构造 LoRa 传输层，使用默认最大载荷 50 字节。
     *
     * @param freq       频率（MHz），如 433.0
     * @param sf         扩频因子，7-12
     * @param bw         带宽（kHz），如 125
     * @param cr         编码率分母，4/5→5, 4/8→8
     * @param txPower    发射功率（dBm），如 20
     */
    public LoRaTransport(double freq, int sf, int bw, int cr, int txPower) {
        this(freq, sf, bw, cr, txPower, DEFAULT_MAX_PAYLOAD);
    }

    /**
     * 构造 LoRa 传输层，指定最大载荷。
     *
     * @param freq            频率（MHz）
     * @param sf              扩频因子，7-12
     * @param bw              带宽（kHz）
     * @param cr              编码率分母
     * @param txPower         发射功率（dBm）
     * @param maxPayloadBytes 单帧最大 LoRa 载荷（字节），必须 &gt; 3
     */
    public LoRaTransport(double freq, int sf, int bw, int cr, int txPower, int maxPayloadBytes) {
        if (sf < SF_MIN || sf > SF_MAX) {
            throw new IllegalArgumentException("spreadingFactor must be " + SF_MIN + "-" + SF_MAX + ", got " + sf);
        }
        if (maxPayloadBytes <= FRAG_HEADER_BYTES) {
            throw new IllegalArgumentException(
                "maxPayloadBytes must be > " + FRAG_HEADER_BYTES + " (header size), got " + maxPayloadBytes);
        }
        this.frequencyMHz = freq;
        this.spreadingFactor = sf;
        this.bandwidthKHz = bw;
        this.codingRate = cr;
        this.txPowerDbm = txPower;
        this.maxPayloadBytes = maxPayloadBytes;
    }

    /** 默认配置：433MHz, SF7, 125kHz, 4/5, 20dBm（~2km, ~50ms 延迟）。 */
    public static LoRaTransport defaultConfig() {
        return new LoRaTransport(433.0, 7, 125, 5, 20);
    }

    /** 远距离配置：433MHz, SF12, 125kHz, 4/8, 20dBm（~5km, ~2s 延迟）。 */
    public static LoRaTransport longRange() {
        return new LoRaTransport(433.0, 12, 125, 8, 20);
    }

    /** 频率（MHz）。 */
    public double frequencyMHz() {
        return frequencyMHz;
    }

    /** 扩频因子（7-12）。 */
    public int spreadingFactor() {
        return spreadingFactor;
    }

    /** 带宽（kHz）。 */
    public int bandwidthKHz() {
        return bandwidthKHz;
    }

    /** 编码率分母（5 表示 4/5, 8 表示 4/8）。 */
    public int codingRate() {
        return codingRate;
    }

    /** 发射功率（dBm）。 */
    public int txPowerDbm() {
        return txPowerDbm;
    }

    /** 单帧最大 LoRa 载荷（字节，含分片头）。 */
    public int maxPayloadBytes() {
        return maxPayloadBytes;
    }

    /**
     * 将 MAVLink 帧分片为 LoRa 载荷数组。
     * <p>
     * 每个分片 = [frameId(1) | totalFragments(1) | fragIndex(1) | payloadChunk(<=maxPayload-3)]。
     * 空载荷返回单个仅含头的分片。
     *
     * @param mavlinkPayload MAVLink 帧载荷（不含 LoRa 头）
     * @param frameId        帧序号，0-255
     * @return 分片数组，长度 ≥ 1
     */
    public byte[][] fragment(byte[] mavlinkPayload, int frameId) {
        if (mavlinkPayload == null) {
            throw new IllegalArgumentException("mavlinkPayload must not be null");
        }
        if (frameId < 0 || frameId > 255) {
            throw new IllegalArgumentException("frameId must be 0-255, got " + frameId);
        }

        int chunkSize = maxPayloadBytes - FRAG_HEADER_BYTES;
        int total = (mavlinkPayload.length + chunkSize - 1) / chunkSize;
        if (total == 0) {
            total = 1; // 空载荷至少 1 个分片
        }
        if (total > 255) {
            throw new IllegalArgumentException(
                "payload too large: " + mavlinkPayload.length + " bytes need " + total + " fragments (max 255)");
        }

        byte[][] fragments = new byte[total][];
        for (int i = 0; i < total; i++) {
            int offset = i * chunkSize;
            int len = Math.min(chunkSize, mavlinkPayload.length - offset);
            byte[] frag = new byte[FRAG_HEADER_BYTES + len];
            frag[0] = (byte) frameId;
            frag[1] = (byte) total;
            frag[2] = (byte) i;
            System.arraycopy(mavlinkPayload, offset, frag, FRAG_HEADER_BYTES, len);
            fragments[i] = frag;
        }
        SimLog.info("LoRa fragment: frameId=" + frameId + " total=" + total
            + " payloadLen=" + mavlinkPayload.length);
        return fragments;
    }

    /**
     * 重组 LoRa 分片为完整 MAVLink 帧。
     * <p>
     * 输入 {@code loraPayload} 包含 3 字节头 + 实际载荷。当某 frameId 的全部分片收齐后返回拼接后的
     * 原始 MAVLink 载荷；未收齐返回 {@code null}。
     *
     * @param frameId     帧序号（用于查找重组缓冲）
     * @param loraPayload 单个 LoRa 分片（含 3 字节头）
     * @return 完整 MAVLink 载荷，或 {@code null} 表示尚未收齐
     */
    public byte[] reassemble(int frameId, byte[] loraPayload) {
        if (loraPayload == null || loraPayload.length < FRAG_HEADER_BYTES) {
            SimLog.warn("LoRa reassemble: invalid payload length "
                + (loraPayload == null ? "null" : loraPayload.length));
            return null;
        }
        int total = loraPayload[1] & 0xFF;
        int index = loraPayload[2] & 0xFF;
        if (total == 0 || index >= total) {
            SimLog.warn("LoRa reassemble: invalid header total=" + total + " index=" + index);
            return null;
        }

        byte[][] frags = reassemblyBuffer.get(frameId);
        if (frags == null || frags.length != total) {
            // 新帧或总分片数不一致，初始化缓冲
            if (frags != null) {
                SimLog.warn("LoRa reassemble: total mismatch for frameId=" + frameId
                    + " expected=" + frags.length + " got=" + total + ", reinitializing");
            }
            frags = new byte[total][];
            reassemblyBuffer.put(frameId, frags);
        }

        // 存放当前分片载荷（去掉头）
        int dataLen = loraPayload.length - FRAG_HEADER_BYTES;
        frags[index] = new byte[dataLen];
        System.arraycopy(loraPayload, FRAG_HEADER_BYTES, frags[index], 0, dataLen);

        // 检查是否收齐
        for (int i = 0; i < total; i++) {
            if (frags[i] == null) {
                return null; // 还没收齐
            }
        }

        // 收齐，拼接
        int totalLen = 0;
        for (byte[] f : frags) {
            totalLen += f.length;
        }
        byte[] result = new byte[totalLen];
        int pos = 0;
        for (byte[] f : frags) {
            System.arraycopy(f, 0, result, pos, f.length);
            pos += f.length;
        }
        reassemblyBuffer.remove(frameId);
        SimLog.info("LoRa reassemble: frameId=" + frameId + " complete, len=" + totalLen);
        return result;
    }

    /**
     * 估算传输延迟（毫秒）。
     * <p>
     * 基础空口延迟按 SF 线性插值：SF7→50ms, SF12→2000ms。
     * 载荷超过单帧容量时按分片数线性叠加（每片一个空口时隙）。
     *
     * @param payloadBytes 待传输载荷字节数
     * @return 估算延迟（毫秒），≥ 1
     */
    public long estimatedLatencyMs(int payloadBytes) {
        // 基础空口延迟：SF7→50ms, SF12→2000ms，线性插值
        // 每级 SF 增加 (2000-50)/(12-7) = 390ms
        long baseLatency = LATENCY_SF7_MS + (spreadingFactor - SF_MIN)
            * ((LATENCY_SF12_MS - LATENCY_SF7_MS) / (SF_MAX - SF_MIN));
        // 按载荷大小线性叠加：每 maxPayloadBytes 字节占用一个空口时隙
        int chunks = Math.max(1, (payloadBytes + maxPayloadBytes - 1) / maxPayloadBytes);
        long latency = baseLatency * chunks;
        return Math.max(1L, latency);
    }

    /**
     * 估算单帧最大有效载荷（字节）。
     * <p>
     * 参考 SX1278 数据手册：去掉 3 字节分片头后的可用载荷。
     *
     * @return 最大有效载荷（字节），= maxPayloadBytes - 3
     */
    public int maxPayload() {
        return maxPayloadBytes - FRAG_HEADER_BYTES;
    }
}
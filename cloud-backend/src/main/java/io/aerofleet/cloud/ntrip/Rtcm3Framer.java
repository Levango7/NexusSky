package io.aerofleet.cloud.ntrip;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * RTCM 3.x 帧切分器：把 NTRIP 的字节流切成一条条完整的 RTCM 报文。
 * <p>
 * <b>为什么必须有这一层</b>：{@link NtripClient#read} 返回的是任意长度的字节块，
 * 边界与 RTCM 帧完全无关（一个 512 字节的 read 可能含 1.3 个帧，也可能只是半帧）。
 * 直接把这种字节块塞进 {@code GPS_RTCM_DATA} 发给飞控，飞控无法重组——
 * 它按帧解析，收到半帧只会整条丢弃。
 * <p>
 * <b>帧格式</b>（RTCM 10403.3 §3.3）：
 * <pre>
 * 偏移  长度  内容
 * 0    1     前导码 0xD3
 * 1    2     6 bit 保留（0）+ 10 bit 载荷长度（大端）
 * 3    n     载荷（n = 长度字段）
 * 3+n  3     CRC-24Q
 * </pre>
 * 因此整帧长度 = {@code 3 + n + 3}，{@code n} 取值 0~1023。
 * <p>
 * <b>CRC-24Q</b>：多项式 {@code 0x1864CFB}，初值 0，覆盖「头 + 载荷」共 {@code 3+n} 字节，
 * 不覆盖 CRC 自身。校验失败说明该帧损坏（链路误码或不是 RTCM 流），必须丢弃而不是转发——
 * 转发一条 CRC 错的改正数比不转发更糟：飞控会把它当作有效改正解算，位置反而更偏。
 * <p>
 * <b>非线程安全</b>：一个实例对应一条流。跨线程用请各持一个。
 */
public final class Rtcm3Framer {

    public static final int PREAMBLE = 0xD3;

    /** 10 bit 长度字段的上限。 */
    public static final int MAX_PAYLOAD = 1023;

    /** 头部 3 字节 + CRC 3 字节。 */
    public static final int OVERHEAD = 6;

    private static final int[] CRC24Q_TABLE = buildTable();

    private final ByteArrayOutputStream pending = new ByteArrayOutputStream(2048);

    private long framesEmitted;
    private long crcFailures;
    private long resyncBytes;

    /**
     * 喂入一段字节，返回本次能切出的**完整且 CRC 正确**的帧。
     *
     * @param chunk 任意长度的字节块
     * @return 完整帧列表（可能为空）
     */
    public List<byte[]> feed(byte[] chunk) {
        if (chunk != null && chunk.length > 0) {
            pending.write(chunk, 0, chunk.length);
        }
        byte[] buf = pending.toByteArray();
        List<byte[]> out = new ArrayList<>();
        int i = 0;
        int consumed = 0;

        while (true) {
            // 1) 找前导码
            while (i < buf.length && (buf[i] & 0xFF) != PREAMBLE) {
                i++;
                resyncBytes++;
            }
            if (buf.length - i < 3) {
                break; // 头部不完整，等更多数据
            }
            // 2) 解长度
            int len = ((buf[i + 1] & 0x03) << 8) | (buf[i + 2] & 0xFF);
            if (len > MAX_PAYLOAD) {
                // 长度字段非法 ⇒ 这个 0xD3 不是真前导码（可能是载荷里的巧合）
                i++;
                resyncBytes++;
                continue;
            }
            int total = len + OVERHEAD;
            if (buf.length - i < total) {
                break; // 帧不完整，等更多数据
            }
            // 3) CRC 校验
            if (crc24q(buf, i, 3 + len) == readU24(buf, i + 3 + len)) {
                byte[] frame = new byte[total];
                System.arraycopy(buf, i, frame, 0, total);
                out.add(frame);
                framesEmitted++;
                i += total;
                consumed = i;
            } else {
                crcFailures++;
                i++; // 从下一个字节重新找前导码
                resyncBytes++;
            }
        }

        // 只保留未消费的尾巴，避免缓冲区无限增长
        if (consumed > 0) {
            pending.reset();
            if (consumed < buf.length) {
                pending.write(buf, consumed, buf.length - consumed);
            }
        }
        return out;
    }

    /** 已成功切出的帧数。 */
    public long framesEmitted() {
        return framesEmitted;
    }

    /** CRC 校验失败的次数（流质量指标：持续增长说明链路有问题）。 */
    public long crcFailures() {
        return crcFailures;
    }

    /** 为重新对齐而跳过的字节数。 */
    public long resyncBytes() {
        return resyncBytes;
    }

    /** 丢弃缓冲区里未成帧的残余（重连时调用，避免旧流的半帧污染新流）。 */
    public void reset() {
        pending.reset();
    }

    // --- CRC-24Q ---------------------------------------------------------

    /**
     * 计算 CRC-24Q。
     *
     * @param data  数据
     * @param off   起始偏移
     * @param len   长度
     * @return 24 位 CRC
     */
    public static int crc24q(byte[] data, int off, int len) {
        int crc = 0;
        for (int k = off; k < off + len; k++) {
            crc = ((crc << 8) & 0xFFFFFF) ^ CRC24Q_TABLE[((crc >>> 16) ^ (data[k] & 0xFF)) & 0xFF];
        }
        return crc;
    }

    private static int[] buildTable() {
        int[] table = new int[256];
        int poly = 0x1864CFB;
        for (int i = 0; i < 256; i++) {
            int crc = i << 16;
            for (int b = 0; b < 8; b++) {
                crc <<= 1;
                if ((crc & 0x1000000) != 0) {
                    crc ^= poly;
                }
            }
            table[i] = crc & 0xFFFFFF;
        }
        return table;
    }

    private static int readU24(byte[] b, int off) {
        return ((b[off] & 0xFF) << 16) | ((b[off + 1] & 0xFF) << 8) | (b[off + 2] & 0xFF);
    }

    /** 构造一条合法帧（测试与本地模拟源用）。 */
    public static byte[] buildFrame(byte[] payload) {
        if (payload == null || payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload 长度非法");
        }
        byte[] frame = new byte[payload.length + OVERHEAD];
        frame[0] = (byte) PREAMBLE;
        frame[1] = (byte) ((payload.length >> 8) & 0x03);
        frame[2] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, frame, 3, payload.length);
        int crc = crc24q(frame, 0, 3 + payload.length);
        frame[3 + payload.length] = (byte) ((crc >> 16) & 0xFF);
        frame[4 + payload.length] = (byte) ((crc >> 8) & 0xFF);
        frame[5 + payload.length] = (byte) (crc & 0xFF);
        return frame;
    }
}

package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * GPS_RTCM_DATA (msgId=233, 基础 LEN=182 + 扩展 2) —— MAVLink 官方标准消息。
 * <p>
 * 用途：把 RTCM 差分改正数（来自 NTRIP caster 或本地基站）注入机载 GPS，
 * 由飞控做 RTK 解算。这是「厘米级定位」在软件侧唯一的入口——
 * 没有它，机载只能停留在 SINGLE 定位（2~5 m），
 * {@code GnssReceiver.setRtkCorrectionsReceived} 永远不会被置位。
 * <p>
 * <b>字段布局（小端）</b>：
 * <pre>
 * 偏移  字段              类型        说明
 * 0    flags            uint8       bit0=分片标志；bit1-2=分片号；bit3-7=序号
 * 1    len              uint8       data 的有效字节数（0~180）
 * 2    data             uint8[180]  RTCM 原始字节（可跨多个分片重组）
 * --- 以下为 MAVLink 2 扩展字段（v1 实现不发送，也不参与 CRC）---
 * 182  target_system    uint8       0 = 广播
 * 183  target_component uint8       0 = 广播
 * </pre>
 * <p>
 * <b>CRC_EXTRA = 35</b>。注意这个数只由前三个字段（flags / len / data）算出，
 * {@code target_system} / {@code target_component} 是扩展字段，<b>不参与 CRC_EXTRA</b>——
 * 理由与 {@link AutopilotVersion} 的 {@code uid2} 完全相同：只实现 v1 的那一端
 * 也必须能校验这个帧，而 v1 根本不认识扩展字段。
 * 已用 pymavlink 核对：{@code MAVLink_gps_rtcm_data_message.crc_extra == 35}、
 * {@code fieldnames == ['flags', 'len', 'data']}（扩展字段不在其中）。
 * <p>
 * <b>分片规则</b>（官方定义）：一条 RTCM 报文超过 180 字节时切成多片，
 * 每片带相同的 {@code sequenceId} 与递增的 {@code fragmentId}，
 * 飞控按 (sequenceId, fragmentId) 重组，丢任何一片则整条丢弃。
 * 因此本类<b>不</b>负责分片——分片是发送方的职责（见 {@code NtripRtcmForwarder}）。
 */
public final class GpsRtcmData extends MavlinkMessage {

    public static final int ID = 233;

    /** 基础载荷长度（不含扩展字段）。 */
    public static final int BASE_LEN = 182;

    /** 完整载荷长度（含 2 个扩展字段）。 */
    public static final int LEN = 184;

    /** data 字段容量。 */
    public static final int DATA_CAPACITY = 180;

    /** flags 的位定义。 */
    public static final int FLAG_FRAGMENTED = 0x01;

    public final int flags;
    public final int len;
    public final byte[] data;
    public final int targetSystem;
    public final int targetComponent;

    public GpsRtcmData(int flags, int len, byte[] data, int targetSystem, int targetComponent) {
        if (data == null) {
            throw new IllegalArgumentException("data 不能为 null（用空数组表示无数据）");
        }
        if (data.length > DATA_CAPACITY) {
            throw new IllegalArgumentException(
                    "data 超过 " + DATA_CAPACITY + " 字节，应先分片：" + data.length);
        }
        if (len < 0 || len > data.length) {
            throw new IllegalArgumentException(
                    "len=" + len + " 超出 data 实际长度 " + data.length);
        }
        this.flags = flags & 0xFF;
        this.len = len & 0xFF;
        this.data = data.clone();
        this.targetSystem = targetSystem & 0xFF;
        this.targetComponent = targetComponent & 0xFF;
    }

    /** 构造一条不分片、广播给所有系统的 RTCM 数据。 */
    public static GpsRtcmData broadcast(byte[] rtcm, int sequenceId) {
        int flags = (sequenceId & 0x1F) << 3;
        return new GpsRtcmData(flags, rtcm.length, rtcm, 0, 0);
    }

    /** flags.bit0：本条是否属于一个分片序列。 */
    public boolean fragmented() {
        return (flags & FLAG_FRAGMENTED) != 0;
    }

    /** flags.bit1-2：分片号（0~3）。 */
    public int fragmentId() {
        return (flags >> 1) & 0x03;
    }

    /** flags.bit3-7：序号（0~31），同一条 RTCM 的各分片相同。 */
    public int sequenceId() {
        return (flags >> 3) & 0x1F;
    }

    /** 有效数据（截到 len）。 */
    public byte[] payload() {
        byte[] out = new byte[len];
        System.arraycopy(data, 0, out, 0, len);
        return out;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, flags);
        PayloadCodec.putU8(buf, 1, len);
        System.arraycopy(data, 0, buf, 2, data.length);
        // 扩展字段写在基础载荷之后；v1 实现会忽略它们，CRC 也不含它们
        PayloadCodec.putU8(buf, BASE_LEN, targetSystem);
        PayloadCodec.putU8(buf, BASE_LEN + 1, targetComponent);
        return buf;
    }

    public static GpsRtcmData decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int flags = PayloadCodec.u8(b, 0);
        int len = PayloadCodec.u8(b, 1);
        byte[] data = new byte[DATA_CAPACITY];
        int available = Math.min(DATA_CAPACITY, Math.max(0, b.limit() - 2));
        for (int i = 0; i < available; i++) {
            data[i] = b.get(2 + i);
        }
        // 扩展字段可能被发送方截断（v1 或未实现扩展的 v2 端）⇒ 缺失时按 0 广播处理
        int ts = b.limit() > BASE_LEN ? (b.get(BASE_LEN) & 0xFF) : 0;
        int tc = b.limit() > BASE_LEN + 1 ? (b.get(BASE_LEN + 1) & 0xFF) : 0;
        return new GpsRtcmData(flags, Math.min(len, DATA_CAPACITY), data, ts, tc);
    }

    @Override
    public String toString() {
        return "GpsRtcmData{len=" + len + ", frag=" + fragmentId() + ", seq=" + sequenceId()
                + ", fragmented=" + fragmented() + "}";
    }
}

package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * THERMAL_HOTSPOT (msgId=30064, LEN=26) —— NexusSky M3 自定义扩展消息（P2 #19 条件③）。
 *
 * <p>逐热点像素坐标。{@link ThermalDataMsg}(30012) 只带聚合量（均值/极值/标准差/热点数
 * 加 sysid），像素坐标一个字节都没有——于是"热源在地面哪个位置"这个问题在链路层
 * 就丢了，云端 {@code GeolocationSolver} 有反投影能力却拿不到输入。
 * 本消息补上这个输入：把 {@code SimulatedThermalSource.detectHotspots} 产出的
 * (x, y, 峰值温度) 逐条下发（固定 3 条容量，超出记为 truncated）。
 *
 * <p><b>为什么不扩展 30012</b>：30012 的 LEN=24 已写死且 u16 reserved 只有 2 字节，
 * 塞不进 3×(x,y,temp)；扩展 LEN 会让 CRC_EXTRA 与字段签名同时变化，
 * 所有既有 30012 收发方（含 pymavlink 侧与 e2e 脚本）都要跟着改。
 * 30064+ 是 ROADMAP 划给"新增扩展消息"的增长段，另起 msgId 不动既有契约。
 *
 * <p>字段布局（小端）：
 * <pre>
 * 偏移  字段          类型   单位/精度
 * 0    sysid         u8     源飞机 sysid
 * 1    count         u8     本帧实际携带的热点数（0..MAX_HOTSPOTS）
 * 2    truncated     u8     detectHotspots 总数超过容量 = 1，否则 0
 * 3    reserved      u8     保留（0）
 * 4    timestamp     u32    采集时间戳（ms），与同帧 30012 同源
 * 8    hotspot0_x    i16    热点 0 像素 x
 * 10   hotspot0_y    i16    热点 0 像素 y
 * 12   hotspot0_t    i16    热点 0 峰值温度 ×10（0.1℃）
 * 14   hotspot1_x    i16    热点 1 像素 x
 * 16   hotspot1_y    i16    热点 1 像素 y
 * 18   hotspot1_t    i16    热点 1 峰值温度 ×10
 * 20   hotspot2_x    i16    热点 2 像素 x
 * 22   hotspot2_y    i16    热点 2 像素 y
 * 24   hotspot2_t    i16    热点 2 峰值温度 ×10
 * </pre>
 * CRC_EXTRA 由 {@code scripts/mavlink-crc-extra-gen.py} 按官方算法重算，不是人工序数。
 */
public final class ThermalHotspotMsg extends MavlinkMessage {

    /** 单帧最多携带的热点数（超出置 truncated，不静默丢弃）。 */
    public static final int MAX_HOTSPOTS = 3;

    /**
     * 8 字节头 + {@link #MAX_HOTSPOTS} × 6 字节（每条 3 个 i16）= 26。
     * <p>写成字面量而非 {@code 8 + MAX_HOTSPOTS * 6}：CRC 生成器
     * （{@code scripts/mavlink-crc-extra-gen.py}）只认字面常量，表达式会让它把
     * LEN 读成 8，报出一堆"字段合计超过 LEN"的假告警。二者相等由单测守着。
     */
    public static final int LEN = 26;

    public static final int ID = 30064;

    /** CRC_EXTRA = 111，由 scripts/mavlink-crc-extra-gen.py 按字段签名重算（非人工序数）。 */
    public static final int CRC_EXTRA = 111;

    public final int sysid;
    public final int count;
    public final boolean truncated;
    public final long timestamp;
    /** 每条 3 个 i16：x、y、峰值温度×10。长度 == count。 */
    public final int[][] hotspots;

    /**
     * @param hotspots 每条 {x, y, peakTempDeciCelsius}，长度不得超过 {@link #MAX_HOTSPOTS}
     */
    public ThermalHotspotMsg(int sysid, long timestamp, boolean truncated, int[][] hotspots) {
        if (hotspots.length > MAX_HOTSPOTS) {
            throw new IllegalArgumentException("too many hotspots: " + hotspots.length);
        }
        for (int[] h : hotspots) {
            if (h.length != 3) {
                throw new IllegalArgumentException("hotspot must be [x, y, deciTemp]");
            }
        }
        this.sysid = sysid;
        this.timestamp = timestamp;
        this.truncated = truncated;
        this.count = hotspots.length;
        this.hotspots = new int[count][];
        for (int i = 0; i < count; i++) {
            this.hotspots[i] = new int[]{hotspots[i][0], hotspots[i][1], hotspots[i][2]};
        }
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, sysid);
        PayloadCodec.putU8(buf, 1, count);
        PayloadCodec.putU8(buf, 2, truncated ? 1 : 0);
        PayloadCodec.putU8(buf, 3, 0);
        PayloadCodec.putU32(buf, 4, timestamp);
        for (int i = 0; i < count; i++) {
            int off = 8 + i * 6;
            PayloadCodec.putI16(buf, off, hotspots[i][0]);
            PayloadCodec.putI16(buf, off + 2, hotspots[i][1]);
            PayloadCodec.putI16(buf, off + 4, hotspots[i][2]);
        }
        return buf;
    }

    public static ThermalHotspotMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        int sysid = len > 0 ? PayloadCodec.u8(b, 0) : 0;
        int count = len > 1 ? PayloadCodec.u8(b, 1) : 0;
        boolean truncated = len > 2 && PayloadCodec.u8(b, 2) != 0;
        long ts = len > 7 ? PayloadCodec.u32(b, 4) : 0L;
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int off = 8 + i * 6;
            if (off + 6 > len) {
                break;   // 截断帧：拿不满整条的丢弃，不猜半个热点
            }
            out.add(new int[]{
                    PayloadCodec.i16(b, off),
                    PayloadCodec.i16(b, off + 2),
                    PayloadCodec.i16(b, off + 4)});
        }
        return new ThermalHotspotMsg(sysid, ts, truncated, out.toArray(new int[0][]));
    }
}

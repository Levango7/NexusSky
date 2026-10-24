package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * AUTOPILOT_VERSION (msgId=148, LEN=78) —— MAVLink 官方标准消息（PX4 / ArduPilot 均会
 * 按请求或周期下发），承载飞控固件版本与能力位。接真机后，GCS 上"这是哪版固件、
 * 支不支持某项能力"只有这一条消息能回答。
 * <p>
 * <b>本条踩过并记下的一个协议细节：</b>MAVLink 2 的<b>扩展字段</b>（本消息的 {@code uid2}，
 * 位于 wire 偏移 60 起、18 字节）<b>不参与 CRC_EXTRA 计算</b>——否则只实现了 v1 的那一端
 * 无法校验帧。因此 CRC_EXTRA=178 只由前 11 个字段（60 字节）算出，把 {@code uid2} 一起喂进
 * 算法会得到 39（错）。本项目 {@code MavlinkMessageChecksum} 的算法本身与官方一致，
 * 调用方只需按同样口径喂字段（{@code MavlinkCrcExtraTest.SIGNATURES} 里 148 的签名
 * 也只列前 11 个）。
 * <p>
 * 字段布局（小端，偏移来自 pymavlink 生成的官方帧反推）：
 * <pre>
 * 偏移  字段                      类型     说明
 * 0    capabilities              uint64  MAV_PROTOCOL_CAPABILITY 位掩码
 * 8    uid                       uint64   硬件 UID（与 uid2 等价，旧字段）
 * 16   flight_sw_version         uint32   固件版本，PX4 口径 (major&lt;&lt;24)|(minor&lt;&lt;16)|patch
 * 20   middleware_sw_version     uint32   中间件版本
 * 24   os_sw_version             uint32   操作系统版本
 * 28   board_version             uint32   板卡版本（低 8 位可为硅片 ID）
 * 32   vendor_id                 uint16   厂商 ID
 * 34   product_id                uint16   产品 ID
 * 36   flight_custom_version     uint8[8] 通常是 git hash 前 8 字节
 * 44   middleware_custom_version uint8[8]
 * 52   os_custom_version         uint8[8]
 * 60   uid2                      uint8[18] 扩展字段（MAVLink2），取代 uid
 * </pre>
 * CRC_EXTRA = 178（由官方 message_checksum 算法对<b>前 11 个字段</b>计算，已与 pymavlink 核对）。
 */
public final class AutopilotVersion extends MavlinkMessage {

    public static final int ID = 148;
    public static final int LEN = 78;

    public final long capabilities;
    public final long uid;
    public final int flightSwVersion;
    public final int middlewareSwVersion;
    public final int osSwVersion;
    public final int boardVersion;
    public final int vendorId;
    public final int productId;
    public final byte[] flightCustomVersion = new byte[8];
    public final byte[] middlewareCustomVersion = new byte[8];
    public final byte[] osCustomVersion = new byte[8];
    /** 扩展字段：不参与 CRC_EXTRA（见类注释）。 */
    public final byte[] uid2 = new byte[18];

    public AutopilotVersion(long capabilities, long uid,
                            int flightSwVersion, int middlewareSwVersion,
                            int osSwVersion, int boardVersion,
                            int vendorId, int productId,
                            byte[] flightCustomVersion, byte[] middlewareCustomVersion,
                            byte[] osCustomVersion, byte[] uid2) {
        this.capabilities = capabilities;
        this.uid = uid;
        this.flightSwVersion = flightSwVersion;
        this.middlewareSwVersion = middlewareSwVersion;
        this.osSwVersion = osSwVersion;
        this.boardVersion = boardVersion;
        this.vendorId = vendorId & 0xFFFF;
        this.productId = productId & 0xFFFF;
        if (flightCustomVersion != null) {
            copyInto(flightCustomVersion, this.flightCustomVersion);
        }
        if (middlewareCustomVersion != null) {
            copyInto(middlewareCustomVersion, this.middlewareCustomVersion);
        }
        if (osCustomVersion != null) {
            copyInto(osCustomVersion, this.osCustomVersion);
        }
        if (uid2 != null) {
            copyInto(uid2, this.uid2);
        }
    }

    /** 拷贝到定长字段：源短了按 0 补齐，源长了截断（都不抛）。 */
    private static void copyInto(byte[] src, byte[] dst) {
        System.arraycopy(src, 0, dst, 0, Math.min(src.length, dst.length));
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU64(buf, 0, capabilities);
        PayloadCodec.putU64(buf, 8, uid);
        PayloadCodec.putU32(buf, 16, flightSwVersion);
        PayloadCodec.putU32(buf, 20, middlewareSwVersion);
        PayloadCodec.putU32(buf, 24, osSwVersion);
        PayloadCodec.putU32(buf, 28, boardVersion);
        PayloadCodec.putU16(buf, 32, vendorId);
        PayloadCodec.putU16(buf, 34, productId);
        putBytes(buf, 36, flightCustomVersion);
        putBytes(buf, 44, middlewareCustomVersion);
        putBytes(buf, 52, osCustomVersion);
        putBytes(buf, 60, uid2);
        return buf;
    }

    public static AutopilotVersion decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        return new AutopilotVersion(
                PayloadCodec.u64(b, 0),
                PayloadCodec.u64(b, 8),
                (int) PayloadCodec.u32(b, 16),
                (int) PayloadCodec.u32(b, 20),
                (int) PayloadCodec.u32(b, 24),
                (int) PayloadCodec.u32(b, 28),
                PayloadCodec.u16(b, 32),
                PayloadCodec.u16(b, 34),
                getBytes(b, 36, 8),
                getBytes(b, 44, 8),
                getBytes(b, 52, 8),
                getBytes(b, 60, 18));
    }

    /**
     * 固件版本号 → "major.minor.patch"（PX4 口径：高 8 位 major、次 8 位 minor、低 16 位 patch）。
     * 全 0 时返回 {@code null}（未上报版本），由调用方决定显示什么。
     */
    public String firmwareVersion() {
        if (flightSwVersion == 0) {
            return null;
        }
        int major = (flightSwVersion >>> 24) & 0xFF;
        int minor = (flightSwVersion >>> 16) & 0xFF;
        int patch = flightSwVersion & 0xFFFF;
        return major + "." + minor + "." + patch;
    }

    private static void putBytes(byte[] buf, int off, byte[] arr) {
        for (int i = 0; i < arr.length; i++) {
            buf[off + i] = arr[i];
        }
    }

    private static byte[] getBytes(ByteBuffer b, int off, int len) {
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = b.get(off + i);
        }
        return out;
    }

    @Override
    public String toString() {
        return "AutopilotVersion{flightSw=" + flightSwVersion
                + " (" + firmwareVersion() + "), vendor=" + vendorId
                + ", product=" + productId + "}";
    }
}

package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * FLIGHT_RESTRICTION (msgId=464, LEN=可变 8+n×8) —— NexusSky M8 复杂地形适配自定义扩展消息。
 * <p>
 * 承载飞行限制通告：限制类型 + 限制值 + 限制区域多边形顶点列表，
 * 由 drone-sim {@code FlightConstraintChecker} 产出并通告至飞控与地面站（FR-30）。
 * <p>
 * 字段布局（小端，可变长度 8 + vertexCount × 8）：
 * <pre>
 * 偏移  字段              类型   说明
 * 0    restrictionType  u8    0=NO_FLY, 1=ALTITUDE_LIMIT, 2=WIND_SHEAR_WARN
 * 1    limitValue       f32   限制值（ALTITUDE_LIMIT 时为限高 m，其余 0）
 * 5    vertexCount      u8    多边形顶点数（≥3）
 * 6    reserved         u16   保留（0）
 * 8    area[n]          每项 8 字节：lat i32 + lon i32（1E7 度）
 * </pre>
 * 内嵌 {@link GeoPoint} record 承载每个多边形顶点。
 * CRC_EXTRA = 244（M8 自定义扩展）。
 */
public final class FlightRestrictionMsg extends MavlinkMessage {

    public static final int ID = 464;
    /** 固定头部长度。 */
    public static final int HEADER_LEN = 8;
    /** 每顶点项长度。 */
    public static final int VERTEX_LEN = 8;
    /** 顶点数上限。 */
    public static final int MAX_VERTICES = 255;
    /** LEN 字段填 -1 表示可变长度。 */
    public static final int LEN = -1;
    public static final int CRC_EXTRA = 244;

    /** 限制类型枚举序数。 */
    public static final int TYPE_NO_FLY = 0;
    public static final int TYPE_ALTITUDE_LIMIT = 1;
    public static final int TYPE_WIND_SHEAR_WARN = 2;

    /** 内嵌地理顶点：lat + lon（1E7 度）。 */
    public record GeoPoint(int latE7, int lonE7) {
        /** 编码到 buf 的指定偏移（8 字节）。 */
        void encode(byte[] buf, int offset) {
            PayloadCodec.putI32(buf, offset, latE7);
            PayloadCodec.putI32(buf, offset + 4, lonE7);
        }

        /** 从 buf 的指定偏移解码（8 字节）。 */
        static GeoPoint decode(ByteBuffer b, int offset) {
            return new GeoPoint(
                    PayloadCodec.i32(b, offset),
                    PayloadCodec.i32(b, offset + 4));
        }
    }

    public final int restrictionType;       // 0/1/2
    public final float limitValue;           // ALTITUDE_LIMIT 时为限高 m
    public final List<GeoPoint> area;        // 多边形顶点

    public FlightRestrictionMsg(int restrictionType, float limitValue, List<GeoPoint> area) {
        this.restrictionType = restrictionType;
        this.limitValue = limitValue;
        this.area = area == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(area));
    }

    /** 顶点数。 */
    public int vertexCount() {
        return area.size();
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        int n = Math.min(area.size(), MAX_VERTICES);
        byte[] buf = PayloadCodec.alloc(HEADER_LEN + n * VERTEX_LEN);
        PayloadCodec.putU8(buf, 0, restrictionType);
        PayloadCodec.putF32(buf, 1, limitValue);
        PayloadCodec.putU8(buf, 5, n);
        PayloadCodec.putU16(buf, 6, 0);
        for (int i = 0; i < n; i++) {
            area.get(i).encode(buf, HEADER_LEN + i * VERTEX_LEN);
        }
        return buf;
    }

    /** 从帧解码；payload 短于所需时容忍（缺失字段填 0 / 截断顶点列表）。 */
    public static FlightRestrictionMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        int restrictionType = len > 0 ? PayloadCodec.u8(b, 0) : 0;
        float limitValue = len > 4 ? PayloadCodec.f32(b, 1) : 0f;
        int declaredCount = len > 5 ? PayloadCodec.u8(b, 5) : 0;
        // 实际可解码的顶点数：取声明值与可用字节计算值的较小者
        int available = Math.max(0, (len - HEADER_LEN) / VERTEX_LEN);
        int n = Math.min(declaredCount, available);
        List<GeoPoint> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(GeoPoint.decode(b, HEADER_LEN + i * VERTEX_LEN));
        }
        return new FlightRestrictionMsg(restrictionType, limitValue, list);
    }

    @Override
    public String toString() {
        return "FlightRestrictionMsg{type=" + restrictionType
                + ", limit=" + limitValue
                + ", vertices=" + area.size() + "}";
    }
}
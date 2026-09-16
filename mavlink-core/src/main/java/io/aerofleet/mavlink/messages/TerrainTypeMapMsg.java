package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TERRAIN_TYPE_MAP (msgId=462, LEN=可变 14+n×1) —— NexusSky M8 复杂地形适配自定义扩展消息。
 * <p>
 * 承载地形分区图下发：原点经纬度 + 网格分辨率 + 网格行列数 + 每网格地形类型序数（0-8），
 * 由 drone-sim {@code TerrainClassifier} 产出于灾区建图后下发至 cloud-backend 与其他节点（FR-28）。
 * <p>
 * 字段布局（小端，可变长度 14 + mapWidth × mapHeight）：
 * <pre>
 * 偏移  字段              类型   说明
 * 0    mapOriginLat     i32   原点纬度（1E7 度）
 * 4    mapOriginLon     i32   原点经度（1E7 度）
 * 8    gridResolution   u16   网格边长（m）
 * 10   mapWidth         u16   网格列数
 * 12   mapHeight        u16   网格行数
 * 14   gridCells[n]     u8[]  每网格 TerrainType 序数（0-8），n = mapWidth × mapHeight
 * </pre>
 * CRC_EXTRA = 242（M8 自定义扩展）。
 */
public final class TerrainTypeMapMsg extends MavlinkMessage {

    public static final int ID = 462;
    /** 固定头部长度。 */
    public static final int HEADER_LEN = 14;
    /** 网格数上限（u16 × u16 = 65535 × 65535，但实际受 payload 长度限制）。 */
    public static final int MAX_CELLS = 65535;
    /** LEN 字段填 -1 表示可变长度。 */
    public static final int LEN = -1;
    public static final int CRC_EXTRA = 242;

    public final int mapOriginLat;       // 1E7 度
    public final int mapOriginLon;       // 1E7 度
    public final int gridResolution;     // m
    public final int mapWidth;           // 网格列数
    public final int mapHeight;          // 网格行数
    /** 每网格地形类型序数（0-8），长度 = mapWidth × mapHeight。 */
    public final List<Integer> gridCells;

    public TerrainTypeMapMsg(int mapOriginLat, int mapOriginLon, int gridResolution,
                             int mapWidth, int mapHeight, List<Integer> gridCells) {
        this.mapOriginLat = mapOriginLat;
        this.mapOriginLon = mapOriginLon;
        this.gridResolution = gridResolution;
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        this.gridCells = gridCells == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(gridCells));
    }

    /** 网格数。 */
    public int cellCount() {
        return gridCells.size();
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        int n = Math.min(gridCells.size(), MAX_CELLS);
        byte[] buf = PayloadCodec.alloc(HEADER_LEN + n);
        PayloadCodec.putI32(buf, 0, mapOriginLat);
        PayloadCodec.putI32(buf, 4, mapOriginLon);
        PayloadCodec.putU16(buf, 8, gridResolution);
        PayloadCodec.putU16(buf, 10, mapWidth);
        PayloadCodec.putU16(buf, 12, mapHeight);
        for (int i = 0; i < n; i++) {
            PayloadCodec.putU8(buf, HEADER_LEN + i, gridCells.get(i));
        }
        return buf;
    }

    /** 从帧解码；payload 短于所需时容忍（缺失字段填 0 / 截断 gridCells）。 */
    public static TerrainTypeMapMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        int mapOriginLat = len > 3 ? PayloadCodec.i32(b, 0) : 0;
        int mapOriginLon = len > 7 ? PayloadCodec.i32(b, 4) : 0;
        int gridResolution = len > 9 ? PayloadCodec.u16(b, 8) : 0;
        int mapWidth = len > 11 ? PayloadCodec.u16(b, 10) : 0;
        int mapHeight = len > 13 ? PayloadCodec.u16(b, 12) : 0;
        int available = Math.max(0, len - HEADER_LEN);
        List<Integer> cells = new ArrayList<>(available);
        for (int i = 0; i < available; i++) {
            cells.add(PayloadCodec.u8(b, HEADER_LEN + i));
        }
        return new TerrainTypeMapMsg(mapOriginLat, mapOriginLon, gridResolution,
                mapWidth, mapHeight, cells);
    }

    @Override
    public String toString() {
        return "TerrainTypeMapMsg{origin=(" + mapOriginLat + "," + mapOriginLon
                + "), res=" + gridResolution + "m, " + mapWidth + "x" + mapHeight
                + ", cells=" + gridCells.size() + "}";
    }
}
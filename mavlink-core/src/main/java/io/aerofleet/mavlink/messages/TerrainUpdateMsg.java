package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TERRAIN_UPDATE (msgId=463, LEN=可变 9+n×4) —— NexusSky M8 复杂地形适配自定义扩展消息。
 * <p>
 * 承载地形变更通知：地形版本号 + 变更原因 + 受影响网格列表（网格索引 + 新地形类型），
 * 由 drone-sim {@code TerrainChangeMonitor} 在灾害事件触发后广播至所有相关模块（FR-29）。
 * <p>
 * 字段布局（小端，可变长度 9 + affectedCount × 4）：
 * <pre>
 * 偏移  字段              类型   说明
 * 0    terrainVersion   u32   地形数据版本号（单调递增）
 * 4    changeReason     u8    变更原因（0=EARTHQUAKE, 1=LANDSLIDE, 2=FIRE）
 * 5    affectedCount    u16   受影响网格数
 * 7    reserved         u16   保留（0）
 * 9    affectedCells[n] 每项 4 字节：
 *                          gridIndex u16 + newTerrainType u8 + reserved u8
 * </pre>
 * 内嵌 {@link AffectedCell} record 承载每个受影响网格项。
 * CRC_EXTRA = 243（M8 自定义扩展）。
 */
public final class TerrainUpdateMsg extends MavlinkMessage {

    public static final int ID = 463;
    /** 固定头部长度。 */
    public static final int HEADER_LEN = 9;
    /** 每受影响网格项长度。 */
    public static final int CELL_LEN = 4;
    /** 受影响网格数上限。 */
    public static final int MAX_CELLS = 65535;
    /** LEN 字段填 -1 表示可变长度。 */
    public static final int LEN = -1;
    public static final int CRC_EXTRA = 243;

    /** 内嵌受影响网格项：gridIndex + newTerrainType。 */
    public record AffectedCell(int gridIndex, int newTerrainType) {
        /** 编码到 buf 的指定偏移（4 字节）。 */
        void encode(byte[] buf, int offset) {
            PayloadCodec.putU16(buf, offset, gridIndex);
            PayloadCodec.putU8(buf, offset + 2, newTerrainType);
            PayloadCodec.putU8(buf, offset + 3, 0);
        }

        /** 从 buf 的指定偏移解码（4 字节）。 */
        static AffectedCell decode(ByteBuffer b, int offset) {
            return new AffectedCell(
                    PayloadCodec.u16(b, offset),
                    PayloadCodec.u8(b, offset + 2));
        }
    }

    public final long terrainVersion;       // 单调递增
    public final int changeReason;          // 0=EARTHQUAKE, 1=LANDSLIDE, 2=FIRE
    public final List<AffectedCell> affectedCells;

    public TerrainUpdateMsg(long terrainVersion, int changeReason,
                            List<AffectedCell> affectedCells) {
        this.terrainVersion = terrainVersion;
        this.changeReason = changeReason;
        this.affectedCells = affectedCells == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(affectedCells));
    }

    /** 受影响网格数。 */
    public int affectedCount() {
        return affectedCells.size();
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        int n = Math.min(affectedCells.size(), MAX_CELLS);
        byte[] buf = PayloadCodec.alloc(HEADER_LEN + n * CELL_LEN);
        PayloadCodec.putU32(buf, 0, terrainVersion);
        PayloadCodec.putU8(buf, 4, changeReason);
        PayloadCodec.putU16(buf, 5, n);
        PayloadCodec.putU16(buf, 7, 0);
        for (int i = 0; i < n; i++) {
            affectedCells.get(i).encode(buf, HEADER_LEN + i * CELL_LEN);
        }
        return buf;
    }

    /** 从帧解码；payload 短于所需时容忍（缺失字段填 0 / 截断列表）。 */
    public static TerrainUpdateMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        long terrainVersion = len > 3 ? PayloadCodec.u32(b, 0) : 0L;
        int changeReason = len > 4 ? PayloadCodec.u8(b, 4) : 0;
        int declaredCount = len > 6 ? PayloadCodec.u16(b, 5) : 0;
        // 实际可解码的项数：取声明值与可用字节计算值的较小者
        int available = Math.max(0, (len - HEADER_LEN) / CELL_LEN);
        int n = Math.min(declaredCount, available);
        List<AffectedCell> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(AffectedCell.decode(b, HEADER_LEN + i * CELL_LEN));
        }
        return new TerrainUpdateMsg(terrainVersion, changeReason, list);
    }

    @Override
    public String toString() {
        return "TerrainUpdateMsg{version=" + terrainVersion
                + ", reason=" + changeReason
                + ", affected=" + affectedCells.size() + "}";
    }
}
package io.aerofleet.sim.terrain;

import io.aerofleet.sim.GeoUtil;
import io.aerofleet.sim.TerrainModel;

import java.util.ArrayList;
import java.util.List;

/**
 * 地形分区图（FR-03, FR-05, §7.3）。
 * <p>
 * 将灾区划分为网格后、每个网格标注一种 {@link TerrainType} 的二维地图，
 * 携带网格分辨率与原点坐标。线程安全：读操作无锁，写操作加锁 + 版本号递增。
 * <p>
 * 高程查询委托给既有 {@link TerrainModel}（DFX 5.5.3 高程兼容）。
 */
public final class TerrainGrid {

    private final TerrainType[] cells;      // mapWidth × mapHeight
    private final int mapWidth;
    private final int mapHeight;
    private final double gridResolution;    // m
    private final double originLat;
    private final double originLon;
    private final TerrainModel terrainModel;  // 高程查询委托（DFX 5.5.3）
    private volatile long version;

    /**
     * @param cells           网格地形类型数组，长度 = mapWidth × mapHeight
     * @param mapWidth        网格列数
     * @param mapHeight       网格行数
     * @param gridResolution  网格边长 (m)
     * @param originLat       原点纬度
     * @param originLon       原点经度
     * @param terrainModel    高程模型（可为 null，flat 兜底）
     */
    public TerrainGrid(TerrainType[] cells, int mapWidth, int mapHeight,
                       double gridResolution, double originLat, double originLon,
                       TerrainModel terrainModel) {
        if (cells == null || cells.length != mapWidth * mapHeight) {
            throw new IllegalArgumentException(
                    "cells length must equal mapWidth * mapHeight");
        }
        if (gridResolution <= 0) {
            throw new IllegalArgumentException("gridResolution must be positive");
        }
        this.cells = cells.clone();
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        this.gridResolution = gridResolution;
        this.originLat = originLat;
        this.originLon = originLon;
        this.terrainModel = terrainModel;
        this.version = 0;
    }

    /** 当前地形版本号（变更后递增）。 */
    public long version() {
        return version;
    }

    public int mapWidth() {
        return mapWidth;
    }

    public int mapHeight() {
        return mapHeight;
    }

    public double gridResolution() {
        return gridResolution;
    }

    public double originLat() {
        return originLat;
    }

    public double originLon() {
        return originLon;
    }

    /** 查询某网格的地形类型（FR-05）。 */
    public TerrainType typeAtCell(int gridX, int gridY) {
        if (gridX < 0 || gridX >= mapWidth || gridY < 0 || gridY >= mapHeight) {
            return TerrainType.FLAT;  // 越界 → FLAT（保守默认）
        }
        return cells[gridY * mapWidth + gridX];
    }

    /**
     * 查询某坐标的地形类型（FR-05），查询精度为网格分辨率级别。
     * <p>
     * 坐标 → 网格映射：使用 {@link GeoUtil} 将经纬度转为相对原点的 north/east 米，
     * 再除以 {@code gridResolution} 得网格索引。
     */
    public TerrainType typeAt(double lat, double lon) {
        double northM = GeoUtil.north(originLat, originLon, lat, lon);
        double eastM = GeoUtil.east(originLat, originLon, lat, lon);
        int gridX = (int) Math.floor(eastM / gridResolution);
        int gridY = (int) Math.floor(northM / gridResolution);
        return typeAtCell(gridX, gridY);
    }

    /** 网格索引 → 经纬度中心。 */
    public double[] cellCenter(int gridX, int gridY) {
        double eastM = (gridX + 0.5) * gridResolution;
        double northM = (gridY + 0.5) * gridResolution;
        double lat = GeoUtil.latOf(originLat, originLon, northM, eastM);
        double lon = GeoUtil.lonOf(originLat, originLon, northM, eastM);
        return new double[]{lat, lon};
    }

    /**
     * 路径沿线地形类型序列（供 RF 估算用）。
     * <p>
     * 在路径上均匀采样 {@code samples} 个点，返回每点的地形类型。
     *
     * @param lat1    起点纬度
     * @param lon1    起点经度
     * @param lat2    终点纬度
     * @param lon2    终点经度
     * @param samples 采样数（≥1）
     * @return 地形类型列表，长度 = samples + 1
     */
    public List<TerrainType> typesAlongPath(double lat1, double lon1,
                                            double lat2, double lon2, int samples) {
        if (samples < 1) {
            samples = 1;
        }
        List<TerrainType> types = new ArrayList<>(samples + 1);
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            double lat = lat1 + t * (lat2 - lat1);
            double lon = lon1 + t * (lon2 - lon1);
            types.add(typeAt(lat, lon));
        }
        return types;
    }

    /**
     * 更新网格地形类型（变更用，FR-22~24）。
     * <p>
     * 线程安全：加锁修改 + 版本号递增。
     *
     * @param gridIndex 网格线性索引（0 ~ mapWidth*mapHeight-1）
     * @param newType   新地形类型
     * @return 新版本号
     */
    public synchronized long updateCell(int gridIndex, TerrainType newType) {
        if (gridIndex < 0 || gridIndex >= cells.length) {
            return version;  // 越界忽略
        }
        cells[gridIndex] = newType;
        return ++version;
    }

    /** 更新网格地形类型（gridX/gridY 版本）。 */
    public synchronized long updateCell(int gridX, int gridY, TerrainType newType) {
        if (gridX < 0 || gridX >= mapWidth || gridY < 0 || gridY >= mapHeight) {
            return version;
        }
        return updateCell(gridY * mapWidth + gridX, newType);
    }

    /** 高程查询委托给 TerrainModel（DFX 5.5.3）。 */
    public double elevationAt(double north, double east) {
        return terrainModel != null ? terrainModel.elevationAt(north, east) : 0;
    }

    /** 网格总数。 */
    public int cellCount() {
        return cells.length;
    }

    /** 网格线性索引 → gridX。 */
    public int gridXOf(int gridIndex) {
        return gridIndex % mapWidth;
    }

    /** 网格线性索引 → gridY。 */
    public int gridYOf(int gridIndex) {
        return gridIndex / mapWidth;
    }
}
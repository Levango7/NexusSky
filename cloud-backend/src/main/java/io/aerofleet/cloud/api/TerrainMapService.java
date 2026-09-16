package io.aerofleet.cloud.api;

import io.aerofleet.mavlink.messages.FlightRestrictionMsg;
import io.aerofleet.mavlink.messages.TerrainTypeMapMsg;
import io.aerofleet.mavlink.messages.TerrainUpdateMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 地形图存储服务（M8 复杂地形适配，FR-31）。
 * <p>
 * 接收各节点上报的 {@link TerrainTypeMapMsg} / {@link TerrainUpdateMsg} / {@link FlightRestrictionMsg}，
 * 维护全局地形图快照、飞行限制区列表与变更历史，提供只读查询。
 * <p>
 * 内部 {@link ConcurrentHashMap}，线程安全。version 递增标记地形变化。
 */
@Service
public class TerrainMapService {

    private static final Logger log = LoggerFactory.getLogger(TerrainMapService.class);

    /** 当前地形图快照（sysid → snapshot）。 */
    private final ConcurrentHashMap<Integer, TerrainMapSnapshot> maps = new ConcurrentHashMap<>();
    /** 飞行限制区列表（sysid → restrictions）。 */
    private final ConcurrentHashMap<Integer, List<FlightRestrictionSnapshot>> restrictions = new ConcurrentHashMap<>();
    /** 变更历史（线程安全列表）。 */
    private final List<TerrainChangeRecord> changeHistory = Collections.synchronizedList(new ArrayList<>());
    /** 地形版本号（每次变化递增）。 */
    private final AtomicLong version = new AtomicLong(0);
    /** 变更历史上限。 */
    private static final int MAX_HISTORY = 1000;

    /**
     * 接收地形图上报（由 TelemetryIngestService 调用）。
     *
     * @param sysid 上报节点 sysid
     * @param msg   TerrainTypeMapMsg
     */
    public void onTerrainTypeMap(int sysid, TerrainTypeMapMsg msg) {
        TerrainMapSnapshot snapshot = new TerrainMapSnapshot(
                version.incrementAndGet(),
                msg.mapWidth, msg.mapHeight,
                msg.gridResolution,
                msg.mapOriginLat / 1e7, msg.mapOriginLon / 1e7,
                msg.gridCells.stream().mapToInt(Integer::intValue).toArray(),
                System.currentTimeMillis());
        maps.put(sysid, snapshot);
        log.debug("terrain map updated: sysid={} cells={}", sysid, msg.cellCount());
    }

    /**
     * 接收地形变更（由 TelemetryIngestService 调用）。
     *
     * @param sysid 上报节点 sysid
     * @param msg   TerrainUpdateMsg
     */
    public void onTerrainUpdate(int sysid, TerrainUpdateMsg msg) {
        List<int[]> affected = new ArrayList<>(msg.affectedCells.size());
        for (TerrainUpdateMsg.AffectedCell cell : msg.affectedCells) {
            affected.add(new int[]{cell.gridIndex(), cell.newTerrainType()});
        }
        TerrainChangeRecord record = new TerrainChangeRecord(
                msg.terrainVersion, msg.changeReason, affected, System.currentTimeMillis());
        changeHistory.add(record);
        while (changeHistory.size() > MAX_HISTORY) {
            changeHistory.remove(0);
        }
        version.set(Math.max(version.get(), msg.terrainVersion));
        log.debug("terrain update: sysid={} version={} affected={}",
                sysid, msg.terrainVersion, msg.affectedCount());
    }

    /**
     * 接收飞行限制（由 TelemetryIngestService 调用）。
     *
     * @param sysid 上报节点 sysid
     * @param msg   FlightRestrictionMsg
     */
    public void onFlightRestriction(int sysid, FlightRestrictionMsg msg) {
        List<double[]> polygon = new ArrayList<>(msg.area.size());
        for (FlightRestrictionMsg.GeoPoint p : msg.area) {
            polygon.add(new double[]{p.latE7() / 1e7, p.lonE7() / 1e7});
        }
        FlightRestrictionSnapshot snapshot = new FlightRestrictionSnapshot(
                msg.restrictionType, msg.limitValue, polygon, System.currentTimeMillis());
        restrictions.computeIfAbsent(sysid, k -> Collections.synchronizedList(new ArrayList<>())).add(snapshot);
        log.debug("flight restriction: sysid={} type={} vertices={}",
                sysid, msg.restrictionType, msg.vertexCount());
    }

    /** 获取所有节点的地形图快照。 */
    public ConcurrentHashMap<Integer, TerrainMapSnapshot> getAllMaps() {
        return maps;
    }

    /** 获取当前地形图（合并所有节点，取最新版本）。 */
    public TerrainMapSnapshot getCurrentMap() {
        TerrainMapSnapshot latest = null;
        for (TerrainMapSnapshot s : maps.values()) {
            if (latest == null || s.version > latest.version) {
                latest = s;
            }
        }
        return latest;
    }

    /** 获取所有飞行限制区列表。 */
    public List<FlightRestrictionSnapshot> getRestrictions() {
        List<FlightRestrictionSnapshot> all = new ArrayList<>();
        for (List<FlightRestrictionSnapshot> list : restrictions.values()) {
            all.addAll(list);
        }
        return all;
    }

    /** 获取变更历史（分页）。 */
    public List<TerrainChangeRecord> getChangeHistory(int offset, int limit) {
        synchronized (changeHistory) {
            int size = changeHistory.size();
            int from = Math.min(offset, size);
            int to = Math.min(from + limit, size);
            return new ArrayList<>(changeHistory.subList(from, to));
        }
    }

    /** 当前地形版本号。 */
    public long currentVersion() {
        return version.get();
    }

    /** 地形图快照。 */
    public record TerrainMapSnapshot(
            long version,
            int mapWidth,
            int mapHeight,
            double gridResolution,
            double originLat,
            double originLon,
            int[] gridCells,
            long timestamp
    ) {
    }

    /** 飞行限制区快照。 */
    public record FlightRestrictionSnapshot(
            int restrictionType,
            double limitValue,
            List<double[]> areaPolygon,
            long timestamp
    ) {
    }

    /** 地形变更记录。 */
    public record TerrainChangeRecord(
            long terrainVersion,
            int changeReason,
            List<int[]> affectedCells,
            long timestamp
    ) {
    }
}
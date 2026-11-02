package io.aerofleet.cloud.api.service;

import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.mavlink.messages.FlightRestrictionMsg;
import io.aerofleet.mavlink.messages.TerrainTypeMapMsg;
import io.aerofleet.mavlink.messages.TerrainUpdateMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
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

    /**
     * 单节点限制区保留条数上限。
     * <p>
     * 一条 30001 消息只描述**一个**限制区，同一节点的多个区本来就靠多条消息累积
     * （所以这里不能像 {@link #maps} 那样按 sysid 覆盖——那会把同节点的其它区丢掉）。
     * 会无限增长的是另一种情况：节点**周期性重报同一批区**，每次都原样再追加一遍。
     * 因此按「内容去重 + 上限兜底」两道闸收口，累加语义不变。
     */
    static final int MAX_RESTRICTIONS_PER_NODE = 200;
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
        synchronized (changeHistory) {
            while (changeHistory.size() > MAX_HISTORY) {
                changeHistory.remove(0);
            }
        }
        version.accumulateAndGet(msg.terrainVersion, Math::max);
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
        List<FlightRestrictionSnapshot> list = restrictions.computeIfAbsent(
                sysid, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            if (!hasEquivalentRestriction(list, snapshot)) {
                list.add(snapshot);
                // 上限兜底：即便内容都不重复（节点真的在不断刷新区），也不允许无界增长
                while (list.size() > MAX_RESTRICTIONS_PER_NODE) {
                    list.remove(0);
                }
            }
        }
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
            synchronized (list) {
                all.addAll(list);
            }
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

    // =====================================================================
    // @EventListener：监听 MavlinkMessageEvent 自行处理地形消息
    // =====================================================================

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.TerrainTypeMapMsg).ID")
    public void onTerrainTypeMapEvent(MavlinkMessageEvent event) {
        onTerrainTypeMap(event.getSysid(), (TerrainTypeMapMsg) event.getMessage());
    }

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.TerrainUpdateMsg).ID")
    public void onTerrainUpdateEvent(MavlinkMessageEvent event) {
        onTerrainUpdate(event.getSysid(), (TerrainUpdateMsg) event.getMessage());
    }

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.FlightRestrictionMsg).ID")
    public void onFlightRestrictionEvent(MavlinkMessageEvent event) {
        onFlightRestriction(event.getSysid(), (FlightRestrictionMsg) event.getMessage());
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

    /**
     * 列表里是否已有「内容相同」的限制区。
     * <p>
     * 不能直接用 {@code list.contains(...)}：{@code FlightRestrictionSnapshot} 是 record，
     * 但多边形是 {@code List<double[]>}，而 {@code double[]} 没有结构相等，
     * 形状一样的两个多边形会被判为不等。故按「类型 + 限值 + 顶点」串一个规范键比较。
     * <p>
     * 这一步是收口的关键：节点周期性重报同一批区时，旧实现每次都原样再追加一遍，
     * 列表随重报次数线性增长；去重后同样的重报不再长。
     */
    private static boolean hasEquivalentRestriction(List<FlightRestrictionSnapshot> list,
                                                    FlightRestrictionSnapshot snapshot) {
        String key = restrictionKey(snapshot);
        for (FlightRestrictionSnapshot existing : list) {
            if (restrictionKey(existing).equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** 限制区内容键（类型|限值|每个顶点的 lat,lon）。 */
    private static String restrictionKey(FlightRestrictionSnapshot s) {
        StringBuilder sb = new StringBuilder();
        sb.append(s.restrictionType()).append('|').append(s.limitValue()).append('|');
        for (double[] p : s.areaPolygon()) {
            sb.append(p[0]).append(',').append(p[1]).append(';');
        }
        return sb.toString();
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
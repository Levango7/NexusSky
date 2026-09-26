package io.aerofleet.cloud.autodispatch;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.tracking.FlightTrackStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 自动出警服务（P0-1 安防报警→无人机自动出警闭环）。
 * <p>
 * 报警事件触发时，自动选择可用无人机，生成起飞任务飞往报警位置。
 * <p>
 * <b>选机策略</b>：优先选距离报警点最近、电量 &gt; {@link AutoDispatchConfig#getMinBatteryPct()}、
 * 距离 ≤ {@link AutoDispatchConfig#getMaxDispatchDistanceM()}、未执行任务（未 armed）的在线无人机。
 * <p>
 * <b>任务模板</b>：起飞 → 飞往报警点 → 悬停侦察（{@link AutoDispatchConfig#getHoverAltitudeM()} 米，
 * 持续 {@link AutoDispatchConfig#getHoverDurationSec()} 秒）→ 返航。任务以 MAVLink 航点序列描述。
 * <p>
 * <b>线程安全</b>：依赖 {@link DeviceRegistry} 与 {@link FlightTrackStore} 的并发实现，
 * 内部使用 {@link ConcurrentHashMap} 维护出警记录，{@link ConcurrentLinkedDeque} 维护历史。
 *
 * @see AutoDispatchConfig
 * @see DispatchResult
 * @see DispatchRecord
 */
@Service
public class AutoDispatchService {

    private static final Logger log = LoggerFactory.getLogger(AutoDispatchService.class);

    /** 地球半径（米），用于 Haversine 距离计算。 */
    private static final double EARTH_RADIUS_M = 6_371_000.0;
    /** 默认无人机巡航速度（m/s），用于估算到达时间。 */
    private static final double DEFAULT_CRUISE_SPEED_MPS = 10.0;

    private final DeviceRegistry deviceRegistry;
    private final FlightTrackStore trackStore;
    /** 自动出警配置（volatile 字段支持运行时更新）。 */
    private final AutoDispatchConfig config;
    /** 活跃出警记录：dispatchId → record。 */
    private final ConcurrentHashMap<String, DispatchRecord> activeRecords = new ConcurrentHashMap<>();
    /** 出警历史（最新在前，线程安全的有界队列）。 */
    private final ConcurrentLinkedDeque<DispatchRecord> history = new ConcurrentLinkedDeque<>();
    /** 历史容量上限。 */
    private static final int HISTORY_CAPACITY = 500;

    public AutoDispatchService(DeviceRegistry deviceRegistry, FlightTrackStore trackStore) {
        this(deviceRegistry, trackStore, new AutoDispatchConfig());
    }

    @Autowired
    public AutoDispatchService(DeviceRegistry deviceRegistry, FlightTrackStore trackStore,
                               AutoDispatchConfig config) {
        this.deviceRegistry = deviceRegistry;
        this.trackStore = trackStore;
        this.config = config;
    }

    /**
     * 触发自动出警：选择可用无人机并生成起飞任务飞往报警位置。
     * <p>
     * 流程：
     * <ol>
     *   <li>检查自动出警是否启用（{@link AutoDispatchConfig#isEnabled()}）</li>
     *   <li>从 {@link DeviceRegistry} 获取在线无人机列表</li>
     *   <li>按选机策略过滤与排序（距离最近、电量充足、未执行任务、距离不超限）</li>
     *   <li>选取前 {@code droneCount} 架无人机生成 MAVLink 航点任务</li>
     *   <li>创建 {@link DispatchRecord} 并存入活跃记录与历史</li>
     * </ol>
     *
     * @param lat        报警位置纬度（WGS84，度）
     * @param lon        报警位置经度（WGS84，度）
     * @param alarmId    关联的报警事件 ID
     * @param droneCount 请求派遣的无人机数量（<=0 时使用配置默认值）
     * @return 派遣结果（含状态与已派遣无人机列表）
     */
    public DispatchResult dispatchDrone(double lat, double lon, String alarmId, int droneCount) {
        String dispatchId = UUID.randomUUID().toString();
        long triggerTime = System.currentTimeMillis();

        // 自动出警未启用时直接返回
        if (!config.isEnabled()) {
            log.info("auto dispatch disabled: alarmId={} lat={} lon={}", alarmId, lat, lon);
            DispatchResult result = new DispatchResult(dispatchId,
                    DispatchResult.Status.NO_DRONE, List.of(), "auto dispatch disabled");
            recordDispatch(dispatchId, alarmId, triggerTime, lat, lon, result);
            return result;
        }

        int requested = droneCount > 0 ? droneCount : config.getDefaultDroneCount();

        // 1. 候选无人机：在线 + 电量充足 + 未 armed + 位置有效 + 距离不超限
        List<CandidateDrone> candidates = selectCandidates(lat, lon);
        log.info("auto dispatch candidates: alarmId={} total={} requested={}",
                alarmId, candidates.size(), requested);

        // 2. 无候选无人机
        if (candidates.isEmpty()) {
            log.warn("no available drone for dispatch: alarmId={} lat={} lon={}", alarmId, lat, lon);
            DispatchResult result = new DispatchResult(dispatchId,
                    DispatchResult.Status.NO_DRONE, List.of(), "no available drone");
            recordDispatch(dispatchId, alarmId, triggerTime, lat, lon, result);
            return result;
        }

        // 3. 选取前 requested 架并生成派遣信息
        int actualCount = Math.min(requested, candidates.size());
        List<DispatchResult.DispatchedDrone> dispatched = new ArrayList<>(actualCount);
        for (int i = 0; i < actualCount; i++) {
            CandidateDrone c = candidates.get(i);
            int etaSec = estimateArrivalSec(c.distanceM);
            dispatched.add(new DispatchResult.DispatchedDrone(c.sysid, true, etaSec));
            // 生成 MAVLink 航点任务（起飞→飞往报警点→悬停侦察→返航）
            List<MavlinkWaypoint> waypoints = buildWaypoints(lat, lon, config.getHoverAltitudeM(),
                    config.getHoverDurationSec());
            log.info("dispatched drone: sysid={} distanceM={} etaSec={} waypoints={}",
                    c.sysid, c.distanceM, etaSec, waypoints.size());
        }

        // 4. 构造结果（全部派遣成功为 SUCCESS，部分为 PARTIAL）
        DispatchResult.Status status = actualCount == requested
                ? DispatchResult.Status.SUCCESS
                : DispatchResult.Status.PARTIAL;
        String message = status == DispatchResult.Status.SUCCESS
                ? "dispatched " + actualCount + " drone(s)"
                : "only " + actualCount + " of " + requested + " drone(s) dispatched";
        DispatchResult result = new DispatchResult(dispatchId, status, dispatched, message);
        recordDispatch(dispatchId, alarmId, triggerTime, lat, lon, result);

        log.info("auto dispatch completed: dispatchId={} alarmId={} status={} dispatched={}",
                dispatchId, alarmId, status, actualCount);
        return result;
    }

    /**
     * 中止出警任务：标记记录为 ABORTED 并从活跃列表移除。
     *
     * @param dispatchId 派遣 ID
     * @return 被中止的记录；不存在返回 null
     */
    public DispatchRecord abortDispatch(String dispatchId) {
        DispatchRecord record = activeRecords.remove(dispatchId);
        if (record == null) {
            log.warn("abort dispatch not found: dispatchId={}", dispatchId);
            return null;
        }
        record.markAborted();
        log.info("dispatch aborted: dispatchId={} alarmId={}", dispatchId, record.getAlarmId());
        return record;
    }

    /** 获取出警历史（最新在前，最多 limit 条）。 */
    public List<DispatchRecord> getHistory(int limit) {
        List<DispatchRecord> snapshot = new ArrayList<>(history);
        if (limit > 0 && snapshot.size() > limit) {
            return new ArrayList<>(snapshot.subList(0, limit));
        }
        return snapshot;
    }

    /** 获取所有进行中的出警任务（不可变快照）。 */
    public List<DispatchRecord> getActiveRecords() {
        return new ArrayList<>(activeRecords.values());
    }

    /** 按 ID 获取出警记录。 */
    public DispatchRecord getRecord(String dispatchId) {
        DispatchRecord active = activeRecords.get(dispatchId);
        if (active != null) {
            return active;
        }
        for (DispatchRecord r : history) {
            if (r.getDispatchId().equals(dispatchId)) {
                return r;
            }
        }
        return null;
    }

    /** 获取自动出警配置。 */
    public AutoDispatchConfig getConfig() {
        return config;
    }

    /** 更新自动出警配置（字段级合并：null/0 值不覆盖）。 */
    public AutoDispatchConfig updateConfig(boolean enabled, Integer minBatteryPct,
                                           Integer maxDispatchDistanceM, Integer defaultDroneCount,
                                           Integer hoverAltitudeM, Integer hoverDurationSec) {
        config.setEnabled(enabled);
        if (minBatteryPct != null && minBatteryPct > 0) {
            config.setMinBatteryPct(minBatteryPct);
        }
        if (maxDispatchDistanceM != null && maxDispatchDistanceM > 0) {
            config.setMaxDispatchDistanceM(maxDispatchDistanceM);
        }
        if (defaultDroneCount != null && defaultDroneCount > 0) {
            config.setDefaultDroneCount(defaultDroneCount);
        }
        if (hoverAltitudeM != null && hoverAltitudeM > 0) {
            config.setHoverAltitudeM(hoverAltitudeM);
        }
        if (hoverDurationSec != null && hoverDurationSec > 0) {
            config.setHoverDurationSec(hoverDurationSec);
        }
        log.info("auto dispatch config updated: {}", config);
        return config;
    }

    /** 活跃出警任务数。 */
    public int activeCount() {
        return activeRecords.size();
    }

    /** 历史出警记录总数。 */
    public int historyCount() {
        return history.size();
    }

    // =====================================================================
    // 选机策略
    // =====================================================================

    /**
     * 选机：从 {@link DeviceRegistry} 获取在线无人机，按策略过滤与排序。
     * <p>
     * 过滤条件：
     * <ul>
     *   <li>无人机在线（{@code online == true}）</li>
     *   <li>电量 ≥ {@link AutoDispatchConfig#getMinBatteryPct()}</li>
     *   <li>未执行任务（{@code armed == false}）</li>
     *   <li>位置有效（lat/lon 非 NaN）</li>
     *   <li>距离报警点 ≤ {@link AutoDispatchConfig#getMaxDispatchDistanceM()}</li>
     * </ul>
     * 排序：距离升序（最近的优先）。
     */
    private List<CandidateDrone> selectCandidates(double targetLat, double targetLon) {
        List<CandidateDrone> candidates = new ArrayList<>();
        int minBattery = config.getMinBatteryPct();
        int maxDistance = config.getMaxDispatchDistanceM();

        for (DroneSnapshot drone : deviceRegistry.all()) {
            if (!drone.online) {
                continue;
            }
            if (drone.battery < minBattery) {
                continue;
            }
            if (drone.armed) {
                continue;
            }
            if (Double.isNaN(drone.lat) || Double.isNaN(drone.lon)) {
                continue;
            }
            double distanceM = haversineM(targetLat, targetLon, drone.lat, drone.lon);
            if (distanceM > maxDistance) {
                continue;
            }
            candidates.add(new CandidateDrone(drone.sysid, distanceM, drone.battery));
        }

        // 距离升序（最近优先）；距离相同时按 sysid 升序保证稳定
        candidates.sort(Comparator.comparingDouble((CandidateDrone c) -> c.distanceM)
                .thenComparingInt(c -> c.sysid));
        return candidates;
    }

    /**
     * 生成 MAVLink 航点任务：起飞 → 飞往报警点 → 悬停侦察 → 返航。
     *
     * @param targetLat       报警点纬度
     * @param targetLon       报警点经度
     * @param hoverAltitudeM  悬停高度（米）
     * @param hoverDurationSec 悬停时长（秒）
     * @return 航点列表（按执行顺序）
     */
    private List<MavlinkWaypoint> buildWaypoints(double targetLat, double targetLon,
                                                 int hoverAltitudeM, int hoverDurationSec) {
        List<MavlinkWaypoint> waypoints = new ArrayList<>(4);
        // MAVLink cmd id: 16=WAYPOINT, 22=TAKEOFF, 17=LOITER_TIME, 20=RETURN_TO_LAUNCH
        waypoints.add(new MavlinkWaypoint(0, 22, 0, 0, 0, hoverAltitudeM, 0, 0));
        waypoints.add(new MavlinkWaypoint(1, 16, targetLat, targetLon, hoverAltitudeM, 0, 0, 0));
        waypoints.add(new MavlinkWaypoint(2, 17, targetLat, targetLon, hoverAltitudeM,
                hoverDurationSec, 0, 0));
        waypoints.add(new MavlinkWaypoint(3, 20, 0, 0, 0, 0, 0, 0));
        return waypoints;
    }

    /** 估算到达时间（秒）：距离 / 默认巡航速度。 */
    private static int estimateArrivalSec(double distanceM) {
        if (distanceM <= 0) {
            return 0;
        }
        return (int) Math.ceil(distanceM / DEFAULT_CRUISE_SPEED_MPS);
    }

    /**
     * Haversine 公式计算两点间球面距离（米）。
     * <p>
     * 用于选机时的距离排序与最大距离过滤。
     */
    private static double haversineM(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_M * c;
    }

    /** 记录出警：存入活跃列表与历史队列。 */
    private void recordDispatch(String dispatchId, String alarmId, long triggerTime,
                                double lat, double lon, DispatchResult result) {
        DispatchRecord record = new DispatchRecord(
                dispatchId, alarmId, triggerTime, lat, lon,
                result.getStatus().name(), result.getDispatchedDrones());
        if (result.getStatus() != DispatchResult.Status.NO_DRONE) {
            activeRecords.put(dispatchId, record);
        }
        history.addFirst(record);
        while (history.size() > HISTORY_CAPACITY) {
            history.pollLast();
        }
    }

    // =====================================================================
    // 内部数据结构
    // =====================================================================

    /** 候选无人机（含距离与电量信息）。 */
    private static final class CandidateDrone {
        final int sysid;
        final double distanceM;
        final int batteryPct;

        CandidateDrone(int sysid, double distanceM, int batteryPct) {
            this.sysid = sysid;
            this.distanceM = distanceM;
            this.batteryPct = batteryPct;
        }
    }

    /**
     * MAVLink 航点（Mission Item）。
     * <p>
     * 字段对齐 MAVLink MISSION_ITEM_INT：seq, cmd, lat, lon, alt, param1-3。
     */
    public static final class MavlinkWaypoint {
        /** 航点序号。 */
        public final int seq;
        /** MAVLink cmd id（16=WAYPOINT, 22=TAKEOFF, 17=LOITER_TIME, 20=RTL）。 */
        public final int cmd;
        public final double lat;
        public final double lon;
        public final double alt;
        public final double param1;
        public final double param2;
        public final double param3;

        public MavlinkWaypoint(int seq, int cmd, double lat, double lon, double alt,
                               double param1, double param2, double param3) {
            this.seq = seq;
            this.cmd = cmd;
            this.lat = lat;
            this.lon = lon;
            this.alt = alt;
            this.param1 = param1;
            this.param2 = param2;
            this.param3 = param3;
        }

        @Override
        public String toString() {
            return "MavlinkWaypoint{seq=" + seq + ", cmd=" + cmd
                    + ", lat=" + lat + ", lon=" + lon + ", alt=" + alt + '}';
        }
    }
}
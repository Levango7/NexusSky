package io.aerofleet.cloud.roc;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 警情驱动调度（E1，spec R3）——对表深圳"1+7+N"的警情驱动航线动态调整。
 * <p>
 * 建议评分 = 三因子线性加权（距离 0.5 + 电量 0.3 + 空闲 0.2），不跑 GA——
 * 那是 M10 的职责，这里只做席位内单机建议。**只建议不执行**：dispatch 端点
 * 由操作员显式确认后才真下发（arm → 任务上传 → start）。
 */
@Service
public class IncidentDispatchService {

    private static final Logger log = LoggerFactory.getLogger(IncidentDispatchService.class);

    // 三因子权重（spec R3）：距离近者优、电量足者优、空闲者优。
    static final double W_DISTANCE = 0.5;
    static final double W_BATTERY = 0.3;
    static final double W_IDLE = 0.2;

    /** 警情。 */
    static final class Incident {
        final long id;
        final double lat;
        final double lon;
        final String priority;
        final String description;
        final long createdAt = System.currentTimeMillis();
        volatile Long suggestedSysid;
        volatile String suggestionReason;
        volatile String status = "SUGGESTED";
        volatile Long dispatchedAt;

        Incident(long id, double lat, double lon, String priority, String description) {
            this.id = id;
            this.lat = lat;
            this.lon = lon;
            this.priority = priority;
            this.description = description;
        }
    }

    private final Map<Long, Incident> incidents = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    private final RocSeatService seats;
    private final DeviceRegistry registry;
    private final DroneCommandService commands;

    public IncidentDispatchService(RocSeatService seats, DeviceRegistry registry,
                                   DroneCommandService commands) {
        this.seats = seats;
        this.registry = registry;
        this.commands = commands;
    }

    // ------------------------------------------------------------------
    // R3 登记 + 建议
    // ------------------------------------------------------------------

    /**
     * 登记警情并生成席位内建议。全忙/全离线 → 建议 null + 原因（不硬塞）。
     */
    public synchronized Map<String, Object> report(long seatId, double lat, double lon,
                                                   String priority, String description) {
        if (!priority.equals("P0") && !priority.equals("P1") && !priority.equals("P2")) {
            throw new IllegalArgumentException("priority must be P0|P1|P2: " + priority);
        }
        RocSeatService.Seat seat = seatsOf(seatId);
        Incident inc = new Incident(idSeq.getAndIncrement(), lat, lon, priority, description);
        incidents.put(inc.id, inc);

        List<Map<String, Object>> scored = scoreFleet(seat, lat, lon);
        if (scored.isEmpty()) {
            inc.suggestionReason = "no available drone in seat (offline/busy/all)";
            log.info("incident {} created: no suggestion (seat {} empty/none available)", inc.id, seatId);
        } else {
            Map<String, Object> best = scored.get(0);
            inc.suggestedSysid = ((Number) best.get("sysid")).longValue();
            inc.suggestionReason = "score=" + best.get("score") + " distM=" + best.get("distanceM");
            log.info("incident {} created: suggest sysid={} (score={}, distM={})",
                    inc.id, best.get("sysid"), best.get("score"), best.get("distanceM"));
        }
        return viewOf(inc, scored);
    }

    /**
     * 三因子评分：仅席位机队内的在线、非 armed（空闲）机参与；
     * 距离按 haversine；电量 -1（未知）按 0 计。
     */
    List<Map<String, Object>> scoreFleet(RocSeatService.Seat seat, double lat, double lon) {
        List<Map<String, Object>> scored = new ArrayList<>();
        for (int sysid : seat.fleet) {
            DroneSnapshot s = registry.get(sysid);
            if (s == null || !s.online || s.armed) {
                continue;   // 离线/执行中不参与建议
            }
            double distM = haversineM(s.lat, s.lon, lat, lon);
            double battery = s.battery < 0 ? 0 : s.battery / 100.0;
            double distScore = 1.0 / (1.0 + distM / 1000.0);   // 距离越近越高（1km 处 0.5）
            double idleScore = "MISSION".equals(s.mode) ? 0 : 1;
            double score = W_DISTANCE * distScore + W_BATTERY * battery + W_IDLE * idleScore;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sysid", sysid);
            m.put("score", Math.round(score * 1000) / 1000.0);
            m.put("distanceM", Math.round(distM));
            m.put("battery", s.battery);
            scored.add(m);
        }
        scored.sort((a, b) -> Double.compare((Double) b.get("score"), (Double) a.get("score")));
        return scored;
    }

    // ------------------------------------------------------------------
    // 确认派飞
    // ------------------------------------------------------------------

    /**
     * 操作员确认派飞：arm → 上传单航点任务（警情上空驻留 30s）→ start。
     * 执行失败如实抛出（状态 FAILED + 原因），不留半执行现场。
     */
    public synchronized Map<String, Object> dispatch(long seatId, long incidentId, double holdSec) {
        Incident inc = incidents.get(incidentId);
        if (inc == null) {
            throw new IllegalArgumentException("unknown incident " + incidentId);
        }
        if ("DISPATCHED".equals(inc.status)) {
            throw new IllegalStateException("incident already dispatched: " + incidentId);
        }
        if (inc.suggestedSysid == null) {
            throw new IllegalStateException("no suggestion to dispatch for incident " + incidentId);
        }
        int sysid = inc.suggestedSysid.intValue();
        try {
            commands.arm(sysid);
            commands.uploadMission(sysid, List.of(
                    new io.aerofleet.mavlink.messages.MissionItemInt(
                            sysid, 0, 0,
                            io.aerofleet.mavlink.enums.MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT,
                            io.aerofleet.mavlink.enums.MavEnums.MAV_CMD_NAV_WAYPOINT,
                            0, 0, (float) holdSec, 0f, 0f, 0f,
                            (int) Math.round(inc.lat * 1e7), (int) Math.round(inc.lon * 1e7),
                            50f, io.aerofleet.mavlink.enums.MavEnums.MAV_MISSION_TYPE_MISSION)));
            commands.startMission(sysid);
        } catch (Exception e) {
            inc.status = "FAILED";
            inc.suggestionReason = "dispatch failed: " + e.getMessage();
            log.warn("incident {} dispatch failed: {}", incidentId, e.getMessage());
            throw new IllegalStateException("dispatch failed: " + e.getMessage(), e);
        }
        inc.status = "DISPATCHED";
        inc.dispatchedAt = System.currentTimeMillis();
        log.info("incident {} dispatched to sysid={} (holdSec={})", incidentId, sysid, holdSec);
        return viewOf(inc, List.of());
    }

    public List<Map<String, Object>> listIncidents(long seatId) {
        seatsOf(seatId);   // 校验席位存在
        List<Map<String, Object>> out = new ArrayList<>();
        incidents.values().stream()
                .sorted((a, b) -> Long.compare(b.createdAt, a.createdAt))
                .limit(50)
                .forEach(i -> out.add(viewOf(i, List.of())));
        return out;
    }

    private RocSeatService.Seat seatsOf(long seatId) {
        // 复用席位校验（单一真值源），IncidentService 不自建席位表
        return seats.seatOf(seatId);
    }

    private static Map<String, Object> viewOf(Incident inc, List<Map<String, Object>> candidates) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("incidentId", inc.id);
        m.put("lat", inc.lat);
        m.put("lon", inc.lon);
        m.put("priority", inc.priority);
        m.put("description", inc.description);
        m.put("status", inc.status);
        m.put("suggestedSysid", inc.suggestedSysid);
        m.put("suggestionReason", inc.suggestionReason);
        m.put("createdAt", inc.createdAt);
        if (!candidates.isEmpty()) {
            m.put("candidates", candidates);
        }
        return m;
    }

    static double haversineM(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}

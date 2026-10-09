package io.aerofleet.cloud.roc;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ROC 席位服务（E1，spec R1）——一控多机的席位化交互，对表深圳"1+7+N"。
 * <p>
 * 席位是**运行时操作会话概念**（in-memory，重启清零——与席位语义一致，非缺陷，
 * 诚实边界 spec §4）。机队绑定全量替换；席位视图实时聚合 DeviceRegistry 快照。
 */
@Service
public class RocSeatService {

    private static final Logger log = LoggerFactory.getLogger(RocSeatService.class);

    /** 单席位机队上限（对表"一控多机"席位语义，深圳实践 6-9 架）。 */
    static final int MAX_FLEET = 9;

    /** 席位。 */
    static final class Seat {
        final long id;
        final String operatorName;
        final List<Integer> fleet = new ArrayList<>();
        volatile long createdAt = System.currentTimeMillis();

        Seat(long id, String operatorName) {
            this.id = id;
            this.operatorName = operatorName;
        }
    }

    private final Map<Long, Seat> seats = new ConcurrentHashMap<>();
    private final Map<String, Long> byName = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    private final DeviceRegistry registry;

    public RocSeatService(DeviceRegistry registry) {
        this.registry = registry;
    }

    // ------------------------------------------------------------------
    // R1 席位
    // ------------------------------------------------------------------

    public synchronized Map<String, Object> create(String operatorName) {
        if (operatorName == null || operatorName.isBlank()) {
            throw new IllegalArgumentException("operatorName must not be blank");
        }
        if (byName.containsKey(operatorName)) {
            throw new IllegalStateException("seat already exists: " + operatorName);
        }
        Seat seat = new Seat(idSeq.getAndIncrement(), operatorName);
        seats.put(seat.id, seat);
        byName.put(operatorName, seat.id);
        log.info("ROC seat created: id={} operator={}", seat.id, operatorName);
        return Map.of("seatId", seat.id, "operatorName", operatorName);
    }

    /** 机队绑定（全量替换；校验已知设备与上限）。 */
    public synchronized void bindFleet(long seatId, List<Integer> sysids) {
        Seat seat = seat(seatId);
        if (sysids == null) {
            sysids = List.of();
        }
        if (sysids.size() > MAX_FLEET) {
            throw new IllegalArgumentException(
                    "fleet exceeds seat limit " + MAX_FLEET + ": " + sysids.size());
        }
        List<Integer> unknown = new ArrayList<>();
        for (int sysid : sysids) {
            if (!registry.isKnownDevice(sysid)) {
                unknown.add(sysid);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown sysids: " + unknown);
        }
        seat.fleet.clear();
        seat.fleet.addAll(sysids);
        log.info("ROC seat {} fleet bound: {} drone(s)", seatId, sysids.size());
    }

    /** 席位视图：机队实时快照。 */
    public Map<String, Object> view(long seatId) {
        Seat seat = seat(seatId);
        List<Map<String, Object>> fleetView = new ArrayList<>();
        for (int sysid : seat.fleet) {
            DroneSnapshot s = registry.get(sysid);
            if (s == null) {
                fleetView.add(Map.of("sysid", sysid, "online", false));
                continue;
            }
            // LinkedHashMap 而非 Map.of：遥测初值 NaN/未上报时字段是 null，
            // Map.of 不接受 null 值（快照数据允许缺——如实展示，不编造 0）
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sysid", sysid);
            m.put("online", s.online);
            m.put("armed", s.armed);
            m.put("mode", s.mode);
            m.put("battery", s.battery);
            m.put("lat", Double.isNaN(s.lat) ? null : s.lat);
            m.put("lon", Double.isNaN(s.lon) ? null : s.lon);
            m.put("relativeAlt", Double.isNaN(s.relativeAlt) ? null : s.relativeAlt);
            fleetView.add(m);
        }
        return Map.of(
                "seatId", seat.id,
                "operatorName", seat.operatorName,
                "fleetSize", seat.fleet.size(),
                "fleet", fleetView);
    }

    public List<Map<String, Object>> listSeats() {
        List<Map<String, Object>> out = new ArrayList<>();
        seats.values().stream().sorted((a, b) -> Long.compare(a.id, b.id)).forEach(s ->
                out.add(Map.of("seatId", s.id, "operatorName", s.operatorName,
                        "fleetSize", s.fleet.size())));
        return out;
    }

    /** 指令目标机：指定子集校验（必须都在席位机队里——不许越席位操作他机）。 */
    List<Integer> targetsOf(long seatId, List<Integer> sysids) {
        Seat seat = seat(seatId);
        if (sysids == null || sysids.isEmpty()) {
            return List.copyOf(seat.fleet);
        }
        List<Integer> notInSeat = new ArrayList<>();
        for (int sysid : sysids) {
            if (!seat.fleet.contains(sysid)) {
                notInSeat.add(sysid);
            }
        }
        if (!notInSeat.isEmpty()) {
            throw new IllegalArgumentException(
                    "sysids not in seat " + seatId + ": " + notInSeat);
        }
        return sysids;
    }

    /** 包内访问（IncidentDispatchService 复用席位校验，单一真值源）。 */
    Seat seatOf(long seatId) {
        return seat(seatId);
    }

    private Seat seat(long seatId) {
        Seat seat = seats.get(seatId);
        if (seat == null) {
            throw new IllegalArgumentException("unknown seat " + seatId);
        }
        return seat;
    }
}

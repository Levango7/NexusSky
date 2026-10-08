package io.aerofleet.cloud.report;

import io.aerofleet.cloud.flightlog.FlightLogEntity;
import io.aerofleet.cloud.flightlog.FlightLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营报表与单位经济（E6，spec §R1-R3）：架次/时长/里程/能耗/成本核算。
 * <p>
 * 数据源 = flight_log 的 telemetry 帧（1Hz 节流），架次 = armed 连续帧段。
 * 成本是**参数化模型**：`aerofleet.report.cost-per-hour`（默认 0）——本仓不编造
 * 电价/折旧，由部署方按其单位经济填（诚实边界 spec §4）。
 * 聚合全部在内存做（窗口上限 31 天由控制器把关，1Hz×N 机数据量可控）。
 */
@Service
public class OperationsReportService {

    private static final Logger log = LoggerFactory.getLogger(OperationsReportService.class);

    /** 帧断档阈值：段内间隔超过此值切分新架次（防数据粘连）。 */
    static final long GAP_MS = 30_000;

    private final FlightLogRepository flightLog;
    private final double costPerHour;

    public OperationsReportService(FlightLogRepository flightLog,
                                   @Value("${aerofleet.report.cost-per-hour:0}") double costPerHour) {
        this.flightLog = flightLog;
        this.costPerHour = costPerHour;
    }

    /** 一个架次（armed 连续帧段）的聚合结果。 */
    record Sortie(int sysid, long startMs, long endMs, double minutes, double distanceKm,
                  Integer batteryStart, Integer batteryEnd, int batteryUsed, String mainMode) {
    }

    // ------------------------------------------------------------------
    // 架次分割（spec R1/R2）
    // ------------------------------------------------------------------

    /** 窗口内全部架次（按机分组后逐机分割）。 */
    List<Sortie> sortiesOf(Instant from, Instant to) {
        List<FlightLogEntity> frames = flightLog
                .findByTypeAndTimestampBetweenOrderByTimestampAscIdAsc("telemetry", from, to);
        Map<Integer, List<FlightLogEntity>> bySysid = new LinkedHashMap<>();
        for (FlightLogEntity f : frames) {
            bySysid.computeIfAbsent(f.getSysid(), k -> new ArrayList<>()).add(f);
        }
        List<Sortie> out = new ArrayList<>();
        for (Map.Entry<Integer, List<FlightLogEntity>> e : bySysid.entrySet()) {
            out.addAll(splitSorties(e.getKey(), e.getValue()));
        }
        out.sort(Comparator.comparingLong(Sortie::startMs));
        return out;
    }

    /** 单机帧序列 → armed 连续段（断档 30s 切分）。纯函数，可全量单测。 */
    static List<Sortie> splitSorties(int sysid, List<FlightLogEntity> frames) {
        List<Sortie> out = new ArrayList<>();
        List<FlightLogEntity> seg = new ArrayList<>();
        for (FlightLogEntity f : frames) {
            boolean armed = Boolean.TRUE.equals(f.getArmed());
            if (armed) {
                if (!seg.isEmpty()
                        && Duration.between(seg.get(seg.size() - 1).getTimestamp(), f.getTimestamp())
                        .toMillis() > GAP_MS) {
                    out.add(buildSortie(sysid, seg));
                    seg = new ArrayList<>();
                }
                seg.add(f);
            } else if (!seg.isEmpty()) {
                out.add(buildSortie(sysid, seg));
                seg = new ArrayList<>();
            }
        }
        if (!seg.isEmpty()) {
            out.add(buildSortie(sysid, seg));
        }
        return out;
    }

    private static Sortie buildSortie(int sysid, List<FlightLogEntity> seg) {
        FlightLogEntity first = seg.get(0);
        FlightLogEntity last = seg.get(seg.size() - 1);
        long startMs = first.getTimestamp().toEpochMilli();
        long endMs = last.getTimestamp().toEpochMilli();
        double minutes = (endMs - startMs) / 60_000.0;
        double km = 0;
        FlightLogEntity prev = null;
        Map<String, Integer> modeCount = new HashMap<>();
        for (FlightLogEntity f : seg) {
            if (prev != null && f.getLat() != null && f.getLon() != null
                    && prev.getLat() != null && prev.getLon() != null) {
                km += haversineM(prev.getLat(), prev.getLon(), f.getLat(), f.getLon()) / 1000.0;
            }
            if (f.getMode() != null) {
                modeCount.merge(f.getMode(), 1, Integer::sum);
            }
            prev = f;
        }
        Integer bStart = first.getBattery();
        Integer bEnd = last.getBattery();
        int used = (bStart != null && bEnd != null) ? Math.max(0, bStart - bEnd) : 0;
        String mainMode = modeCount.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("UNKNOWN");
        return new Sortie(sysid, startMs, endMs, Math.round(minutes * 1000) / 1000.0,
                Math.round(km * 100) / 100.0, bStart, bEnd, used, mainMode);
    }

    // ------------------------------------------------------------------
    // 三维聚合（spec R3）
    // ------------------------------------------------------------------

    Map<String, Object> report(Instant from, Instant to, String groupBy) {
        List<Sortie> sorties = sortiesOf(from, to);
        double totalMin = sorties.stream().mapToDouble(Sortie::minutes).sum();
        double totalKm = sorties.stream().mapToDouble(Sortie::distanceKm).sum();
        int totalBattery = sorties.stream().mapToInt(Sortie::batteryUsed).sum();
        double cost = Math.round(totalMin / 60.0 * costPerHour * 100) / 100.0;

        Map<String, List<Sortie>> groups = switch (groupBy == null ? "day" : groupBy) {
            case "drone" -> groupByDrone(sorties);
            case "tenant" -> groupByTenant(sorties);
            default -> groupByDay(sorties, ZoneId.systemDefault());
        };

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("groupBy", groupBy == null ? "day" : groupBy);
        out.put("costPerHour", costPerHour);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sorties", sorties.size());
        summary.put("flightMinutes", Math.round(totalMin * 100) / 100.0);
        summary.put("distanceKm", Math.round(totalKm * 100) / 100.0);
        summary.put("batteryUsedPct", totalBattery);
        summary.put("cost", cost);
        out.put("summary", summary);
        out.put("groups", groups.entrySet().stream()
                .map(e -> Map.of("key", e.getKey(),
                        "sorties", e.getValue().size(),
                        "flightMinutes", Math.round(e.getValue().stream()
                                .mapToDouble(Sortie::minutes).sum() * 100) / 100.0,
                        "distanceKm", Math.round(e.getValue().stream()
                                .mapToDouble(Sortie::distanceKm).sum() * 100) / 100.0))
                .toList());
        out.put("topSorties", sorties.stream()
                .sorted(Comparator.comparingDouble(Sortie::minutes).reversed())
                .limit(20)
                .map(s -> Map.of(
                        "sysid", s.sysid(),
                        "startMs", s.startMs(),
                        "minutes", s.minutes(),
                        "distanceKm", s.distanceKm(),
                        "batteryUsed", s.batteryUsed(),
                        "mode", s.mainMode()))
                .toList());
        log.info("operations report: {} sorties, {} min, {} km, groupBy={}",
                sorties.size(), Math.round(totalMin), Math.round(totalKm), groupBy);
        return out;
    }

    private static Map<String, List<Sortie>> groupByDay(List<Sortie> sorties, ZoneId zone) {
        Map<String, List<Sortie>> out = new LinkedHashMap<>();
        for (Sortie s : sorties) {
            String day = LocalDate.ofInstant(Instant.ofEpochMilli(s.startMs()), zone).toString();
            out.computeIfAbsent(day, k -> new ArrayList<>()).add(s);
        }
        return out;
    }

    private static Map<String, List<Sortie>> groupByDrone(List<Sortie> sorties) {
        Map<String, List<Sortie>> out = new LinkedHashMap<>();
        for (Sortie s : sorties) {
            out.computeIfAbsent("Drone-" + s.sysid(), k -> new ArrayList<>()).add(s);
        }
        return out;
    }

    private static Map<String, List<Sortie>> groupByTenant(List<Sortie> sorties) {
        // tenant 维度需要帧上的 tenantId——Sortie 现未携带（PoC 诚实边界：
        // 未归属/无租户标记的架次计入 unassigned；tenantId 精确归属属设备归属功能范畴）
        Map<String, List<Sortie>> out = new LinkedHashMap<>();
        out.put("unassigned", new ArrayList<>(sorties));
        return out;
    }

    /** 球面距离（米，仓库惯例同款）。 */
    static double haversineM(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}

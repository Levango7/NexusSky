package io.aerofleet.cloud.dock;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.cloud.mission.common.MissionItemRequest;
import io.aerofleet.mavlink.messages.MissionItemInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 无人值守定时任务（spec R4）。
 * <p>
 * 轮询 due 任务 → 前置门控（机巢状态/托管机/并发）→ 全链路编排：
 * 开门 → 等待 OPEN → arm → 上传任务 → MISSION_START → 监测落地（相对高度回 0）→ 关门。
 * 门控不满足记 {@code SKIPPED:reason} 并推进到下个周期，不堆积重试；
 * 同一机巢同时仅一个进行中任务（重复触发记 SKIPPED:busy）。
 * <p>
 * 飞控链路全部复用 {@link DroneCommandService}（不自建），完成判定用遥测
 * （曾起飞 corpus + 已落地）；超时按 {@code unattended-flight-timeout-min} 判 FAILED 并尝试关门。
 */
@Service
public class UnattendedScheduler {

    private static final Logger log = LoggerFactory.getLogger(UnattendedScheduler.class);

    private final DockScheduleRepository schedules;
    private final DockRunLogRepository runLogs;
    private final DockRepository docks;
    private final DockService dockService;
    private final DroneCommandService droneCmd;
    private final DeviceRegistry registry;
    private final DockProperties props;
    private final ObjectMapper mapper;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dock-unattended");
        t.setDaemon(true);
        return t;
    });

    public UnattendedScheduler(DockScheduleRepository schedules, DockRunLogRepository runLogs,
                               DockRepository docks, DockService dockService,
                               DroneCommandService droneCmd, DeviceRegistry registry,
                               DockProperties props, ObjectMapper mapper) {
        this.schedules = schedules;
        this.runLogs = runLogs;
        this.docks = docks;
        this.dockService = dockService;
        this.droneCmd = droneCmd;
        this.registry = registry;
        this.props = props;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------
    // 轮询
    // ------------------------------------------------------------------

    @Scheduled(fixedDelayString = "${aerofleet.dock.schedule-poll-ms:30000}", initialDelay = 15_000)
    public void poll() {
        try {
            for (DockScheduleEntity s : schedules.findByEnabledTrue()) {
                try {
                    if (due(s)) {
                        attempt(s);
                    }
                } catch (Exception e) {
                    log.warn("dock schedule {} dispatch failed: {}", s.id, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("dock schedule poll failed: {}", e.getMessage());
        }
    }

    boolean due(DockScheduleEntity s) {
        long now = System.currentTimeMillis();
        if (s.nextDueMs == null) {
            // 首见：只登记下个触发点，不立刻补跑（避免"刚建的每分钟任务"开机风暴）
            s.nextDueMs = next(s, now);
            schedules.save(s);
            return false;
        }
        return now >= s.nextDueMs;
    }

    long next(DockScheduleEntity s, long fromMs) {
        CronExpression cron = CronExpression.parse(s.cronExpr);
        ZonedDateTime from = java.time.Instant.ofEpochMilli(fromMs).atZone(java.time.ZoneId.systemDefault());
        ZonedDateTime nextAt = cron.next(from);
        if (nextAt == null) {
            throw new IllegalArgumentException("cron has no next fire time: " + s.cronExpr);
        }
        return nextAt.toInstant().toEpochMilli();
    }

    // ------------------------------------------------------------------
    // 门控与编排
    // ------------------------------------------------------------------

    void attempt(DockScheduleEntity s) {
        DockEntity dock = docks.findById(s.dockId).orElse(null);
        String skip = gate(dock, s);
        if (skip != null) {
            recordSkip(s, dock, skip);
            advance(s);
            return;
        }
        advance(s);
        final DockEntity target = dock;
        executor.submit(() -> execute(s, target));
    }

    /** 返回 null = 可执行；否则返回 SKIP 原因。 */
    String gate(DockEntity dock, DockScheduleEntity s) {
        if (dock == null) {
            return "dock-absent";
        }
        if (dock.state == DockState.OFFLINE) {
            return "dock-offline";
        }
        if (dock.state != DockState.IDLE && dock.state != DockState.CHARGING) {
            return "state:" + dock.state.name();
        }
        if (dock.droneSysid == null) {
            return "no-docked-drone";
        }
        // 托管机必须在线：否则会白开门、白等到飞行超时再 FAILED。
        // 与"机巢离线"同理——前置不满足就 SKIP，不要留下半执行的现场（门开着、飞机没动）。
        DroneSnapshot drone = registry.get(dock.droneSysid);
        if (drone == null || !drone.online) {
            return "drone-offline";
        }
        for (DockRunLogEntity r : runLogs.findByDockIdOrderByStartedAtDesc(dock.id)) {
            if ("RUNNING".equals(r.result)) {
                return "busy";
            }
        }
        return null;
    }

    private void recordSkip(DockScheduleEntity s, DockEntity dock, String reason) {
        DockRunLogEntity r = new DockRunLogEntity();
        r.scheduleId = s.id;
        r.dockId = s.dockId;
        r.result = "SKIPPED";
        r.reason = reason;
        r.finishedAt = r.startedAt;
        runLogs.save(r);
        s.lastResult = "SKIPPED:" + reason;
        s.lastRunAt = System.currentTimeMillis();
        schedules.save(s);
        log.info("dock schedule {} skipped: {}", s.id, reason);
    }

    private void advance(DockScheduleEntity s) {
        s.lastRunAt = System.currentTimeMillis();
        s.nextDueMs = next(s, System.currentTimeMillis());
        schedules.save(s);
    }

    /** 全链路编排（异步执行；结果写 run log）。 */
    void execute(DockScheduleEntity s, DockEntity dock) {
        DockRunLogEntity run = new DockRunLogEntity();
        run.scheduleId = s.id;
        run.dockId = dock.id;
        run.result = "RUNNING";
        runLogs.save(run);

        int sysid = dock.droneSysid;
        long start = System.currentTimeMillis();
        boolean airborneSeen = false;
        try {
            dockService.sendCommand(dock.id, DockCommand.OPEN_DOOR);
            waitForState(dock.id, DockState.OPEN, 30_000);

            List<MissionItemRequest> requests = missionFrom(s, dock);
            List<MissionItemInt> items = droneCmd.toMissionItems(requests, sysid);
            droneCmd.uploadMission(sysid, items);
            droneCmd.arm(sysid);
            droneCmd.startMission(sysid);
            log.info("unattended mission started dock={} schedule={} sysid={}", dock.sn, s.id, sysid);

            long deadline = start + props.getUnattendedFlightTimeoutMin() * 60_000L;
            while (System.currentTimeMillis() < deadline) {
                DroneSnapshot snap = registry.get(sysid);
                boolean airborne = snap != null && snap.armed && snap.relativeAlt > 1.0;
                if (airborne) {
                    airborneSeen = true;
                } else if (airborneSeen) {
                    break; // 曾起飞且已落地 = 任务完成（RTL 后状态）
                }
                sleepQuietly(2_000);
            }
            boolean timedOut = System.currentTimeMillis() >= deadline && airborneSeen;
            if (System.currentTimeMillis() >= deadline) {
                timedOut = true;
            }

            closeDoorQuietly(dock.id);

            run.finishedAt = System.currentTimeMillis();
            run.flightMinutes = (run.finishedAt - start) / 60_000.0;
            run.result = timedOut ? "FAILED" : "OK";
            run.reason = timedOut ? "flight timeout" : null;
            runLogs.save(run);
            s.lastResult = run.result;
            schedules.save(s);
            log.info("unattended mission finished dock={} result={} flightMin={}",
                    dock.sn, run.result, String.format("%.2f", run.flightMinutes));
        } catch (Exception e) {
            run.finishedAt = System.currentTimeMillis();
            run.result = "FAILED";
            run.reason = e.getMessage();
            runLogs.save(run);
            s.lastResult = "FAILED";
            schedules.save(s);
            closeDoorQuietly(dock.id);
            log.warn("unattended mission failed dock={}: {}", dock.sn, e.getMessage());
        }
    }

    /** 航点 JSON → 任务项：takeoff + waypoints + rtl（复用 DroneCommandService 的构造）。 */
    List<MissionItemRequest> missionFrom(DockScheduleEntity s, DockEntity dock) throws Exception {
        double[][] wps = parseWaypoints(s.waypoints);
        if (wps.length == 0) {
            throw new IllegalArgumentException("schedule has no waypoints");
        }
        List<MissionItemRequest> requests = new ArrayList<>();
        double takeoffAlt = wps[0][2] > 0 ? wps[0][2] : 60;
        double lat = wps[0][0];
        double lon = wps[0][1];
        requests.add(new MissionItemRequest("takeoff", lat, lon, takeoffAlt, 0));
        for (double[] wp : wps) {
            requests.add(new MissionItemRequest("waypoint", wp[0], wp[1], wp[2], 0));
        }
        requests.add(new MissionItemRequest("rtl", wps[wps.length - 1][0], wps[wps.length - 1][1], 0, 0));
        return requests;
    }

    private double[][] parseWaypoints(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return new double[0][];
        }
        List<List<Double>> raw = mapper.readValue(json, new TypeReference<>() {
        });
        double[][] out = new double[raw.size()][3];
        for (int i = 0; i < raw.size(); i++) {
            List<Double> p = raw.get(i);
            out[i][0] = p.get(0);
            out[i][1] = p.get(1);
            out[i][2] = p.size() > 2 ? p.get(2) : 60;
        }
        return out;
    }

    void waitForState(Long dockId, DockState want, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            DockEntity d = docks.findById(dockId).orElse(null);
            if (d != null && d.state == want) {
                return;
            }
            sleepQuietly(500);
        }
        throw new IllegalStateException("dock did not reach " + want + " in " + timeoutMs + "ms");
    }

    private void closeDoorQuietly(Long dockId) {
        try {
            DockEntity d = docks.findById(dockId).orElse(null);
            if (d != null && d.state == DockState.OPEN) {
                dockService.sendCommand(dockId, DockCommand.CLOSE_DOOR);
            }
        } catch (Exception e) {
            log.warn("close door failed dock={}: {}", dockId, e.getMessage());
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 供测试注入更短的轮询间隔（生产固定 2s）。 */
    Duration monitorInterval() {
        return Duration.ofSeconds(2);
    }
}

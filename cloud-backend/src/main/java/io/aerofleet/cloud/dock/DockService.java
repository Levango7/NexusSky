package io.aerofleet.cloud.dock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 机巢核心服务（spec R1/R2/R3）：
 * <ul>
 *   <li>OSD 摄取：心跳、温度、电量落地；稳态迁移经状态机裁决（过渡态不被云端覆盖）</li>
 *   <li>命令下发：状态机硬校验 → 网关 → 过渡态；reboot 走 8s 定时器回 IDLE</li>
 *   <li>离线巡检：超时无心跳置 OFFLINE（不删记录）</li>
 *   <li>所有迁移写 dock_state_log（度量与审计数据源）</li>
 * </ul>
 */
@Service
public class DockService {

    private static final Logger log = LoggerFactory.getLogger(DockService.class);

    /** reboot 后回 IDLE 的固定延时（spec R2）。 */
    static final long REBOOT_MS = 8000;

    private final DockRepository docks;
    private final DockStateLogRepository stateLog;
    private final DockScheduleRepository schedules;
    private final DockStateMachine machine;
    private final DockGateway gateway;
    private final DockProperties props;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dock-reboot-timer");
        t.setDaemon(true);
        return t;
    });

    public DockService(DockRepository docks, DockStateLogRepository stateLog,
                       DockScheduleRepository schedules,
                       DockStateMachine machine, DockGateway gateway, DockProperties props) {
        this.docks = docks;
        this.stateLog = stateLog;
        this.schedules = schedules;
        this.machine = machine;
        this.gateway = gateway;
        this.props = props;
    }

    // ------------------------------------------------------------------
    // 注册与查询
    // ------------------------------------------------------------------

    @Transactional
    public DockEntity register(String name, String model, String sn,
                               Double lat, Double lon, Integer droneSysid, String tenantId) {
        if (docks.findBySn(sn).isPresent()) {
            throw new IllegalStateException("dock sn already registered: " + sn);
        }
        DockEntity d = new DockEntity();
        d.name = name;
        d.model = model;
        d.sn = sn;
        d.lat = lat;
        d.lon = lon;
        d.droneSysid = droneSysid;
        d.tenantId = tenantId;
        d.tempWarnC = props.getTempWarnC();
        d.tempCritC = props.getTempCritC();
        return docks.save(d);
    }

    public List<DockEntity> list() {
        return docks.findAll();
    }

    public DockEntity get(Long id) {
        return docks.findById(id).orElseThrow(() -> new IllegalArgumentException("unknown dock " + id));
    }

    public List<DockStateLogEntity> recentLog(Long dockId, int limit) {
        List<DockStateLogEntity> all = stateLog.findByDockIdOrderByTsDesc(dockId);
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    // ------------------------------------------------------------------
    // OSD 摄取（机巢 → 云）
    // ------------------------------------------------------------------

    /**
     * 处理一条机巢 OSD。
     *
     * @param sn          机巢 SN
     * @param reported    机巢自报稳态（可空 = 不请求迁移）
     * @param temperature 温度（可空）
     * @param batteryPct  备电电量（可空）
     * @param dockedDrone 托管机 sysid（可空）
     */
    @Transactional
    public DockEntity ingestOsd(String sn, DockState reported, Double temperature,
                                Integer batteryPct, Integer dockedDrone) {
        DockEntity d = docks.findBySn(sn)
                .orElseThrow(() -> new IllegalArgumentException("unknown dock sn " + sn));
        d.lastHeartbeatMs = System.currentTimeMillis();
        if (temperature != null) {
            d.temperatureC = temperature;
        }
        if (batteryPct != null) {
            d.batteryPct = batteryPct;
        }
        if (dockedDrone != null) {
            d.droneSysid = dockedDrone;
        }

        // 温度裁决优先于状态迁移：临界温度直接置 FAULT（spec R2）
        if (d.temperatureC != null) {
            if (d.temperatureC >= d.tempCritC && d.state != DockState.FAULT) {
                transition(d, DockState.FAULT,
                        String.format("temperature %.1fC >= critical %.1fC", d.temperatureC, d.tempCritC));
            } else if (d.temperatureC >= d.tempWarnC && d.state != DockState.FAULT) {
                log.warn("dock {} temperature excursion: {}C >= warn {}C (state={})",
                        d.sn, String.format("%.1f", d.temperatureC), d.tempWarnC, d.state);
                // 温度告警事件留痕（度量 tempExcursions 的数据源）——不迁移状态
                stateLog.save(logEntry(d, d.state, d.state,
                        String.format("temp-warning: %.1fC", d.temperatureC)));
            }
        }

        // 上下线：首次心跳 OFFLINE → 机巢自报稳态（缺省 IDLE）
        if (d.state == DockState.OFFLINE) {
            transition(d, reported != null ? reported : DockState.IDLE, "osd heartbeat (online)");
        } else if (reported != null && machine.canFollowOsd(d.state, reported)) {
            // 过渡态由机巢推进到稳态（OPENING→OPEN 等）；FAULT 不被 OSD 覆盖
            transition(d, reported, "osd state follow");
        }

        d.updatedAt = java.time.Instant.now();
        return docks.save(d);
    }

    // ------------------------------------------------------------------
    // 命令下发（云 → 机巢）
    // ------------------------------------------------------------------

    /**
     * 下发动作命令：状态机裁决 → 网关 → 记录过渡态。
     * 非法迁移抛 {@link IllegalDockTransitionException}（控制器 409）；
     * 通道失败抛 {@link DockGateway.DockGatewayException}（控制器 504）。
     */
    @Transactional
    public Map<String, Object> sendCommand(Long dockId, DockCommand command) {
        DockEntity d = get(dockId);
        if (d.rebootUntilMs > System.currentTimeMillis()) {
            throw new IllegalDockTransitionException(d.state, command);
        }
        DockState target = machine.apply(d.state, command);
        if (target != null) {
            // 先记过渡态再发命令：通道失败时状态由使能层回滚（见 catch）
            DockState before = d.state;
            transition(d, target, "command " + command.method());
            try {
                DockGateway.DockReply reply = gateway.sendCommand(d.sn, command, Map.of());
                if (!reply.ok()) {
                    transition(d, before, "command " + command.method() + " rejected by dock: result=" + reply.result());
                    throw new DockGateway.DockGatewayException(
                            "dock rejected command: result=" + reply.result() + " " +
                                    (reply.message() == null ? "" : reply.message()));
                }
                d.updatedAt = java.time.Instant.now();
                docks.save(d);
                return Map.of("dockId", d.id, "sn", d.sn, "method", command.method(),
                        "state", d.state.name(), "transport", gateway.transportName(),
                        "tid", reply.tid());
            } catch (DockGateway.DockGatewayException e) {
                // 通道不可达：回滚过渡态，如实向上抛（不留下卡死的 OPENING）
                transition(d, before, "command " + command.method() + " transport failed: " + e.getMessage());
                d.updatedAt = java.time.Instant.now();
                docks.save(d);
                throw e;
            }
        }
        // REBOOT：转发给机巢（真机重启），本地登记 8s 后回 IDLE 并清 FAULT。
        // 转发失败如实抛（504），不假装已重启。
        gateway.sendCommand(d.sn, command, Map.of());
        scheduleReboot(d);
        d.updatedAt = java.time.Instant.now();
        docks.save(d);
        return Map.of("dockId", d.id, "sn", d.sn, "method", command.method(),
                "state", d.state.name(), "rebootUntilMs", d.rebootUntilMs);
    }

    private void scheduleReboot(DockEntity d) {
        d.rebootUntilMs = System.currentTimeMillis() + REBOOT_MS;
        final Long dockId = d.id;
        timer.schedule(() -> {
            try {
                DockEntity fresh = docks.findById(dockId).orElse(null);
                if (fresh == null) {
                    return;
                }
                fresh.rebootUntilMs = 0;
                if (fresh.state == DockState.FAULT || fresh.state == DockState.MAINTENANCE) {
                    DockState before = fresh.state;
                    fresh.state = DockState.IDLE;
                    stateLog.save(logEntry(fresh, before, DockState.IDLE, "reboot completed"));
                }
                fresh.updatedAt = java.time.Instant.now();
                docks.save(fresh);
                log.info("dock {} reboot completed, state={}", fresh.sn, fresh.state);
            } catch (Exception e) {
                log.warn("dock reboot timer failed: {}", e.getMessage());
            }
        }, REBOOT_MS, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------
    // 定时任务管理（spec R4）
    // ------------------------------------------------------------------

    /** 创建定时任务；cron 语法错误立即拒绝（不让坏任务进库静默不触发）。 */
    @Transactional
    public DockScheduleEntity createSchedule(Long dockId, String name, String cronExpr,
                                             String waypointsJson, boolean enabled) {
        get(dockId); // 404 语义
        if (cronExpr == null || !CronExpression.isValidExpression(cronExpr)) {
            throw new IllegalArgumentException("invalid cron expression: " + cronExpr);
        }
        DockScheduleEntity s = new DockScheduleEntity();
        s.dockId = dockId;
        s.name = name;
        s.cronExpr = cronExpr;
        s.waypoints = waypointsJson;
        s.enabled = enabled;
        // 首次触发点由调度器 poll 时登记（不在创建时计算，避免与调度器时钟口径分叉）
        return schedules.save(s);
    }

    public List<DockScheduleEntity> listSchedules(Long dockId) {
        get(dockId);
        return schedules.findByDockIdOrderById(dockId);
    }

    @Transactional
    public DockScheduleEntity setScheduleEnabled(Long dockId, Long scheduleId, boolean enabled) {
        DockScheduleEntity s = schedules.findById(scheduleId)
                .filter(x -> java.util.Objects.equals(x.dockId, dockId))
                .orElseThrow(() -> new IllegalArgumentException("unknown schedule " + scheduleId));
        s.enabled = enabled;
        if (!enabled) {
            s.nextDueMs = null; // 重新启用时重算触发点
        }
        return schedules.save(s);
    }

    @Transactional
    public void deleteSchedule(Long dockId, Long scheduleId) {
        DockScheduleEntity s = schedules.findById(scheduleId)
                .filter(x -> java.util.Objects.equals(x.dockId, dockId))
                .orElseThrow(() -> new IllegalArgumentException("unknown schedule " + scheduleId));
        schedules.delete(s);
    }

    // ------------------------------------------------------------------
    // 离线巡检
    // ------------------------------------------------------------------

    /** 超过 offlineAfterPeriods × heartbeatPeriodMs 无心跳 → OFFLINE（不删记录）。 */
    @Scheduled(fixedDelayString = "${aerofleet.dock.heartbeat-period-ms:5000}")
    @Transactional
    public void offlineSweep() {
        long cutoff = System.currentTimeMillis()
                - props.getOfflineAfterPeriods() * props.getHeartbeatPeriodMs();
        for (DockEntity d : docks.findAll()) {
            if (d.state != DockState.OFFLINE && d.lastHeartbeatMs > 0 && d.lastHeartbeatMs < cutoff) {
                transition(d, DockState.OFFLINE,
                        "heartbeat timeout (" + props.getOfflineAfterPeriods() + " periods)");
                d.updatedAt = java.time.Instant.now();
                docks.save(d);
            }
        }
    }

    // ------------------------------------------------------------------

    /** 状态迁移统一出口：写日志 + 更新状态。非法迁移由调用方先经状态机裁决。 */
    void transition(DockEntity d, DockState to, String reason) {
        if (to == null || to == d.state) {
            return;
        }
        DockState from = d.state;
        d.state = to;
        d.updatedAt = java.time.Instant.now();
        stateLog.save(logEntry(d, from, to, reason));
        log.info("dock {} state {} -> {} ({})", d.sn, from, to, reason);
    }

    private DockStateLogEntity logEntry(DockEntity d, DockState from, DockState to, String reason) {
        DockStateLogEntity e = new DockStateLogEntity();
        e.dockId = d.id;
        e.fromState = from;
        e.toState = to;
        e.reason = reason;
        e.ts = System.currentTimeMillis();
        return e;
    }
}

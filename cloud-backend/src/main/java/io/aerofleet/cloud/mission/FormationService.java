package io.aerofleet.cloud.mission;

import io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException;
import io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.gateway.UdpGateway;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.LedControlMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 编队调度核心（FR-03/FR-04/FR-11/FR-14~FR-17，DFX 4.2 并发安全 + 单机故障隔离）。
 *
 * 持有 {@code ConcurrentHashMap<formationId, Formation>}，承载状态机 + 队形分配 +
 * 命令扇出 + 队形变换 + 灯光同步 + 单机脱离重整。
 *
 * 状态机：FORMING → STABLE → TRANSITIONING → STABLE → DISSOLVED
 *   - FORMING → STABLE：所有在线成员到位（由 FormationKeeper 检测）
 *   - STABLE → TRANSITIONING：收到变换命令（transition()）
 *   - TRANSITIONING → STABLE：变换完成（由 FormationKeeper 检测到位）
 *   - * → DISSOLVED：收到解散命令 或 成员 < 2
 *
 * 复用（只读/逐机，不修改既有服务）：
 *   - SquadRoleService：roleOf/leaderSysid/refresh/batteryCritical（只读）
 *   - DroneCommandService：command/takeoff/rtl（逐机）
 *   - DeviceRegistry：all/get（只读）
 *   - UdpGateway：send(sysid, ledFrame)（灯光帧 fire-and-forget）
 *   - FormationClock：formationClockUs/ready/pollLeaderHeartbeat（队内时钟基准）
 *
 * 并发安全：
 *   - formations ConcurrentHashMap：REST 线程写、Keeper/Pusher 读
 *   - nextFormationId AtomicInteger：创建线程并发分配 ID
 *   - Formation 内部 volatile state + AtomicInteger version（见 Formation javadoc）
 */
@Service
public class FormationService {

    private static final Logger log = LoggerFactory.getLogger(FormationService.class);

    /** 地球半径（m），等距矩形近似（与 drone-sim GeoUtil 一致）。 */
    private static final double EARTH_R = 6371000.0;

    /** 命令扇出并发超时（ms），单机超时不阻塞其余（DFX 4.2）。 */
    private static final long FANOUT_TIMEOUT_MS = 6_000;

    private final ConcurrentHashMap<Integer, Formation> formations = new ConcurrentHashMap<>();
    private final AtomicInteger nextFormationId = new AtomicInteger(0);
    private final AtomicInteger nextSeq = new AtomicInteger(0);

    private final SquadRoleService roles;
    private final DroneCommandService commands;
    private final DeviceRegistry registry;
    private final UdpGateway gateway;
    private final FormationClock formationClock;

    @Autowired
    private FormationRepository repository;

    public FormationService(SquadRoleService roles,
                           DroneCommandService commands,
                           DeviceRegistry registry,
                           UdpGateway gateway,
                           FormationClock formationClock) {
        this.roles = roles;
        this.commands = commands;
        this.registry = registry;
        this.gateway = gateway;
        this.formationClock = formationClock;
    }

    @PostConstruct
    void restoreFormations() {
        List<FormationEntity> entities = repository.findAll();
        if (entities.isEmpty()) {
            return;
        }
        int maxId = 0;
        for (FormationEntity entity : entities) {
            maxId = Math.max(maxId, entity.getFormationId());
            if (entity.getState() == Formation.FormationState.DISSOLVED) {
                continue;
            }
            Set<Integer> members = new HashSet<>(entity.getMembers());
            List<FormationGeometry.LocalPos> localPositions = FormationGeometry.compute(
                    entity.getShape(), members.size(), entity.getSpacing(), entity.getHeading());
            Map<Integer, Formation.GeoPos> assignments = assign(
                    members, localPositions, entity.getRefLat(), entity.getRefLon(), entity.getRefAlt());
            Formation f = new Formation(entity.getFormationId(), members, entity.getShape(),
                    entity.getSpacing(), entity.getHeading(), entity.getRefLat(), entity.getRefLon(),
                    entity.getRefAlt(), assignments, entity.getLeaderSysid());
            f.state = entity.getState();
            f.version.incrementAndGet();
            formations.put(entity.getFormationId(), f);
            log.info("formation {} restored: members={}, shape={}, state={}",
                    entity.getFormationId(), f.sortedMembers(), entity.getShape(), entity.getState());
        }
        nextFormationId.set(maxId);
        log.info("restored {} formations from database (nextFormationId={})",
                formations.size(), nextFormationId.get());
    }

    // =====================================================================
    // 公共结果类型
    // =====================================================================

    /** 单机命令 ACK 结果（不可变值对象）。 */
    public record AckResult(int result, String error) {
        public static AckResult ok() { return new AckResult(0, null); }
        public static AckResult fail(String msg) { return new AckResult(-1, msg); }
    }

    /** 编队创建结果（FR-14）。 */
    public record FormationCreateResult(int formationId,
                                        Map<Integer, Formation.GeoPos> assignments,
                                        Formation.FormationState state,
                                        int leaderSysid) {}

    /** 编队命令 DTO（FR-15）。 */
    public static final class FormationCommand {
        public enum Type { TAKEOFF, TRANSITION, LIGHTS, RTL, DISSOLVE }
        public final Type type;
        public final double alt;                       // TAKEOFF 高度
        public final FormationGeometry.Shape newShape; // TRANSITION 新队形
        public final int steps;                        // TRANSITION 插值步数
        public final LedControlCommand ledCommand;     // LIGHTS 灯光命令

        public FormationCommand(Type type, double alt,
                                FormationGeometry.Shape newShape, int steps,
                                LedControlCommand ledCommand) {
            this.type = type;
            this.alt = alt;
            this.newShape = newShape;
            this.steps = steps;
            this.ledCommand = ledCommand;
        }

        public static FormationCommand takeoff(double alt) {
            return new FormationCommand(Type.TAKEOFF, alt, null, 0, null);
        }
        public static FormationCommand rtl() {
            return new FormationCommand(Type.RTL, 0, null, 0, null);
        }
        public static FormationCommand dissolve() {
            return new FormationCommand(Type.DISSOLVE, 0, null, 0, null);
        }
        public static FormationCommand transition(FormationGeometry.Shape newShape, int steps) {
            return new FormationCommand(Type.TRANSITION, 0, newShape, steps, null);
        }
        public static FormationCommand lights(LedControlCommand led) {
            return new FormationCommand(Type.LIGHTS, 0, null, 0, led);
        }
    }

    // =====================================================================
    // 编队创建（FR-14）
    // =====================================================================

    /**
     * 创建编队：参数校验 → 选举 Leader → 几何计算 → 队形分配 → 入表（FR-14）。
     *
     * @throws BadRequestException 参数非法（members < 2、spacing < 2、经纬高非法）
     */
    @Transactional
    public FormationCreateResult create(FormationCreateRequest req) {
        // FR-02 参数校验
        if (req.members == null || req.members.size() < 2) {
            throw new BadRequestException("formation requires >= 2 members");
        }
        for (int sysid : req.members) {
            if (sysid < 1 || sysid > 254) {
                throw new BadRequestException("member sysid must be 1-254");
            }
        }
        if (req.spacing < FormationGeometry.MIN_SPACING_M) {
            throw new BadRequestException("spacing must be >= " + FormationGeometry.MIN_SPACING_M + "m");
        }
        if (req.heading < 0 || req.heading > 359) {
            throw new BadRequestException("heading must be 0-359");
        }
        if (req.refLat < -90 || req.refLat > 90 || req.refLon < -180 || req.refLon > 180) {
            throw new BadRequestException("reference lat/lon out of range");
        }
        if (req.refAlt < 0) {
            throw new BadRequestException("reference alt must be >= 0");
        }

        // FR-14 复用 SquadRoleService 确定 Leader（只读）
        roles.refresh();
        int leader = req.leaderSysid > 0 ? req.leaderSysid : roles.leaderSysid();
        if (leader <= 0) {
            throw new BadRequestException("no online leader available");
        }

        // FR-01 几何计算
        List<FormationGeometry.LocalPos> localPositions = FormationGeometry.compute(
                req.shape, req.members.size(), req.spacing, req.heading);

        // FR-03 队形分配（sysid 升序对应 slotIndex）
        Map<Integer, Formation.GeoPos> assignments = assign(
                req.members, localPositions, req.refLat, req.refLon, req.refAlt);

        // 创建编队实例
        int formationId = nextFormationId.incrementAndGet();
        Formation f = new Formation(formationId, req.members, req.shape,
                req.spacing, req.heading, req.refLat, req.refLon, req.refAlt,
                assignments, leader);
        f.state = Formation.FormationState.FORMING;
        f.version.incrementAndGet();
        formations.put(formationId, f);

        // 持久化编队配置到数据库（只保存核心配置数据，不保存运行时数据）
        repository.save(new FormationEntity(formationId, req.shape,
                req.spacing, req.heading, req.refLat, req.refLon, req.refAlt,
                leader, Formation.FormationState.FORMING,
                new ArrayList<>(req.members)));

        // 轮询 Leader 心跳以建立队内时钟基准（零修改 TelemetryIngestService）
        formationClock.pollLeaderHeartbeat(leader);

        log.info("formation {} created: members={}, shape={}, leader={}, state=FORMING",
                formationId, f.sortedMembers(), req.shape, leader);
        return new FormationCreateResult(formationId, assignments,
                Formation.FormationState.FORMING, leader);
    }

    // =====================================================================
    // 队形分配（FR-03）
    // =====================================================================

    /**
     * 按 sysid 升序排序成员，与目标位置序号一一对应，转为经纬高（FR-03）。
     */
    Map<Integer, Formation.GeoPos> assign(Set<Integer> members,
                                           List<FormationGeometry.LocalPos> localPositions,
                                           double refLat, double refLon, double refAlt) {
        List<Integer> sortedSysids = new ArrayList<>(members);
        Collections.sort(sortedSysids);
        Map<Integer, Formation.GeoPos> geoAssignments = new LinkedHashMap<>();
        for (int i = 0; i < sortedSysids.size(); i++) {
            FormationGeometry.LocalPos lp = localPositions.get(i);
            double lat = latOf(refLat, lp.north());
            double lon = lonOf(refLat, refLon, lp.east());
            geoAssignments.put(sortedSysids.get(i), new Formation.GeoPos(lat, lon, refAlt));
        }
        return geoAssignments;
    }

    // =====================================================================
    // 编队命令下发（FR-15）
    // =====================================================================

    /**
     * 编队命令下发：校验存在 + 非 DISSOLVED → switch 分发（FR-15）。
     * TRANSITION/LIGHTS 由专用方法处理，此处只处理 TAKEOFF/RTL/DISSOLVE。
     *
     * @throws NotFoundException 编队不存在
     * @throws BadRequestException 编队已解散
     */
    @Transactional
    public Map<Integer, AckResult> command(int formationId, FormationCommand cmd) {
        Formation f = formations.get(formationId);
        if (f == null) {
            throw new NotFoundException("formation " + formationId + " not found");
        }
        synchronized (f) {
            if (f.state == Formation.FormationState.DISSOLVED) {
                throw new BadRequestException("formation " + formationId + " dissolved");
            }
            return switch (cmd.type) {
                case TAKEOFF -> fanoutTakeoff(f, cmd.alt);
                case RTL -> fanoutRtl(f);
                case DISSOLVE -> dissolve(f);
                case TRANSITION -> {
                    // 变换由 transition() 处理，此处仅触发状态转移
                    // transition() 内部 synchronized(f) 可重入，不会死锁
                    transition(formationId, cmd.newShape, cmd.steps);
                    yield Collections.emptyMap();
                }
                case LIGHTS -> lights(formationId, cmd.ledCommand);
            };
        }
    }

    /** 编队起飞：逐机 TAKEOFF + per-sysid try-catch（单机故障隔离，FR-15/DFX 4.2）。 */
    private Map<Integer, AckResult> fanoutTakeoff(Formation f, double alt) {
        Map<Integer, AckResult> results = new ConcurrentHashMap<>();
        for (int sysid : f.sortedMembers()) {
            DroneSnapshot snap = registry.get(sysid);
            if (snap == null || !snap.online) {
                results.put(sysid, AckResult.fail("offline"));
                continue;
            }
            try {
                commands.takeoff(sysid, alt);
                results.put(sysid, AckResult.ok());
            } catch (Exception e) {
                log.warn("formation {} takeoff failed for sysid={}: {}",
                        f.formationId, sysid, e.getMessage());
                results.put(sysid, AckResult.fail(e.getMessage()));
            }
        }
        f.version.incrementAndGet();
        return results;
    }

    /** 编队返航：逐机 RTL + per-sysid try-catch。 */
    private Map<Integer, AckResult> fanoutRtl(Formation f) {
        Map<Integer, AckResult> results = new ConcurrentHashMap<>();
        for (int sysid : f.sortedMembers()) {
            DroneSnapshot snap = registry.get(sysid);
            if (snap == null || !snap.online) {
                results.put(sysid, AckResult.fail("offline"));
                continue;
            }
            try {
                commands.rtl(sysid);
                results.put(sysid, AckResult.ok());
            } catch (Exception e) {
                log.warn("formation {} rtl failed for sysid={}: {}",
                        f.formationId, sysid, e.getMessage());
                results.put(sysid, AckResult.fail(e.getMessage()));
            }
        }
        return results;
    }

    // =====================================================================
    // 队形平滑变换（FR-04）
    // =====================================================================

    /**
     * 队形变换：计算新队形 → 逐机线性插值生成 steps 航点 → 逐点下发 DO_REPOSITION
     * → state=TRANSITIONING（FR-04，线性插值无锐角折返）。
     *
     * @param formationId 编队 ID
     * @param newShape    新队形
     * @param steps       插值步数（>= 1）
     * @return 每架飞机的插值航点列表（sysid → 航点序列）
     * @throws NotFoundException 编队不存在
     * @throws BadRequestException steps < 1
     */

    public Map<Integer, List<Formation.GeoPos>> transition(int formationId,
                                                            FormationGeometry.Shape newShape,
                                                            int steps) {
        Formation f = formations.get(formationId);
        if (f == null) {
            throw new NotFoundException("formation " + formationId + " not found");
        }
        if (steps < 1) {
            throw new BadRequestException("steps must be >= 1");
        }
        Map<Integer, List<Formation.GeoPos>> waypoints;
        synchronized (f) {
            if (f.state == Formation.FormationState.DISSOLVED) {
                throw new BadRequestException("formation " + formationId + " dissolved");
            }

            // 计算新队形目标位置
            List<FormationGeometry.LocalPos> newLocal = FormationGeometry.compute(
                    newShape, f.members.size(), f.spacing, f.heading);
            Map<Integer, Formation.GeoPos> newTargets = assign(
                    f.members, newLocal, f.refLat, f.refLon, f.refAlt);

            // 逐机线性插值
            waypoints = new LinkedHashMap<>();
            for (int sysid : f.sortedMembers()) {
                Formation.GeoPos current = currentPosition(sysid, f);
                Formation.GeoPos target = newTargets.get(sysid);
                List<Formation.GeoPos> interp = new ArrayList<>(steps);
                for (int s = 0; s < steps; s++) {
                    double t = (s + 1.0) / steps;  // t 从 1/steps 到 1.0
                    double lat = current.lat() + (target.lat() - current.lat()) * t;
                    double lon = current.lon() + (target.lon() - current.lon()) * t;
                    double alt = current.alt() + (target.alt() - current.alt()) * t;
                    interp.add(new Formation.GeoPos(lat, lon, alt));
                }
                waypoints.put(sysid, interp);
            }

            // 状态转移：STABLE → TRANSITIONING（变换完成后由 Keeper 检测到位转回 STABLE）
            // synchronized(f) 保证 targetPositions.clear()/putAll() 原子，避免并发变换混入两个队形位置
            f.shape = newShape;
            f.targetPositions.clear();
            f.targetPositions.putAll(newTargets);
            f.state = Formation.FormationState.TRANSITIONING;
            f.version.incrementAndGet();

            // 同步更新持久化实体的状态和队形
            repository.findById(formationId).ifPresent(entity -> {
                entity.setState(Formation.FormationState.TRANSITIONING);
                entity.setShape(newShape);
                repository.save(entity);
            });
        }

        // 逐机逐点下发 DO_REPOSITION（即时机动引导，线性插值路径单调趋近目标，无折返）
        // 在 synchronized 块外执行，避免持锁等待网络
        for (var e : waypoints.entrySet()) {
            int sysid = e.getKey();
            DroneSnapshot snap = registry.get(sysid);
            if (snap == null || !snap.online) {
                log.warn("formation {} transition: sysid={} offline, skipped",
                        formationId, sysid);
                continue;
            }
            for (Formation.GeoPos p : e.getValue()) {
                try {
                    commands.command(sysid, MavEnums.MAV_CMD_DO_REPOSITION,
                            0, 0, 0, Float.NaN,
                            (float) p.lat(), (float) p.lon(), (float) p.alt());
                } catch (Exception ex) {
                    log.warn("formation {} transition DO_REPOSITION failed for sysid={}: {}",
                            formationId, sysid, ex.getMessage());
                    break;  // 该机失败，放弃后续航点，不阻塞其余
                }
            }
        }

        log.info("formation {} transition -> {} ({} steps), state=TRANSITIONING",
                formationId, newShape, steps);
        return waypoints;
    }

    /** 读取飞机当前位置；无遥测时回退到目标位置（避免插值起点 NaN）。 */
    private Formation.GeoPos currentPosition(int sysid, Formation f) {
        DroneSnapshot snap = registry.get(sysid);
        if (snap != null && !Double.isNaN(snap.lat) && !Double.isNaN(snap.lon)) {
            double alt = Double.isNaN(snap.relativeAlt) ? f.refAlt : snap.relativeAlt;
            return new Formation.GeoPos(snap.lat, snap.lon, alt);
        }
        Formation.GeoPos target = f.targetPositions.get(sysid);
        return target != null ? target : new Formation.GeoPos(f.refLat, f.refLon, f.refAlt);
    }

    // =====================================================================
    // 灯光同步（FR-11）
    // =====================================================================

    /**
     * 编队级灯光命令：统一 phaseStartUs → 逐机构造 LedControlMsg → gateway.send
     * fire-and-forget → 离线机跳过 + WARN（FR-11/异常 5.3.3-3）。
     *
     * @throws NotFoundException 编队不存在
     * @throws BadRequestException sync=true 但队内时钟未就绪
     */
    public Map<Integer, AckResult> lights(int formationId, LedControlCommand cmd) {
        Formation f = formations.get(formationId);
        if (f == null) {
            throw new NotFoundException("formation " + formationId + " not found");
        }
        if (f.state == Formation.FormationState.DISSOLVED) {
            throw new BadRequestException("formation " + formationId + " dissolved");
        }

        // FR-11 统一相位起点（队内时钟基准）
        long phaseStart = cmd.sync ? formationClock.formationClockUs() : 0;
        if (cmd.sync && !formationClock.ready()) {
            throw new BadRequestException("formation clock not ready (leader heartbeat pending)");
        }

        Map<Integer, AckResult> results = new ConcurrentHashMap<>();
        for (int sysid : f.sortedMembers()) {
            DroneSnapshot snap = registry.get(sysid);
            if (snap == null || !snap.online) {
                // 异常 5.3.3-3 目标飞机未在线：跳过，不阻塞其余
                log.warn("formation {} lights: sysid={} offline, skipped", formationId, sysid);
                results.put(sysid, AckResult.fail("offline"));
                continue;
            }
            try {
                LedControlMsg msg = new LedControlMsg(
                        sysid, 1,  // targetSystem=sysid, targetComponent=1(autopilot)
                        cmd.colorR, cmd.colorG, cmd.colorB,
                        cmd.pattern, cmd.brightness, cmd.freq,
                        cmd.on, cmd.sync, phaseStart,
                        0, 0);  // ledIndex=0(全部), transitionMs=0(立即)
                gateway.send(sysid, msg.toFrame(
                        UdpGateway.GCS_SYSID, UdpGateway.GCS_COMPID, nextSeq()));
                results.put(sysid, AckResult.ok());
            } catch (Exception e) {
                log.warn("formation {} lights failed for sysid={}: {}",
                        formationId, sysid, e.getMessage());
                results.put(sysid, AckResult.fail(e.getMessage()));
            }
        }
        // 更新编队灯光状态视图
        f.lastLightCommand = cmd;
        f.version.incrementAndGet();
        return results;
    }

    // =====================================================================
    // 单机脱离与队形重整（FR-17）
    // =====================================================================

    /**
     * 移除成员 → 成员 < 2 解散 → 否则重算队形 + state=TRANSITIONING + 下发 DO_REPOSITION（FR-17）。
     *
     * @throws NotFoundException 编队不存在
     */
    @Transactional
    public void removeMember(int formationId, int sysid) {
        Formation f = formations.get(formationId);
        if (f == null) {
            throw new NotFoundException("formation " + formationId + " not found");
        }
        synchronized (f) {
            if (!f.members.contains(sysid)) {
                throw new BadRequestException("sysid " + sysid + " not a member of formation " + formationId);
            }
            f.members.remove(sysid);
            f.targetPositions.remove(sysid);
            if (f.members.size() < 2) {
                // 成员不足：解散
                f.state = Formation.FormationState.DISSOLVED;
                f.version.incrementAndGet();

                // 同步更新持久化实体状态为 DISSOLVED
                repository.findById(formationId).ifPresent(entity -> {
                    entity.setState(Formation.FormationState.DISSOLVED);
                    entity.setMembers(new ArrayList<>(f.members));
                    repository.save(entity);
                });

                log.info("formation {} dissolved: < 2 members after removal of sysid={}",
                        formationId, sysid);
                return;
            }
            // 以剩余数量重新计算队形目标位置
            // 队形收缩可能违反几何约束（VEE/CIRCLE 要求 n>=3，DIAMOND 要求 n>=4），
            // 捕获 IllegalArgumentException 后降级为 LINE 队形（LINE 只要求 n>=2），
            // 避免"成员已移除但队形未重算"的不一致状态
            FormationGeometry.Shape effectiveShape = f.shape;
            List<FormationGeometry.LocalPos> newLocal;
            try {
                newLocal = FormationGeometry.compute(
                        f.shape, f.members.size(), f.spacing, f.heading);
            } catch (IllegalArgumentException e) {
                log.warn("formation {} shape {} infeasible for {} members, degrade to LINE: {}",
                        formationId, f.shape, f.members.size(), e.getMessage());
                effectiveShape = FormationGeometry.Shape.LINE;
                newLocal = FormationGeometry.compute(
                        effectiveShape, f.members.size(), f.spacing, f.heading);
            }
            Map<Integer, Formation.GeoPos> newTargets = assign(
                    f.members, newLocal, f.refLat, f.refLon, f.refAlt);
            f.shape = effectiveShape;
            f.targetPositions.clear();
            f.targetPositions.putAll(newTargets);
            f.state = Formation.FormationState.TRANSITIONING;
            f.version.incrementAndGet();

            // 同步更新持久化实体的状态和队形
            final FormationGeometry.Shape finalShape = effectiveShape;
            repository.findById(formationId).ifPresent(entity -> {
                entity.setState(Formation.FormationState.TRANSITIONING);
                entity.setShape(finalShape);
                entity.setMembers(new ArrayList<>(f.members));
                repository.save(entity);
            });

            // 下发调整命令（逐机 DO_REPOSITION）
            for (var e : newTargets.entrySet()) {
                int sid = e.getKey();
                DroneSnapshot snap = registry.get(sid);
                if (snap == null || !snap.online) {
                    continue;
                }
                Formation.GeoPos p = e.getValue();
                try {
                    commands.command(sid, MavEnums.MAV_CMD_DO_REPOSITION,
                            0, 0, 0, Float.NaN, (float) p.lat(), (float) p.lon(), (float) p.alt());
                } catch (Exception ex) {
                    log.warn("formation {} removeMember reposition failed for sysid={}: {}",
                            formationId, sid, ex.getMessage());
                }
            }
            log.info("formation {} member {} removed, recompute {} members -> TRANSITIONING",
                    formationId, sysid, f.members.size());
        }
    }

    // =====================================================================
    // 编队解散（FR-16）
    // =====================================================================

    /** 编队解散：在飞成员 RTL → state=DISSOLVED（异常 5.4.3-3）。 */
    private Map<Integer, AckResult> dissolve(Formation f) {
        Map<Integer, AckResult> results = new ConcurrentHashMap<>();
        for (int sysid : f.sortedMembers()) {
            DroneSnapshot snap = registry.get(sysid);
            if (snap != null && snap.online && snap.armed) {
                try {
                    commands.rtl(sysid);
                    results.put(sysid, AckResult.ok());
                } catch (Exception e) {
                    results.put(sysid, AckResult.fail(e.getMessage()));
                }
            } else {
                results.put(sysid, AckResult.fail("offline or disarmed"));
            }
        }
        f.state = Formation.FormationState.DISSOLVED;
        f.version.incrementAndGet();

        // 同步更新持久化实体状态为 DISSOLVED
        repository.findById(f.formationId).ifPresent(entity -> {
            entity.setState(Formation.FormationState.DISSOLVED);
            repository.save(entity);
        });

        log.info("formation {} dissolved", f.formationId);
        return results;
    }

    /** 将运行时 Formation 的当前状态回写到数据库（供 FormationKeeper 调用）。 */
    @Transactional
    void persistState(int formationId) {
        Formation f = formations.get(formationId);
        if (f == null) {
            return;
        }
        repository.findById(formationId).ifPresent(entity -> {
            entity.setState(f.state);
            repository.save(entity);
        });
    }

    // =====================================================================
    // 查询方法
    // =====================================================================

    /** 获取编队（只读视图）。 */
    public Formation formation(int id) {
        return formations.get(id);
    }

    /** 所有活跃编队（非 DISSOLVED）。 */
    public List<Formation> activeFormations() {
        List<Formation> out = new ArrayList<>();
        for (Formation f : formations.values()) {
            if (f.state != Formation.FormationState.DISSOLVED) {
                out.add(f);
            }
        }
        return out;
    }

    /** 所有编队（含已解散）。 */
    public List<Formation> allFormations() {
        return new ArrayList<>(formations.values());
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    private int nextSeq() {
        return nextSeq.getAndIncrement() & 0xFF;
    }

    /** 纬度（等距矩形近似，与 drone-sim GeoUtil 一致）。 */
    private static double latOf(double refLat, double northM) {
        return refLat + northM / metersPerDegLat();
    }

    /** 经度（等距矩形近似）。 */
    private static double lonOf(double refLat, double refLon, double eastM) {
        return refLon + eastM / metersPerDegLon(refLat);
    }

    private static double metersPerDegLat() {
        return Math.PI / 180.0 * EARTH_R;
    }

    private static double metersPerDegLon(double refLat) {
        return Math.PI / 180.0 * EARTH_R * Math.cos(Math.toRadians(refLat));
    }
}
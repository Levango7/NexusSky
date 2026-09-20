package io.aerofleet.cloud.mission;

import io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException;
import io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.gateway.UdpGateway;
import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * 编队调度核心单测（FR-03/FR-04/FR-11/FR-14~FR-17，DFX 4.2 单机故障隔离）。
 *
 * 真实对象：DeviceRegistry + SquadRoleService + FormationClock（构造简单）。
 * Mock：DroneCommandService + UdpGateway（构造复杂，逐机/灯光帧交互）。
 * 不起 Spring。
 */
class FormationServiceTest {

    private static final double HOME_LAT = 22.5907;
    private static final double HOME_LON = 113.9345;
    private static final double HOME_ALT = 50.0;

    private DeviceRegistry registry;
    private SquadRoleService roles;
    private FormationClock clock;
    private DroneCommandService commands;
    private UdpGateway gateway;
    private FormationService service;

    @BeforeEach
    void setUp() throws Exception {
        registry = new DeviceRegistry();
        roles = new SquadRoleService(registry);
        clock = new FormationClock(registry);
        commands = mock(DroneCommandService.class);
        gateway = mock(UdpGateway.class);
        service = new FormationService(roles, commands, registry, gateway, clock);

        // 创建 mock FormationRepository 模拟内存存储，反射注入到 @Autowired 字段
        FormationRepository mockRepo = mock(FormationRepository.class);
        java.util.Map<Integer, FormationEntity> formMap = new java.util.concurrent.ConcurrentHashMap<>();
        when(mockRepo.save(any(FormationEntity.class))).thenAnswer(inv -> {
            FormationEntity e = inv.getArgument(0);
            formMap.put(e.getFormationId(), e);
            return e;
        });
        when(mockRepo.findById(anyInt()))
                .thenAnswer(inv -> java.util.Optional.ofNullable(formMap.get(inv.getArgument(0))));
        java.lang.reflect.Field f = FormationService.class.getDeclaredField("repository");
        f.setAccessible(true);
        f.set(service, mockRepo);
    }

    /** 注册一架在线、电量充足的飞机。 */
    private DroneSnapshot online(int sysid) {
        DroneSnapshot d = registry.registerIfAbsent(sysid);
        d.online = true;
        d.battery = 90;
        d.lat = HOME_LAT;
        d.lon = HOME_LON;
        d.relativeAlt = HOME_ALT;
        d.armed = false;
        d.lastHeartbeatMs = System.currentTimeMillis();
        return d;
    }

    private FormationService.FormationCreateResult createLine(int... sysids) {
        Set<Integer> members = new java.util.HashSet<>();
        for (int s : sysids) {
            online(s);
            members.add(s);
        }
        FormationCreateRequest req = new FormationCreateRequest(
                members, FormationGeometry.Shape.LINE, 5.0, 0,
                HOME_LAT, HOME_LON, HOME_ALT, 0);
        return service.create(req);
    }

    @Test
    void createFormationReturnsAssignments() {
        // 创建 [1,2,3] LINE → formationId + 3 assignments + state=FORMING + leader=最小在线（FR-14）
        FormationService.FormationCreateResult r = createLine(1, 2, 3);
        assertTrue(r.formationId() > 0, "formationId assigned");
        assertEquals(3, r.assignments().size(), "3 assignments");
        assertEquals(Formation.FormationState.FORMING, r.state(), "state=FORMING");
        assertEquals(1, r.leaderSysid(), "lowest online sysid is leader");
    }

    @Test
    void assignBySysidAscending() {
        // 创建 [3,1,2] → sysid=1 分配 slot 0、sysid=2 slot 1、sysid=3 slot 2（FR-03）
        FormationService.FormationCreateResult r = createLine(3, 1, 2);
        List<FormationGeometry.LocalPos> slots = FormationGeometry.compute(
                FormationGeometry.Shape.LINE, 3, 5.0, 0);
        // slot 0 = 最小 sysid=1
        assertEquals(slots.get(0).north(), localNorth(r.assignments().get(1)), 1e-9);
        assertEquals(slots.get(0).east(), localEast(r.assignments().get(1)), 1e-9);
        // slot 1 = sysid=2
        assertEquals(slots.get(1).north(), localNorth(r.assignments().get(2)), 1e-9);
        // slot 2 = sysid=3
        assertEquals(slots.get(2).north(), localNorth(r.assignments().get(3)), 1e-9);
    }

    @Test
    void transitionGeneratesIntermediateWaypoints() {
        // LINE→CIRCLE steps=4 → 每机 4 航点，末=新目标（FR-04）
        FormationService.FormationCreateResult r = createLine(1, 2, 3);
        Map<Integer, List<Formation.GeoPos>> wp = service.transition(
                r.formationId(), FormationGeometry.Shape.CIRCLE, 4);
        for (int sysid : List.of(1, 2, 3)) {
            List<Formation.GeoPos> pts = wp.get(sysid);
            assertEquals(4, pts.size(), "4 waypoints per drone");
            // 末航点 = 新队形目标位置
            Formation.GeoPos target = service.formation(r.formationId()).targetPositions.get(sysid);
            assertEquals(target.lat(), pts.get(3).lat(), 1e-6, "last waypoint = target lat");
            assertEquals(target.lon(), pts.get(3).lon(), 1e-6, "last waypoint = target lon");
        }
        assertEquals(Formation.FormationState.TRANSITIONING,
                service.formation(r.formationId()).state, "state=TRANSITIONING");
    }

    @Test
    void transitionNoSharpTurnback() {
        // 变换路径单调趋近目标，无锐角折返（FR-04）
        FormationService.FormationCreateResult r = createLine(1, 2);
        Map<Integer, List<Formation.GeoPos>> wp = service.transition(
                r.formationId(), FormationGeometry.Shape.COLUMN, 5);
        for (int sysid : List.of(1, 2)) {
            List<Formation.GeoPos> pts = wp.get(sysid);
            Formation.GeoPos target = service.formation(r.formationId()).targetPositions.get(sysid);
            double prevDist = Double.MAX_VALUE;
            for (Formation.GeoPos p : pts) {
                double d = Math.hypot(p.lat() - target.lat(), p.lon() - target.lon());
                assertTrue(d <= prevDist + 1e-9, "distance to target non-increasing");
                prevDist = d;
            }
        }
    }

    @Test
    void fanoutCommandAggregatesAcks() {
        // 编队 RTL → 所有在线成员收到命令 + ACK 聚合（FR-15）
        FormationService.FormationCreateResult r = createLine(1, 2, 3);
        Map<Integer, FormationService.AckResult> results =
                service.command(r.formationId(), FormationService.FormationCommand.rtl());
        assertEquals(3, results.size(), "3 acks aggregated");
        for (FormationService.AckResult ack : results.values()) {
            assertEquals(0, ack.result(), "rtl accepted");
        }
        verify(commands, times(1)).rtl(1);
        verify(commands, times(1)).rtl(2);
        verify(commands, times(1)).rtl(3);
    }

    @Test
    void singleDroneTimeoutDoesNotBlockOthers() {
        // 一机超时（rtl 抛异常）→ 其余飞机命令仍下发（FR-15/DFX 4.2）
        FormationService.FormationCreateResult r = createLine(1, 2, 3);
        when(commands.rtl(2)).thenThrow(new RuntimeException("timeout"));
        Map<Integer, FormationService.AckResult> results =
                service.command(r.formationId(), FormationService.FormationCommand.rtl());
        assertEquals(-1, results.get(2).result(), "sysid=2 failed");
        assertEquals(0, results.get(1).result(), "sysid=1 still succeeded");
        assertEquals(0, results.get(3).result(), "sysid=3 still succeeded");
        verify(commands, times(1)).rtl(1);
        verify(commands, times(1)).rtl(3);
    }

    @Test
    void stateMachineTransitions() {
        // FORMING→STABLE→TRANSITIONING→STABLE→DISSOLVED 转移条件正确（FR-16）
        FormationService.FormationCreateResult r = createLine(1, 2);
        Formation f = service.formation(r.formationId());
        assertEquals(Formation.FormationState.FORMING, f.state, "FORMING after create");

        // FORMING → STABLE（模拟 Keeper 检测到位）
        f.state = Formation.FormationState.STABLE;
        f.version.incrementAndGet();
        assertEquals(Formation.FormationState.STABLE, f.state);

        // STABLE → TRANSITIONING（变换命令）
        service.transition(r.formationId(), FormationGeometry.Shape.COLUMN, 2);
        assertEquals(Formation.FormationState.TRANSITIONING, f.state, "TRANSITIONING after transition");

        // TRANSITIONING → STABLE（模拟 Keeper 检测到位）
        f.state = Formation.FormationState.STABLE;
        f.version.incrementAndGet();
        assertEquals(Formation.FormationState.STABLE, f.state);

        // STABLE → DISSOLVED（解散命令）
        service.command(r.formationId(), FormationService.FormationCommand.dissolve());
        assertEquals(Formation.FormationState.DISSOLVED, f.state, "DISSOLVED after dissolve");
    }

    @Test
    void removeMemberRecomputeFormation() {
        // 3 机 STABLE 移除 1 机 → 剩余 2 机重算 LINE + state=TRANSITIONING（FR-17）
        FormationService.FormationCreateResult r = createLine(1, 2, 3);
        Formation f = service.formation(r.formationId());
        f.state = Formation.FormationState.STABLE;
        service.removeMember(r.formationId(), 2);
        assertEquals(2, f.members.size(), "2 members remain");
        assertFalse(f.members.contains(2), "sysid=2 removed");
        assertEquals(Formation.FormationState.TRANSITIONING, f.state, "TRANSITIONING after recompute");
        assertEquals(2, f.targetPositions.size(), "2 target positions");
    }

    @Test
    void removedMemberNotReceivingCommands() {
        // 被移除机不再收到编队命令（FR-17）
        FormationService.FormationCreateResult r = createLine(1, 2, 3);
        Formation f = service.formation(r.formationId());
        f.state = Formation.FormationState.STABLE;
        service.removeMember(r.formationId(), 2);
        // RTL 扇出：sysid=2 不应收到
        reset(commands);
        service.command(r.formationId(), FormationService.FormationCommand.rtl());
        verify(commands, never()).rtl(2);
        verify(commands, times(1)).rtl(1);
        verify(commands, times(1)).rtl(3);
    }

    @Test
    void lightsFanoutToOnlineMembers() throws Exception {
        // 灯光命令 → 所有在线成员收到 + 统一 phaseStartUs（FR-11）
        FormationService.FormationCreateResult r = createLine(1, 2, 3);
        LedControlCommand led = new LedControlCommand(
                true, 255, 0, 0, 1, 80, 2, false);  // sync=false → phaseStart=0
        Map<Integer, FormationService.AckResult> results = service.lights(r.formationId(), led);
        assertEquals(3, results.size(), "3 lights results");
        for (FormationService.AckResult ack : results.values()) {
            assertEquals(0, ack.result(), "lights sent");
        }
        verify(gateway, times(3)).send(anyInt(), any(MavlinkFrame.class));
        // 编队灯光状态已更新
        assertEquals(led, service.formation(r.formationId()).lastLightCommand);
    }

    @Test
    void lightsOfflineMemberSkipped() throws Exception {
        // 离线机灯光跳过 + 不阻塞其余（异常 5.3.3-3）
        FormationService.FormationCreateResult r = createLine(1, 2, 3);
        registry.get(2).online = false;  // sysid=2 离线
        LedControlCommand led = new LedControlCommand(
                true, 255, 0, 0, 1, 80, 2, false);
        Map<Integer, FormationService.AckResult> results = service.lights(r.formationId(), led);
        assertEquals(-1, results.get(2).result(), "offline member skipped");
        assertEquals(0, results.get(1).result(), "online member 1 still sent");
        assertEquals(0, results.get(3).result(), "online member 3 still sent");
        verify(gateway, times(2)).send(anyInt(), any(MavlinkFrame.class));
    }

    @Test
    void lightsSyncRequiresClockReady() throws Exception {
        // sync=true 但时钟未就绪 → BadRequestException（FR-11）
        // 用 mock clock 模拟未就绪（create 会 pollLeaderHeartbeat，真实 clock 会就绪）
        FormationClock mockClock = mock(FormationClock.class);
        when(mockClock.ready()).thenReturn(false);
        when(mockClock.formationClockUs()).thenReturn(-1L);
        FormationService svc = new FormationService(roles, commands, registry, gateway, mockClock);

        // svc 也需要注入 mock FormationRepository
        FormationRepository mockRepo2 = mock(FormationRepository.class);
        java.util.Map<Integer, FormationEntity> formMap2 = new java.util.concurrent.ConcurrentHashMap<>();
        when(mockRepo2.save(any(FormationEntity.class))).thenAnswer(inv -> {
            FormationEntity e = inv.getArgument(0);
            formMap2.put(e.getFormationId(), e);
            return e;
        });
        when(mockRepo2.findById(anyInt()))
                .thenAnswer(inv -> java.util.Optional.ofNullable(formMap2.get(inv.getArgument(0))));
        java.lang.reflect.Field f2 = FormationService.class.getDeclaredField("repository");
        f2.setAccessible(true);
        f2.set(svc, mockRepo2);

        Set<Integer> members = new java.util.HashSet<>();
        for (int s : new int[]{1, 2}) {
            online(s);
            members.add(s);
        }
        FormationCreateRequest req = new FormationCreateRequest(
                members, FormationGeometry.Shape.LINE, 5.0, 0,
                HOME_LAT, HOME_LON, HOME_ALT, 0);
        FormationService.FormationCreateResult r = svc.create(req);

        LedControlCommand led = new LedControlCommand(
                true, 255, 0, 0, 1, 80, 2, true);  // sync=true
        // clock 未就绪 → ready()=false
        assertThrows(BadRequestException.class, () -> svc.lights(r.formationId(), led));
    }

    @Test
    void nonMemberRemovalRejected() {
        // 移除非成员 → BadRequestException（异常 5.3.3-1）
        FormationService.FormationCreateResult r = createLine(1, 2, 3);
        assertThrows(BadRequestException.class,
                () -> service.removeMember(r.formationId(), 99));
    }

    @Test
    void dissolveSendsRtlThenDissolved() {
        // 解散 → 在飞成员 RTL + state=DISSOLVED（异常 5.4.3-3）
        FormationService.FormationCreateResult r = createLine(1, 2);
        // 标记为已解锁（armed），dissolve 才会下发 RTL
        registry.get(1).armed = true;
        registry.get(2).armed = true;
        Map<Integer, FormationService.AckResult> results =
                service.command(r.formationId(), FormationService.FormationCommand.dissolve());
        assertEquals(Formation.FormationState.DISSOLVED,
                service.formation(r.formationId()).state, "DISSOLVED");
        verify(commands, times(1)).rtl(1);
        verify(commands, times(1)).rtl(2);
        assertEquals(2, results.size());
    }

    @Test
    void formationNotFoundReturns404() {
        // 不存在的 formationId → 查询返回 null，操作抛 NotFoundException（异常 5.4.3-1）
        assertNull(service.formation(99999), "formation() returns null for unknown id");
        assertThrows(NotFoundException.class,
                () -> service.command(99999, FormationService.FormationCommand.rtl()));
        assertThrows(NotFoundException.class,
                () -> service.transition(99999, FormationGeometry.Shape.LINE, 1));
    }

    @Test
    void createRejectsInvalidParameters() {
        // 参数校验（FR-02）
        // members < 2
        online(1);
        FormationCreateRequest r1 = new FormationCreateRequest(
                Set.of(1), FormationGeometry.Shape.LINE, 5.0, 0,
                HOME_LAT, HOME_LON, HOME_ALT, 0);
        assertThrows(BadRequestException.class, () -> service.create(r1));

        // spacing < 2
        online(2);
        FormationCreateRequest r2 = new FormationCreateRequest(
                Set.of(1, 2), FormationGeometry.Shape.LINE, 1.0, 0,
                HOME_LAT, HOME_LON, HOME_ALT, 0);
        assertThrows(BadRequestException.class, () -> service.create(r2));

        // heading 越界
        FormationCreateRequest r3 = new FormationCreateRequest(
                Set.of(1, 2), FormationGeometry.Shape.LINE, 5.0, 400,
                HOME_LAT, HOME_LON, HOME_ALT, 0);
        assertThrows(BadRequestException.class, () -> service.create(r3));
    }

    /** 从 GeoPos 反算本地 north 米（相对参考点）。 */
    private double localNorth(Formation.GeoPos p) {
        return (p.lat() - HOME_LAT) * Math.PI / 180.0 * 6371000.0;
    }

    private double localEast(Formation.GeoPos p) {
        return (p.lon() - HOME_LON) * Math.PI / 180.0 * 6371000.0
                * Math.cos(Math.toRadians(HOME_LAT));
    }
}
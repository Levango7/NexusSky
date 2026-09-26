package io.aerofleet.cloud.mission.formation;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.gateway.UdpGateway;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.cloud.mission.squad.SquadRoleService;
import io.aerofleet.mavlink.enums.MavEnums;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 队形保持与修复单测（FR-07/FR-08/FR-16）。
 *
 * 覆盖偏差容差判定、DO_REPOSITION 下发、TRANSITIONING→STABLE 检测。
 * 真实对象：DeviceRegistry + SquadRoleService + FormationClock + FormationService。
 * Mock：DroneCommandService + UdpGateway。
 * 不起 Spring。
 */
class FormationKeeperTest {

    private static final double HOME_LAT = 22.5907;
    private static final double HOME_LON = 113.9345;
    private static final double HOME_ALT = 50.0;
    /** 1 度纬度 ≈ 111km，0.00002 度 ≈ 2.2m（> 1m 容差）。 */
    private static final double OFFSET_2M_DEG = 0.00002;

    private DeviceRegistry registry;
    private FormationService service;
    private DroneCommandService commands;
    private FormationKeeper keeper;

    @BeforeEach
    void setUp() throws Exception {
        registry = new DeviceRegistry();
        SquadRoleService roles = new SquadRoleService(registry);
        FormationClock clock = new FormationClock(registry);
        commands = mock(DroneCommandService.class);
        UdpGateway gateway = mock(UdpGateway.class);
        service = new FormationService(roles, commands, registry, gateway, clock);
        keeper = new FormationKeeper(service, registry, commands);

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

    private DroneSnapshot online(int sysid) {
        DroneSnapshot d = registry.registerIfAbsent(sysid);
        d.online = true;
        d.battery = 90;
        d.lat = HOME_LAT;
        d.lon = HOME_LON;
        d.relativeAlt = HOME_ALT;
        d.lastHeartbeatMs = System.currentTimeMillis();
        return d;
    }

    private int createStableLine(int... sysids) {
        Set<Integer> members = new java.util.HashSet<>();
        for (int s : sysids) {
            online(s);
            members.add(s);
        }
        FormationCreateRequest req = new FormationCreateRequest(
                members, FormationGeometry.Shape.LINE, 5.0, 0,
                HOME_LAT, HOME_LON, HOME_ALT, 0);
        FormationService.FormationCreateResult r = service.create(req);
        Formation f = service.formation(r.formationId());
        f.state = Formation.FormationState.STABLE;
        // 将飞机位置设到目标位置（偏差=0）
        for (var e : f.targetPositions.entrySet()) {
            DroneSnapshot snap = registry.get(e.getKey());
            snap.lat = e.getValue().lat();
            snap.lon = e.getValue().lon();
            snap.relativeAlt = e.getValue().alt();
        }
        return r.formationId();
    }

    @Test
    void deviationWithinToleranceNoCorrection() {
        // 偏差 < 1m → 不下发 DO_REPOSITION（FR-07）
        int id = createStableLine(1, 2);
        keeper.check();
        verify(commands, never()).command(anyInt(), eq(MavEnums.MAV_CMD_DO_REPOSITION),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat());
        assertEquals(0, keeper.correctionCount(), "no corrections issued");
    }

    @Test
    void deviationBeyondToleranceSendsCorrection() {
        // 偏差 > 1m → 下发 DO_REPOSITION 到目标位置（FR-08）
        int id = createStableLine(1, 2);
        // 偏移 sysid=1 的位置 2m
        registry.get(1).lat = registry.get(1).lat + OFFSET_2M_DEG;
        keeper.check();
        verify(commands, times(1)).command(eq(1), eq(MavEnums.MAV_CMD_DO_REPOSITION),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat());
        assertEquals(1, keeper.correctionCount(), "1 correction issued");
    }

    @Test
    void correctionStopsWhenDeviationRecovers() {
        // 偏差回落后停止修正（FR-08）
        int id = createStableLine(1, 2);
        // 第一次：偏差 > 1m → 下发修正
        registry.get(1).lat = registry.get(1).lat + OFFSET_2M_DEG;
        keeper.check();
        assertEquals(1, keeper.correctionCount(), "1 correction after first check");
        // 第二次：位置恢复到目标（偏差=0）→ 不下发
        Formation f = service.formation(id);
        registry.get(1).lat = f.targetPositions.get(1).lat();
        keeper.check();
        assertEquals(1, keeper.correctionCount(), "no new correction after recovery");
        verify(commands, times(1)).command(eq(1), eq(MavEnums.MAV_CMD_DO_REPOSITION),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat());
    }

    @Test
    void transitioningToStableWhenAllInTolerance() {
        // TRANSITIONING + 所有到位 → STABLE（FR-16）
        int id = createStableLine(1, 2);
        Formation f = service.formation(id);
        f.state = Formation.FormationState.TRANSITIONING;
        keeper.check();
        assertEquals(Formation.FormationState.STABLE, f.state, "TRANSITIONING -> STABLE");
    }

    @Test
    void transitioningStaysWhenNotAllInTolerance() {
        // TRANSITIONING + 有成员未到位 → 保持 TRANSITIONING（FR-16）
        int id = createStableLine(1, 2);
        Formation f = service.formation(id);
        f.state = Formation.FormationState.TRANSITIONING;
        // 偏移 sysid=1 使其未到位
        registry.get(1).lat = registry.get(1).lat + OFFSET_2M_DEG;
        keeper.check();
        assertEquals(Formation.FormationState.TRANSITIONING, f.state, "stays TRANSITIONING");
    }

    @Test
    void onlyStableStateChecked() {
        // FORMING 状态不检查偏差（FR-07）
        int id = createStableLine(1, 2);
        Formation f = service.formation(id);
        f.state = Formation.FormationState.FORMING;
        // 偏移 sysid=1 的位置 2m
        registry.get(1).lat = registry.get(1).lat + OFFSET_2M_DEG;
        keeper.check();
        verify(commands, never()).command(anyInt(), eq(MavEnums.MAV_CMD_DO_REPOSITION),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat());
        assertEquals(0, keeper.correctionCount(), "FORMING: no corrections");
    }

    @Test
    void offlineMemberSkipped() {
        // 离线成员跳过，不阻塞其余（DFX 4.2）
        int id = createStableLine(1, 2);
        registry.get(1).online = false;
        // sysid=2 偏差 > 1m
        registry.get(2).lat = registry.get(2).lat + OFFSET_2M_DEG;
        keeper.check();
        // 仅 sysid=2 收到修正
        verify(commands, times(1)).command(eq(2), eq(MavEnums.MAV_CMD_DO_REPOSITION),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat());
        verify(commands, never()).command(eq(1), eq(MavEnums.MAV_CMD_DO_REPOSITION),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat());
    }
}
package io.aerofleet.cloud.mission.formation;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.UdpGateway;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.cloud.mission.squad.SquadRoleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FormationService#restoreFormations()} 的启动恢复口径。
 * <p>
 * 背景（2026-10-30）：原实现 {@code findAll()} 把**整张编队表**读进堆（该表只增——
 * {@code dissolve} 只把状态改成 {@code DISSOLVED}，不删行；见 {@code FormationService.java}
 * 的 dissolve 分支），然后再逐行跳过已解散行，只为两件事：恢复活跃编队、
 * 算 {@code nextFormationId}（maxId）。与 flightlog/audit/geofence 的"全量取回再截尾"
 * 是同族问题。
 * <p>
 * 这里钉三件事：
 * ① 只加载未解散的编队（{@code findRestorable}），**绝不**再碰 {@code findAll()}；
 * ② {@code nextFormationId} 取的是**含已解散行**的 MAX——若只统计未解散行，
 *    重启后新编队会复用已解散行的主键（{@code formation_id} 是 PK）；
 * ③ 脏数据（members 为 null/空）不毒死启动。
 */
class FormationStartupRestoreTest {

    private FormationService service;
    private FormationRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        DeviceRegistry registry = new DeviceRegistry();
        SquadRoleService roles = new SquadRoleService(registry);
        FormationClock clock = new FormationClock(registry);
        DroneCommandService commands = mock(DroneCommandService.class);
        UdpGateway gateway = mock(UdpGateway.class);
        service = new FormationService(roles, commands, registry, gateway, clock);

        repository = mock(FormationRepository.class);
        Field f = FormationService.class.getDeclaredField("repository");
        f.setAccessible(true);
        f.set(service, repository);
    }

    private static FormationEntity entity(int id, Formation.FormationState state, Integer tenantId,
                                          List<Integer> members) {
        FormationEntity e = entityShell(id, state, tenantId);
        e.setMembers(members);
        return e;
    }

    /**
     * members 为 null 的脏数据行。
     * <p>
     * {@code setMembers} 带防御性拷贝（{@code new ArrayList<>(members)}），传 null 会 NPE，
     * 无法用它模拟"历史脏数据给 null"——那正是启动恢复的注释里说过的毒点
     * （{@code @PostConstruct} 里一个 NPE 会让整个 backend 起不来），故直接反射置空。
     */
    private static FormationEntity entityWithNullMembers(int id, Formation.FormationState state,
                                                         Integer tenantId) throws Exception {
        FormationEntity e = entityShell(id, state, tenantId);
        Field f = FormationEntity.class.getDeclaredField("members");
        f.setAccessible(true);
        f.set(e, null);
        return e;
    }

    private static FormationEntity entityShell(int id, Formation.FormationState state, Integer tenantId) {
        FormationEntity e = new FormationEntity();
        e.setFormationId(id);
        e.setShape(FormationGeometry.Shape.LINE);
        e.setSpacing(10.0);
        e.setHeading(0);
        e.setRefLat(22.5907);
        e.setRefLon(113.9345);
        e.setRefAlt(50.0);
        e.setLeaderSysid(1);
        e.setState(state);
        e.setTenantId(tenantId);
        return e;
    }

    private int nextFormationId() throws Exception {
        Field f = FormationService.class.getDeclaredField("nextFormationId");
        f.setAccessible(true);
        return ((AtomicInteger) f.get(service)).get();
    }

    @Test
    @DisplayName("启动恢复只加载未解散编队；nextFormationId 取含已解散行的 MAX，不复用主键")
    void restoreLoadsOnlyActiveAndKeepsMaxIncludingDissolved() throws Exception {
        // 已解散的 id=7（时间上更晚），活跃的 id=5
        when(repository.findMaxFormationId()).thenReturn(7);
        when(repository.findRestorable(Formation.FormationState.DISSOLVED))
                .thenReturn(List.of(entity(5, Formation.FormationState.STABLE, 1,
                        new ArrayList<>(List.of(1, 2)))));

        service.restoreFormations();

        assertThat(service.allFormations())
                .extracting(f -> f.formationId)
                .as("已解散行不进运行时 map")
                .containsExactly(5);
        assertThat(nextFormationId())
                .as("下一个 ID 从含已解散行的 MAX 续接：7 ⇒ 新建得 8，不会复用 7")
                .isEqualTo(7);
        verify(repository, never()).findAll();
    }

    @Test
    @DisplayName("空库：nextFormationId 归 0，不抛异常、不调用 findAll()")
    void restoreHandlesEmptyDatabase() throws Exception {
        when(repository.findMaxFormationId()).thenReturn(null);
        when(repository.findRestorable(Formation.FormationState.DISSOLVED)).thenReturn(List.of());

        service.restoreFormations();

        assertThat(service.allFormations()).isEmpty();
        assertThat(nextFormationId()).isZero();
        verify(repository, never()).findAll();
    }

    @Test
    @DisplayName("脏数据（members 为 null）不毒死启动：跳过该行，其余照常恢复")
    void restoreSkipsRowWithNullMembers() throws Exception {
        when(repository.findMaxFormationId()).thenReturn(9);
        when(repository.findRestorable(Formation.FormationState.DISSOLVED))
                .thenReturn(List.of(
                        entityWithNullMembers(9, Formation.FormationState.STABLE, 1),
                        entity(4, Formation.FormationState.FORMING, 1,
                                new ArrayList<>(List.of(3, 6)))));

        service.restoreFormations();

        assertThat(service.allFormations())
                .extracting(f -> f.formationId)
                .containsExactly(4);
        assertThat(nextFormationId()).isEqualTo(9);
    }

    @Test
    @DisplayName("仓储抛异常只告警：启动不因编队表而失败（原语义保持）")
    void restoreSurvivesRepositoryFailure() throws Exception {
        when(repository.findMaxFormationId()).thenThrow(new RuntimeException("db down"));
        when(repository.findRestorable(any())).thenReturn(List.of());

        service.restoreFormations();

        assertThat(service.allFormations()).isEmpty();
    }
}

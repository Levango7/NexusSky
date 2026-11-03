package io.aerofleet.cloud.mission.formation;

import io.aerofleet.cloud.api.exception.ApiExceptionHandler.NotFoundException;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.mission.squad.SquadRoleService;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.cloud.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

/**
 * {@link FormationService} 编队归属的**租户隔离**回归测试。
 *
 * <h2>被钉住的缺陷（两个，同源）</h2>
 *
 * <ol>
 *   <li><b>映射缺失即放行</b>：{@code isFormationForTenant} 原写法是
 *       {@code formationTenant == null || tenantId.equals(formationTenant)}，与
 *       #235 的 {@code GeofenceStore.isZoneForTenant}、#223 的
 *       {@code EmergencyOrchService.checkTenantAccess} 同族。两条路径让映射稳定缺失：
 *       全局管理员建编队（创建路径的 {@code if (tenantId != null)} 门槛不登记）、
 *       以及重启后 DB 恢复的同类门槛。</li>
 *   <li><b>写方法完全不设卡</b>：{@code command} / {@code transition} / {@code lights} /
 *       {@code removeMember} 四个写方法此前直接 {@code formations.get(...)}，
 *       任何租户只要猜到 formationId 就能对他人的编队下令、改队形、摘成员。</li>
 * </ol>
 *
 * <h2>本类证明的不变式</h2>
 *
 * 未归属编队<b>只对全局管理员可见</b>；租户会话对未归属/他租户编队的读与写都按
 * 「编队不存在」响应（不泄露存在性）；口径与 {@code DeviceRegistry.isVisibleTo} 对齐。
 */
@DisplayName("FormationService 编队归属的租户隔离")
class FormationTenantIsolationTest {

    private FormationService service;

    @BeforeEach
    void setUp() {
        DeviceRegistry registry = new DeviceRegistry();
        service = new FormationService(new SquadRoleService(registry),
                mock(DroneCommandService.class),
                registry,
                null,
                new FormationClock(registry));
        // 纯内存模式：repository 缺失（多数既有单测同款姿势），走内存不落库
        ReflectionTestUtils.setField(service, "repository", null);
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** 合法的最小创建请求（2 架机，满足 FR-02 的 members >= 2）。 */
    private FormationCreateRequest twoPlanes() {
        return new FormationCreateRequest(Set.of(1, 2), FormationGeometry.Shape.LINE,
                10.0, 90.0, 39.9, 116.3, 100.0, 1);
    }

    private int createAsGlobalAdmin() {
        TenantContext.clear();
        return service.create(twoPlanes()).formationId();
    }

    // ---------------------------------------------------------------- 读侧

    @Test
    @DisplayName("未归属编队（全局管理员所建）：租户会话读不到、列表不含")
    void unownedFormationIsInvisibleToTenantSessions() {
        int id = createAsGlobalAdmin();

        TenantContext.setTenantId(7);
        assertThat(service.formation(id))
                .as("未归属编队不应对租户会话可见")
                .isNull();
        assertThat(service.activeFormations())
                .as("列表也不得包含未归属编队")
                .isEmpty();
        assertThat(service.allFormations())
                .as("含已解散的列表同样不得包含")
                .isEmpty();
    }

    @Test
    @DisplayName("全局管理员视角：未归属编队照常可读")
    void globalAdminStillReadsUnownedFormations() {
        int id = createAsGlobalAdmin();

        assertThat(service.formation(id)).isNotNull();
        assertThat(service.activeFormations()).hasSize(1);
    }

    @Test
    @DisplayName("归属明确的编队：本租户可读，他租户按不存在")
    void ownedFormationIsIsolatedBetweenTenants() {
        TenantContext.setTenantId(7);
        int id = service.create(twoPlanes()).formationId();

        TenantContext.setTenantId(8);
        assertThat(service.formation(id)).isNull();
        assertThat(service.activeFormations()).isEmpty();

        TenantContext.setTenantId(7);
        assertThat(service.formation(id)).isNotNull();
        assertThat(service.activeFormations()).hasSize(1);
    }

    // ---------------------------------------------------------------- 写侧

    @Test
    @DisplayName("写方法对他租户编队：一律按「不存在」拒绝，不泄露存在性")
    void writesOnForeignFormationAreRejectedAsNotFound() {
        TenantContext.setTenantId(7);
        int id = service.create(twoPlanes()).formationId();

        TenantContext.setTenantId(8);
        assertThatThrownBy(() -> service.transition(id, FormationGeometry.Shape.COLUMN, 2))
                .as("跨租户改队形必须拒绝")
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.command(id,
                FormationService.FormationCommand.rtl()))
                .as("跨租户下令必须拒绝")
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.lights(id, null))
                .as("跨租户灯光命令必须拒绝")
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.removeMember(id, 1))
                .as("跨租户摘成员必须拒绝")
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("未归属编队：租户会话的写方法同样被拒（映射缺失不是授权）")
    void writesOnUnownedFormationAreRejected() {
        int id = createAsGlobalAdmin();

        TenantContext.setTenantId(7);
        assertThatThrownBy(() -> service.transition(id, FormationGeometry.Shape.COLUMN, 2))
                .as("映射缺失不得被当成授权")
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("全局管理员的写方法不受影响（不因收紧把管理员挡掉）")
    void globalAdminWritesStillWork() {
        int id = createAsGlobalAdmin();

        Map<Integer, List<Formation.GeoPos>> waypoints =
                service.transition(id, FormationGeometry.Shape.COLUMN, 2);
        assertThat(waypoints)
                .as("管理员改队形应照常返回 2 架机的航点")
                .isNotEmpty();
    }

    @Test
    @DisplayName("纯逻辑探针：从未创建的 formationId 对租户会话必须「不存在」")
    void regressionProof_lookupMissMustNotAllow() {
        TenantContext.setTenantId(7);
        assertThat(service.formation(9999))
                .as("映射缺失不得被当成授权")
                .isNull();
        assertThatThrownBy(() -> service.transition(9999, FormationGeometry.Shape.COLUMN, 2))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("回归证明：把 isFormationForTenant 改回「缺失即放行」会让读侧断言红")
    void regressionProof_missMustNotAllow() {
        // 机械形式的 A/B 锚点：下面的读侧用例依赖 formationTenant != null && equals(...)。
        // 若退回 formationTenant == null || equals(...)，unownedFormationIsInvisibleToTenantSessions
        // 会立刻红。此处用等价的纯逻辑探针把形状钉住，避免依赖用例顺序。
        int id = createAsGlobalAdmin();
        TenantContext.setTenantId(7);
        assertThat(service.formation(id)).isNull();
    }
}

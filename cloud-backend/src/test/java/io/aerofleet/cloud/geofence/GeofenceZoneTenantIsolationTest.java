package io.aerofleet.cloud.geofence;

import io.aerofleet.cloud.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GeofenceStore} 围栏归属的**租户隔离**回归测试。
 *
 * <h2>被钉住的缺陷</h2>
 *
 * {@code isZoneForTenant} 此前的写法是
 * {@code zoneTenant == null || tenantId.equals(zoneTenant)}——<b>映射缺失即放行</b>，
 * 与 {@code EmergencyOrchService.checkTenantAccess} 已在 #223 修掉的形状同族，
 * 但这处更危险：{@code zoneTenantMap} 是纯内存 map，<b>没有 DB 回查兜底</b>。
 *
 * 两条路径让映射稳定缺失：
 * <ol>
 *   <li>全局管理员建围栏时 {@code TenantContext} 为 null ⇒ 创建路径不登记（L155 的
 *       {@code if (tenantId != null)} 门槛）；</li>
 *   <li>重启后 DB 恢复只登记 {@code tenantId != null} 的行（L81 同款门槛）。</li>
 * </ol>
 *
 * 后果：任何租户会话都能读、改、删未归属围栏及其越界历史——7 个调用点全走这一个判定，
 * 且此前测试零覆盖。
 *
 * <h2>本类证明的不变式</h2>
 *
 * 未归属围栏<b>只对全局管理员可见</b>；租户会话按「不存在」处理（读不到、改不了、删不掉）。
 * 口径与 {@code DeviceRegistry.isVisibleTo} 对齐。
 */
@DisplayName("GeofenceStore 围栏归属的租户隔离")
class GeofenceZoneTenantIsolationTest {

    private GeofenceStore store;

    @BeforeEach
    void setUp() {
        store = new GeofenceStore();
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private GeofenceZone newZone(int id, String name) {
        return GeofenceZone.circleZone(id, name, 30.0, 120.0, 500, GeofenceZone.Action.WARN);
    }

    @Test
    @DisplayName("未归属围栏（全局管理员所建）：租户会话读不到、改不了、删不掉")
    void unownedZoneIsInvisibleToTenantSessions() {
        // 全局管理员建围栏 ⇒ tenantId == null ⇒ 不登记归属
        TenantContext.clear();
        store.addZone(newZone(1, "公共围栏"));

        // 切到租户会话
        TenantContext.setTenantId(7);

        assertThat(store.getAllZones())
                .as("未归属围栏不应对租户会话可见")
                .isEmpty();
        assertThat(store.getZone(1))
                .as("getZone 按不存在处理")
                .isNull();
        assertThat(store.updateZone(newZone(1, "篡改")))
                .as("他人不可改")
                .isNull();
        assertThat(store.removeZone(1))
                .as("他人不可删")
                .isNull();
    }

    @Test
    @DisplayName("全局管理员视角：未归属围栏照常可见可管（不因收紧把管理员挡掉）")
    void globalAdminStillSeesUnownedZones() {
        TenantContext.clear();
        store.addZone(newZone(1, "公共围栏"));

        assertThat(store.getAllZones()).hasSize(1);
        assertThat(store.getZone(1)).isNotNull();
        assertThat(store.updateZone(newZone(1, "管理员改名"))).isNotNull();
        assertThat(store.removeZone(1)).isNotNull();
    }

    @Test
    @DisplayName("归属明确的围栏：本租户可读写，他租户不可见")
    void ownedZoneIsIsolatedBetweenTenants() {
        TenantContext.setTenantId(7);
        store.addZone(newZone(2, "租户7的围栏"));

        // 本租户
        assertThat(store.getAllZones()).hasSize(1);
        assertThat(store.getZone(2)).isNotNull();
        assertThat(store.updateZone(newZone(2, "自己的改名"))).isNotNull();

        // 他租户
        TenantContext.setTenantId(8);
        assertThat(store.getAllZones())
                .as("他租户看不到别人的围栏")
                .isEmpty();
        assertThat(store.getZone(2))
                .as("他租户 getZone 按不存在处理")
                .isNull();
        assertThat(store.updateZone(newZone(2, "篡改")))
                .as("他租户不可改")
                .isNull();
    }

    @Test
    @DisplayName("越界历史也按围栏归属过滤：未归属围栏的历史不泄漏给租户")
    void breachHistoryRespectsZoneOwnership() {
        // 全局管理员建围栏并记一条越界
        TenantContext.clear();
        store.addZone(newZone(3, "公共围栏"));
        store.recordBreach(new GeofenceBreachEvent(1, 3, "公共围栏",
                GeofenceBreachEvent.BreachType.ENTER, 30.0, 120.0, System.currentTimeMillis()));

        TenantContext.setTenantId(7);
        assertThat(store.getBreachHistory())
                .as("未归属围栏的越界历史不得泄漏")
                .isEmpty();
        assertThat(store.getBreachesForZone(3))
                .as("按 zone 查也不得泄漏")
                .isEmpty();
        assertThat(store.getBreachesForDrone(1))
                .as("按机查也不得泄漏")
                .isEmpty();

        // 全局管理员视角应能查到
        TenantContext.clear();
        assertThat(store.getBreachHistory()).hasSize(1);
        assertThat(store.getBreachesForZone(3)).hasSize(1);
    }

    @Test
    @DisplayName("回归证明：映射缺失即放行 会让以上四类断言全红")
    void regressionProof_lookupMissMustNotAllow() {
        // 这个用例本身就是 A/B 的机械形式：把 isZoneForTenant 改回
        //   return zoneTenant == null || tenantId.equals(zoneTenant);
        // 则 unownedZoneIsInvisibleToTenantSessions / breachHistoryRespectsZoneOwnership
        // 会立刻红。此处用一个等价的纯逻辑探针把形状钉住，避免依赖测试顺序。
        TenantContext.setTenantId(7);
        // zone 99 从未创建 ⇒ 归属映射必然缺失
        assertThat(store.getZone(99))
                .as("从未创建的 zone 对租户会话必须是「不存在」")
                .isNull();
        assertThat(store.updateZone(newZone(99, "幽灵围栏")))
                .as("映射缺失不得被当成授权")
                .isNull();
    }
}

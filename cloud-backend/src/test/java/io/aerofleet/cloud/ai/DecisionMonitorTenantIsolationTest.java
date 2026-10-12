package io.aerofleet.cloud.ai;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 决策监控的租户隔离回归（2026-10-12，外部评估报告 P1：ai 出口漏滤，可跨租户读取）。
 *
 * <p>修前：{@code GET /api/v1/ai/decisions} 直接回全量分桶、
 * {@code /decisions/{sysid}} 不判归属——任一租户的 OBSERVER 能看到别人设备的决策历史。
 * 修后按 {@code DeviceRegistry.tenantOf(sysid)} 过滤：租户会话只看本租户设备，
 * 未归属设备只对全局管理员可见（口径同 dock/alarm）。
 */
@DisplayName("AI 决策监控：按设备租户归属过滤")
class DecisionMonitorTenantIsolationTest {

    private DecisionMonitorService service;
    private DeviceRegistry registry;

    @BeforeEach
    void setUp() {
        service = new DecisionMonitorService();
        service.recordDecision(2, "RTL", "low link", 0.9);
        service.recordDecision(3, "AVOID", "obstacle", 0.8);

        registry = mock(DeviceRegistry.class);
        when(registry.tenantOf(2)).thenReturn(42);
        when(registry.tenantOf(3)).thenReturn(43);
    }

    @AfterEach
    void tearDown() {
        TenantContext.setTenantId(null);
    }

    @Test
    @DisplayName("租户会话：单机查询只回本租户设备的决策，他租户返空")
    void droneDecisionsScopedByTenant() {
        DecisionMonitorController controller = new DecisionMonitorController(service, registry);

        TenantContext.setTenantId(42);
        List<Map<String, Object>> mine = controller.droneDecisions(2);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("type")).isEqualTo("RTL");

        assertThat(controller.droneDecisions(3)).isEmpty();
    }

    @Test
    @DisplayName("租户会话：全量查询只含本租户设备的分桶")
    void allDecisionsScopedByTenant() {
        DecisionMonitorController controller = new DecisionMonitorController(service, registry);

        TenantContext.setTenantId(42);
        assertThat(controller.allDecisions().keySet()).containsExactly(2);

        TenantContext.setTenantId(43);
        assertThat(controller.allDecisions().keySet()).containsExactly(3);
    }

    @Test
    @DisplayName("全局管理员：看全部（含未归属设备）")
    void globalScopeSeesAll() {
        DecisionMonitorController controller = new DecisionMonitorController(service, registry);
        TenantContext.setTenantId(null);
        assertThat(controller.allDecisions().keySet()).containsExactlyInAnyOrder(2, 3);
        assertThat(controller.droneDecisions(2)).hasSize(1);
        assertThat(controller.droneDecisions(3)).hasSize(1);
    }

    @Test
    @DisplayName("未归属设备的决策：租户会话看不到，全局管理员可以")
    void unassignedDeviceHiddenFromTenants() {
        service.recordDecision(99, "ADAPT_PATH", "wind", 0.7);   // registry.tenantOf(99) = null
        DecisionMonitorController controller = new DecisionMonitorController(service, registry);

        TenantContext.setTenantId(42);
        assertThat(controller.droneDecisions(99)).isEmpty();
        assertThat(controller.allDecisions().keySet()).containsExactly(2);

        TenantContext.setTenantId(null);
        assertThat(controller.droneDecisions(99)).hasSize(1);
        assertThat(controller.allDecisions().keySet()).containsExactlyInAnyOrder(2, 3, 99);
    }
}

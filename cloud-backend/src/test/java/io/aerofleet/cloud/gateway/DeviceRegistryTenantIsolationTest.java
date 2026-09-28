package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设备租户可见性测试（P0-1）。
 * <p>
 * 设备快照由 UDP 接收线程创建，该线程无请求上下文，注册时 tenantId 恒为 null。
 * 修复前 null 被当作"对所有租户可见"，任何租户都能给未归属设备下发指令；
 * 修复后 null 语义为"未归属"，只对全局管理员上下文（有效租户 ID 为 null）可见。
 */
@DisplayName("DeviceRegistry 租户可见性 (P0-1)")
class DeviceRegistryTenantIsolationTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("未归属设备对租户上下文不可见（get 与 all）")
    void unassignedDeviceInvisibleToTenant() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(7);

        TenantContext.setTenantId(2);
        assertThat(registry.get(7)).isNull();
        assertThat(registry.all()).isEmpty();
    }

    @Test
    @DisplayName("未归属设备对全局管理员上下文可见")
    void unassignedDeviceVisibleToGlobalAdmin() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(7);

        TenantContext.setTenantId(null);
        assertThat(registry.get(7)).isNotNull();
        assertThat(registry.all()).hasSize(1);
    }

    @Test
    @DisplayName("assignTenant 后仅归属租户可见，其他租户不可见")
    void assignedDeviceVisibleOnlyToOwningTenant() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(7);

        assertThat(registry.assignTenant(7, 2)).isTrue();

        TenantContext.setTenantId(2);
        assertThat(registry.get(7)).isNotNull();
        assertThat(registry.all()).hasSize(1);

        TenantContext.setTenantId(3);
        assertThat(registry.get(7)).isNull();
        assertThat(registry.all()).isEmpty();
    }

    @Test
    @DisplayName("assignTenant 可解绑，解绑后回到未归属（租户均不可见）")
    void unassignReturnsDeviceToUnowned() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(7);
        registry.assignTenant(7, 2);

        TenantContext.setTenantId(2);
        assertThat(registry.get(7)).isNotNull();

        assertThat(registry.assignTenant(7, null)).isTrue();
        assertThat(registry.get(7)).isNull();
        assertThat(registry.unassigned()).containsExactly(7);
    }

    @Test
    @DisplayName("unassigned 只列出无归属设备")
    void unassignedListsOnlyUnowned() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(7);
        registry.registerIfAbsent(8);
        registry.assignTenant(8, 1);

        assertThat(registry.unassigned()).containsExactly(7);
    }

    @Test
    @DisplayName("内存态下未知 sysid 指派返回 false（persist=false 无仓库）")
    void assignUnknownDeviceFailsWithoutPersistence() {
        DeviceRegistry registry = new DeviceRegistry();

        assertThat(registry.assignTenant(99, 1)).isFalse();
        assertThat(registry.get(99)).isNull();
    }

    @Test
    @DisplayName("心跳不会覆盖已指派的归属")
    void heartbeatDoesNotOverwriteAssignment() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(7);
        registry.assignTenant(7, 2);

        // 后续心跳走 registerIfAbsent，已存在的快照不会被重建
        registry.registerIfAbsent(7);

        TenantContext.setTenantId(2);
        assertThat(registry.get(7)).isNotNull();
    }
}

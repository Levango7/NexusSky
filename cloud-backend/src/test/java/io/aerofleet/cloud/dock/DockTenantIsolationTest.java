package io.aerofleet.cloud.dock;

import io.aerofleet.cloud.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 机巢租户隔离回归（2026-10-12，外部评估报告 P1：dock 出口漏滤，可跨租户读取）。
 *
 * <p>修前三处硬伤：
 * <ol>
 *   <li>{@code register} 把 tenantId 硬编码 null ⇒ 机巢永远"未归属"，
 *       任何具体租户的 OBSERVER 在列表里看到全部机巢（含位置）；</li>
 *   <li>{@code list()} 直接 {@code findAll()}，无租户过滤；</li>
 *   <li>{@code get(id)} / {@code recentLog()} 不判可见性，越权 ID 直接返回数据。</li>
 * </ol>
 * 口径与 {@code AlarmEventStore.isVisibleTo()} 一致：null 域=全局管理员看全部；
 * 否则只看本租户；未归属资源对任何具体租户都不可见；越权应答与"不存在"相同（不制造探测面）。
 */
@DisplayName("机巢租户隔离：归属写入方 + 列表/详情/日志按租户过滤")
class DockTenantIsolationTest {

    private DockRepository docks;
    private DockStateLogRepository stateLog;
    private DockService service;

    private DockEntity dock(int id, String tenantId) {
        DockEntity d = new DockEntity();
        d.name = "dock-" + id;
        d.tenantId = tenantId;
        return d;
    }

    @BeforeEach
    void setUp() {
        docks = mock(DockRepository.class);
        stateLog = mock(DockStateLogRepository.class);
        service = new DockService(docks, stateLog,
                mock(io.aerofleet.cloud.dock.DockScheduleRepository.class),
                new DockStateMachine(), mock(DockGateway.class), new DockProperties());
    }

    @AfterEach
    void tearDown() {
        TenantContext.setTenantId(null);
    }

    @Test
    @DisplayName("register 落库的 tenantId = 调用方传入的归属（控制器传当前租户）")
    void registerPersistsGivenTenant() {
        when(docks.findBySn(anyString())).thenReturn(Optional.empty());

        service.register("dock", "model", "SN-1", 1.0, 2.0, 7, "42");

        org.mockito.ArgumentCaptor<DockEntity> captor =
                org.mockito.ArgumentCaptor.forClass(DockEntity.class);
        verify(docks).save(captor.capture());
        assertThat(captor.getValue().tenantId).isEqualTo("42");
    }

    @Test
    @DisplayName("控制器侧：全局管理员创建时机巢为未归属（null），不被哨兵污染")
    void registerGlobalScopeStaysUnassigned() {
        TenantContext.setTenantId(null);
        // 控制器把 getWritableTenantId() 转成 String：null 必须仍是 null
        Integer writable = TenantContext.getWritableTenantId();
        assertThat(writable).isNull();

        when(docks.findBySn(anyString())).thenReturn(Optional.empty());
        service.register("dock", "model", "SN-2", 1.0, 2.0, 7,
                writable == null ? null : String.valueOf(writable));

        org.mockito.ArgumentCaptor<DockEntity> captor =
                org.mockito.ArgumentCaptor.forClass(DockEntity.class);
        verify(docks).save(captor.capture());
        assertThat(captor.getValue().tenantId).isNull();
    }

    @Test
    @DisplayName("租户会话的 list() 只返回本租户机巢（未归属的看不到）")
    void listFiltersByTenant() {
        DockEntity mine = dock(1, "42");
        DockEntity others = dock(2, "43");
        DockEntity unassigned = dock(3, null);
        when(docks.findAll()).thenReturn(List.of(mine, others, unassigned));
        when(docks.findByTenantIdOrderById("42")).thenReturn(List.of(mine));
        when(docks.findByTenantIdOrderById("43")).thenReturn(List.of(others));

        TenantContext.setTenantId(42);
        assertThat(service.list()).containsExactly(mine);

        TenantContext.setTenantId(43);
        assertThat(service.list()).containsExactly(others);
    }

    @Test
    @DisplayName("全局管理员看全部（含未归属）")
    void listGlobalScopeSeesAll() {
        when(docks.findAll()).thenReturn(List.of(dock(1, "42"), dock(2, null)));

        TenantContext.setTenantId(null);
        assertThat(service.list()).hasSize(2);
    }

    @Test
    @DisplayName("NO_ACCESS 会话什么都看不到（不能当成全局管理员）")
    void listNoAccessSeesNothing() {
        when(docks.findByTenantIdOrderById(anyString())).thenReturn(List.of());
        TenantContext.setTenantId(TenantContext.NO_ACCESS);
        assertThat(service.list()).isEmpty();
    }

    @Test
    @DisplayName("越权 ID 的 get()/recentLog() 与\"不存在\"同一应答")
    void getAndLogRejectForeignTenant() {
        DockEntity foreign = dock(9, "43");
        when(docks.findById(9L)).thenReturn(Optional.of(foreign));

        TenantContext.setTenantId(42);
        assertThatThrownBy(() -> service.get(9L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown dock 9");
        assertThatThrownBy(() -> service.recentLog(9L, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown dock 9");
    }

    @Test
    @DisplayName("本租户机巢正常可取（含日志）")
    void getAndLogAllowOwnTenant() {
        DockEntity mine = dock(9, "42");
        when(docks.findById(9L)).thenReturn(Optional.of(mine));
        when(stateLog.findByDockIdOrderByTsDesc(9L)).thenReturn(List.of());

        TenantContext.setTenantId(42);
        assertThat(service.get(9L)).isSameAs(mine);
        assertThat(service.recentLog(9L, 10)).isEmpty();
    }
}

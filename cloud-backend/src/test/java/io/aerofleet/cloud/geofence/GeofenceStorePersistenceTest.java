package io.aerofleet.cloud.geofence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GeofenceStore 持久化模式单测（直接实例化，无 Spring 上下文）。
 * <p>
 * 验证纯内存模式（repository=null）和持久化模式（repository!=null）的行为差异。
 * 持久化模式通过反射注入 mock repository。
 */
@DisplayName("GeofenceStore 持久化模式测试")
class GeofenceStorePersistenceTest {

    private GeofenceStore store;

    @BeforeEach
    void setUp() {
        store = new GeofenceStore();
    }

    // ===== 纯内存模式（repository=null）=====

    @Test
    @DisplayName("纯内存模式 addZone 正常工作")
    void memoryMode_addZone_works() {
        GeofenceZone zone = GeofenceZone.circleZone(1, "围栏1", 30.0, 120.0, 500, GeofenceZone.Action.WARN);

        GeofenceZone previous = store.addZone(zone);

        assertThat(previous).isNull();
        assertThat(store.zoneCount()).isEqualTo(1);
        assertThat(store.getZone(1)).isEqualTo(zone);
    }

    @Test
    @DisplayName("纯内存模式 addZone 覆盖已存在区域返回旧值")
    void memoryMode_addZone_overwriteReturnsPrevious() {
        GeofenceZone zone1 = GeofenceZone.circleZone(1, "围栏1", 30.0, 120.0, 500, GeofenceZone.Action.WARN);
        GeofenceZone zone2 = GeofenceZone.circleZone(1, "围栏1更新", 31.0, 121.0, 600, GeofenceZone.Action.LOCK_RTH);

        store.addZone(zone1);
        GeofenceZone previous = store.addZone(zone2);

        assertThat(previous).isEqualTo(zone1);
        assertThat(store.getZone(1).getName()).isEqualTo("围栏1更新");
    }

    @Test
    @DisplayName("纯内存模式 updateZone 正常工作")
    void memoryMode_updateZone_works() {
        GeofenceZone zone = GeofenceZone.circleZone(1, "围栏1", 30.0, 120.0, 500, GeofenceZone.Action.WARN);
        store.addZone(zone);

        GeofenceZone updated = zone.withEnabled(false);
        GeofenceZone result = store.updateZone(updated);

        assertThat(result).isNotNull();
        assertThat(store.getZone(1).isEnabled()).isFalse();
    }

    @Test
    @DisplayName("纯内存模式 updateZone 不存在返回 null")
    void memoryMode_updateZone_notExists_returnsNull() {
        GeofenceZone zone = GeofenceZone.circleZone(999, "不存在", 30.0, 120.0, 500, GeofenceZone.Action.WARN);

        GeofenceZone result = store.updateZone(zone);

        assertThat(result).isNull();
        assertThat(store.zoneCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("纯内存模式 removeZone 正常工作")
    void memoryMode_removeZone_works() {
        GeofenceZone zone = GeofenceZone.circleZone(1, "围栏1", 30.0, 120.0, 500, GeofenceZone.Action.WARN);
        store.addZone(zone);

        GeofenceZone removed = store.removeZone(1);

        assertThat(removed).isEqualTo(zone);
        assertThat(store.zoneCount()).isEqualTo(0);
        assertThat(store.getZone(1)).isNull();
    }

    @Test
    @DisplayName("纯内存模式 removeZone 不存在返回 null")
    void memoryMode_removeZone_notExists_returnsNull() {
        GeofenceZone removed = store.removeZone(999);

        assertThat(removed).isNull();
    }

    @Test
    @DisplayName("纯内存模式 recordBreach 正常工作")
    void memoryMode_recordBreach_works() {
        GeofenceBreachEvent event = new GeofenceBreachEvent(
                1, 1, "围栏1", GeofenceBreachEvent.BreachType.ENTER, 30.0, 120.0, 1000L);

        store.recordBreach(event);

        assertThat(store.breachCount()).isEqualTo(1);
        assertThat(store.getBreachHistory()).hasSize(1);
        assertThat(store.getBreachHistory().get(0).getSysid()).isEqualTo(1);
    }

    @Test
    @DisplayName("纯内存模式 clearBreachHistory 正常工作")
    void memoryMode_clearBreachHistory_works() {
        GeofenceBreachEvent event = new GeofenceBreachEvent(
                1, 1, "围栏1", GeofenceBreachEvent.BreachType.ENTER, 30.0, 120.0, 1000L);
        store.recordBreach(event);

        store.clearBreachHistory();

        assertThat(store.breachCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("纯内存模式 recordBreach 超过上限时丢弃最旧记录")
    void memoryMode_recordBreach_overLimit_dropsOldest() {
        for (int i = 0; i < GeofenceStore.MAX_BREACH_HISTORY + 5; i++) {
            GeofenceBreachEvent event = new GeofenceBreachEvent(
                    1, 1, "围栏1", GeofenceBreachEvent.BreachType.ENTER, 30.0, 120.0, i);
            store.recordBreach(event);
        }

        assertThat(store.breachCount()).isEqualTo(GeofenceStore.MAX_BREACH_HISTORY);
        // 最旧的 5 条被丢弃，第一条的 timestampMs 应为 5
        assertThat(store.getBreachHistory().get(0).getTimestampMs()).isEqualTo(5L);
    }

    // ===== 持久化模式（repository!=null）=====

    @Test
    @DisplayName("持久化模式 addZone 同时更新内存和数据库")
    void persistenceMode_addZone_updatesBoth() throws Exception {
        GeofenceZoneRepository zoneRepo = mock(GeofenceZoneRepository.class);
        GeofenceBreachEventRepository breachRepo = mock(GeofenceBreachEventRepository.class);
        injectRepositories(zoneRepo, breachRepo);

        GeofenceZone zone = GeofenceZone.circleZone(1, "围栏1", 30.0, 120.0, 500, GeofenceZone.Action.WARN);

        store.addZone(zone);

        // 内存中有
        assertThat(store.getZone(1)).isEqualTo(zone);
        // 数据库也写了
        verify(zoneRepo).save(any(GeofenceZoneEntity.class));
    }

    @Test
    @DisplayName("持久化模式 updateZone 同时更新内存和数据库")
    void persistenceMode_updateZone_updatesBoth() throws Exception {
        GeofenceZoneRepository zoneRepo = mock(GeofenceZoneRepository.class);
        GeofenceBreachEventRepository breachRepo = mock(GeofenceBreachEventRepository.class);
        injectRepositories(zoneRepo, breachRepo);

        GeofenceZone zone = GeofenceZone.circleZone(1, "围栏1", 30.0, 120.0, 500, GeofenceZone.Action.WARN);
        store.addZone(zone);

        GeofenceZone updated = zone.withEnabled(false);
        store.updateZone(updated);

        assertThat(store.getZone(1).isEnabled()).isFalse();
        // addZone 写一次 + updateZone 写一次 = 2 次
        verify(zoneRepo, times(2)).save(any(GeofenceZoneEntity.class));
    }

    @Test
    @DisplayName("持久化模式 removeZone 同时更新内存和数据库")
    void persistenceMode_removeZone_updatesBoth() throws Exception {
        GeofenceZoneRepository zoneRepo = mock(GeofenceZoneRepository.class);
        GeofenceBreachEventRepository breachRepo = mock(GeofenceBreachEventRepository.class);
        injectRepositories(zoneRepo, breachRepo);

        GeofenceZone zone = GeofenceZone.circleZone(1, "围栏1", 30.0, 120.0, 500, GeofenceZone.Action.WARN);
        store.addZone(zone);

        store.removeZone(1);

        assertThat(store.getZone(1)).isNull();
        verify(zoneRepo).deleteById(1);
    }

    @Test
    @DisplayName("持久化模式 recordBreach 同时更新内存和数据库")
    void persistenceMode_recordBreach_updatesBoth() throws Exception {
        GeofenceZoneRepository zoneRepo = mock(GeofenceZoneRepository.class);
        GeofenceBreachEventRepository breachRepo = mock(GeofenceBreachEventRepository.class);
        injectRepositories(zoneRepo, breachRepo);

        GeofenceBreachEvent event = new GeofenceBreachEvent(
                1, 1, "围栏1", GeofenceBreachEvent.BreachType.ENTER, 30.0, 120.0, 1000L);

        store.recordBreach(event);

        assertThat(store.breachCount()).isEqualTo(1);
        verify(breachRepo).save(any(GeofenceBreachEventEntity.class));
    }

    @Test
    @DisplayName("持久化模式 clearBreachHistory 同时清空内存和数据库")
    void persistenceMode_clearBreachHistory_updatesBoth() throws Exception {
        GeofenceZoneRepository zoneRepo = mock(GeofenceZoneRepository.class);
        GeofenceBreachEventRepository breachRepo = mock(GeofenceBreachEventRepository.class);
        injectRepositories(zoneRepo, breachRepo);

        GeofenceBreachEvent event = new GeofenceBreachEvent(
                1, 1, "围栏1", GeofenceBreachEvent.BreachType.ENTER, 30.0, 120.0, 1000L);
        store.recordBreach(event);

        store.clearBreachHistory();

        assertThat(store.breachCount()).isEqualTo(0);
        verify(breachRepo).deleteAll();
    }

    @Test
    @DisplayName("纯内存模式 addZone 不调用 repository")
    void memoryMode_addZone_noRepoCall() {
        GeofenceZone zone = GeofenceZone.circleZone(1, "围栏1", 30.0, 120.0, 500, GeofenceZone.Action.WARN);

        store.addZone(zone);

        // repository 为 null，不会抛异常，仅写内存
        assertThat(store.zoneCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("启动恢复只取最新 MAX_BREACH_HISTORY 条（LIMIT 下推），不整表读堆")
    void loadFromDb_loadsOnlyNewestBreachPage() throws Exception {
        GeofenceZoneRepository zoneRepo = mock(GeofenceZoneRepository.class);
        GeofenceBreachEventRepository breachRepo = mock(GeofenceBreachEventRepository.class);
        injectRepositories(zoneRepo, breachRepo);

        GeofenceBreachEventEntity older = breachEntity(1, 1000L);
        GeofenceBreachEventEntity newest = breachEntity(2, 2000L);
        // 仓储按契约返回"最新在前"
        when(breachRepo.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(newest, older)));

        store.loadFromDb();

        assertThat(store.getBreachHistory())
                .extracting(GeofenceBreachEvent::getTimestampMs)
                .as("deque 必须旧→新，最新在末尾")
                .containsExactly(1000L, 2000L);

        ArgumentCaptor<Pageable> page =
                ArgumentCaptor.forClass(Pageable.class);
        verify(breachRepo).findAll(page.capture());
        assertThat(page.getValue().getPageSize())
                .as("只取上限条数，而不是整张只增表")
                .isEqualTo(GeofenceStore.MAX_BREACH_HISTORY);
        assertThat(page.getValue().getSort().getOrderFor("timestampMs").getDirection())
                .isEqualTo(Sort.Direction.DESC);
        verify(breachRepo, never()).findAll();
    }

    @Test
    @DisplayName("越界事件读取失败不影响围栏区域恢复（启动不得因历史表而失败）")
    void loadFromDb_breachFailureDoesNotBreakZoneRestore() throws Exception {
        GeofenceZoneRepository zoneRepo = mock(GeofenceZoneRepository.class);
        GeofenceBreachEventRepository breachRepo = mock(GeofenceBreachEventRepository.class);
        injectRepositories(zoneRepo, breachRepo);

        GeofenceZoneEntity zoneEntity = new GeofenceZoneEntity();
        zoneEntity.setId(9);
        zoneEntity.setName("围栏9");
        zoneEntity.setType("CIRCLE");
        zoneEntity.setCenterLat(30.0);
        zoneEntity.setCenterLon(120.0);
        zoneEntity.setRadiusM(500);
        zoneEntity.setEnabled(true);
        zoneEntity.setAction("WARN");
        zoneEntity.setTenantId(1);
        when(zoneRepo.findAll()).thenReturn(List.of(zoneEntity));
        when(breachRepo.findAll(any(Pageable.class)))
                .thenThrow(new RuntimeException("history table unavailable"));

        store.loadFromDb();

        assertThat(store.getZone(9)).as("围栏本身已恢复").isNotNull();
        assertThat(store.breachCount()).isZero();
    }

    // ===== 辅助方法 =====

    private static GeofenceBreachEventEntity breachEntity(int zoneId, long ts) {
        GeofenceBreachEventEntity e = new GeofenceBreachEventEntity();
        e.setSysid(1);
        e.setZoneId(zoneId);
        e.setZoneName("围栏" + zoneId);
        e.setBreachType(GeofenceBreachEvent.BreachType.ENTER.name());
        e.setLat(30.0);
        e.setLon(120.0);
        e.setTimestampMs(ts);
        return e;
    }

    /**
     * 通过反射注入 mock repository 到 GeofenceStore 的私有字段。
     */
    private void injectRepositories(GeofenceZoneRepository zoneRepo,
                                     GeofenceBreachEventRepository breachRepo) throws Exception {
        Field zoneField = GeofenceStore.class.getDeclaredField("zoneRepository");
        zoneField.setAccessible(true);
        zoneField.set(store, zoneRepo);

        Field breachField = GeofenceStore.class.getDeclaredField("breachEventRepository");
        breachField.setAccessible(true);
        breachField.set(store, breachRepo);
    }
}
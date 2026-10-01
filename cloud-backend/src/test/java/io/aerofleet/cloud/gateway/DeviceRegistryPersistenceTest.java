package io.aerofleet.cloud.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DeviceRegistry 持久化模式集成测试（@SpringBootTest + H2 内存数据库）。
 * <p>
 * 验证 persist=true 时设备注册、离线扫描、重启恢复的数据库持久化行为，
 * 以及 persist=false 时纯内存兼容和 DB 不可用时的降级行为。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:deviceregistry-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "aerofleet.device-registry.persist=true",
        "aerofleet.flightlog.persist-to-db=false",
        "spring.cache.type=none",
        "aerofleet.udp-port=0",
        "aerofleet.drone-port=14549",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
})
@DisplayName("DeviceRegistry 持久化模式集成测试")
class DeviceRegistryPersistenceTest {

    @Autowired
    private DeviceRegistry deviceRegistry;

    @Autowired
    private DeviceRepository deviceRepository;

    @BeforeEach
    void cleanUp() throws Exception {
        deviceRepository.deleteAll();
        clearDronesMap();
        setPersist(true);
        setHeartbeatTimeoutSeconds(10);
    }

    // ===== 持久化模式测试 =====

    @Test
    @DisplayName("registerIfAbsent 同时写内存和数据库")
    void registerWritesToDb() {
        DroneSnapshot snapshot = deviceRegistry.registerIfAbsent(1);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.sysid).isEqualTo(1);
        assertThat(snapshot.online).isTrue();

        Optional<DeviceEntity> entity = deviceRepository.findById(1);
        assertThat(entity).isPresent();
        assertThat(entity.get().getOnline()).isTrue();
    }

    @Test
    @DisplayName("sweepOffline 将离线状态同步到数据库")
    void sweepOfflineUpdatesDb() throws Exception {
        // 注册设备（写入 DB，online=true）
        DroneSnapshot snapshot = deviceRegistry.registerIfAbsent(2);
        assertThat(deviceRepository.findById(2).get().getOnline()).isTrue();

        // 设置旧心跳时间，使设备超时
        setHeartbeatTimeoutSeconds(1);
        snapshot.lastHeartbeatMs = System.currentTimeMillis() - 5000;

        // 执行离线扫描
        List<Integer> offline = deviceRegistry.sweepOffline();

        assertThat(offline).contains(2);
        assertThat(snapshot.online).isFalse();

        // DB 中 online 应已更新为 false
        DeviceEntity entity = deviceRepository.findById(2).orElseThrow();
        assertThat(entity.getOnline()).isFalse();
    }

    @Test
    @DisplayName("restoreFromRepository 从数据库恢复设备列表到内存")
    void restoreFromRepositoryOnRestart() throws Exception {
        // 在 DB 中插入设备记录（模拟之前运行时持久化的状态）
        DeviceEntity entity = new DeviceEntity(3);
        entity.setOnline(true);
        entity.setTenantId(1);
        deviceRepository.save(entity);

        // 清空内存缓存（模拟重启后内存为空）
        clearDronesMap();
        assertThat(deviceRegistry.get(3)).isNull();

        // 手动调用 restoreFromRepository（模拟 @PostConstruct 行为）
        deviceRegistry.restoreFromRepository();

        // 内存中应恢复设备，且初始状态为 offline
        DroneSnapshot restored = deviceRegistry.get(3);
        assertThat(restored).isNotNull();
        assertThat(restored.sysid).isEqualTo(3);
        assertThat(restored.online).isFalse();
    }

    // ===== 纯内存模式测试 =====

    @Test
    @DisplayName("persist=false 时纯内存行为，不写数据库")
    void persistFalseKeepsInMemoryBehavior() throws Exception {
        setPersist(false);

        DroneSnapshot snapshot = deviceRegistry.registerIfAbsent(4);

        // 内存中有
        assertThat(snapshot).isNotNull();
        assertThat(deviceRegistry.get(4)).isNotNull();

        // DB 中没有
        assertThat(deviceRepository.findById(4)).isEmpty();
    }

    // ===== 降级测试 =====

    @Test
    @DisplayName("DB 不可用时降级为纯内存模式")
    void dbUnavailableDegradesToInMemory() throws Exception {
        // 替换 repository 为会抛异常的 mock
        DeviceRepository mockRepo = mock(DeviceRepository.class);
        when(mockRepo.findById(any())).thenThrow(new RuntimeException("DB unavailable"));
        when(mockRepo.save(any())).thenThrow(new RuntimeException("DB unavailable"));
        injectRepository(mockRepo);

        // 注册设备（DB 操作失败，但内存应正常）
        DroneSnapshot snapshot = deviceRegistry.registerIfAbsent(5);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.sysid).isEqualTo(5);
        assertThat(snapshot.online).isTrue();

        // 内存中有设备
        assertThat(deviceRegistry.get(5)).isNotNull();
    }

    // ===== 显式登记设备（#46 prod 白名单死锁）=====

    @Test
    @DisplayName("provision 登记的设备以 offline 入库，重启后可恢复进白名单")
    void provisionPersistsOfflineDevice() throws Exception {
        assertThat(deviceRegistry.provision(51, 3)).isTrue();

        DeviceEntity entity = deviceRepository.findById(51).orElseThrow();
        assertThat(entity.getOnline()).isFalse();
        assertThat(entity.getTenantId()).isEqualTo(3);

        clearDronesMap();
        deviceRegistry.restoreFromRepository();
        assertThat(deviceRegistry.isKnownDevice(51)).isTrue();
        assertThat(deviceRegistry.tenantOf(51)).isEqualTo(3);
    }

    @Test
    @DisplayName("provision 幂等：重复登记返回 false，不覆盖既有归属")
    void provisionIsIdempotent() {
        assertThat(deviceRegistry.provision(52, 4)).isTrue();
        assertThat(deviceRegistry.provision(52, 5)).isFalse();
        assertThat(deviceRegistry.tenantOf(52)).isEqualTo(4);
    }

    @Test
    @DisplayName("provision 后 registerIfAbsent 复用同一快照：在线位留给心跳，不重复入库")
    void provisionThenFirstFrame() {
        deviceRegistry.provision(54, 2);
        long rowsBefore = deviceRepository.count();

        DroneSnapshot snapshot = deviceRegistry.registerIfAbsent(54);

        // online 由 TelemetrySnapshotListener.onHeartbeat 置位，registerIfAbsent 对已存在快照不做任何事
        assertThat(snapshot.online).isFalse();
        assertThat(snapshot.tenantId).isEqualTo(2);
        assertThat(deviceRepository.count()).isEqualTo(rowsBefore);
    }

    @Test
    @DisplayName("persist=false 时 provision 只进内存，isPersisting=false")
    void provisionWithoutPersist() throws Exception {
        setPersist(false);

        assertThat(deviceRegistry.isPersisting()).isFalse();
        assertThat(deviceRegistry.provision(53, null)).isTrue();
        assertThat(deviceRegistry.isKnownDevice(53)).isTrue();
        assertThat(deviceRepository.findById(53)).isEmpty();
    }

    @Test
    @DisplayName("provision 拒绝越界 sysid（0 保留、255 为 GCS）")
    void provisionRejectsOutOfRange() {
        assertThatThrownBy(() -> deviceRegistry.provision(0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deviceRegistry.provision(255, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(deviceRegistry.isKnownDevice(255)).isFalse();
    }

    @Test
    @DisplayName("deregister 同时摘掉内存条目与库里的行，撤销后白名单重新拒收")
    void deregisterRemovesMemoryAndDbRow() {
        deviceRegistry.provision(55, 6);
        assertThat(deviceRegistry.isKnownDevice(55)).isTrue();
        assertThat(deviceRepository.existsById(55)).isTrue();

        assertThat(deviceRegistry.deregister(55)).isTrue();

        assertThat(deviceRegistry.isKnownDevice(55)).isFalse();
        assertThat(deviceRepository.existsById(55)).isFalse();
    }

    @Test
    @DisplayName("deregister 对未知设备返回 false；persist=false 时不动库里的行")
    void deregisterUnknownAndNonPersisting() throws Exception {
        assertThat(deviceRegistry.deregister(599)).isFalse();

        deviceRepository.save(new DeviceEntity(56));
        setPersist(false);
        assertThat(deviceRegistry.deregister(56)).isFalse();   // 内存没有、又不读库 ⇒ 视为未知
        assertThat(deviceRepository.existsById(56)).isTrue();  // 不得悄悄删库
    }

    // ===== 辅助方法 =====

    /**
     * 通过反射清空 DeviceRegistry 的 drones 内存缓存。
     */
    @SuppressWarnings("unchecked")
    private void clearDronesMap() throws Exception {
        Field field = DeviceRegistry.class.getDeclaredField("drones");
        field.setAccessible(true);
        Map<Integer, DroneSnapshot> drones = (Map<Integer, DroneSnapshot>) field.get(deviceRegistry);
        drones.clear();
    }

    // ===== 设备归属（P0-1）=====

    @Test
    @DisplayName("指派离线（仅入库）设备返回成功并写入 tenant_id")
    void assignTenantPersistsForOfflineDevice() throws Exception {
        deviceRepository.save(new DeviceEntity(43));
        clearDronesMap();

        assertThat(deviceRegistry.assignTenant(43, 9)).isTrue();
        assertThat(deviceRepository.findById(43).orElseThrow().getTenantId()).isEqualTo(9);
    }

    @Test
    @DisplayName("心跳重建快照时从库中恢复归属，指派不因重连丢失")
    void assignmentSurvivesReconnect() throws Exception {
        DeviceEntity entity = new DeviceEntity(42);
        entity.setTenantId(7);
        deviceRepository.save(entity);
        clearDronesMap();

        DroneSnapshot snapshot = deviceRegistry.registerIfAbsent(42);

        assertThat(snapshot.tenantId).isEqualTo(7);
    }

    /**
     * 通过反射设置 persist 字段。
     */
    private void setPersist(boolean value) throws Exception {
        Field field = DeviceRegistry.class.getDeclaredField("persist");
        field.setAccessible(true);
        field.setBoolean(deviceRegistry, value);
    }

    /**
     * 通过反射设置 heartbeatTimeoutSeconds 字段。
     */
    private void setHeartbeatTimeoutSeconds(int value) throws Exception {
        Field field = DeviceRegistry.class.getDeclaredField("heartbeatTimeoutSeconds");
        field.setAccessible(true);
        field.setInt(deviceRegistry, value);
    }

    /**
     * 通过反射注入 mock repository。
     */
    private void injectRepository(DeviceRepository repo) throws Exception {
        Field field = DeviceRegistry.class.getDeclaredField("repository");
        field.setAccessible(true);
        field.set(deviceRegistry, repo);
    }
}
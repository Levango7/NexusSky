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
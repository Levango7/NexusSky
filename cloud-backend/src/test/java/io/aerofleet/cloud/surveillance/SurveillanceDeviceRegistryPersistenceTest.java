package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SurveillanceDeviceRegistry 持久化模式单测（直接实例化，无 Spring 上下文）。
 * <p>
 * 验证纯内存模式（repository=null）和持久化模式（repository!=null）的行为差异。
 * 持久化模式通过反射注入 mock repository。
 */
@DisplayName("SurveillanceDeviceRegistry 持久化模式测试")
class SurveillanceDeviceRegistryPersistenceTest {

    private SurveillanceDeviceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SurveillanceDeviceRegistry();
    }

    // ===== 纯内存模式（repository=null）=====

    @Test
    @DisplayName("纯内存模式 register 正常工作")
    void memoryMode_register_works() {
        SurveillanceDevice device = new SurveillanceDevice(
                "cam-001", "摄像头1", SurveillanceDevice.Vendor.HIKVISION,
                "192.168.1.100", 80, "admin", "pass");

        SurveillanceDevice result = registry.register(device);

        assertThat(result).isEqualTo(device);
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.getDevice("cam-001")).isEqualTo(device);
    }

    @Test
    @DisplayName("纯内存模式 register 覆盖同 ID 设备")
    void memoryMode_register_overwriteSameId() {
        SurveillanceDevice device1 = new SurveillanceDevice(
                "cam-001", "摄像头1", SurveillanceDevice.Vendor.HIKVISION,
                "192.168.1.100", 80, "admin", "pass");
        SurveillanceDevice device2 = new SurveillanceDevice(
                "cam-001", "摄像头1更新", SurveillanceDevice.Vendor.DAHUA,
                "192.168.1.200", 8080, "admin2", "pass2");

        registry.register(device1);
        registry.register(device2);

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.getDevice("cam-001").name).isEqualTo("摄像头1更新");
        assertThat(registry.getDevice("cam-001").vendor).isEqualTo(SurveillanceDevice.Vendor.DAHUA);
    }

    @Test
    @DisplayName("纯内存模式 unregister 正常工作")
    void memoryMode_unregister_works() {
        SurveillanceDevice device = new SurveillanceDevice(
                "cam-001", "摄像头1", SurveillanceDevice.Vendor.HIKVISION,
                "192.168.1.100", 80, "admin", "pass");
        registry.register(device);

        SurveillanceDevice removed = registry.unregister("cam-001");

        assertThat(removed).isEqualTo(device);
        assertThat(registry.size()).isEqualTo(0);
        assertThat(registry.getDevice("cam-001")).isNull();
    }

    @Test
    @DisplayName("纯内存模式 unregister 不存在返回 null")
    void memoryMode_unregister_notExists_returnsNull() {
        SurveillanceDevice removed = registry.unregister("not-exists");

        assertThat(removed).isNull();
    }

    @Test
    @DisplayName("纯内存模式 listDevices 返回按 id 排序的设备列表")
    void memoryMode_listDevices_sortedById() {
        SurveillanceDevice d1 = new SurveillanceDevice(
                "cam-002", "摄像头2", SurveillanceDevice.Vendor.HIKVISION,
                "192.168.1.102", 80, "admin", "pass");
        SurveillanceDevice d2 = new SurveillanceDevice(
                "cam-001", "摄像头1", SurveillanceDevice.Vendor.DAHUA,
                "192.168.1.101", 80, "admin", "pass");

        registry.register(d1);
        registry.register(d2);

        List<SurveillanceDevice> list = registry.listDevices();

        assertThat(list).hasSize(2);
        assertThat(list.get(0).id).isEqualTo("cam-001");
        assertThat(list.get(1).id).isEqualTo("cam-002");
    }

    @Test
    @DisplayName("纯内存模式 updateHeartbeat 正常工作")
    void memoryMode_updateHeartbeat_works() {
        SurveillanceDevice device = new SurveillanceDevice(
                "cam-001", "摄像头1", SurveillanceDevice.Vendor.HIKVISION,
                "192.168.1.100", 80, "admin", "pass");
        registry.register(device);

        boolean result = registry.updateHeartbeat("cam-001");

        assertThat(result).isTrue();
        assertThat(registry.getDevice("cam-001").status).isEqualTo(SurveillanceDevice.Status.ONLINE);
    }

    @Test
    @DisplayName("纯内存模式 updateHeartbeat 不存在返回 false")
    void memoryMode_updateHeartbeat_notExists_returnsFalse() {
        boolean result = registry.updateHeartbeat("not-exists");

        assertThat(result).isFalse();
    }

    // ===== 持久化模式（repository!=null）=====

    @Test
    @DisplayName("持久化模式 register 同时写内存和数据库")
    void persistenceMode_register_updatesBoth() throws Exception {
        SurveillanceDeviceRepository repo = mock(SurveillanceDeviceRepository.class);
        injectRepository(repo);

        SurveillanceDevice device = new SurveillanceDevice(
                "cam-001", "摄像头1", SurveillanceDevice.Vendor.HIKVISION,
                "192.168.1.100", 80, "admin", "pass");

        registry.register(device);

        // 内存中有
        assertThat(registry.getDevice("cam-001")).isEqualTo(device);
        // 数据库也写了
        verify(repo).save(any(SurveillanceDeviceEntity.class));
    }

    @Test
    @DisplayName("持久化模式 unregister 同时从内存和数据库删除")
    void persistenceMode_unregister_updatesBoth() throws Exception {
        SurveillanceDeviceRepository repo = mock(SurveillanceDeviceRepository.class);
        injectRepository(repo);

        SurveillanceDevice device = new SurveillanceDevice(
                "cam-001", "摄像头1", SurveillanceDevice.Vendor.HIKVISION,
                "192.168.1.100", 80, "admin", "pass");
        registry.register(device);

        registry.unregister("cam-001");

        // 内存中已删除
        assertThat(registry.getDevice("cam-001")).isNull();
        // 数据库也删了
        verify(repo).deleteById("cam-001");
    }

    @Test
    @DisplayName("持久化模式 updateHeartbeat 仅写内存不写数据库")
    void persistenceMode_updateHeartbeat_memoryOnly() throws Exception {
        SurveillanceDeviceRepository repo = mock(SurveillanceDeviceRepository.class);
        injectRepository(repo);

        SurveillanceDevice device = new SurveillanceDevice(
                "cam-001", "摄像头1", SurveillanceDevice.Vendor.HIKVISION,
                "192.168.1.100", 80, "admin", "pass");
        registry.register(device);

        // 清除 register 时的 save 调用计数
        org.mockito.Mockito.clearInvocations(repo);

        registry.updateHeartbeat("cam-001");

        // 心跳仅写内存，不写数据库
        verify(repo, never()).save(any(SurveillanceDeviceEntity.class));
        // 内存中状态已更新
        assertThat(registry.getDevice("cam-001").status).isEqualTo(SurveillanceDevice.Status.ONLINE);
    }

    // ===== 辅助方法 =====

    /**
     * 通过反射注入 mock repository 到 SurveillanceDeviceRegistry 的私有字段。
     */
    private void injectRepository(SurveillanceDeviceRepository repo) throws Exception {
        Field field = SurveillanceDeviceRegistry.class.getDeclaredField("repository");
        field.setAccessible(true);
        field.set(registry, repo);
    }
}
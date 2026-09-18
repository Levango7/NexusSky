package io.aerofleet.cloud.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DeviceRegistry 单元测试：直接实例化（无 Spring 上下文），用 AssertJ 断言。
 * <p>
 * 无参构造器下 heartbeatTimeoutSeconds=0、persist=false、repository=null，
 * 因此 sweepOffline 的 cutoff=0，任何 lastHeartbeatMs>0 的在线设备都会被标记离线；
 * 而刚注册的设备 lastHeartbeatMs 默认为 0，不会被标记。
 */
class DeviceRegistryTest {

    @Test
    @DisplayName("registerIfAbsent 新 sysid 创建快照并标记在线")
    void registerIfAbsent_newSysid_createsSnapshot() {
        DeviceRegistry registry = new DeviceRegistry();

        DroneSnapshot s = registry.registerIfAbsent(7);

        assertThat(s).isNotNull();
        assertThat(s.sysid).isEqualTo(7);
        assertThat(s.online).isTrue();
    }

    @Test
    @DisplayName("registerIfAbsent 重复 sysid 返回同一快照实例")
    void registerIfAbsent_existingSysid_returnsSameSnapshot() {
        DeviceRegistry registry = new DeviceRegistry();

        DroneSnapshot first = registry.registerIfAbsent(3);
        DroneSnapshot second = registry.registerIfAbsent(3);

        assertThat(second).isSameAs(first);
    }

    @Test
    @DisplayName("get 未知 sysid 返回 null")
    void get_unknownSysid_returnsNull() {
        DeviceRegistry registry = new DeviceRegistry();

        assertThat(registry.get(99)).isNull();
    }

    @Test
    @DisplayName("get 已知 sysid 返回已注册快照")
    void get_knownSysid_returnsSnapshot() {
        DeviceRegistry registry = new DeviceRegistry();

        DroneSnapshot registered = registry.registerIfAbsent(11);
        DroneSnapshot fetched = registry.get(11);

        assertThat(fetched).isSameAs(registered);
    }

    @Test
    @DisplayName("all 空注册表返回空列表")
    void all_empty_returnsEmptyList() {
        DeviceRegistry registry = new DeviceRegistry();

        assertThat(registry.all()).isEmpty();
    }

    @Test
    @DisplayName("all 多设备按 sysid 升序排列")
    void all_multipleDrones_sortedBySysid() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(5);
        registry.registerIfAbsent(1);
        registry.registerIfAbsent(9);

        List<DroneSnapshot> all = registry.all();

        assertThat(all).hasSize(3);
        assertThat(all.get(0).sysid).isEqualTo(1);
        assertThat(all.get(1).sysid).isEqualTo(5);
        assertThat(all.get(2).sysid).isEqualTo(9);
    }

    @Test
    @DisplayName("sweepOffline 超时设备被标记离线并返回其 sysid")
    void sweepOffline_marksStaleDroneOffline() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot s = registry.registerIfAbsent(4);
        // 模拟 100 秒前最后一次心跳
        s.lastHeartbeatMs = System.currentTimeMillis() - 100_000L;

        List<Integer> swept = registry.sweepOffline();

        assertThat(swept).contains(4);
        assertThat(s.online).isFalse();
    }

    @Test
    @DisplayName("sweepOffline 刚注册设备（无心跳）保持在线")
    void sweepOffline_freshDrone_staysOnline() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot s = registry.registerIfAbsent(6);
        // lastHeartbeatMs 默认 0，sweepOffline 要求 lastHeartbeatMs > 0 才会处理

        List<Integer> swept = registry.sweepOffline();

        assertThat(swept).doesNotContain(6);
        assertThat(s.online).isTrue();
    }
}
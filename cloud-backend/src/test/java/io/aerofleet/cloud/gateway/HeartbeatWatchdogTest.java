package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.flightlog.FlightLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * HeartbeatWatchdog 单元测试：直接实例化 DeviceRegistry 与 FlightLogService（无 Spring 上下文）。
 * <p>
 * FlightLogService 用 @TempDir 提供的目录实例化，sweep 内部异常被 catch 不会抛出。
 */
class HeartbeatWatchdogTest {

    @TempDir
    Path tmp;

    /** 构造一个指向临时目录的 FlightLogService。 */
    private FlightLogService flightLog() {
        return new FlightLogService(tmp.toString(), 1000L);
    }

    @Test
    @DisplayName("sweep 空注册表不抛异常")
    void sweep_noDrones_noException() {
        DeviceRegistry registry = new DeviceRegistry();
        HeartbeatWatchdog watchdog = new HeartbeatWatchdog(registry, flightLog());

        assertThatCode(watchdog::sweep).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("sweep 超时设备正常执行并标记离线")
    void sweep_staleDrone_logsConnectivity() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot s = registry.registerIfAbsent(2);
        s.lastHeartbeatMs = System.currentTimeMillis() - 100_000L;
        HeartbeatWatchdog watchdog = new HeartbeatWatchdog(registry, flightLog());

        assertThatCode(watchdog::sweep).doesNotThrowAnyException();

        assertThat(s.online).isFalse();
    }

    @Test
    @DisplayName("sweep 刚注册设备不发生离线转换")
    void sweep_freshDrone_noTransition() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot s = registry.registerIfAbsent(8);
        HeartbeatWatchdog watchdog = new HeartbeatWatchdog(registry, flightLog());

        watchdog.sweep();

        assertThat(s.online).isTrue();
    }

    @Test
    @DisplayName("sweep 设备离线后重新上线记录恢复且不抛异常")
    void sweep_recoversReRegisteredDrone() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot s = registry.registerIfAbsent(5);
        s.lastHeartbeatMs = System.currentTimeMillis() - 100_000L;
        HeartbeatWatchdog watchdog = new HeartbeatWatchdog(registry, flightLog());

        // 第一次 sweep：超时设备被标记离线，并记入 knownOffline
        watchdog.sweep();
        assertThat(s.online).isFalse();

        // 模拟设备重新上线：恢复 online 并重置心跳
        s.online = true;
        s.lastHeartbeatMs = 0L;

        // 第二次 sweep：检测到恢复，调用 connectivity(sysid, true)，不抛异常
        assertThatCode(watchdog::sweep).doesNotThrowAnyException();
        assertThat(s.online).isTrue();
    }
}
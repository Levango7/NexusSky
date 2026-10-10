package io.aerofleet.cloud.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C4「最后已知飞行态」持久化与恢复（V25）。
 *
 * <p><b>覆盖什么</b>：V25 给 {@code devices} 表补了 last_mode / last_armed /
 * last_protocol 三列，让重启后 GCS 首屏能显示"这架机最后在做什么"。
 * 位置与电量不在本类职责内——它们由 {@code drone_last_known_position} +
 * {@code FlightTrackStore} 负责，见 {@code C4TelemetryRestoreTest}。
 *
 * <p><b>测试重点</b>：
 * <ol>
 *   <li>离线转换时三个字段真的落库；</li>
 *   <li>重启后能原样恢复；</li>
 *   <li><b>NULL（历史行/从没写过）不伪装成确定值</b>——这是本设计的关键语义，
 *       若被改成 NOT NULL + 默认值，这条会转红；</li>
 *   <li>恢复出的设备仍是 offline（既有语义不变）。</li>
 * </ol>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:flightstate-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
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
        "aerofleet.heartbeat-timeout-seconds=1",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
})
@DisplayName("C4: 最后已知飞行态持久化与恢复（V25）")
class DeviceFlightStateRestoreTest {

    @Autowired
    private DeviceRegistry deviceRegistry;

    @Autowired
    private DeviceRepository deviceRepository;

    @Test
    @DisplayName("1. 离线转换时三个字段落库")
    void offlineTransition_persistsFlightState() throws Exception {
        int sysid = 51;
        DroneSnapshot s = deviceRegistry.registerIfAbsent(sysid); // 注册即 online=true
        s.mode = "AUTO.MISSION";
        s.armed = true;
        s.protocol = "dji-cloud";
        setHeartbeatTimeoutSeconds(1);
        s.lastHeartbeatMs = System.currentTimeMillis() - 5000; // 早已超时

        List<Integer> swept = deviceRegistry.sweepOffline();

        assertThat(swept).contains(sysid);
        DeviceEntity e = deviceRepository.findById(sysid).orElseThrow();
        assertThat(e.getLastMode()).isEqualTo("AUTO.MISSION");
        assertThat(e.getLastArmed()).isTrue();
        assertThat(e.getLastProtocol()).isEqualTo("dji-cloud");
        // 既有的 online 语义不能被本改动破坏
        assertThat(e.getOnline()).isFalse();
    }

    @Test
    @DisplayName("2. 清空内存后能恢复出这三个字段")
    void restart_restoresFlightState() throws Exception {
        int sysid = 52;
        DroneSnapshot s = deviceRegistry.registerIfAbsent(sysid);
        s.mode = "AUTO.RTL";
        s.armed = false;
        s.protocol = "mavlink";
        setHeartbeatTimeoutSeconds(1);
        s.lastHeartbeatMs = System.currentTimeMillis() - 5000;
        deviceRegistry.sweepOffline();

        clearDrones();
        deviceRegistry.restoreFromRepository();

        DroneSnapshot back = deviceRegistry.get(sysid);
        assertThat(back).isNotNull();
        assertThat(back.mode).isEqualTo("AUTO.RTL");
        assertThat(back.armed).isFalse();
        assertThat(back.protocol).isEqualTo("mavlink");
        // 既有语义不变：恢复出来是 offline
        assertThat(back.online).isFalse();
    }

    @Test
    @DisplayName("3. NULL（历史行）不伪装成确定值，恢复后维持 DroneSnapshot 默认")
    void nullColumns_stayAtDefaults() throws Exception {
        // 直接插一行"老数据"：只有 registerIfAbsent 会写的两列，三个新列全 NULL
        int sysid = 53;
        DeviceEntity legacy = new DeviceEntity(sysid);
        legacy.setOnline(false);
        deviceRepository.save(legacy);

        clearDrones();
        deviceRegistry.restoreFromRepository();

        DroneSnapshot back = deviceRegistry.get(sysid);
        assertThat(back).isNotNull();
        assertThat(back.mode).isEqualTo("UNKNOWN");
        assertThat(back.armed).isFalse();
        assertThat(back.protocol).isEqualTo("mavlink");
    }

    @Test
    @DisplayName("4. persist=false 时不碰库（出厂行为不变）")
    void persistDisabled_doesNotThrow() {
        // 默认上下文里 persist=true；这里只验证 restore 在没有 repository 时也不炸
        DeviceRegistry fresh = new DeviceRegistry();
        fresh.restoreFromRepository();   // 应为 no-op，不抛
        assertThat(fresh.isPersisting()).isFalse();
    }

    private void clearDrones() throws Exception {
        var f = DeviceRegistry.class.getDeclaredField("drones");
        f.setAccessible(true);
        ((java.util.Map<Integer, DroneSnapshot>) f.get(deviceRegistry)).clear();
    }

    private void setHeartbeatTimeoutSeconds(int value) throws Exception {
        var f = DeviceRegistry.class.getDeclaredField("heartbeatTimeoutSeconds");
        f.setAccessible(true);
        f.setInt(deviceRegistry, value);
    }
}

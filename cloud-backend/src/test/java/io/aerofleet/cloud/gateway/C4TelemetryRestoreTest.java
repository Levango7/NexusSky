package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.tracking.DroneLastKnownPositionEntity;
import io.aerofleet.cloud.tracking.DroneLastKnownPositionRepository;
import io.aerofleet.cloud.tracking.FlightTrackStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * C4「遥测热态重启恢复」的**实测锚点**。
 *
 * <p><b>为什么写这个文件</b>：ROADMAP:322-331 把 C4 的剩余缺口写成
 * 「DroneSnapshot 的电量/经纬/姿态/模式等 volatile 字段不落库……
 * 重启后历史位置轨迹只能从 flight_log 重建，不能从注册表恢复」。
 *
 * <p>逐条核对代码后发现这个描述**言过其实**：
 * <ul>
 *   <li>位置/电量其实早就落库了——{@code V5__track_last_known.sql} 建了
 *       {@code drone_last_known_position} 表，{@link FlightTrackStore} 按
 *       {@code PERSIST_INTERVAL}（20Hz 下约 0.5s/机）节流写入、
 *       {@code @PreDestroy} 兜底刷盘、{@code @PostConstruct} 启动加载，
 *       这条链是通的；</li>
 *   <li>真正没恢复的只有 {@code mode} / {@code armed} / {@code protocol}。</li>
 * </ul>
 *
 * <p>于是问题从「缺功能」变成「**没人验证过这条链真的通**」。
 * 本文件用真实 Spring + H2 把"重启后到底什么活下来"钉成测试：
 * 既防将来有人改坏恢复路径无人知晓，也把 ROADMAP 的真实边界固定住。
 *
 * <p>注：这里的"重启"是清内存态后真走一遍恢复方法，并直接查库验证
 * （不经内存缓存），避免"反射调一次 @PostConstruct 就以为测到了装配"。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:c4-restore-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
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
@DisplayName("C4: 遥测热态重启恢复（实测锚点）")
class C4TelemetryRestoreTest {

    private static final int PERSIST_INTERVAL = 10; // 与 FlightTrackStore 同值，超过它才触发节流写入

    @Autowired
    private DeviceRegistry deviceRegistry;

    @Autowired
    private FlightTrackStore trackStore;

    @Autowired
    private DroneLastKnownPositionRepository lastKnownRepo;

    // ==================== 位置与电量：已落库并恢复 ====================

    @Test
    @DisplayName("1. 节流写入真的落库：库里有行（不经过内存缓存）")
    void throttledWrite_hitsDatabase() throws Exception {
        int sysid = 41;
        deviceRegistry.registerIfAbsent(sysid);
        point(sysid, 0, 22.5907, 113.9418, 120.5, 77);

        assertThat(lastKnownRepo.findById(sysid))
                .as("PERSIST_INTERVAL 未到时不该落库（节流生效）")
                .isEmpty();

        for (int i = 1; i <= PERSIST_INTERVAL; i++) {
            point(sysid, i, 22.5907 + i * 0.0001, 113.9418, 120.5, 77);
        }

        Optional<DroneLastKnownPositionEntity> opt = awaitLastKnown(sysid);
        assertThat(opt).as("达到节流阈值并等队列刷新后应落库").isPresent();
        assertThat(opt.get().getBatteryPct()).isEqualTo(77.0);
    }

    /**
     * 落库走 {@code BatchedWriteQueue}（50 条一批 / 200ms 一刷），是**异步**的；
     * 刚 addPoint 完就查库必然查不到。等队列刷新，别用 sleep 硬等。
     */
    private Optional<DroneLastKnownPositionEntity> awaitLastKnown(int sysid) {
        await().atMost(5, SECONDS).untilAsserted(() ->
                assertThat(lastKnownRepo.findById(sysid)).isPresent());
        return lastKnownRepo.findById(sysid);
    }

    @Test
    @DisplayName("2. 清空内存后能重载最后已知位置（恢复链路是通的）")
    void reload_restoresLastKnownPosition() throws Exception {
        int sysid = 42;
        deviceRegistry.registerIfAbsent(sysid);
        point(sysid, 0, 30.10, 120.20, 50.0, 88);
        for (int i = 1; i <= PERSIST_INTERVAL; i++) {
            point(sysid, i, 30.10 + i * 0.0001, 120.20, 50.0, 88);
        }

        // 模拟重启：内存全清，然后走恢复。先确保上一条已落库（异步队列）。
        awaitLastKnown(sysid);
        trackStore.clearTrack(sysid);
        clearDronesMap();
        trackStore.loadLastKnownPositions();

        FlightTrackStore.TrackPoint back = trackStore.getLastKnown(sysid);
        assertThat(back).as("恢复后应能取回最后已知位置").isNotNull();
        // 落库按 PERSIST_INTERVAL 节流，且写的是"触发那一刻的点"，
        // 所以恢复值最多滞后一个周期（实测滞后 0.0001 即一个 interval）。
        // 这里如实按"一个 interval 的容差"断言，不假装它等于最后写入的那个点。
        assertThat(back.lat).isCloseTo(30.10 + PERSIST_INTERVAL * 0.0001, within(1.1e-4));
        assertThat(back.batteryPct).isEqualTo(88.0);
    }

    @Test
    @DisplayName("3. @PreDestroy 兜底刷盘：低频打点也不丢")
    void preDestroy_flushesRegardlessOfThrottle() throws Exception {
        int sysid = 43;
        deviceRegistry.registerIfAbsent(sysid);
        point(sysid, 0, 31.0, 121.0, 10.0, 60);
        lastKnownRepo.deleteAll();

        trackStore.persistAllOnShutdown();

        Optional<DroneLastKnownPositionEntity> opt = awaitLastKnown(sysid);
        assertThat(opt).as("关闭时应刷盘").isPresent();
        assertThat(opt.get().getBatteryPct()).isEqualTo(60.0);
    }

    // ==================== mode / armed / protocol：确实不恢复 ====================

    @Test
    @DisplayName("4. mode/armed/protocol 重启后丢失——ROADMAP 的真实边界在此，不在位置")
    void modeArmedProtocol_areLostOnRestart() throws Exception {
        int sysid = 44;
        DroneSnapshot snap = deviceRegistry.registerIfAbsent(sysid);
        snap.mode = "AUTO.MISSION";
        snap.armed = true;
        snap.protocol = "dji-cloud";
        snap.battery = 55;

        // 只清内存，devices 表里的行仍在
        clearDronesMap();
        deviceRegistry.restoreFromRepository();

        DroneSnapshot back = deviceRegistry.get(sysid);
        assertThat(back).as("设备本身应被恢复").isNotNull();
        assertThat(back.mode).isEqualTo("UNKNOWN");
        assertThat(back.armed).isFalse();
        assertThat(back.protocol).isEqualTo("mavlink");
        // 恢复出来的设备是 offline（javadoc 声明的语义：等心跳确认）
        assertThat(back.online).isFalse();
    }

    // ==================== 辅助 ====================

    private void point(int sysid, int i, double lat, double lon, double alt, int battery) {
        trackStore.addPoint(sysid, new FlightTrackStore.TrackPoint(
                sysid, 1_700_000_000_000L + i * 100L, lat, lon, alt,
                0, 0, 0, 90.0, battery));
    }

    private void clearDronesMap() throws Exception {
        Field f = DeviceRegistry.class.getDeclaredField("drones");
        f.setAccessible(true);
        ((Map<Integer, DroneSnapshot>) f.get(deviceRegistry)).clear();
    }
}

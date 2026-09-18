package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SurveillanceDeviceRegistry 单元测试。
 * <p>
 * 直接实例化（无 Spring 上下文），覆盖：注册/注销/查询/心跳/超时清理/并发安全/厂商筛选。
 */
@DisplayName("SurveillanceDeviceRegistry 设备注册表")
class SurveillanceDeviceRegistryTest {

    private static SurveillanceDevice newDevice(String id, SurveillanceDevice.Vendor vendor) {
        return new SurveillanceDevice(id, "cam-" + id, vendor, "192.168.1." + id.hashCode() % 200,
                80, "admin", "pass123");
    }

    // ===== 注册 =====

    @Test
    @DisplayName("register 新设备返回该设备且 size=1")
    void register_newDevice_returnsDeviceAndSizeOne() {
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry();
        SurveillanceDevice d = newDevice("cam-1", SurveillanceDevice.Vendor.HIKVISION);

        SurveillanceDevice registered = registry.register(d);

        assertThat(registered).isSameAs(d);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("register 同 ID 设备覆盖旧设备")
    void register_sameId_overwrites() {
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry();
        SurveillanceDevice d1 = newDevice("cam-1", SurveillanceDevice.Vendor.HIKVISION);
        d1.name = "old-name";
        SurveillanceDevice d2 = newDevice("cam-1", SurveillanceDevice.Vendor.DAHUA);
        d2.name = "new-name";

        registry.register(d1);
        registry.register(d2);

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.getDevice("cam-1").name).isEqualTo("new-name");
        assertThat(registry.getDevice("cam-1").vendor).isEqualTo(SurveillanceDevice.Vendor.DAHUA);
    }

    @Test
    @DisplayName("register null 抛 IllegalArgumentException")
    void register_null_throws() {
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry();
        try {
            registry.register(null);
            org.assertj.core.api.Assertions.fail("应抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("device must not be null");
        }
    }

    // ===== 注销 =====

    @Test
    @DisplayName("unregister 已存在设备返回该设备")
    void unregister_existing_returnsDevice() {
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry();
        SurveillanceDevice d = newDevice("cam-1", SurveillanceDevice.Vendor.HIKVISION);
        registry.register(d);

        SurveillanceDevice removed = registry.unregister("cam-1");

        assertThat(removed).isSameAs(d);
        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("unregister 不存在设备返回 null")
    void unregister_nonExisting_returnsNull() {
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry();

        assertThat(registry.unregister("nope")).isNull();
    }

    @Test
    @DisplayName("unregister null 返回 null")
    void unregister_null_returnsNull() {
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry();
        assertThat(registry.unregister(null)).isNull();
    }

    // ===== 查询 =====

    @Test
    @DisplayName("getDevice 已存在返回设备")
    void getDevice_existing_returnsDevice() {
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry();
        SurveillanceDevice d = newDevice("cam-1", SurveillanceDevice.Vendor.HIKVISION);
        registry.register(d);

        assertThat(registry.getDevice("cam-1")).isSameAs(d);
    }

    @Test
    @DisplayName("getDevice 不存在返回 null")
    void getDevice_nonExisting_returnsNull() {
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry();
        assertThat(registry.getDevice("nope")).isNull();
    }

    @Test
    @DisplayName("listDevices 空注册表返回空列表")
    void listDevices_empty_returnsEmptyList() {
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry();
        assertThat(registry.listDevices()).isEmpty();
    }

    @Test
    @DisplayName("listDevices 多设备按 id 字典序排列")
    void listDevices_multiple_sortedById() {
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry();
        registry.register(newDevice("cam-3", SurveillanceDevice.Vendor.HIKVISION));
        registry.register(newDevice("cam-1", SurveillanceDevice.Vendor.DAHUA));
        registry.register(newDevice("cam-2", SurveillanceDevice.Vendor.UNIVIEW));

        List<SurveillanceDevice> all = registry.listDevices();

        assertThat(all).hasSize(3);
        assertThat(all.get(0).id).isEqualTo("cam-1");
        assertThat(all.get(1).id).isEqualTo("cam-2");
        assertThat(all.get(2).id).isEqualTo("cam-3");
    }

    // ===== 厂商筛选 =====

    @Test
    @DisplayName("listDevicesByVendor 仅返回指定厂商设备")
    void listDevicesByVendor_filtersCorrectly() {
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry();
        registry.register(newDevice("hik-1", SurveillanceDevice.Vendor.HIKVISION));
        registry.register(newDevice("hik-2", SurveillanceDevice.Vendor.HIKVISION));
        registry.register(newDevice("dahua-1", SurveillanceDevice.Vendor.DAHUA));
        registry.register(newDevice("uniview-1", SurveillanceDevice.Vendor.UNIVIEW));

        List<SurveillanceDevice> hik = registry.listDevicesByVendor(SurveillanceDevice.Vendor.HIKVISION);

        assertThat(hik).hasSize(2);
        assertThat(hik).allMatch(d -> d.vendor == SurveillanceDevice.Vendor.HIKVISION);
    }

    @Test
    @DisplayName("listDevicesByVendor null 返回空列表")
    void listDevicesByVendor_null_returnsEmpty() {
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry();
        registry.register(newDevice("cam-1", SurveillanceDevice.Vendor.HIKVISION));

        assertThat(registry.listDevicesByVendor(null)).isEmpty();
    }

    // ===== 心跳 =====

    @Test
    @DisplayName("updateHeartbeat 已存在设备返回 true 并刷新心跳时间")
    void updateHeartbeat_existing_returnsTrueAndRefreshes() throws InterruptedException {
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry();
        SurveillanceDevice d = newDevice("cam-1", SurveillanceDevice.Vendor.HIKVISION);
        registry.register(d);
        long oldHeartbeat = d.lastHeartbeatMs;
        Thread.sleep(5);

        boolean ok = registry.updateHeartbeat("cam-1");

        assertThat(ok).isTrue();
        assertThat(d.lastHeartbeatMs).isGreaterThan(oldHeartbeat);
        assertThat(d.status).isEqualTo(SurveillanceDevice.Status.ONLINE);
    }

    @Test
    @DisplayName("updateHeartbeat 不存在设备返回 false")
    void updateHeartbeat_nonExisting_returnsFalse() {
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry();
        assertThat(registry.updateHeartbeat("nope")).isFalse();
    }

    // ===== 超时清理 =====

    @Test
    @DisplayName("pruneStaleDevices 超时设备被标记 OFFLINE 并返回其 id")
    void pruneStaleDevices_marksStaleOffline() {
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry();
        SurveillanceDevice d = newDevice("cam-1", SurveillanceDevice.Vendor.HIKVISION);
        registry.register(d);
        // 模拟 100 秒前心跳
        d.lastHeartbeatMs = System.currentTimeMillis() - 100_000L;

        List<String> stale = registry.pruneStaleDevices(10_000L);

        assertThat(stale).contains("cam-1");
        assertThat(d.status).isEqualTo(SurveillanceDevice.Status.OFFLINE);
    }

    @Test
    @DisplayName("pruneStaleDevices 新鲜设备保持 ONLINE")
    void pruneStaleDevices_freshStaysOnline() {
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry();
        SurveillanceDevice d = newDevice("cam-1", SurveillanceDevice.Vendor.HIKVISION);
        registry.register(d);
        // 刚注册，心跳新鲜

        List<String> stale = registry.pruneStaleDevices(10_000L);

        assertThat(stale).doesNotContain("cam-1");
        assertThat(d.status).isEqualTo(SurveillanceDevice.Status.ONLINE);
    }

    @Test
    @DisplayName("pruneStaleDevices 已 OFFLINE 设备不会被重复处理")
    void pruneStaleDevices_alreadyOffline_skipped() {
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry();
        SurveillanceDevice d = newDevice("cam-1", SurveillanceDevice.Vendor.HIKVISION);
        registry.register(d);
        d.status = SurveillanceDevice.Status.OFFLINE;
        d.lastHeartbeatMs = System.currentTimeMillis() - 100_000L;

        List<String> stale = registry.pruneStaleDevices(10_000L);

        assertThat(stale).doesNotContain("cam-1");
    }

    @Test
    @DisplayName("pruneStaleDevices 负数 timeoutMs 抛异常")
    void pruneStaleDevices_negativeTimeout_throws() {
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry();
        try {
            registry.pruneStaleDevices(-1);
            org.assertj.core.api.Assertions.fail("应抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("timeoutMs");
        }
    }

    // ===== 并发安全 =====

    @Test
    @DisplayName("并发注册不同设备不丢失且无异常")
    void concurrentRegister_noLossNoException() throws InterruptedException {
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry();
        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        registry.register(newDevice("cam-" + (base + i),
                                SurveillanceDevice.Vendor.HIKVISION));
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();

        assertThat(errors.get()).isEqualTo(0);
        assertThat(registry.size()).isEqualTo(threads * perThread);
    }

    @Test
    @DisplayName("并发注册同 ID 设备最终 size=1")
    void concurrentRegister_sameId_eventuallySizeOne() throws InterruptedException {
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    registry.register(newDevice("same-id", SurveillanceDevice.Vendor.HIKVISION));
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();

        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("并发注册 + 注销 + 查询不抛异常")
    void concurrentMixedOperations_noException() throws InterruptedException {
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry();
        int n = 200;
        for (int i = 0; i < n; i++) {
            registry.register(newDevice("cam-" + i, SurveillanceDevice.Vendor.HIKVISION));
        }

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads * 3);
        AtomicInteger errors = new AtomicInteger();

        // 并发查询
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < n; i++) {
                        registry.getDevice("cam-" + i);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        // 并发注销
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < n / 4; i++) {
                        registry.unregister("cam-" + i);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        // 并发列表
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < 10; i++) {
                        registry.listDevices();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();

        assertThat(errors.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("size 反映当前注册设备数")
    void size_reflectsCurrentCount() {
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry();
        assertThat(registry.size()).isEqualTo(0);

        registry.register(newDevice("cam-1", SurveillanceDevice.Vendor.HIKVISION));
        registry.register(newDevice("cam-2", SurveillanceDevice.Vendor.DAHUA));
        assertThat(registry.size()).isEqualTo(2);

        registry.unregister("cam-1");
        assertThat(registry.size()).isEqualTo(1);
    }
}
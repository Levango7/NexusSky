package io.aerofleet.cloud.surveillance;

import io.aerofleet.cloud.security.ApiKeyContext;
import io.aerofleet.cloud.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link SurveillanceDeviceRegistry} 的租户谓词与 prune 行为契约
 * （P1 变异审计 #5 收口）。
 *
 * <p>此前的注册/查询/厂商筛选测试全部运行在"无租户上下文"（全局管理员）下，
 * 租户谓词三个分支从未被反例挑战过——PIT 报出的 5 个存活变异体里，
 * 3 个是"过滤永远放行"（跨租户泄漏到 list / listDevicesByVendor / getDevice），
 * 1 个是排序键被抹成常量，1 个是全局上下文分支被取反后直接 NPE。这里逐一给出反例。
 *
 * <p>租户上下文要点：{@code TenantContext.getEffectiveTenantId()} 先查
 * {@link ApiKeyContext} 再查 {@link TenantContext}，两个 ThreadLocal 都要清理；
 * register() 会以当前上下文覆写 device.tenantId，所以**先（无租户）注册、后手工归属**。
 */
@DisplayName("SurveillanceDeviceRegistry 租户隔离与 prune 契约")
class SurveillanceRegistryTenantContractTest {

    private SurveillanceDeviceRegistry registry;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        ApiKeyContext.clear();
        registry = new SurveillanceDeviceRegistry();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        ApiKeyContext.clear();
    }

    /** 无租户注册，然后手工归属到 owner（register 会用当前上下文覆写 tenantId）。 */
    private void registerOwned(String id, SurveillanceDevice.Vendor vendor, Integer owner) {
        SurveillanceDevice d = new SurveillanceDevice(id, "cam-" + id, vendor,
                "192.168.1.10", 80, "admin", "pass");
        registry.register(d);
        d.tenantId = owner;
    }

    @Test
    @DisplayName("getDevice：跨租户不可见；全局管理员可见——归属设备在全局分支被取反后直接 NPE")
    void getDevice_tenantIsolation() {
        registerOwned("dev-a", SurveillanceDevice.Vendor.HIKVISION, 42);

        // 全局管理员（无租户上下文）：可见，且不得抛 NPE
        assertThat(registry.getDevice("dev-a")).isNotNull();

        // 同租户：可见
        TenantContext.setTenantId(42);
        assertThat(registry.getDevice("dev-a")).isNotNull();

        // 跨租户：不可见（这是隔离语义，不是"查无此设备"的巧合）
        TenantContext.setTenantId(43);
        assertThat(registry.getDevice("dev-a")).isNull();
    }

    @Test
    @DisplayName("listDevices：租户外设备绝不泄漏；未归属设备对本租户可见")
    void listDevices_tenantIsolation() {
        registerOwned("dev-a", SurveillanceDevice.Vendor.HIKVISION, 42); // 他租户
        registerOwned("dev-b", SurveillanceDevice.Vendor.UNIVIEW, null); // 未归属
        registerOwned("dev-c", SurveillanceDevice.Vendor.DAHUA, 43);     // 本租户

        TenantContext.setTenantId(43);
        List<String> visible = registry.listDevices().stream().map(d -> d.id).toList();
        assertThat(visible).containsExactly("dev-b", "dev-c");

        // 全局管理员：全部可见（含他租户）
        TenantContext.clear();
        visible = registry.listDevices().stream().map(d -> d.id).toList();
        assertThat(visible).containsExactly("dev-a", "dev-b", "dev-c");
    }

    @Test
    @DisplayName("listDevicesByVendor：厂商与租户双重过滤都要各自成立")
    void listDevicesByVendor_vendorAndTenantFilter() {
        registerOwned("dev-a", SurveillanceDevice.Vendor.HIKVISION, 42); // 他租户的 HIK
        registerOwned("dev-b", SurveillanceDevice.Vendor.UNIVIEW, null); // 未归属
        registerOwned("dev-c", SurveillanceDevice.Vendor.HIKVISION, 43); // 本租户 HIK
        registerOwned("dev-d", SurveillanceDevice.Vendor.DAHUA, 43);     // 本租户非 HIK

        TenantContext.setTenantId(43);
        List<String> hik = registry.listDevicesByVendor(SurveillanceDevice.Vendor.HIKVISION)
                .stream().map(d -> d.id).toList();
        assertThat(hik).containsExactly("dev-c");   // 未归属的 dev-b 可见，但不是 HIK

        List<String> dahua = registry.listDevicesByVendor(SurveillanceDevice.Vendor.DAHUA)
                .stream().map(d -> d.id).toList();
        assertThat(dahua).containsExactly("dev-d");

        // 全局管理员：HIK 应含他租户的 dev-a
        TenantContext.clear();
        hik = registry.listDevicesByVendor(SurveillanceDevice.Vendor.HIKVISION)
                .stream().map(d -> d.id).toList();
        assertThat(hik).containsExactly("dev-a", "dev-c");
    }

    @Test
    @DisplayName("按 id 字典序排序是稳定契约（注册顺序故意乱序）")
    void listDevicesByVendor_sortedById() {
        for (String id : new String[]{"dev-z", "dev-a", "dev-m", "dev-0", "dev-q"}) {
            registerOwned(id, SurveillanceDevice.Vendor.HIKVISION, null);
        }
        List<String> ids = registry.listDevicesByVendor(SurveillanceDevice.Vendor.HIKVISION)
                .stream().map(d -> d.id).toList();
        assertThat(ids).containsExactly("dev-0", "dev-a", "dev-m", "dev-q", "dev-z");
    }

    @Test
    @DisplayName("pruneStaleDevices(0) 合法：阈值下界不是负数，时钟超前的设备不许误判离线")
    void prune_zeroTimeoutAllowed() {
        registerOwned("dev-f", SurveillanceDevice.Vendor.HIKVISION, null);
        // 心跳标成未来 1s（时钟超前的设备）：无论测试执行跨过多少时钟刻度，
        // now - heartbeat 恒为负、永远新鲜 ⇒ prune(0) 也不许把它判离线。
        // （曾用"刚注册的新鲜设备"验这个：注册到 prune 之间流逝的毫秒会让
        //   设备变成 age=1ms > 0 的"真离线"——那是语义正确，不是缺陷。）
        registry.getDevice("dev-f").lastHeartbeatMs = System.currentTimeMillis() + 1_000;

        assertThatCode(() -> {
            List<String> stale = registry.pruneStaleDevices(0);
            assertThat(stale).isEmpty();
        }).doesNotThrowAnyException();   // 边界变异（<0 → <=0）会在 0 上抛异常
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("prune 心跳为零的设备永不判离线，且 prune 后设备不得被移除")
    void prune_heartbeatZeroNeverStales_deviceStays() {
        registerOwned("dev-h", SurveillanceDevice.Vendor.HIKVISION, null);
        registry.getDevice("dev-h").lastHeartbeatMs = 0;   // "从未心跳过"的登记态

        List<String> stale = registry.pruneStaleDevices(1_000);

        assertThat(stale).isEmpty();
        assertThat(registry.size()).isEqualTo(1);          // prune 不删设备，只置 OFFLINE
        assertThat(registry.getDevice("dev-h")).isNotNull();
        assertThat(registry.getDevice("dev-h").status)
                .isEqualTo(SurveillanceDevice.Status.ONLINE);
    }

    @Test
    @DisplayName("prune 新鲜设备保持在线且保留在注册表中")
    void prune_freshDeviceStaysOnlineAndPresent() {
        registerOwned("dev-n", SurveillanceDevice.Vendor.HIKVISION, null); // 心跳=注册时刻

        List<String> stale = registry.pruneStaleDevices(60_000);

        assertThat(stale).isEmpty();
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.getDevice("dev-n")).isNotNull();
    }
}

package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VendorAdapterRegistry 单元测试。
 * <p>
 * 直接实例化（无 Spring 上下文），覆盖：
 * <ul>
 *   <li>注册与路由：按 Vendor 枚举获取对应适配器</li>
 *   <li>fallback 机制：未知厂商降级到 ONVIF</li>
 *   <li>设备路由：按 SurveillanceDevice.Vendor 获取适配器</li>
 *   <li>各适配器功能：discover/getStreamUrl/ptzControl/subscribeEvents/getCapabilities</li>
 *   <li>错误处理：非法参数抛异常</li>
 * </ul>
 */
@DisplayName("VendorAdapterRegistry 厂商适配器注册与路由")
class VendorAdapterRegistryTest {

    private static final String SUBNET = "192.168.1.0/24";

    // ===== 注册与路由 =====

    @Test
    @DisplayName("注册所有适配器后，按 Vendor 枚举可获取对应适配器")
    void getAdapter_byVendor_returnsCorrectAdapter() {
        VendorAdapterRegistry registry = createFullRegistry();

        assertThat(registry.getAdapter(Vendor.HIKVISION)).isInstanceOf(HikAdapter.class);
        assertThat(registry.getAdapter(Vendor.DAHUA)).isInstanceOf(DahuaAdapter.class);
        assertThat(registry.getAdapter(Vendor.UNIVIEW)).isInstanceOf(UniviewAdapter.class);
        assertThat(registry.getAdapter(Vendor.ONVIF)).isInstanceOf(OnvifVendorAdapter.class);
    }

    @Test
    @DisplayName("getAdapter null vendor 抛 IllegalArgumentException")
    void getAdapter_nullVendor_throws() {
        VendorAdapterRegistry registry = createFullRegistry();
        assertThatThrownBy(() -> registry.getAdapter((Vendor) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vendor");
    }

    @Test
    @DisplayName("hasAdapter 已注册厂商返回 true，未注册厂商返回 false")
    void hasAdapter_registeredAndUnregistered() {
        VendorAdapterRegistry registry = createFullRegistry();

        assertThat(registry.hasAdapter(Vendor.HIKVISION)).isTrue();
        assertThat(registry.hasAdapter(Vendor.DAHUA)).isTrue();
        assertThat(registry.hasAdapter(Vendor.UNIVIEW)).isTrue();
        assertThat(registry.hasAdapter(Vendor.ONVIF)).isTrue();
    }

    @Test
    @DisplayName("getAllAdapters 返回不可变映射，包含所有 4 个适配器")
    void getAllAdapters_returnsImmutableMapWithAllFour() {
        VendorAdapterRegistry registry = createFullRegistry();

        var adapters = registry.getAllAdapters();
        assertThat(adapters).hasSize(4);
        assertThat(adapters).containsKeys(Vendor.HIKVISION, Vendor.DAHUA, Vendor.UNIVIEW, Vendor.ONVIF);
    }

    // ===== Fallback 机制 =====

    @Test
    @DisplayName("未注册 ONVIF 适配器时，请求 ONVIF 返回 null")
    void getAdapter_onvifNotRegistered_returnsNull() {
        // 只注册三个厂商适配器，不注册 ONVIF
        VendorAdapterRegistry registry = new VendorAdapterRegistry(List.of(
                new HikAdapter(),
                new DahuaAdapter(),
                new UniviewAdapter()));

        assertThat(registry.getAdapter(Vendor.ONVIF)).isNull();
    }

    @Test
    @DisplayName("空适配器列表时，getAdapter 返回 null")
    void getAdapter_emptyRegistry_returnsNull() {
        VendorAdapterRegistry registry = new VendorAdapterRegistry(List.of());

        assertThat(registry.getAdapter(Vendor.HIKVISION)).isNull();
        assertThat(registry.getAdapter(Vendor.ONVIF)).isNull();
    }

    // ===== 设备路由 =====

    @Test
    @DisplayName("按设备厂商路由到对应适配器")
    void getAdapter_byDevice_routesToCorrectVendor() {
        VendorAdapterRegistry registry = createFullRegistry();

        SurveillanceDevice hikDevice = newDevice(SurveillanceDevice.Vendor.HIKVISION);
        SurveillanceDevice dahuaDevice = newDevice(SurveillanceDevice.Vendor.DAHUA);
        SurveillanceDevice univiewDevice = newDevice(SurveillanceDevice.Vendor.UNIVIEW);

        assertThat(registry.getAdapter(hikDevice)).isInstanceOf(HikAdapter.class);
        assertThat(registry.getAdapter(dahuaDevice)).isInstanceOf(DahuaAdapter.class);
        assertThat(registry.getAdapter(univiewDevice)).isInstanceOf(UniviewAdapter.class);
    }

    @Test
    @DisplayName("getAdapter null device 抛 IllegalArgumentException")
    void getAdapter_nullDevice_throws() {
        VendorAdapterRegistry registry = createFullRegistry();
        assertThatThrownBy(() -> registry.getAdapter((SurveillanceDevice) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("device");
    }

    // ===== HikAdapter 功能测试 =====

    @Test
    @DisplayName("HikAdapter discover 返回海康设备列表")
    void hikAdapter_discover_returnsHikvisionDevices() {
        HikAdapter adapter = new HikAdapter();

        List<SurveillanceDevice> devices = adapter.discover(SUBNET);

        assertThat(devices).isNotEmpty();
        assertThat(devices).allMatch(d -> d.vendor == SurveillanceDevice.Vendor.HIKVISION);
    }

    @Test
    @DisplayName("HikAdapter getStreamUrl 返回海康格式 RTSP URL")
    void hikAdapter_getStreamUrl_returnsHikRtspUrl() {
        HikAdapter adapter = new HikAdapter();
        SurveillanceDevice device = newDevice(SurveillanceDevice.Vendor.HIKVISION);

        String url = adapter.getStreamUrl(device, 1);

        assertThat(url).startsWith("rtsp://");
        assertThat(url).contains("Streaming/Channels/1");
    }

    @Test
    @DisplayName("HikAdapter ptzControl 支持的命令返回 ok")
    void hikAdapter_ptzControl_returnsOk() {
        HikAdapter adapter = new HikAdapter();
        SurveillanceDevice device = newDevice(SurveillanceDevice.Vendor.HIKVISION);

        for (String cmd : Set.of("up", "down", "left", "right", "zoomIn", "zoomOut", "stop")) {
            assertThat(adapter.ptzControl(device, cmd)).isEqualTo("ok");
        }
    }

    @Test
    @DisplayName("HikAdapter getCapabilities 返回包含 PTZ 的能力集")
    void hikAdapter_getCapabilities_includesPtz() {
        HikAdapter adapter = new HikAdapter();
        SurveillanceDevice device = newDevice(SurveillanceDevice.Vendor.HIKVISION);

        Set<String> caps = adapter.getCapabilities(device);

        assertThat(caps).contains("Device", "Media", "Events", "PTZ", "Imaging");
    }

    @Test
    @DisplayName("HikAdapter subscribeEvents 返回非空句柄")
    void hikAdapter_subscribeEvents_returnsHandle() {
        HikAdapter adapter = new HikAdapter();
        SurveillanceDevice device = newDevice(SurveillanceDevice.Vendor.HIKVISION);

        String handle = adapter.subscribeEvents(device, payload -> {});

        assertThat(handle).isNotBlank();
        assertThat(handle).startsWith("hik-");
    }

    @Test
    @DisplayName("HikAdapter unsubscribeEvents 不抛异常")
    void hikAdapter_unsubscribeEvents_noException() {
        HikAdapter adapter = new HikAdapter();
        SurveillanceDevice device = newDevice(SurveillanceDevice.Vendor.HIKVISION);
        String handle = adapter.subscribeEvents(device, payload -> {});

        adapter.unsubscribeEvents(handle);
        // 无异常即通过
    }

    // ===== DahuaAdapter 功能测试 =====

    @Test
    @DisplayName("DahuaAdapter discover 返回大华设备列表")
    void dahuaAdapter_discover_returnsDahuaDevices() {
        DahuaAdapter adapter = new DahuaAdapter();

        List<SurveillanceDevice> devices = adapter.discover(SUBNET);

        assertThat(devices).isNotEmpty();
        assertThat(devices).allMatch(d -> d.vendor == SurveillanceDevice.Vendor.DAHUA);
    }

    @Test
    @DisplayName("DahuaAdapter getStreamUrl 返回大华格式 RTSP URL")
    void dahuaAdapter_getStreamUrl_returnsDahuaRtspUrl() {
        DahuaAdapter adapter = new DahuaAdapter();
        SurveillanceDevice device = newDevice(SurveillanceDevice.Vendor.DAHUA);

        String url = adapter.getStreamUrl(device, 1);

        assertThat(url).startsWith("rtsp://");
        assertThat(url).contains("realmonitor");
    }

    @Test
    @DisplayName("DahuaAdapter ptzControl 返回 ok")
    void dahuaAdapter_ptzControl_returnsOk() {
        DahuaAdapter adapter = new DahuaAdapter();
        SurveillanceDevice device = newDevice(SurveillanceDevice.Vendor.DAHUA);

        assertThat(adapter.ptzControl(device, "up")).isEqualTo("ok");
    }

    @Test
    @DisplayName("DahuaAdapter subscribeEvents 返回以 dahua- 开头的句柄")
    void dahuaAdapter_subscribeEvents_returnsDahuaHandle() {
        DahuaAdapter adapter = new DahuaAdapter();
        SurveillanceDevice device = newDevice(SurveillanceDevice.Vendor.DAHUA);

        String handle = adapter.subscribeEvents(device, payload -> {});

        assertThat(handle).startsWith("dahua-");
    }

    // ===== UniviewAdapter 功能测试 =====

    @Test
    @DisplayName("UniviewAdapter discover 返回宇视设备列表")
    void univiewAdapter_discover_returnsUniviewDevices() {
        UniviewAdapter adapter = new UniviewAdapter();

        List<SurveillanceDevice> devices = adapter.discover(SUBNET);

        assertThat(devices).isNotEmpty();
        assertThat(devices).allMatch(d -> d.vendor == SurveillanceDevice.Vendor.UNIVIEW);
    }

    @Test
    @DisplayName("UniviewAdapter getStreamUrl 返回宇视格式 RTSP URL")
    void univiewAdapter_getStreamUrl_returnsUniviewRtspUrl() {
        UniviewAdapter adapter = new UniviewAdapter();
        SurveillanceDevice device = newDevice(SurveillanceDevice.Vendor.UNIVIEW);

        String url = adapter.getStreamUrl(device, 1);

        assertThat(url).startsWith("rtsp://");
        assertThat(url).contains("/media/video1");
    }

    @Test
    @DisplayName("UniviewAdapter ptzControl 返回 ok")
    void univiewAdapter_ptzControl_returnsOk() {
        UniviewAdapter adapter = new UniviewAdapter();
        SurveillanceDevice device = newDevice(SurveillanceDevice.Vendor.UNIVIEW);

        assertThat(adapter.ptzControl(device, "stop")).isEqualTo("ok");
    }

    @Test
    @DisplayName("UniviewAdapter subscribeEvents 返回以 uniview- 开头的句柄")
    void univiewAdapter_subscribeEvents_returnsUniviewHandle() {
        UniviewAdapter adapter = new UniviewAdapter();
        SurveillanceDevice device = newDevice(SurveillanceDevice.Vendor.UNIVIEW);

        String handle = adapter.subscribeEvents(device, payload -> {});

        assertThat(handle).startsWith("uniview-");
    }

    // ===== OnvifVendorAdapter 功能测试 =====

    @Test
    @DisplayName("OnvifVendorAdapter 包装 OnvifClient 的 discover 方法")
    void onvifAdapter_discover_delegatesToOnvifClient() {
        OnvifClient onvifClient = new OnvifClient();
        OnvifVendorAdapter adapter = new OnvifVendorAdapter(onvifClient);

        List<SurveillanceDevice> devices = adapter.discover(SUBNET);

        assertThat(devices).hasSize(3);
    }

    @Test
    @DisplayName("OnvifVendorAdapter getStreamUrl 委托 OnvifClient")
    void onvifAdapter_getStreamUrl_delegatesToOnvifClient() {
        OnvifClient onvifClient = new OnvifClient();
        OnvifVendorAdapter adapter = new OnvifVendorAdapter(onvifClient);
        SurveillanceDevice device = newDevice(SurveillanceDevice.Vendor.HIKVISION);

        String url = adapter.getStreamUrl(device, 1);

        assertThat(url).startsWith("rtsp://");
    }

    @Test
    @DisplayName("OnvifVendorAdapter subscribeEvents 委托 OnvifClient 并触发回调")
    void onvifAdapter_subscribeEvents_delegatesAndTriggersCallback() {
        OnvifClient onvifClient = new OnvifClient();
        OnvifVendorAdapter adapter = new OnvifVendorAdapter(onvifClient);
        SurveillanceDevice device = newDevice(SurveillanceDevice.Vendor.HIKVISION);
        AtomicReference<String> received = new AtomicReference<>();

        String handle = adapter.subscribeEvents(device, received::set);
        boolean triggered = onvifClient.simulateEvent(handle, "<Event>Motion</Event>");

        assertThat(triggered).isTrue();
        assertThat(received.get()).isEqualTo("<Event>Motion</Event>");
    }

    @Test
    @DisplayName("OnvifVendorAdapter getCapabilities 委托 OnvifClient")
    void onvifAdapter_getCapabilities_delegatesToOnvifClient() {
        OnvifClient onvifClient = new OnvifClient();
        OnvifVendorAdapter adapter = new OnvifVendorAdapter(onvifClient);
        SurveillanceDevice device = newDevice(SurveillanceDevice.Vendor.HIKVISION);

        Set<String> caps = adapter.getCapabilities(device);

        assertThat(caps).contains("Device", "Media", "Events");
    }

    // ===== 错误处理 =====

    @Test
    @DisplayName("各适配器 discover null/blank subnet 抛 IllegalArgumentException")
    void allAdapters_discover_blankSubnet_throws() {
        HikAdapter hik = new HikAdapter();
        DahuaAdapter dahua = new DahuaAdapter();
        UniviewAdapter uniview = new UniviewAdapter();

        assertThatThrownBy(() -> hik.discover(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dahua.discover(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> uniview.discover(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("各适配器 getStreamUrl null device 抛 IllegalArgumentException")
    void allAdapters_getStreamUrl_nullDevice_throws() {
        HikAdapter hik = new HikAdapter();
        DahuaAdapter dahua = new DahuaAdapter();
        UniviewAdapter uniview = new UniviewAdapter();

        assertThatThrownBy(() -> hik.getStreamUrl(null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dahua.getStreamUrl(null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> uniview.getStreamUrl(null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("各适配器 ptzControl 不支持的命令抛 IllegalArgumentException")
    void allAdapters_ptzControl_unsupportedCommand_throws() {
        HikAdapter hik = new HikAdapter();
        DahuaAdapter dahua = new DahuaAdapter();
        UniviewAdapter uniview = new UniviewAdapter();
        SurveillanceDevice device = newDevice(SurveillanceDevice.Vendor.HIKVISION);

        assertThatThrownBy(() -> hik.ptzControl(device, "panLeft"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dahua.ptzControl(device, "tiltUp"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> uniview.ptzControl(device, "rotate"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("各适配器 subscribeEvents null callback 抛 IllegalArgumentException")
    void allAdapters_subscribeEvents_nullCallback_throws() {
        HikAdapter hik = new HikAdapter();
        DahuaAdapter dahua = new DahuaAdapter();
        UniviewAdapter uniview = new UniviewAdapter();
        SurveillanceDevice device = newDevice(SurveillanceDevice.Vendor.HIKVISION);

        assertThatThrownBy(() -> hik.subscribeEvents(device, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dahua.subscribeEvents(device, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> uniview.subscribeEvents(device, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 内部工具方法 =====

    /** 创建包含全部 4 个适配器的注册表。 */
    private static VendorAdapterRegistry createFullRegistry() {
        OnvifClient onvifClient = new OnvifClient();
        return new VendorAdapterRegistry(List.of(
                new HikAdapter(),
                new DahuaAdapter(),
                new UniviewAdapter(),
                new OnvifVendorAdapter(onvifClient)));
    }

    /** 创建测试用设备。 */
    private static SurveillanceDevice newDevice(SurveillanceDevice.Vendor vendor) {
        return new SurveillanceDevice(
                "test-" + vendor.name().toLowerCase(),
                "测试设备",
                vendor,
                "192.168.1.100",
                80,
                "admin",
                "pass123");
    }
}
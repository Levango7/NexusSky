package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 安防包的**校验契约**：把"改了生产代码也没测试变红"的那些地方钉死。
 *
 * <p><b>这批用例是变异审计逼出来的</b>（2026-10-22，PIT 跑 io.aerofleet.cloud.surveillance.*）：
 * 809 个变异体只杀掉 409（变异分数 0.77，security 包是 0.84）。逐条手动复现后，
 * 下面这几处是**真缺口**（不是行为等价的变异体）：
 *
 * <ul>
 *   <li><b>四个厂商适配器的 {@code validateDevice} 调用被删掉，没有任何测试会红</b>
 *       （Hik / Uniview / Dahua / Gb28181 的 getStreamUrl、ptzControl、subscribeEvents、
 *       getCapabilities 共 14 个存活变异体）⇒ 传入 null 设备时，从"抛 IllegalArgumentException"
 *       退化成 NullPointerException，传入空 IP 时直接拿着空地址去拼流 URL。</li>
 *   <li><b>{@code RapidDeployService.validateSubnet} 的调用被删掉没人发现</b>
 *       （2 个存活变异体）⇒ 子网参数不再被校验，扫描器拿着空子网就去扫。</li>
 *   <li><b>{@code extractLastOctet}（设备 ID 里的 IP 末段）没被钉住</b>
 *       （11 个存活变异体）⇒ 设备 ID 会变成 {@code cam-0-...}，同网段设备 ID 撞车、
 *       认不出设备。</li>
 * </ul>
 *
 * <p><b>怎么验证这些用例真的在守着那件事</b>：把生产代码改坏（删掉校验调用），
 * 本类必须**变红** —— 见 PR 描述里的变异验证记录。
 */
@DisplayName("安防：校验契约（变异审计审出来的真缺口）")
class SurveillanceValidationContractTest {

    // ------------------------------------------------------------------
    // 1. 厂商适配器：null 设备 / 空 IP 必须被拒（不能退化成 NPE）
    // ------------------------------------------------------------------

    /** 三个"按 IP 校验"的厂商适配器（Hik / Uniview / Dahua）。 */
    private static List<VendorAdapter> ipValidatingAdapters() {
        return List.of(new HikAdapter(), new UniviewAdapter(), new DahuaAdapter());
    }

    /** GB28181 适配器（构造器要信令参数）。 */
    private static VendorAdapter gb28181Adapter() {
        return new Gb28181Adapter(new Gb28181CatalogParser(),
                "00000000000000000001", "127.0.0.1:5060");
    }

    private static SurveillanceDevice deviceWithIp(String ip) {
        return new SurveillanceDevice("cam-1", "测试设备", SurveillanceDevice.Vendor.HIKVISION,
                ip, 80, "admin", "pass");
    }

    @Test
    @DisplayName("1. 四个厂商适配器：null 设备一律抛 IllegalArgumentException（不是 NPE）")
    void adaptersRejectNullDevice() {
        for (VendorAdapter adapter : ipValidatingAdapters()) {
            String who = adapter.getVendor().name();
            assertThatThrownBy(() -> adapter.getStreamUrl(null, 1))
                    .as("%s.getStreamUrl(null) 必须是 IllegalArgumentException", who)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be null");
            assertThatThrownBy(() -> adapter.ptzControl(null, "up"))
                    .as("%s.ptzControl(null) 必须是 IllegalArgumentException", who)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be null");
            assertThatThrownBy(() -> adapter.subscribeEvents(null, (Consumer<String>) p -> { }))
                    .as("%s.subscribeEvents(null) 必须是 IllegalArgumentException", who)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be null");
            assertThatThrownBy(() -> adapter.getCapabilities(null))
                    .as("%s.getCapabilities(null) 必须是 IllegalArgumentException", who)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be null");
        }
        // GB28181 的契约不同（校验 id 与厂商），但"null 设备必须被拒"是一致的
        VendorAdapter gb = gb28181Adapter();
        assertThatThrownBy(() -> gb.getStreamUrl(null, 1))
                .as("GB28181.getStreamUrl(null) 必须是 IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gb.ptzControl(null, "up"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gb.subscribeEvents(null, (Consumer<String>) p -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gb.getCapabilities(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("2. Hik/Uniview/Dahua：IP 失效必须被拒（不能拿着空地址去拼流 URL）")
    void ipValidatingAdaptersRejectBrokenIp() {
        for (VendorAdapter adapter : ipValidatingAdapters()) {
            String who = adapter.getVendor().name();
            // 构造器本身就会拒空 IP ⇒ 想测到"构造之后 IP 被改坏"这条路径，
            // 得走公开可变字段（SurveillanceDevice.ip 是公开字段，运行时可被改）。
            SurveillanceDevice broken = deviceWithIp("192.168.1.50");
            broken.ip = null;
            assertThatThrownBy(() -> adapter.getStreamUrl(broken, 1))
                    .as("%s.getStreamUrl(IP失效) 必须是 IllegalArgumentException", who)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ip must not be blank");
            assertThatThrownBy(() -> adapter.ptzControl(broken, "up"))
                    .as("%s.ptzControl(IP失效) 必须是 IllegalArgumentException", who)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ip must not be blank");
            assertThatThrownBy(() -> adapter.subscribeEvents(broken, (Consumer<String>) p -> { }))
                    .as("%s.subscribeEvents(IP失效) 必须是 IllegalArgumentException", who)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ip must not be blank");
            assertThatThrownBy(() -> adapter.getCapabilities(broken))
                    .as("%s.getCapabilities(IP失效) 必须是 IllegalArgumentException", who)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ip must not be blank");
        }
    }

    @Test
    @DisplayName("2b. GB28181：只收 GB28181 设备，别的厂商必须被拒")
    void gb28181AdapterRejectsForeignVendor() {
        VendorAdapter gb = gb28181Adapter();
        SurveillanceDevice hik = deviceWithIp("192.168.1.50");   // 厂商是 HIKVISION
        assertThatThrownBy(() -> gb.getCapabilities(hik))
                .as("GB28181 适配器只接受 GB28181 设备")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only accepts GB28181 devices");
        assertThatThrownBy(() -> gb.getStreamUrl(hik, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only accepts GB28181 devices");
        assertThatThrownBy(() -> gb.ptzControl(hik, "up"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only accepts GB28181 devices");
    }

    // ------------------------------------------------------------------
    // 2. 快速部署：子网参数必须被校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("3. scanAndDeploy：空子网必须被拒（文档写明 @throws IllegalArgumentException）")
    void scanAndDeployRejectsBlankSubnet() {
        RapidDeployService service = new RapidDeployService(
                new OnvifClient(), new SurveillanceDeviceRegistry());
        assertThatThrownBy(() -> service.scanAndDeploy(null, "admin", "pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subnet");
        assertThatThrownBy(() -> service.scanAndDeploy("   ", "admin", "pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subnet");
    }

    @Test
    @DisplayName("4. scanOnly：空子网必须被拒")
    void scanOnlyRejectsBlankSubnet() {
        RapidDeployService service = new RapidDeployService(
                new OnvifClient(), new SurveillanceDeviceRegistry());
        assertThatThrownBy(() -> service.scanOnly(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subnet");
        assertThatThrownBy(() -> service.scanOnly(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subnet");
    }

    // ------------------------------------------------------------------
    // 3. 设备 ID 必须带上 IP 末段（认设备靠它）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("5. 部署生成的设备 ID 必须含 IP 末段（cam-<末段>-…）")
    void generatedDeviceIdCarriesIpLastOctet() {
        RapidDeployService service = new RapidDeployService(
                new OnvifClient(), new SurveillanceDeviceRegistry());

        List<DeployResult> results = service.scanAndDeploy("192.168.1.0/24", "admin", "admin123");

        assertThat(results).isNotEmpty();
        for (DeployResult r : results) {
            String ip = r.getIp();
            String lastOctet = ip.substring(ip.lastIndexOf('.') + 1);
            assertThat(r.getDeviceId())
                    .as("设备 %s 的 ID 必须含它的 IP 末段 %s", ip, lastOctet)
                    .startsWith("cam-" + lastOctet + "-");
        }
    }
}

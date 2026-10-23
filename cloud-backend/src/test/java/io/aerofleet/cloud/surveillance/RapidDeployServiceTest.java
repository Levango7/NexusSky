package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RapidDeployService} 单元测试。
 * <p>
 * 覆盖布控球自动发现+一键注册的核心场景：
 * <ul>
 *   <li>新设备发现 → SUCCESS</li>
 *   <li>已注册设备 → SKIPPED</li>
 *   <li>RTSP 获取失败 → FAILED</li>
 *   <li>仅扫描不注册、批量部署厂商过滤、并发安全、默认凭据等</li>
 * </ul>
 * <p>
 * 多数测试使用真实的 {@link OnvifClient}（模拟实现）+ {@link SurveillanceDeviceRegistry}，
 * 需要控制发现结果或模拟失败的场景使用 Mockito mock。
 */
@DisplayName("RapidDeployService 布控球快速部署")
class RapidDeployServiceTest {

    private OnvifClient onvifClient;
    private SurveillanceDeviceRegistry registry;
    private RapidDeployService service;

    @BeforeEach
    void setUp() {
        onvifClient = new OnvifClient();
        registry = new SurveillanceDeviceRegistry();
        service = new RapidDeployService(onvifClient, registry);
    }

    /** 构造一个已注册设备并放入 Registry。 */
    private static SurveillanceDevice preRegister(SurveillanceDeviceRegistry reg,
                                                  String id, String ip,
                                                  SurveillanceDevice.Vendor vendor) {
        SurveillanceDevice d = new SurveillanceDevice(id, "已有设备", vendor, ip, 80, "admin", "pass");
        reg.register(d);
        return d;
    }

    // ------------------------------------------------------------------
    // scanAndDeploy
    // ------------------------------------------------------------------

    @Test
    @DisplayName("1. scanAndDeploy 发现新设备 → SUCCESS + 设备已注册")
    void scanAndDeployNewDevicesSuccess() {
        List<DeployResult> results = service.scanAndDeploy("192.168.1.0/24", "admin", "admin123");

        assertThat(results).hasSize(3);
        for (DeployResult r : results) {
            assertThat(r.getStatus()).isEqualTo(DeployResult.Status.SUCCESS);
            assertThat(r.getRtspUrl()).isNotNull();
            assertThat(r.getMessage()).isEqualTo("discovered and registered");
            assertThat(r.getDeviceId()).startsWith("cam-");
        }
        assertThat(registry.size()).isEqualTo(3);

        // 验证每个成功结果对应 Registry 中已注册设备
        for (DeployResult r : results) {
            SurveillanceDevice registered = registry.getDevice(r.getDeviceId());
            assertThat(registered).isNotNull();
            assertThat(registered.ip).isEqualTo(r.getIp());
            assertThat(registered.rtspUrl).isEqualTo(r.getRtspUrl());
        }
    }

    @Test
    @DisplayName("2. scanAndDeploy 已注册设备 → SKIPPED")
    void scanAndDeployAlreadyRegisteredSkipped() {
        preRegister(registry, "existing-100", "192.168.1.100", SurveillanceDevice.Vendor.HIKVISION);

        List<DeployResult> results = service.scanAndDeploy("192.168.1.0/24", "admin", "admin123");

        DeployResult skipped = results.stream()
                .filter(r -> "192.168.1.100".equals(r.getIp()))
                .findFirst().orElseThrow();
        assertThat(skipped.getStatus()).isEqualTo(DeployResult.Status.SKIPPED);
        assertThat(skipped.getMessage()).isEqualTo("already registered");
        assertThat(skipped.getDeviceId()).isEqualTo("existing-100");
        assertThat(skipped.getRtspUrl()).isNull();
    }

    @Test
    @DisplayName("3. scanAndDeploy 无设备发现 → 空列表")
    void scanAndDeployNoDevicesReturnsEmpty() {
        OnvifClient mockOnvif = mock(OnvifClient.class);
        SurveillanceDeviceRegistry reg = new SurveillanceDeviceRegistry();
        RapidDeployService svc = new RapidDeployService(mockOnvif, reg);

        when(mockOnvif.discoverDevices("192.168.1.0/24")).thenReturn(Collections.emptyList());

        List<DeployResult> results = svc.scanAndDeploy("192.168.1.0/24", "admin", "pass");

        assertThat(results).isEmpty();
        assertThat(reg.size()).isEqualTo(0);
    }

    /**
     * 变异收口：extractLastOctet 的三个存活变异（{@code >=0} 边界、
     * {@code <len-1} 边界、减→加）在常规 IP 上都不可见。用 ".8"（点在第 0 位，
     * 原实现取 "8"）与 "x."（末尾点，原实现取 "0"）两个畸形形状逐个现形。
     */
    @Test
    @DisplayName("10. 设备 id 尾段取 IP 最后一个点之后（畸形形状不许退化）")
    void deviceIdLastOctetEdgeShapes() {
        OnvifClient mockOnvif = mock(OnvifClient.class);
        SurveillanceDeviceRegistry reg = new SurveillanceDeviceRegistry();
        RapidDeployService svc = new RapidDeployService(mockOnvif, reg);

        SurveillanceDevice dot0 = new SurveillanceDevice(
                "preset-dot", "cam", SurveillanceDevice.Vendor.ONVIF, ".8", 80, "admin", "pass");
        SurveillanceDevice trailingDot = new SurveillanceDevice(
                "preset-tail", "cam", SurveillanceDevice.Vendor.ONVIF, "x.", 80, "admin", "pass");
        SurveillanceDevice normal = new SurveillanceDevice(
                "preset-100", "cam", SurveillanceDevice.Vendor.ONVIF, "1.2.3.100", 80, "admin", "pass");
        when(mockOnvif.discoverDevices("10.0.0.0/24"))
                .thenReturn(List.of(dot0, trailingDot, normal));
        when(mockOnvif.getDeviceCapabilities(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(Set.of("Media"));
        when(mockOnvif.getRtspUrl(anyString(), anyInt(), anyString(), anyString(), anyInt()))
                .thenReturn("rtsp://stub/Streaming/Channels/1");

        List<DeployResult> results = svc.scanAndDeploy("10.0.0.0/24", "admin", "pass");

        assertThat(results).extracting(DeployResult::getStatus)
                .containsOnly(DeployResult.Status.SUCCESS);
        assertThat(results).extracting(DeployResult::getDeviceId)
                .anySatisfy(id -> assertThat(id).startsWith("cam-8-"))    // ".8" → "8"
                .anySatisfy(id -> assertThat(id).startsWith("cam-0-"))    // "x." → 无末段取 "0"
                .anySatisfy(id -> assertThat(id).startsWith("cam-100-")); // 规则 IP 取末段

        // 注册进 Registry 的设备能力集是真实抓取的（变异体删 setCapabilities 后是空集）
        for (DeployResult r : results) {
            SurveillanceDevice deployed = reg.getDevice(r.getDeviceId());
            assertThat(deployed).isNotNull();
            assertThat(deployed.getCapabilities()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("4. scanAndDeploy 多设备混合（部分新+部分已注册）→ 各自状态正确")
    void scanAndDeployMixedDevices() throws Exception {
        preRegister(registry, "existing-100", "192.168.1.100", SurveillanceDevice.Vendor.HIKVISION);

        List<DeployResult> results;
        try (LogWiretap tap = LogWiretap.on(RapidDeployService.class)) {
            results = service.scanAndDeploy("192.168.1.0/24", "admin", "admin123");

            // 收口（变异体在此存活过）：完成日志的计数逐项固定——筛选谓词取反/恒真
            // 不会改变返回值，只会让运维看到的 success/skipped/failed 数字说谎
            String countLine = tap.lines().stream()
                    .filter(s -> s.contains("Rapid deploy complete:"))
                    .findFirst().orElseThrow();
            assertThat(countLine).contains("total=3 success=2 skipped=1 failed=0");
        }

        assertThat(results).hasSize(3);

        // .100 SKIPPED
        DeployResult skipped = results.stream()
                .filter(r -> "192.168.1.100".equals(r.getIp()))
                .findFirst().orElseThrow();
        assertThat(skipped.getStatus()).isEqualTo(DeployResult.Status.SKIPPED);

        // .101, .102 SUCCESS
        List<DeployResult> success = results.stream()
                .filter(r -> r.getStatus() == DeployResult.Status.SUCCESS)
                .toList();
        assertThat(success).hasSize(2);
        assertThat(success).extracting(DeployResult::getIp)
                .containsExactlyInAnyOrder("192.168.1.101", "192.168.1.102");

        // Registry 中 3 个设备（1 已有 + 2 新注册）
        assertThat(registry.size()).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // scanOnly
    // ------------------------------------------------------------------

    @Test
    @DisplayName("5. scanOnly 不注册到 Registry")
    void scanOnlyDoesNotRegister() {
        int sizeBefore = registry.size();

        List<DeployResult> results = service.scanOnly("192.168.1.0/24");

        assertThat(results).hasSize(3);
        assertThat(registry.size()).isEqualTo(sizeBefore);
        for (DeployResult r : results) {
            assertThat(r.getStatus()).isEqualTo(DeployResult.Status.SKIPPED);
            assertThat(r.getMessage()).isEqualTo("discovered (scan only)");
            assertThat(r.getRtspUrl()).isNull();
        }
    }

    // ------------------------------------------------------------------
    // batchDeploy
    // ------------------------------------------------------------------

    @Test
    @DisplayName("6. batchDeploy 无 vendorFilter → 部署所有厂商")
    void batchDeployNoFilter() {
        List<DeployResult> results = service.batchDeploy("192.168.1.0/24", "admin", "admin123", null);

        assertThat(results).hasSize(3);
        assertThat(results).extracting(DeployResult::getStatus)
                .containsOnly(DeployResult.Status.SUCCESS);
        assertThat(results).extracting(DeployResult::getVendor)
                .containsExactlyInAnyOrder("HIKVISION", "DAHUA", "UNIVIEW");
    }

    @Test
    @DisplayName("7. batchDeploy 有 vendorFilter → 仅部署匹配厂商")
    void batchDeployWithFilter() {
        List<DeployResult> results = service.batchDeploy("192.168.1.0/24", "admin", "admin123", "HIKVISION");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getVendor()).isEqualTo("HIKVISION");
        assertThat(results.get(0).getStatus()).isEqualTo(DeployResult.Status.SUCCESS);
        assertThat(results.get(0).getIp()).isEqualTo("192.168.1.100");
    }

    @Test
    @DisplayName("7b. batchDeploy vendorFilter 大小写不敏感")
    void batchDeployFilterCaseInsensitive() {
        List<DeployResult> results = service.batchDeploy("192.168.1.0/24", "admin", "admin123", "dahua");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getVendor()).isEqualTo("DAHUA");
    }

    // ------------------------------------------------------------------
    // 并发安全
    // ------------------------------------------------------------------

    @Test
    @DisplayName("8. scanAndDeploy 并发安全（多线程同时调用）")
    void scanAndDeployConcurrentSafety() throws InterruptedException {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);
        List<DeployResult> allResults = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    List<DeployResult> results = service.scanAndDeploy("192.168.1.0/24", "admin", "admin123");
                    allResults.addAll(results);
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    endGate.countDown();
                }
            });
        }
        startGate.countDown();
        boolean finished = endGate.await(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(finished).as("所有线程应在 30s 内完成").isTrue();
        assertThat(errors.get()).as("不应抛出异常").isEqualTo(0);

        // 至少注册了 3 个不同 IP 的设备
        assertThat(registry.size()).isGreaterThanOrEqualTo(3);
        // 所有结果状态为 SUCCESS 或 SKIPPED
        for (DeployResult r : allResults) {
            assertThat(r.getStatus()).isIn(DeployResult.Status.SUCCESS, DeployResult.Status.SKIPPED);
        }
        // 至少有一个 SUCCESS（第一次调用的线程会成功注册）
        long successCount = allResults.stream()
                .filter(r -> r.getStatus() == DeployResult.Status.SUCCESS)
                .count();
        assertThat(successCount).isGreaterThanOrEqualTo(3);
    }

    // ------------------------------------------------------------------
    // 参数校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("9. scanAndDeploy 子网为空 → 抛 IllegalArgumentException")
    void scanAndDeployBlankSubnetThrows() {
        assertThatThrownBy(() -> service.scanAndDeploy("", "admin", "pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subnet");
        assertThatThrownBy(() -> service.scanAndDeploy(null, "admin", "pass"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.scanAndDeploy("   ", "admin", "pass"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("9b. scanOnly 子网为空 → 抛 IllegalArgumentException")
    void scanOnlyBlankSubnetThrows() {
        assertThatThrownBy(() -> service.scanOnly(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 变异收口：子网校验是<b>服务自身</b>的入参契约。既有测试用真实客户端，
     * 而 {@code OnvifClient.discoverDevices} 恰好对空子网抛同形异常——删掉
     * {@code validateSubnet} 调用两条测试都看不出差别。把客户端 mock 掉，
     * 服务这一道闸必须自己立住（ Kraut 检查的双保险，删一道必须现形）。
     */
    @Test
    @DisplayName("10b. 即使下游客户端被 mock，服务也必须自己校验子网")
    void blankSubnetSelfValidatedEvenWithMockClient() {
        RapidDeployService svc = new RapidDeployService(
                mock(OnvifClient.class), new SurveillanceDeviceRegistry());

        assertThatThrownBy(() -> svc.scanAndDeploy("   ", "admin", "pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subnet");
        assertThatThrownBy(() -> svc.scanOnly("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subnet");
    }

    @Test
    @DisplayName("9c. batchDeploy 子网为空 → 抛 IllegalArgumentException")
    void batchDeployBlankSubnetThrows() {
        assertThatThrownBy(() -> service.batchDeploy("", "admin", "pass", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // 默认凭据
    // ------------------------------------------------------------------

    @Test
    @DisplayName("10. scanAndDeploy username/password 为空 → 使用默认值")
    void scanAndDeployDefaultCredentials() {
        List<DeployResult> results = service.scanAndDeploy("192.168.1.0/24", null, null);

        assertThat(results).hasSize(3);
        for (DeployResult r : results) {
            assertThat(r.getStatus()).isEqualTo(DeployResult.Status.SUCCESS);
            // RTSP URL 应包含默认凭据 admin:admin123
            assertThat(r.getRtspUrl()).contains("admin:admin123@");
        }

        // 验证 Registry 中设备的凭据为默认值
        for (SurveillanceDevice d : registry.listDevices()) {
            assertThat(d.username).isEqualTo(RapidDeployService.DEFAULT_USERNAME);
            assertThat(d.password).isEqualTo(RapidDeployService.DEFAULT_PASSWORD);
        }
    }

    @Test
    @DisplayName("10b. scanAndDeploy username/password 为空白 → 使用默认值")
    void scanAndDeployBlankCredentialsUseDefault() {
        List<DeployResult> results = service.scanAndDeploy("192.168.1.0/24", "  ", "  ");

        assertThat(results).hasSize(3);
        for (DeployResult r : results) {
            assertThat(r.getStatus()).isEqualTo(DeployResult.Status.SUCCESS);
            assertThat(r.getRtspUrl()).contains("admin:admin123@");
        }
    }

    // ------------------------------------------------------------------
    // 失败场景
    // ------------------------------------------------------------------

    @Test
    @DisplayName("11. scanAndDeploy 设备发现成功但获取RTSP失败 → FAILED")
    void scanAndDeployRtspFailureReturnsFailed() {
        OnvifClient mockOnvif = mock(OnvifClient.class);
        SurveillanceDeviceRegistry reg = new SurveillanceDeviceRegistry();
        RapidDeployService svc = new RapidDeployService(mockOnvif, reg);

        List<SurveillanceDevice> devices = List.of(
                new SurveillanceDevice("d1", "cam", SurveillanceDevice.Vendor.HIKVISION,
                        "192.168.1.100", 80, "admin", "pass"));
        when(mockOnvif.discoverDevices("192.168.1.0/24")).thenReturn(devices);
        when(mockOnvif.getDeviceCapabilities(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(Set.of("Device", "Media"));
        when(mockOnvif.getRtspUrl(anyString(), anyInt(), anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("connection refused"));

        List<DeployResult> results = svc.scanAndDeploy("192.168.1.0/24", "admin", "pass");

        assertThat(results).hasSize(1);
        DeployResult r = results.get(0);
        assertThat(r.getStatus()).isEqualTo(DeployResult.Status.FAILED);
        assertThat(r.getMessage()).contains("rtsp url acquisition failed");
        assertThat(r.getMessage()).contains("connection refused");
        assertThat(r.getRtspUrl()).isNull();
        // FAILED 设备不应注册到 Registry
        assertThat(reg.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("11b. scanAndDeploy 获取能力失败 → FAILED")
    void scanAndDeployCapabilitiesFailureReturnsFailed() {
        OnvifClient mockOnvif = mock(OnvifClient.class);
        SurveillanceDeviceRegistry reg = new SurveillanceDeviceRegistry();
        RapidDeployService svc = new RapidDeployService(mockOnvif, reg);

        List<SurveillanceDevice> devices = List.of(
                new SurveillanceDevice("d1", "cam", SurveillanceDevice.Vendor.DAHUA,
                        "10.0.0.50", 80, "admin", "pass"));
        when(mockOnvif.discoverDevices("10.0.0.0/24")).thenReturn(devices);
        when(mockOnvif.getDeviceCapabilities(anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(new RuntimeException("auth failed"));

        List<DeployResult> results = svc.scanAndDeploy("10.0.0.0/24", "admin", "pass");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getStatus()).isEqualTo(DeployResult.Status.FAILED);
        assertThat(results.get(0).getMessage()).contains("capabilities acquisition failed");
        assertThat(reg.size()).isEqualTo(0);
    }

    // ------------------------------------------------------------------
    // RTSP URL 正确性
    // ------------------------------------------------------------------

    @Test
    @DisplayName("12. scanAndDeploy 结果包含正确的 RTSP URL")
    void scanAndDeployContainsCorrectRtspUrl() {
        List<DeployResult> results = service.scanAndDeploy("192.168.1.0/24", "admin", "admin123");

        for (DeployResult r : results) {
            String url = r.getRtspUrl();
            assertThat(url).as("RTSP URL 不应为空").isNotNull();
            assertThat(url).startsWith("rtsp://");
            assertThat(url).contains("admin:admin123@");
            assertThat(url).contains(":554/Streaming/Channels/1");
            // IP 应出现在 URL 中
            assertThat(url).contains(r.getIp());
        }
    }

    @Test
    @DisplayName("12b. scanAndDeploy 不同子网生成不同 IP 的 RTSP URL")
    void scanAndDeployDifferentSubnetDifferentIp() {
        List<DeployResult> results = service.scanAndDeploy("10.0.0.0/24", "admin", "pass");

        assertThat(results).hasSize(3);
        assertThat(results).extracting(DeployResult::getIp)
                .containsExactlyInAnyOrder("10.0.0.100", "10.0.0.101", "10.0.0.102");
        for (DeployResult r : results) {
            assertThat(r.getRtspUrl()).contains(r.getIp());
        }
    }

    // ------------------------------------------------------------------
    // 设备 ID 唯一性
    // ------------------------------------------------------------------

    @Test
    @DisplayName("13. scanAndDeploy 生成的设备 ID 唯一")
    void scanAndDeployGeneratesUniqueDeviceIds() {
        List<DeployResult> results = service.scanAndDeploy("192.168.1.0/24", "admin", "admin123");

        List<String> ids = results.stream().map(DeployResult::getDeviceId).toList();
        assertThat(ids).hasSize(3);
        assertThat(ids).doesNotHaveDuplicates();
        for (String id : ids) {
            assertThat(id).startsWith("cam-");
        }
    }
}
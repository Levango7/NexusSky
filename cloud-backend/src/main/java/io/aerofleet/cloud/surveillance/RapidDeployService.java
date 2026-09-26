package io.aerofleet.cloud.surveillance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 布控球快速部署服务。
 * <p>
 * 应急场景下，便携式球形监控摄像头（海康/大华/宇视）需要快速上线：
 * <ol>
 *   <li>扫描子网自动发现 ONVIF 设备（{@link OnvifClient#discoverDevices}）</li>
 *   <li>对每个发现的设备检查是否已注册（同 IP 已存在则跳过）</li>
 *   <li>自动生成设备 ID、获取能力与 RTSP 流，注册到 {@link SurveillanceDeviceRegistry}</li>
 *   <li>返回每个设备的部署结果（{@link DeployResult}）</li>
 * </ol>
 * <p>
 * <b>线程安全</b>：依赖 {@link OnvifClient} 与 {@link SurveillanceDeviceRegistry} 的线程安全
 * 实现，设备 ID 生成使用 {@link AtomicLong} 计数器避免并发冲突。
 * <p>
 * <b>默认凭据</b>：当 username/password 为空时使用默认值（admin/admin123），
 * 适配多数布控球出厂配置。
 */
@Component
public class RapidDeployService {

    private static final Logger log = LoggerFactory.getLogger(RapidDeployService.class);

    /** 默认用户名（多数布控球出厂默认）。 */
    public static final String DEFAULT_USERNAME = "admin";
    /** 默认密码（多数布控球出厂默认）。 */
    public static final String DEFAULT_PASSWORD = "admin123";
    /** 设备 ID 前缀。 */
    public static final String DEVICE_ID_PREFIX = "cam";
    /** RTSP 默认通道号。 */
    public static final int DEFAULT_CHANNEL = 1;

    private final OnvifClient onvifClient;
    private final SurveillanceDeviceRegistry registry;

    /** 设备 ID 并发计数器，避免同一毫秒内多线程生成相同 ID。 */
    private final AtomicLong idCounter = new AtomicLong(0);

    public RapidDeployService(OnvifClient onvifClient, SurveillanceDeviceRegistry registry) {
        this.onvifClient = onvifClient;
        this.registry = registry;
    }

    /**
     * 扫描子网并自动注册所有发现的设备。
     * <p>
     * 流程：
     * <ol>
     *   <li>调用 {@link OnvifClient#discoverDevices} 发现设备</li>
     *   <li>对每个设备检查 Registry 中是否已有同 IP 设备（已注册则 SKIPPED）</li>
     *   <li>生成设备 ID、获取能力与 RTSP 流、注册到 Registry（SUCCESS）</li>
     *   <li>获取能力或 RTSP 流失败则 FAILED（不注册）</li>
     * </ol>
     *
     * @param subnet   子网 CIDR，如 "192.168.1.0/24"（不可为空）
     * @param username ONVIF 登录用户名（为空时使用 {@link #DEFAULT_USERNAME}）
     * @param password ONVIF 登录密码（为空时使用 {@link #DEFAULT_PASSWORD}）
     * @return 每个设备的部署结果列表（不为 null）
     * @throws IllegalArgumentException 若 subnet 为空
     */
    public List<DeployResult> scanAndDeploy(String subnet, String username, String password) {
        validateSubnet(subnet);
        String effectiveUser = orDefault(username, DEFAULT_USERNAME);
        String effectivePass = orDefault(password, DEFAULT_PASSWORD);

        log.info("Rapid deploy: scanning subnet={} with user={}", subnet, effectiveUser);
        List<SurveillanceDevice> discovered = onvifClient.discoverDevices(subnet);
        List<DeployResult> results = new ArrayList<>(discovered.size());

        for (SurveillanceDevice device : discovered) {
            DeployResult result = deploySingleDevice(device, effectiveUser, effectivePass);
            results.add(result);
        }

        int success = (int) results.stream().filter(r -> r.getStatus() == DeployResult.Status.SUCCESS).count();
        int skipped = (int) results.stream().filter(r -> r.getStatus() == DeployResult.Status.SKIPPED).count();
        int failed = (int) results.stream().filter(r -> r.getStatus() == DeployResult.Status.FAILED).count();
        log.info("Rapid deploy complete: subnet={} total={} success={} skipped={} failed={}",
                subnet, results.size(), success, skipped, failed);
        return results;
    }

    /**
     * 仅扫描子网发现设备，不注册到 Registry。
     * <p>
     * 返回的 {@link DeployResult} 状态全为 {@link DeployResult.Status#SKIPPED}，
     * 仅展示发现的设备信息，{@link DeployResult#getDeviceId()} 为发现时的原始设备 ID。
     *
     * @param subnet 子网 CIDR（不可为空）
     * @return 发现的设备列表（状态全为 SKIPPED，不为 null）
     * @throws IllegalArgumentException 若 subnet 为空
     */
    public List<DeployResult> scanOnly(String subnet) {
        validateSubnet(subnet);
        log.info("Scan only: scanning subnet={}", subnet);
        List<SurveillanceDevice> discovered = onvifClient.discoverDevices(subnet);
        List<DeployResult> results = new ArrayList<>(discovered.size());
        for (SurveillanceDevice device : discovered) {
            results.add(DeployResult.skipped(
                    device.id, device.ip, device.vendor.name(),
                    "discovered (scan only)"));
        }
        log.info("Scan only complete: subnet={} discovered={}", subnet, results.size());
        return results;
    }

    /**
     * 批量部署：扫描子网并注册设备，可按厂商过滤。
     * <p>
     * 当 {@code vendorFilter} 为空或 null 时，部署所有厂商设备；
     * 否则仅部署厂商名称匹配（大小写不敏感）的设备。
     *
     * @param subnet       子网 CIDR（不可为空）
     * @param username     ONVIF 登录用户名（为空时使用默认值）
     * @param password     ONVIF 登录密码（为空时使用默认值）
     * @param vendorFilter 厂商过滤（如 "HIKVISION"），为空或 null 时不过滤
     * @return 部署结果列表（不为 null）
     * @throws IllegalArgumentException 若 subnet 为空
     */
    public List<DeployResult> batchDeploy(String subnet, String username, String password,
                                          String vendorFilter) {
        List<DeployResult> all = scanAndDeploy(subnet, username, password);
        if (vendorFilter == null || vendorFilter.isBlank()) {
            return all;
        }
        String filter = vendorFilter.trim().toUpperCase(java.util.Locale.ROOT);
        List<DeployResult> filtered = new ArrayList<>();
        for (DeployResult r : all) {
            if (r.getVendor() != null && r.getVendor().toUpperCase(java.util.Locale.ROOT).equals(filter)) {
                filtered.add(r);
            }
        }
        log.info("Batch deploy with vendorFilter={}: total={} filtered={}",
                vendorFilter, all.size(), filtered.size());
        return filtered;
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    /**
     * 部署单个设备：检查是否已注册 → 获取能力/RTSP → 注册。
     */
    private DeployResult deploySingleDevice(SurveillanceDevice device,
                                            String username, String password) {
        String ip = device.ip;
        String vendorName = device.vendor.name();

        // 1. 检查是否已注册（同 IP 已存在则 SKIPPED）
        SurveillanceDevice existing = findDeviceByIp(ip);
        if (existing != null) {
            log.info("Device already registered: ip={} id={}", ip, existing.id);
            return DeployResult.skipped(existing.id, ip, vendorName, "already registered");
        }

        // 2. 生成新设备 ID
        String newDeviceId = generateDeviceId(ip);

        // 3. 获取设备能力与 RTSP 流
        Set<String> capabilities;
        String rtspUrl;
        try {
            capabilities = onvifClient.getDeviceCapabilities(ip, device.port, username, password);
        } catch (Exception e) {
            log.warn("GetCapabilities failed for {}: {}", ip, e.getMessage());
            return DeployResult.failed(newDeviceId, ip, vendorName,
                    "capabilities acquisition failed: " + e.getMessage());
        }
        try {
            rtspUrl = onvifClient.getRtspUrl(ip, device.port, username, password, DEFAULT_CHANNEL);
        } catch (Exception e) {
            log.warn("GetStreamUri failed for {}: {}", ip, e.getMessage());
            return DeployResult.failed(newDeviceId, ip, vendorName,
                    "rtsp url acquisition failed: " + e.getMessage());
        }

        // 4. 创建并注册设备
        SurveillanceDevice newDevice = new SurveillanceDevice(
                newDeviceId, device.name, device.vendor, ip, device.port, username, password);
        newDevice.setCapabilities(capabilities);
        newDevice.rtspUrl = rtspUrl;
        registry.register(newDevice);

        log.info("Device deployed: id={} ip={} vendor={} rtspUrl={}",
                newDeviceId, ip, vendorName, rtspUrl);
        return DeployResult.success(newDeviceId, ip, vendorName,
                "discovered and registered", rtspUrl);
    }

    /**
     * 在 Registry 中查找指定 IP 的已注册设备。
     * <p>
     * 遍历当前注册列表，返回首个 IP 匹配的设备。线程安全：基于 Registry 的快照遍历。
     *
     * @param ip 设备 IP
     * @return 已注册设备；若不存在返回 null
     */
    private SurveillanceDevice findDeviceByIp(String ip) {
        if (ip == null) return null;
        for (SurveillanceDevice d : registry.listDevices()) {
            if (Objects.equals(d.ip, ip)) {
                return d;
            }
        }
        return null;
    }

    /**
     * 生成设备 ID：{@code cam-<IP末段>-<timestamp>-<counter>}。
     * <p>
     * 使用 AtomicLong 计数器保证同一毫秒内多线程生成的 ID 唯一。
     *
     * @param ip 设备 IP
     * @return 唯一设备 ID
     */
    private String generateDeviceId(String ip) {
        String lastOctet = extractLastOctet(ip);
        long timestamp = System.currentTimeMillis();
        long counter = idCounter.incrementAndGet();
        return DEVICE_ID_PREFIX + "-" + lastOctet + "-" + timestamp + "-" + counter;
    }

    /** 提取 IP 最后一段，如 "192.168.1.100" -> "100"；非 IP 格式返回 "0"。 */
    private static String extractLastOctet(String ip) {
        if (ip == null || ip.isEmpty()) return "0";
        int lastDot = ip.lastIndexOf('.');
        return lastDot >= 0 && lastDot < ip.length() - 1
                ? ip.substring(lastDot + 1)
                : "0";
    }

    /** 若 value 为 null 或空白返回 defaultValue，否则返回 trim 后的 value。 */
    private static String orDefault(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    /** 校验子网非空。 */
    private static void validateSubnet(String subnet) {
        if (subnet == null || subnet.isBlank()) {
            throw new IllegalArgumentException("subnet must not be blank");
        }
    }
}
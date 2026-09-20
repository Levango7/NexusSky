package io.aerofleet.cloud.scenario;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 场景启动器服务（P0-2）。
 * <p>
 * 加载场景模板 → 创建编排计划 → 分配无人机 → 启动执行。
 * <p>
 * 根据模板配置：
 * <ul>
 *   <li>从 {@link DeviceRegistry} 选取在线无人机</li>
 *   <li>围绕中心点 (lat, lon) 生成圆形分布航点</li>
 *   <li>按协同策略分配角色（侦察 / 中继 / 执行）</li>
 *   <li>设置通信模式</li>
 * </ul>
 */
@Service
public class ScenarioLauncherService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioLauncherService.class);

    /** 启动记录状态。 */
    public enum LaunchStatus {
        RUNNING, ABORTED, COMPLETED, FAILED
    }

    /** 启动记录（内部）。 */
    public static class LaunchRecord {
        public final String launchId;
        public final long planId;
        public final String templateId;
        public final String templateName;
        public final double centerLat;
        public final double centerLon;
        public final List<Integer> assignedDrones;
        public final List<String> roleAssignments;
        public final LaunchResult.Status launchStatus;
        public final Instant startTime;
        public volatile LaunchStatus status;
        public volatile Instant endTime;
        public final double estimatedCoveragePct;
        public final String message;

        LaunchRecord(String launchId, long planId, String templateId, String templateName,
                     double centerLat, double centerLon, List<Integer> assignedDrones,
                     List<String> roleAssignments, LaunchResult.Status launchStatus,
                     double estimatedCoveragePct, String message) {
            this.launchId = launchId;
            this.planId = planId;
            this.templateId = templateId;
            this.templateName = templateName;
            this.centerLat = centerLat;
            this.centerLon = centerLon;
            this.assignedDrones = assignedDrones;
            this.roleAssignments = roleAssignments;
            this.launchStatus = launchStatus;
            this.startTime = Instant.now();
            this.status = launchStatus == LaunchResult.Status.FAILED ? LaunchStatus.FAILED : LaunchStatus.RUNNING;
            this.estimatedCoveragePct = estimatedCoveragePct;
            this.message = message;
        }
    }

    private final DeviceRegistry deviceRegistry;
    private final Map<String, LaunchRecord> launches = new ConcurrentHashMap<>();
    private final AtomicLong planIdSeq = new AtomicLong(1000);

    public ScenarioLauncherService(DeviceRegistry deviceRegistry) {
        this.deviceRegistry = deviceRegistry;
    }

    /**
     * 启动场景。
     * <p>
     * 流程：加载模板 → 选取在线无人机 → 分配角色 → 生成航点 → 创建编排计划。
     *
     * @param template 场景模板
     * @param lat      中心纬度
     * @param lon      中心经度
     * @return 启动结果
     */
    public LaunchResult launch(ScenarioTemplate template, double lat, double lon) {
        String launchId = "launch-" + UUID.randomUUID().toString().substring(0, 8);
        long planId = planIdSeq.incrementAndGet();

        // 1. 选取在线无人机
        List<Integer> availableDrones = selectAvailableDrones(template.getDroneCount());

        // 2. 分配角色
        List<String> roleAssignments = assignRoles(template, availableDrones.size());

        // 3. 生成航点（围绕中心点圆形分布）
        List<ScenarioTemplate.Wp> waypoints = generateWaypoints(lat, lon,
                template.getRadiusKm(), availableDrones.size());

        // 4. 确定启动状态
        LaunchResult.Status status;
        String message;
        double coveragePct;

        if (availableDrones.isEmpty()) {
            status = LaunchResult.Status.FAILED;
            message = "no available drones for template " + template.getName();
            coveragePct = 0.0;
            log.warn("Launch failed (no drones): launchId={} template={}", launchId, template.getId());
        } else if (availableDrones.size() < template.getDroneCount()) {
            status = LaunchResult.Status.PARTIAL;
            message = String.format("partial launch: requested %d drones, assigned %d",
                    template.getDroneCount(), availableDrones.size());
            coveragePct = estimateCoverage(availableDrones.size(), template.getDroneCount(),
                    template.getRadiusKm());
            log.warn("Partial launch: launchId={} template={} requested={} assigned={}",
                    launchId, template.getId(), template.getDroneCount(), availableDrones.size());
        } else {
            status = LaunchResult.Status.SUCCESS;
            message = "launch success: " + availableDrones.size() + " drones assigned";
            coveragePct = estimateCoverage(availableDrones.size(), template.getDroneCount(),
                    template.getRadiusKm());
            log.info("Launch success: launchId={} template={} planId={} drones={} roles={}",
                    launchId, template.getId(), planId, availableDrones.size(), roleAssignments);
        }

        LaunchResult result = new LaunchResult(launchId, status, planId,
                availableDrones, message, coveragePct);

        // 5. 记录启动信息
        LaunchRecord record = new LaunchRecord(launchId, planId, template.getId(),
                template.getName(), lat, lon, availableDrones, roleAssignments,
                status, coveragePct, message);
        launches.put(launchId, record);

        return result;
    }

    /**
     * 查询进行中的场景启动。
     *
     * @return 状态为 RUNNING 的启动记录列表
     */
    public List<LaunchRecord> getActiveLaunches() {
        List<LaunchRecord> result = new ArrayList<>();
        for (LaunchRecord r : launches.values()) {
            if (r.status == LaunchStatus.RUNNING) {
                result.add(r);
            }
        }
        return result;
    }

    /**
     * 查询历史启动记录（按启动时间倒序）。
     *
     * @return 全部启动记录列表
     */
    public List<LaunchRecord> getHistory() {
        List<LaunchRecord> result = new ArrayList<>(launches.values());
        result.sort((a, b) -> b.startTime.compareTo(a.startTime));
        return result;
    }

    /**
     * 中止场景执行。
     *
     * @param launchId 启动 ID
     * @return 中止成功返回 true；启动不存在或已结束返回 false
     */
    public boolean abort(String launchId) {
        LaunchRecord r = launches.get(launchId);
        if (r == null) {
            return false;
        }
        if (r.status != LaunchStatus.RUNNING) {
            return false;
        }
        r.status = LaunchStatus.ABORTED;
        r.endTime = Instant.now();
        log.info("Launch aborted: launchId={} planId={}", launchId, r.planId);
        return true;
    }

    /**
     * 查询场景执行状态。
     *
     * @param launchId 启动 ID
     * @return 启动记录；不存在返回 null
     */
    public LaunchRecord getStatus(String launchId) {
        return launches.get(launchId);
    }

    /**
     * 获取启动记录（getStatus 的别名，语义更清晰）。
     */
    public LaunchRecord getLaunch(String launchId) {
        return launches.get(launchId);
    }

    // =====================================================================
    // 内部方法
    // =====================================================================

    /**
     * 从 DeviceRegistry 选取在线无人机，最多选取 {@code requested} 架。
     * <p>
     * 测试可通过预先 registerIfAbsent 注册无人机来控制可用机队。
     */
    List<Integer> selectAvailableDrones(int requested) {
        List<Integer> online = new ArrayList<>();
        if (deviceRegistry == null) {
            return online;
        }
        for (DroneSnapshot s : deviceRegistry.all()) {
            if (s.online && online.size() < requested) {
                online.add(s.sysid);
            }
        }
        return online;
    }

    /**
     * 根据协同策略分配无人机角色。
     * <p>
     * <ul>
     *   <li>RECON_ONLY：全部侦察</li>
     *   <li>RECON_RELAY：约 70% 侦察 + 30% 中继</li>
     *   <li>RECON_RELAY_EXEC：约 50% 侦察 + 20% 中继 + 30% 执行</li>
     * </ul>
     */
    List<String> assignRoles(ScenarioTemplate template, int actualCount) {
        List<String> roles = new ArrayList<>();
        ScenarioTemplate.CollaborationStrategy strategy = template.getCollaborationStrategy();
        int n = actualCount > 0 ? actualCount : template.getDroneCount();

        switch (strategy) {
            case RECON_ONLY:
                for (int i = 0; i < n; i++) {
                    roles.add("RECON");
                }
                break;
            case RECON_RELAY:
                int relayCount = Math.max(1, n / 3);
                int reconCount = n - relayCount;
                for (int i = 0; i < reconCount; i++) {
                    roles.add("RECON");
                }
                for (int i = 0; i < relayCount; i++) {
                    roles.add("RELAY");
                }
                break;
            case RECON_RELAY_EXEC:
                int execCount = Math.max(1, n / 3);
                int relayCount2 = Math.max(1, n / 5);
                int reconCount2 = n - execCount - relayCount2;
                if (reconCount2 < 1) {
                    reconCount2 = 1;
                    execCount = n - reconCount2 - relayCount2;
                    if (execCount < 0) {
                        execCount = 0;
                        relayCount2 = n - reconCount2;
                    }
                }
                for (int i = 0; i < reconCount2; i++) {
                    roles.add("RECON");
                }
                for (int i = 0; i < relayCount2; i++) {
                    roles.add("RELAY");
                }
                for (int i = 0; i < execCount; i++) {
                    roles.add("EXEC");
                }
                break;
            default:
                for (int i = 0; i < n; i++) {
                    roles.add("RECON");
                }
        }
        return roles;
    }

    /**
     * 围绕中心点生成圆形分布航点。
     * <p>
     * 每架无人机分配一个圆周上的航点，半径 = radiusKm。
     */
    List<ScenarioTemplate.Wp> generateWaypoints(double centerLat, double centerLon,
                                                double radiusKm, int droneCount) {
        List<ScenarioTemplate.Wp> wps = new ArrayList<>();
        if (droneCount <= 0) {
            return wps;
        }
        // 将半径 km 转为纬度/经度偏移（近似：1° lat ≈ 111km）
        double latOffset = radiusKm / 111.0;
        double lonOffset = radiusKm / (111.0 * Math.cos(Math.toRadians(centerLat)));
        for (int i = 0; i < droneCount; i++) {
            double angle = 2 * Math.PI * i / droneCount;
            double wpLat = centerLat + latOffset * Math.sin(angle);
            double wpLon = centerLon + lonOffset * Math.cos(angle);
            wps.add(new ScenarioTemplate.Wp(wpLat, wpLon));
        }
        return wps;
    }

    /**
     * 预估覆盖率：实际无人机数 / 请求数 × 基准覆盖率（80%）。
     */
    double estimateCoverage(int actual, int requested, double radiusKm) {
        if (requested <= 0) {
            return 0.0;
        }
        double ratio = (double) actual / requested;
        double base = 80.0;
        return Math.round(ratio * base * 10) / 10.0;
    }

    /** 测试辅助：直接注入 DeviceRegistry（用于无 Spring 上下文的单元测试）。 */
    DeviceRegistry getDeviceRegistry() {
        return deviceRegistry;
    }
}
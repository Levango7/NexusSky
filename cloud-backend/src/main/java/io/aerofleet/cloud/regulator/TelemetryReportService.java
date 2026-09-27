package io.aerofleet.cloud.regulator;

import io.aerofleet.cloud.regulator.model.ComplianceState;
import io.aerofleet.cloud.regulator.model.ComplianceStatus;
import io.aerofleet.cloud.regulator.model.FlightStatus;
import io.aerofleet.cloud.regulator.model.TelemetryReport;
import io.aerofleet.cloud.regulator.model.TelemetryResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 遥测上报服务。
 * <p>
 * 作为 {@code TelemetryIngestService} 的可选监听器接入，不修改既有遥测处理逻辑。
 * 内部定时器按配置周期从快照中提取数据，通过 {@link RegulatorReportSink#reportTelemetry(TelemetryReport)}
 * 上报至监管平台。
 * <p>
 * 核心功能：
 * <ul>
 *   <li>{@link #onTelemetry} — 接收遥测更新，存入快照</li>
 *   <li>{@link #startReporting} — 启动定时上报任务（{@link ScheduledExecutorService}）</li>
 *   <li>{@link #stopReporting} — 停止定时上报任务并输出统计</li>
 * </ul>
 * <p>
 * 自动行为：
 * <ul>
 *   <li>首次成功遥测上报后自动将状态从 {@code ACTIVATED} 转为 {@code OPERATING}</li>
 *   <li>设备离线超过 3 个上报周期时自动 {@link #stopReporting}</li>
 *   <li>上报队列有界（{@code reportQueueCapacity}），满时丢弃最旧项并 WARN 日志</li>
 *   <li>上报失败时记录 WARN 日志，不补发，下一周期继续</li>
 * </ul>
 *
 * @see RegulatorReportSink
 * @see ComplianceStateManager
 * @see RegulatorConfig
 */
@Component
public class TelemetryReportService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryReportService.class);

    /** 离线检测阈值：超过 3 个上报周期无新遥测数据则判定为离线。 */
    private static final int OFFLINE_THRESHOLD_PERIODS = 3;

    private final RegulatorReportSink sink;
    private final ComplianceStateManager stateManager;
    private final RegulatorConfig config;

    /** sysid → 最新遥测快照。 */
    private final ConcurrentHashMap<Integer, TelemetrySnapshot> latestTelemetry = new ConcurrentHashMap<>();

    /** sysid → 最近一次遥测数据接收时间（用于离线检测）。 */
    private final ConcurrentHashMap<Integer, Long> lastTelemetryTime = new ConcurrentHashMap<>();

    /** sysid → 定时上报任务句柄。 */
    private final ConcurrentHashMap<Integer, ScheduledFuture<?>> reportingTasks = new ConcurrentHashMap<>();

    /** sysid → 上报统计。 */
    private final ConcurrentHashMap<Integer, ReportStats> reportStats = new ConcurrentHashMap<>();

    /** 定时调度器（每个 ACTIVATED/OPERATING 无人机一个定时任务）。 */
    private final ScheduledExecutorService scheduler;

    /** 异步上报线程池（有界队列，满时丢弃最旧项）。 */
    private final ExecutorService reportExecutor;

    /** 上报队列容量（用于日志输出）。 */
    private final int queueCapacity;

    /**
     * 遥测快照内部 record，存储最近一次遥测数据。
     *
     * @param lat      纬度坐标（WGS-84）
     * @param lon      经度坐标（WGS-84）
     * @param alt      高度（米）
     * @param speed    地面速度（米/秒）
     * @param heading  航向角（度，0-360）
     * @param airborne 是否空中飞行
     * @param timestamp 快照时间戳（Unix epoch 毫秒）
     */
    private record TelemetrySnapshot(double lat, double lon, double alt,
                                     double speed, double heading, boolean airborne,
                                     long timestamp) {}

    /**
     * 上报统计内部类，记录周期统计信息。
     */
    private static class ReportStats {
        volatile int successCount = 0;
        volatile int failureCount = 0;
        volatile long lastReportTime = 0;
    }

    /**
     * 构造遥测上报服务。
     *
     * @param sink         监管上报通道
     * @param stateManager 合规状态管理器
     * @param config       监管对接配置
     */
    public TelemetryReportService(RegulatorReportSink sink,
                                  ComplianceStateManager stateManager,
                                  RegulatorConfig config) {
        this.sink = sink;
        this.stateManager = stateManager;
        this.config = config;
        this.queueCapacity = config.getReportQueueCapacity();

        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "telemetry-report-scheduler");
            t.setDaemon(true);
            return t;
        });

        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(queueCapacity);
        RejectedExecutionHandler discardOldestWithLog = (r, executor) -> {
            log.warn("上报队列已满（容量={}），丢弃最旧的上报任务", queueCapacity);
            if (!executor.isShutdown()) {
                executor.getQueue().poll();
                executor.execute(r);
            }
        };

        this.reportExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                queue,
                r -> {
                    Thread t = new Thread(r, "telemetry-report-worker");
                    t.setDaemon(true);
                    return t;
                },
                discardOldestWithLog
        );
    }

    /**
     * 接收遥测数据更新（由 {@code TelemetryIngestService} 调用）。
     * <p>
     * 将最新遥测数据存入快照，并更新最近接收时间（用于离线检测）。
     *
     * @param sysid    无人机系统标识（MAVLink sysid）
     * @param lat      纬度坐标（WGS-84）
     * @param lon      经度坐标（WGS-84）
     * @param alt      高度（米）
     * @param speed    地面速度（米/秒）
     * @param heading  航向角（度，0-360）
     * @param airborne 是否空中飞行
     */
    public void onTelemetry(int sysid, double lat, double lon, double alt,
                            double speed, double heading, boolean airborne) {
        long now = System.currentTimeMillis();
        latestTelemetry.put(sysid, new TelemetrySnapshot(lat, lon, alt, speed, heading, airborne, now));
        lastTelemetryTime.put(sysid, now);
    }

    /**
     * 启动定时上报任务。
     * <p>
     * 使用 {@link ScheduledExecutorService} 按配置周期（{@code telemetryReportInterval} 秒）
     * 定时执行上报逻辑。若该 sysid 已有正在运行的定时任务，忽略重复启动。
     *
     * @param sysid 无人机系统标识
     */
    public void startReporting(int sysid) {
        int intervalSeconds = config.getTelemetryReportInterval();

        ScheduledFuture<?> existing = reportingTasks.get(sysid);
        if (existing != null && !existing.isDone()) {
            log.warn("sysid={} 的定时上报任务已在运行，忽略重复启动", sysid);
            return;
        }

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> doReportCycle(sysid),
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS
        );
        reportingTasks.put(sysid, future);
        reportStats.putIfAbsent(sysid, new ReportStats());

        log.info("启动定时上报: sysid={}, 周期={}s", sysid, intervalSeconds);
    }

    /**
     * 停止定时上报任务并输出周期统计。
     * <p>
     * 取消定时任务，输出累计统计（成功数/失败数/最近上报时间），清理统计记录。
     *
     * @param sysid 无人机系统标识
     */
    public void stopReporting(int sysid) {
        ScheduledFuture<?> future = reportingTasks.remove(sysid);
        if (future != null) {
            future.cancel(false);
            log.info("停止定时上报: sysid={}", sysid);
        }

        ReportStats stats = reportStats.remove(sysid);
        if (stats != null) {
            log.info("上报统计: sysid={}, 成功={}, 失败={}, 最近上报时间={}",
                    sysid, stats.successCount, stats.failureCount,
                    stats.lastReportTime > 0 ? stats.lastReportTime : "无");
        }
    }

    /**
     * 定时上报周期逻辑。
     * <p>
     * 执行流程：
     * <ol>
     *   <li>检查合规状态是否为 {@code ACTIVATED}/{@code OPERATING}</li>
     *   <li>设备离线检测：超过 3 个上报周期无新遥测数据则自动停止</li>
     *   <li>从快照提取遥测数据</li>
     *   <li>构造 {@link TelemetryReport} 并提交到异步线程池执行</li>
     * </ol>
     *
     * @param sysid 无人机系统标识
     */
    private void doReportCycle(int sysid) {
        try {
            // 1. 检查合规状态
            ComplianceStatus status = stateManager.get(sysid);
            if (status == null) {
                log.warn("sysid={} 无合规状态记录，跳过上报", sysid);
                return;
            }
            if (status.status() != ComplianceState.ACTIVATED && status.status() != ComplianceState.OPERATING) {
                log.warn("sysid={} 合规状态为 {}，非 ACTIVATED/OPERATING，跳过上报", sysid, status.status());
                return;
            }

            // 2. 设备离线检测
            Long lastTelTime = lastTelemetryTime.get(sysid);
            long now = System.currentTimeMillis();
            long intervalMs = config.getTelemetryReportInterval() * 1000L;
            if (lastTelTime != null && (now - lastTelTime) > (long) OFFLINE_THRESHOLD_PERIODS * intervalMs) {
                log.warn("sysid={} 设备离线超过 {} 个上报周期，自动停止上报", sysid, OFFLINE_THRESHOLD_PERIODS);
                stopReporting(sysid);
                return;
            }

            // 3. 从快照提取数据
            TelemetrySnapshot snapshot = latestTelemetry.get(sysid);
            if (snapshot == null) {
                log.warn("sysid={} 无遥测快照数据，跳过上报", sysid);
                return;
            }

            // 4. 构造 TelemetryReport 并提交到异步线程池
            TelemetryReport report = new TelemetryReport(
                    sysid,
                    status.productSerialNo(),
                    snapshot.timestamp(),
                    snapshot.lat(),
                    snapshot.lon(),
                    snapshot.alt(),
                    snapshot.speed(),
                    snapshot.heading(),
                    snapshot.airborne() ? FlightStatus.AIRBORNE : FlightStatus.GROUND
            );

            reportExecutor.submit(() -> doReport(sysid, report, status));

            // 5. 输出当前周期统计
            ReportStats stats = reportStats.get(sysid);
            if (stats != null) {
                log.info("周期统计: sysid={}, 成功={}, 失败={}, 最近上报时间={}",
                        sysid, stats.successCount, stats.failureCount,
                        stats.lastReportTime > 0 ? stats.lastReportTime : "无");
            }

        } catch (Exception e) {
            log.error("sysid={} 上报周期异常: {}", sysid, e.getMessage(), e);
        }
    }

    /**
     * 执行实际遥测上报（在异步线程池中执行）。
     * <p>
     * 成功时更新统计与最近上报时间；若当前状态为 {@code ACTIVATED}，自动转为 {@code OPERATING}。
     * 失败时记录 WARN 日志，不补发，下一周期继续。
     *
     * @param sysid  无人机系统标识
     * @param report 遥测数据
     * @param status 上报时的合规状态快照
     */
    private void doReport(int sysid, TelemetryReport report, ComplianceStatus status) {
        ReportStats stats = reportStats.get(sysid);
        try {
            TelemetryResult result = sink.reportTelemetry(report);
            if (result.success()) {
                if (stats != null) {
                    stats.successCount++;
                    stats.lastReportTime = System.currentTimeMillis();
                }
                log.debug("sysid={} 遥测上报成功", sysid);

                // 首次成功遥测上报后自动将状态从 ACTIVATED 转为 OPERATING
                if (status.status() == ComplianceState.ACTIVATED) {
                    try {
                        stateManager.transition(sysid, ComplianceState.OPERATING);
                        log.info("sysid={} 首次遥测上报成功，状态自动转为 OPERATING", sysid);
                    } catch (IllegalStateException e) {
                        log.warn("sysid={} 状态转换 ACTIVATED→OPERATING 失败: {}", sysid, e.getMessage());
                    }
                }
            } else {
                if (stats != null) {
                    stats.failureCount++;
                }
                log.warn("sysid={} 遥测上报失败: {}", sysid, result.errorMessage());
            }
        } catch (Exception e) {
            if (stats != null) {
                stats.failureCount++;
            }
            log.warn("sysid={} 遥测上报异常: {}", sysid, e.getMessage());
        }
    }

    /**
     * 关闭遥测上报服务，清理线程池资源。
     * <p>
     * 停止所有定时上报任务，关闭调度器和异步上报线程池。
     */
    @PreDestroy
    public void shutdown() {
        log.info("关闭遥测上报服务...");

        // 停止所有定时任务
        for (Integer sysid : reportingTasks.keySet()) {
            stopReporting(sysid);
        }

        scheduler.shutdown();
        reportExecutor.shutdown();

        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!reportExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                reportExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            reportExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("遥测上报服务已关闭");
    }
}
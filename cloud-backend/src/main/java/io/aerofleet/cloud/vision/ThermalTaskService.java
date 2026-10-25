package io.aerofleet.cloud.vision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 热成像任务调度（M3 感知成像增强，FR-10）。
 * <p>
 * 任务状态机 PENDING→RUNNING→COMPLETED + 航点下发 + 结果收集。
 * 纯内存状态，进程重启后恢复默认（DFX 4.2）。
 */
@Service
public class ThermalTaskService {

    private static final Logger log = LoggerFactory.getLogger(ThermalTaskService.class);

    private final ConcurrentHashMap<String, ThermalTask> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    /**
     * FR-10 创建热成像任务。
     *
     * @param sysid           目标飞机
     * @param region          采样区域（lat/lon/radiusM）
     * @param hotspotThreshold 热点阈值（℃）
     * @return 任务实例（state=PENDING）
     */
    public ThermalTask create(int sysid, Map<String, Object> region, double hotspotThreshold) {
        String taskId = "th-" + nextId.getAndIncrement();
        ThermalTask task = new ThermalTask(taskId, sysid, region, hotspotThreshold);
        tasks.put(taskId, task);
        log.info("thermal task created: taskId={} sysid={} threshold={}°C",
                taskId, sysid, hotspotThreshold);
        return task;
    }

    /** 查询任务状态 + 温度场结果。 */
    public ThermalTask get(String taskId) {
        return tasks.get(taskId);
    }

    /** 所有任务。 */
    public Map<String, ThermalTask> all() {
        return new LinkedHashMap<>(tasks);
    }

    /**
     * THERMAL_DATA 帧到达（ingest 监听器调用）：把该 sysid **最老的活跃任务**
     * （PENDING 或 RUNNING）用这帧的分析结果收账。
     *
     * <p>语义说明（诚实记录）：每帧 THERMAL_DATA 都是一份完整的温度场分析，
     * "收到帧 = 捕获已完成"——所以任务从 PENDING 直接收账为 COMPLETED，
     * RUNNING 态在这条接线里不可观测（真实无人机上它是"飞往采样区域"的中间态，
     * 未来若帧携带 capture-in-progress 语义再引入）。没有活跃任务时只刷机队
     * 最新读数（由监听器写快照），不重开任务。
     *
     * @return 收账的任务；该 sysid 无活跃任务时返回 null
     */
    public ThermalTask completeOldestActive(int sysid, double tempMean, double tempMin,
                                            double tempMax, double tempStdDev, int hotspotCount) {
        ThermalTask oldest = tasks.values().stream()
                .filter(t -> t.sysid == sysid && !"COMPLETED".equals(t.state))
                .min(java.util.Comparator.comparingLong(t -> t.createdAt))
                .orElse(null);
        if (oldest == null) {
            return null;
        }
        oldest.tempMean = tempMean;
        oldest.tempMin = tempMin;
        oldest.tempMax = tempMax;
        oldest.tempStdDev = tempStdDev;
        oldest.hotspotCount = hotspotCount;
        oldest.state = "COMPLETED";
        oldest.completedAt = System.currentTimeMillis();
        log.info("thermal task completed: taskId={} sysid={} tempMax={}°C hotspots={}",
                oldest.taskId, sysid, tempMax, hotspotCount);
        return oldest;
    }

    /** 热成像任务记录。 */
    public static class ThermalTask {
        public final String taskId;
        public final int sysid;
        public final Map<String, Object> region;
        public final double hotspotThreshold;
        public volatile String state = "PENDING";  // PENDING/RUNNING/COMPLETED
        public volatile double tempMean;
        public volatile double tempMin;
        public volatile double tempMax;
        public volatile double tempStdDev;
        public volatile int hotspotCount;
        public volatile long createdAt = System.currentTimeMillis();
        public volatile long completedAt;

        public ThermalTask(String taskId, int sysid, Map<String, Object> region,
                           double hotspotThreshold) {
            this.taskId = taskId;
            this.sysid = sysid;
            this.region = region;
            this.hotspotThreshold = hotspotThreshold;
        }

        public Map<String, Object> toView() {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("taskId", taskId);
            v.put("sysid", sysid);
            v.put("state", state);
            v.put("hotspotThreshold", hotspotThreshold);
            v.put("createdAt", createdAt);
            if ("COMPLETED".equals(state)) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("tempMean", tempMean);
                r.put("tempMin", tempMin);
                r.put("tempMax", tempMax);
                r.put("tempStdDev", tempStdDev);
                r.put("hotspotCount", hotspotCount);
                v.put("result", r);
                v.put("completedAt", completedAt);
            }
            return v;
        }
    }
}
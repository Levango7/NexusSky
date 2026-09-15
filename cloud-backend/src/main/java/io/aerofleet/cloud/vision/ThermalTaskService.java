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
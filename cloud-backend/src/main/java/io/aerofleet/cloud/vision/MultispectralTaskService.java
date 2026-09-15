package io.aerofleet.cloud.vision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多光谱任务调度（M3 感知成像增强，FR-07）。
 * <p>
 * 任务状态机 PENDING→RUNNING→COMPLETED + 航点下发 + 结果收集。
 * 纯内存状态，进程重启后恢复默认（DFX 4.2）。
 */
@Service
public class MultispectralTaskService {

    private static final Logger log = LoggerFactory.getLogger(MultispectralTaskService.class);

    private final ConcurrentHashMap<String, MultispectralTask> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    /**
     * FR-07 创建多光谱任务。
     *
     * @param sysid  目标飞机
     * @param bands  波段选择（如 ["NIR","RED"]）
     * @param region 采样区域（lat/lon/radiusM）
     * @return 任务实例（state=PENDING）
     */
    public MultispectralTask create(int sysid, List<String> bands, Map<String, Object> region) {
        String taskId = "ms-" + nextId.getAndIncrement();
        MultispectralTask task = new MultispectralTask(taskId, sysid, bands, region);
        tasks.put(taskId, task);
        log.info("multispectral task created: taskId={} sysid={} bands={}", taskId, sysid, bands);
        return task;
    }

    /** 查询任务状态 + NDVI 结果。 */
    public MultispectralTask get(String taskId) {
        return tasks.get(taskId);
    }

    /** 所有任务。 */
    public Map<String, MultispectralTask> all() {
        return new LinkedHashMap<>(tasks);
    }

    /** 多光谱任务记录。 */
    public static class MultispectralTask {
        public final String taskId;
        public final int sysid;
        public final List<String> bands;
        public final Map<String, Object> region;
        public volatile String state = "PENDING";  // PENDING/RUNNING/COMPLETED
        public volatile double ndviMean;
        public volatile double ndviMin;
        public volatile double ndviMax;
        public volatile double vegetationCoverage;
        public volatile long createdAt = System.currentTimeMillis();
        public volatile long completedAt;

        public MultispectralTask(String taskId, int sysid, List<String> bands,
                                 Map<String, Object> region) {
            this.taskId = taskId;
            this.sysid = sysid;
            this.bands = bands;
            this.region = region;
        }

        public Map<String, Object> toView() {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("taskId", taskId);
            v.put("sysid", sysid);
            v.put("state", state);
            v.put("bands", bands);
            v.put("createdAt", createdAt);
            if ("COMPLETED".equals(state)) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("ndviMean", ndviMean);
                r.put("ndviMin", ndviMin);
                r.put("ndviMax", ndviMax);
                r.put("vegetationCoverage", vegetationCoverage);
                v.put("result", r);
                v.put("completedAt", completedAt);
            }
            return v;
        }
    }
}
package io.aerofleet.cloud.flightlog;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 飞行日志的「任务归因 + 去重序号」上下文（V42 列的写入侧来源）。
 *
 * <p>调度服务把「sysid → 当前任务」登记进来，{@link FlightLogService} 在组装每条事件时
 * 取出来写进 {@code flight_log.task_id}；{@code seq} 由本类按 sysid 生成。
 * 用独立 Context 而不是让日志服务反向依赖调度服务，是为了保持
 * {@code 采集 → 日志} 这条热路径的包依赖方向，也方便单测（无 Spring 上下文）。
 *
 * <p><b>seq 为什么用时间基准</b>：若从 0/1 重新计数，进程重启后新行的 seq 会与库里残留的
 * 历史行重复，撞上 V42 的唯一索引 {@code (tenant_id, sysid, seq)}。这里用
 * {@code max(上次+1, 当前毫秒)}，既保证同机单调，又保证重启后大概率大于旧值；
 * 时钟大幅回拨需靠 NTP/PTP 纠正（与分区键口径一致）。
 */
@Component
public class FlightLogTaskContext {

    /** sysid → 当前任务 ID；同一飞机多任务并发时以最后一次分配为准。 */
    private final Map<Integer, String> taskBySysid = new ConcurrentHashMap<>();

    /** sysid → 单调序号（时间基准）。 */
    private final Map<Integer, AtomicLong> seqBySysid = new ConcurrentHashMap<>();

    /** 登记任务归因；null/空白 taskId 视为清除。 */
    public void bind(int sysid, String taskId) {
        if (taskId == null || taskId.isBlank()) {
            taskBySysid.remove(sysid);
        } else {
            taskBySysid.put(sysid, taskId);
        }
    }

    /** 任务结束/取消后清除归因。 */
    public void unbind(int sysid) {
        taskBySysid.remove(sysid);
    }

    /** 任务改派到别的飞机时，清掉旧飞机上残留的同名 taskId 归因。 */
    public void unbindTask(String taskId) {
        if (taskId != null) {
            taskBySysid.entrySet().removeIf(e -> taskId.equals(e.getValue()));
        }
    }

    /** 当前归因；未登记返回 null（落库后 task_id 为 NULL，与历史行一致）。 */
    public String taskIdOf(int sysid) {
        return taskBySysid.get(sysid);
    }

    /** 同一 sysid 单调递增的序号，重启后仍大于旧值（除非时钟回拨）。 */
    public long nextSeq(int sysid) {
        return seqBySysid.computeIfAbsent(sysid, k -> new AtomicLong(System.currentTimeMillis()))
                .updateAndGet(prev -> Math.max(prev + 1, System.currentTimeMillis()));
    }
}

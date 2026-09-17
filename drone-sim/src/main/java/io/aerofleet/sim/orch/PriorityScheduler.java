package io.aerofleet.sim.orch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 优先级调度器（M9 应急任务编排，T4 优先级调度）。
 * <p>
 * 维护 4 个优先级队列（{@link PriorityLevel}），按 code 从小到大（优先级从高到低）
 * 扫描调度。支持任务提交、动态优先级调整、抢占、完成、取消、快照查询。
 * <p>
 * 线程安全：使用 {@link ConcurrentHashMap} + {@link ConcurrentLinkedQueue} + {@link AtomicLong}，
 * 任务状态字段 volatile。submitTask 可多线程并发调用。
 * <p>
 * 调度顺序：SEARCH_RESCUE &gt; COMMAND &gt; MAPPING &gt; ROUTINE。
 * 抢占：当高优先级任务需要无人机但无空闲时，可抢占低优先级运行中任务。
 */
public class PriorityScheduler {

    /** 4 个优先级队列（按优先级分桶）。 */
    private final ConcurrentHashMap<PriorityLevel, ConcurrentLinkedQueue<PriorityTask>> queues;
    /** 运行中任务（taskId → task）。 */
    private final ConcurrentHashMap<Long, PriorityTask> runningTasks;
    /** 无人机分配（droneId → taskId）。 */
    private final ConcurrentHashMap<Integer, Long> droneAssignments;
    /** 任务 id 生成器。 */
    private final AtomicLong taskIdGenerator;
    /** 所有任务索引（taskId → task），含队列中与运行中，便于 adjustPriority/getTask 查找。 */
    private final ConcurrentHashMap<Long, PriorityTask> taskIndex;

    /** 构造调度器，初始化 4 个优先级队列与 id 生成器。 */
    public PriorityScheduler() {
        this.queues = new ConcurrentHashMap<>();
        for (PriorityLevel pl : PriorityLevel.values()) {
            this.queues.put(pl, new ConcurrentLinkedQueue<>());
        }
        this.runningTasks = new ConcurrentHashMap<>();
        this.droneAssignments = new ConcurrentHashMap<>();
        this.taskIdGenerator = new AtomicLong(0);
        this.taskIndex = new ConcurrentHashMap<>();
    }

    /**
     * 提交任务：创建 {@link PriorityTask} 放入对应优先级队列，返回 taskId。
     *
     * @param planId      所属计划 id
     * @param priority    优先级
     * @param description 任务描述
     * @param context     任务上下文
     * @return 新任务 id
     */
    public long submitTask(long planId, PriorityLevel priority, String description, String context) {
        long taskId = taskIdGenerator.incrementAndGet();
        PriorityTask task = new PriorityTask(taskId, planId, priority, description,
                System.currentTimeMillis(), context);
        queues.get(priority).offer(task);
        taskIndex.put(taskId, task);
        return taskId;
    }

    /**
     * 动态调整优先级：从原队列移除，更新优先级，放入新队列。
     *
     * @param taskId     任务 id
     * @param newPriority 新优先级
     * @param reason     调整原因（用于日志，本方法不强制非空）
     */
    public void adjustPriority(long taskId, PriorityLevel newPriority, String reason) {
        PriorityTask task = taskIndex.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        PriorityLevel oldPriority = task.getPriority();
        if (oldPriority == newPriority) {
            return;
        }
        // 仅对队列中（PENDING/PREEMPTED）任务调整；运行中/已完成/已取消不动队列
        ConcurrentLinkedQueue<PriorityTask> oldQueue = queues.get(oldPriority);
        if (oldQueue.remove(task)) {
            task.adjustPriority(newPriority);
            queues.get(newPriority).offer(task);
        } else {
            // 不在队列中（可能运行中），仅更新优先级字段
            task.adjustPriority(newPriority);
        }
    }

    /**
     * 调度下一个任务：从最高优先级队列开始扫描，非空则取队首，分配无人机，转入运行中。
     *
     * @param availableDroneId 空闲无人机 id
     * @return 被调度的任务，无任务则 null
     */
    public PriorityTask scheduleNext(int availableDroneId) {
        // 按 code 从小到大（优先级从高到低）扫描
        for (PriorityLevel pl : PriorityLevel.values()) {
            ConcurrentLinkedQueue<PriorityTask> q = queues.get(pl);
            PriorityTask task = q.poll();
            if (task != null) {
                task.start(availableDroneId);
                runningTasks.put(task.getTaskId(), task);
                droneAssignments.put(availableDroneId, task.getTaskId());
                return task;
            }
        }
        return null;
    }

    /**
     * 抢占：找到 droneId 上运行的优先级低于 minPriority 的任务，抢占它。
     * <p>
     * "低于 minPriority" 指任务优先级 code &gt; minPriority.code（数值越大优先级越低）。
     *
     * @param droneId     目标无人机 id
     * @param minPriority 最低优先级阈值（仅抢占优先级比此更低的任务）
     * @return 被抢占的任务，无符合条件的任务则 null
     */
    public PriorityTask preempt(int droneId, PriorityLevel minPriority) {
        Long taskId = droneAssignments.get(droneId);
        if (taskId == null) {
            return null;
        }
        PriorityTask task = runningTasks.get(taskId);
        if (task == null) {
            return null;
        }
        // 仅抢占优先级更低（code 更大）的任务
        if (task.getPriority().code > minPriority.code) {
            task.preempt(0L);
            runningTasks.remove(taskId);
            droneAssignments.remove(droneId);
            // 抢占后任务回到原优先级队列，等待重新调度
            queues.get(task.getPriority()).offer(task);
            return task;
        }
        return null;
    }

    /**
     * 完成任务：从运行中移除，释放无人机，标记 COMPLETED。
     *
     * @param taskId 任务 id
     */
    public void completeTask(long taskId) {
        PriorityTask task = runningTasks.remove(taskId);
        if (task != null) {
            task.complete();
            droneAssignments.remove(task.getAssignedDroneId());
        }
    }

    /**
     * 取消任务：从队列或运行中移除，标记 CANCELLED。
     *
     * @param taskId 任务 id
     */
    public void cancelTask(long taskId) {
        PriorityTask task = taskIndex.get(taskId);
        if (task == null) {
            return;
        }
        // 从运行中移除
        PriorityTask running = runningTasks.remove(taskId);
        if (running != null) {
            droneAssignments.remove(running.getAssignedDroneId());
        }
        // 从队列移除
        queues.get(task.getPriority()).remove(task);
        task.cancel();
    }

    /**
     * 清理 taskIndex 中已完成（COMPLETED）和已取消（CANCELLED）的任务索引，
     * 避免 taskIndex 只增不减导致内存泄漏。
     * <p>
     * 可由外部 ScheduledExecutorService 定期调用。清理后 {@link #getTask} 对这些任务返回 null。
     *
     * @return 被清理的任务数量
     */
    public int purgeCompletedTasks() {
        int removed = 0;
        for (Map.Entry<Long, PriorityTask> entry : taskIndex.entrySet()) {
            PriorityTask task = entry.getValue();
            if (task.getStatus() == PriorityTask.Status.COMPLETED
                    || task.getStatus() == PriorityTask.Status.CANCELLED) {
                taskIndex.remove(entry.getKey());
                removed++;
            }
        }
        return removed;
    }

    /**
     * 返回所有队列快照（按优先级分桶，每桶为列表副本）。
     *
     * @return 优先级 → 任务列表
     */
    public Map<PriorityLevel, List<PriorityTask>> getQueues() {
        Map<PriorityLevel, List<PriorityTask>> snapshot = new EnumMap<>(PriorityLevel.class);
        for (PriorityLevel pl : PriorityLevel.values()) {
            snapshot.put(pl, new ArrayList<>(queues.get(pl)));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * 返回运行中任务列表（快照副本）。
     *
     * @return 运行中任务列表
     */
    public List<PriorityTask> getRunningTasks() {
        return new ArrayList<>(runningTasks.values());
    }

    /**
     * 从所有队列和运行中查找任务。
     *
     * @param taskId 任务 id
     * @return 任务，找不到则 null
     */
    public PriorityTask getTask(long taskId) {
        // 先查运行中
        PriorityTask task = runningTasks.get(taskId);
        if (task != null) {
            return task;
        }
        // 再查索引（含队列中）
        return taskIndex.get(taskId);
    }
}
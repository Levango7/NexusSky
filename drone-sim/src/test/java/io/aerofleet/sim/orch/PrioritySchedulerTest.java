package io.aerofleet.sim.orch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PriorityScheduler 优先级调度器单测（M9 应急任务编排，T4 优先级调度）。
 * <p>
 * 覆盖：提交入队、最高优先级优先调度、四级调度顺序、抢占、动态调整优先级、
 * 完成/取消、队列快照、并发安全提交。
 */
@DisplayName("PriorityScheduler 优先级调度器 (T4)")
class PrioritySchedulerTest {

    @Test
    @DisplayName("submitTask 创建任务并放入正确优先级队列")
    void submitTaskPutsIntoCorrectQueue() {
        PriorityScheduler scheduler = new PriorityScheduler();

        long id1 = scheduler.submitTask(100L, PriorityLevel.SEARCH_RESCUE, "搜救A", "ctx");
        long id2 = scheduler.submitTask(100L, PriorityLevel.ROUTINE, "巡检B", "ctx");

        Map<PriorityLevel, List<PriorityTask>> queues = scheduler.getQueues();
        assertThat(queues.get(PriorityLevel.SEARCH_RESCUE)).hasSize(1);
        assertThat(queues.get(PriorityLevel.ROUTINE)).hasSize(1);
        assertThat(queues.get(PriorityLevel.COMMAND)).isEmpty();
        assertThat(queues.get(PriorityLevel.MAPPING)).isEmpty();

        assertThat(queues.get(PriorityLevel.SEARCH_RESCUE).get(0).getTaskId()).isEqualTo(id1);
        assertThat(queues.get(PriorityLevel.ROUTINE).get(0).getTaskId()).isEqualTo(id2);
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    @DisplayName("scheduleNext 从最高优先级队列取任务")
    void scheduleNextTakesFromHighestPriority() {
        PriorityScheduler scheduler = new PriorityScheduler();
        scheduler.submitTask(100L, PriorityLevel.ROUTINE, "巡检", "ctx");
        scheduler.submitTask(100L, PriorityLevel.SEARCH_RESCUE, "搜救", "ctx");

        PriorityTask task = scheduler.scheduleNext(1);

        assertThat(task).isNotNull();
        assertThat(task.getPriority()).isEqualTo(PriorityLevel.SEARCH_RESCUE);
        assertThat(task.getStatus()).isEqualTo(PriorityTask.Status.RUNNING);
        assertThat(task.getAssignedDroneId()).isEqualTo(1);
        // 搜救队列出队后为空，巡检仍在队列
        assertThat(scheduler.getQueues().get(PriorityLevel.SEARCH_RESCUE)).isEmpty();
        assertThat(scheduler.getQueues().get(PriorityLevel.ROUTINE)).hasSize(1);
    }

    @Test
    @DisplayName("四级调度顺序：SEARCH_RESCUE > COMMAND > MAPPING > ROUTINE")
    void fourLevelSchedulingOrder() {
        PriorityScheduler scheduler = new PriorityScheduler();
        // 逆序提交，验证按优先级出队
        long idRoutine = scheduler.submitTask(1L, PriorityLevel.ROUTINE, "巡检", "ctx");
        long idMapping = scheduler.submitTask(1L, PriorityLevel.MAPPING, "测绘", "ctx");
        long idCommand = scheduler.submitTask(1L, PriorityLevel.COMMAND, "指挥", "ctx");
        long idSearch = scheduler.submitTask(1L, PriorityLevel.SEARCH_RESCUE, "搜救", "ctx");

        PriorityTask t1 = scheduler.scheduleNext(1);
        PriorityTask t2 = scheduler.scheduleNext(2);
        PriorityTask t3 = scheduler.scheduleNext(3);
        PriorityTask t4 = scheduler.scheduleNext(4);

        assertThat(t1.getTaskId()).isEqualTo(idSearch);
        assertThat(t2.getTaskId()).isEqualTo(idCommand);
        assertThat(t3.getTaskId()).isEqualTo(idMapping);
        assertThat(t4.getTaskId()).isEqualTo(idRoutine);

        PriorityTask t5 = scheduler.scheduleNext(5);
        assertThat(t5).isNull();
    }

    @Test
    @DisplayName("preempt 抢占低优先级任务")
    void preemptLowerPriorityTask() {
        PriorityScheduler scheduler = new PriorityScheduler();
        long idRoutine = scheduler.submitTask(1L, PriorityLevel.ROUTINE, "巡检", "ctx");

        // 调度到无人机 1
        PriorityTask running = scheduler.scheduleNext(1);
        assertThat(running.getTaskId()).isEqualTo(idRoutine);
        assertThat(running.getStatus()).isEqualTo(PriorityTask.Status.RUNNING);

        // 抢占：要求最低 SEARCH_RESCUE，巡检(code=4) > 搜救(code=1)，可抢占
        PriorityTask preempted = scheduler.preempt(1, PriorityLevel.SEARCH_RESCUE);
        assertThat(preempted).isNotNull();
        assertThat(preempted.getTaskId()).isEqualTo(idRoutine);
        assertThat(preempted.getStatus()).isEqualTo(PriorityTask.Status.PREEMPTED);

        // 运行中已无该任务
        assertThat(scheduler.getRunningTasks()).isEmpty();
        // 抢占后任务回到原队列等待重新调度
        assertThat(scheduler.getQueues().get(PriorityLevel.ROUTINE)).hasSize(1);
    }

    @Test
    @DisplayName("preempt 不抢占同优先级或更高优先级任务")
    void preemptDoesNotPreemptSameOrHigherPriority() {
        PriorityScheduler scheduler = new PriorityScheduler();
        long idCommand = scheduler.submitTask(1L, PriorityLevel.COMMAND, "指挥", "ctx");
        scheduler.scheduleNext(1);

        // 要求最低 COMMAND，指挥(code=2) 不 > 指挥(code=2)，同优先级不抢占
        PriorityTask notPreempted = scheduler.preempt(1, PriorityLevel.COMMAND);
        assertThat(notPreempted).isNull();
        assertThat(scheduler.getRunningTasks()).hasSize(1);

        // 要求最低 MAPPING(code=3)，指挥(code=2) 不 > 测绘(code=3)，指挥优先级更高不抢占
        PriorityTask notPreempted2 = scheduler.preempt(1, PriorityLevel.MAPPING);
        assertThat(notPreempted2).isNull();
    }

    @Test
    @DisplayName("adjustPriority 移动任务到新队列")
    void adjustPriorityMovesTaskToNewQueue() {
        PriorityScheduler scheduler = new PriorityScheduler();
        long id = scheduler.submitTask(1L, PriorityLevel.ROUTINE, "巡检", "ctx");

        assertThat(scheduler.getQueues().get(PriorityLevel.ROUTINE)).hasSize(1);
        assertThat(scheduler.getQueues().get(PriorityLevel.SEARCH_RESCUE)).isEmpty();

        scheduler.adjustPriority(id, PriorityLevel.SEARCH_RESCUE, "升级为搜救");

        assertThat(scheduler.getQueues().get(PriorityLevel.ROUTINE)).isEmpty();
        assertThat(scheduler.getQueues().get(PriorityLevel.SEARCH_RESCUE)).hasSize(1);
        PriorityTask task = scheduler.getTask(id);
        assertThat(task.getPriority()).isEqualTo(PriorityLevel.SEARCH_RESCUE);
    }

    @Test
    @DisplayName("completeTask 正确移除运行中任务并释放无人机")
    void completeTaskRemovesRunningAndReleasesDrone() {
        PriorityScheduler scheduler = new PriorityScheduler();
        long id = scheduler.submitTask(1L, PriorityLevel.COMMAND, "指挥", "ctx");
        scheduler.scheduleNext(7);

        assertThat(scheduler.getRunningTasks()).hasSize(1);

        scheduler.completeTask(id);

        assertThat(scheduler.getRunningTasks()).isEmpty();
        PriorityTask task = scheduler.getTask(id);
        assertThat(task.getStatus()).isEqualTo(PriorityTask.Status.COMPLETED);
    }

    @Test
    @DisplayName("cancelTask 从队列移除待调度任务")
    void cancelTaskRemovesPendingFromQueue() {
        PriorityScheduler scheduler = new PriorityScheduler();
        long id = scheduler.submitTask(1L, PriorityLevel.MAPPING, "测绘", "ctx");

        assertThat(scheduler.getQueues().get(PriorityLevel.MAPPING)).hasSize(1);

        scheduler.cancelTask(id);

        assertThat(scheduler.getQueues().get(PriorityLevel.MAPPING)).isEmpty();
        PriorityTask task = scheduler.getTask(id);
        assertThat(task.getStatus()).isEqualTo(PriorityTask.Status.CANCELLED);
    }

    @Test
    @DisplayName("cancelTask 从运行中移除并释放无人机")
    void cancelTaskRemovesRunning() {
        PriorityScheduler scheduler = new PriorityScheduler();
        long id = scheduler.submitTask(1L, PriorityLevel.MAPPING, "测绘", "ctx");
        scheduler.scheduleNext(3);

        scheduler.cancelTask(id);

        assertThat(scheduler.getRunningTasks()).isEmpty();
        PriorityTask task = scheduler.getTask(id);
        assertThat(task.getStatus()).isEqualTo(PriorityTask.Status.CANCELLED);
    }

    @Test
    @DisplayName("getQueues 返回正确快照")
    void getQueuesReturnsSnapshot() {
        PriorityScheduler scheduler = new PriorityScheduler();
        scheduler.submitTask(1L, PriorityLevel.SEARCH_RESCUE, "搜救", "ctx");
        scheduler.submitTask(1L, PriorityLevel.SEARCH_RESCUE, "搜救2", "ctx");
        scheduler.submitTask(1L, PriorityLevel.COMMAND, "指挥", "ctx");

        Map<PriorityLevel, List<PriorityTask>> queues = scheduler.getQueues();

        assertThat(queues.get(PriorityLevel.SEARCH_RESCUE)).hasSize(2);
        assertThat(queues.get(PriorityLevel.COMMAND)).hasSize(1);
        assertThat(queues.get(PriorityLevel.MAPPING)).isEmpty();
        assertThat(queues.get(PriorityLevel.ROUTINE)).isEmpty();
        // 快照不可变
        assertThat(queues).isUnmodifiable();
    }

    @Test
    @DisplayName("getRunningTasks 返回运行中任务列表")
    void getRunningTasksReturnsRunningList() {
        PriorityScheduler scheduler = new PriorityScheduler();
        scheduler.submitTask(1L, PriorityLevel.SEARCH_RESCUE, "搜救", "ctx");
        scheduler.submitTask(1L, PriorityLevel.COMMAND, "指挥", "ctx");

        scheduler.scheduleNext(1);
        scheduler.scheduleNext(2);

        List<PriorityTask> running = scheduler.getRunningTasks();
        assertThat(running).hasSize(2);
        assertThat(running).allSatisfy(t ->
                assertThat(t.getStatus()).isEqualTo(PriorityTask.Status.RUNNING));
    }

    @Test
    @DisplayName("getTask 从队列和运行中查找任务")
    void getTaskFindsInQueuesAndRunning() {
        PriorityScheduler scheduler = new PriorityScheduler();
        long idPending = scheduler.submitTask(1L, PriorityLevel.ROUTINE, "巡检", "ctx");
        long idRunning = scheduler.submitTask(1L, PriorityLevel.SEARCH_RESCUE, "搜救", "ctx");
        scheduler.scheduleNext(1);

        PriorityTask pending = scheduler.getTask(idPending);
        PriorityTask running = scheduler.getTask(idRunning);
        assertThat(pending).isNotNull();
        assertThat(pending.getStatus()).isEqualTo(PriorityTask.Status.PENDING);
        assertThat(running).isNotNull();
        assertThat(running.getStatus()).isEqualTo(PriorityTask.Status.RUNNING);

        assertThat(scheduler.getTask(99999L)).isNull();
    }

    @Test
    @DisplayName("并发安全：多线程 submitTask 不丢任务")
    void concurrentSubmitTaskIsSafe() throws InterruptedException {
        PriorityScheduler scheduler = new PriorityScheduler();
        int threadCount = 8;
        int tasksPerThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger generated = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    for (int j = 0; j < tasksPerThread; j++) {
                        scheduler.submitTask(1L, PriorityLevel.ROUTINE, "task", "ctx");
                        generated.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(generated.get()).isEqualTo(threadCount * tasksPerThread);
        // 所有任务都应进入 ROUTINE 队列
        int totalInQueues = scheduler.getQueues().values().stream()
                .mapToInt(List::size)
                .sum();
        assertThat(totalInQueues).isEqualTo(threadCount * tasksPerThread);
        assertThat(scheduler.getQueues().get(PriorityLevel.ROUTINE))
                .hasSize(threadCount * tasksPerThread);
    }

    @Test
    @DisplayName("PriorityLevel.fromCode 正确反查")
    void priorityLevelFromCode() {
        assertThat(PriorityLevel.fromCode(1)).isEqualTo(PriorityLevel.SEARCH_RESCUE);
        assertThat(PriorityLevel.fromCode(2)).isEqualTo(PriorityLevel.COMMAND);
        assertThat(PriorityLevel.fromCode(3)).isEqualTo(PriorityLevel.MAPPING);
        assertThat(PriorityLevel.fromCode(4)).isEqualTo(PriorityLevel.ROUTINE);
    }

    @Test
    @DisplayName("PriorityLevel.fromCode 越界抛 IllegalArgumentException")
    void priorityLevelFromCodeThrowsOnUnknown() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> PriorityLevel.fromCode(99));
    }
}
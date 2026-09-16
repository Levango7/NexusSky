package io.aerofleet.sim.scheduling;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskExecutorTest {
    @Test
    void testTaskLifecycle() {
        TaskExecutor exec = new TaskExecutor(1);
        assertEquals("IDLE", exec.getTaskStatus());
        exec.assignTask("task-001");
        assertEquals("ASSIGNED", exec.getTaskStatus());
        assertEquals("task-001", exec.getTaskId());
        exec.startTask();
        assertEquals("IN_PROGRESS", exec.getTaskStatus());
        for (int i = 0; i < 100; i++) exec.tick();
        assertEquals("COMPLETED", exec.getTaskStatus());
        assertEquals(100, exec.getProgress());
    }

    @Test
    void testAbort() {
        TaskExecutor exec = new TaskExecutor(2);
        exec.assignTask("task-002");
        exec.startTask();
        exec.abortTask();
        assertEquals("ABORTED", exec.getTaskStatus());
    }
}

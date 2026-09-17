package io.aerofleet.sim.edge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EdgeNode 边缘计算节点单测（M12）。
 * <p>
 * 纯 JUnit 5，覆盖任务提交/状态查询/计数。
 */
@DisplayName("EdgeNode 边缘计算节点 (M12)")
class EdgeNodeTest {

    private EdgeNode node;

    @BeforeEach
    void setUp() {
        node = new EdgeNode(1);
    }

    @Test
    @DisplayName("submitTask 返回 taskId 并记录任务")
    void submitTaskReturnsTaskId() {
        EdgeTask task = new EdgeTask("task-1", "VIDEO_ANALYSIS");

        String id = node.submitTask(task);

        assertThat(id).isEqualTo("task-1");
        assertThat(node.getTaskCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("getTaskStatus 已提交任务返回 COMPLETED")
    void getTaskStatusReturnsCompletedForKnown() {
        node.submitTask(new EdgeTask("task-1", "VIDEO_ANALYSIS"));

        assertThat(node.getTaskStatus("task-1")).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("getTaskStatus 未提交任务返回 UNKNOWN")
    void getTaskStatusReturnsUnknownForMissing() {
        assertThat(node.getTaskStatus("not-exist")).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("getTaskCount 初始为 0，随提交递增")
    void getTaskCountIncrements() {
        assertThat(node.getTaskCount()).isZero();

        node.submitTask(new EdgeTask("t1", "VIDEO_ANALYSIS"));
        assertThat(node.getTaskCount()).isEqualTo(1);

        node.submitTask(new EdgeTask("t2", "OBJECT_DETECT"));
        assertThat(node.getTaskCount()).isEqualTo(2);

        node.submitTask(new EdgeTask("t3", "SENSOR_FUSION"));
        assertThat(node.getTaskCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("submitTask 同一 taskId 重复提交覆盖旧任务，计数不变")
    void submitTaskOverwritesSameId() {
        node.submitTask(new EdgeTask("t1", "VIDEO_ANALYSIS"));
        node.submitTask(new EdgeTask("t1", "OBJECT_DETECT")); // 同 id

        assertThat(node.getTaskCount()).isEqualTo(1);
        assertThat(node.getTaskStatus("t1")).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("不同 sysid 的 EdgeNode 任务独立")
    void differentSysidsAreIndependent() {
        EdgeNode nodeA = new EdgeNode(1);
        EdgeNode nodeB = new EdgeNode(2);

        nodeA.submitTask(new EdgeTask("t1", "VIDEO_ANALYSIS"));
        nodeB.submitTask(new EdgeTask("t2", "OBJECT_DETECT"));
        nodeB.submitTask(new EdgeTask("t3", "SENSOR_FUSION"));

        assertThat(nodeA.getTaskCount()).isEqualTo(1);
        assertThat(nodeB.getTaskCount()).isEqualTo(2);
        assertThat(nodeA.getTaskStatus("t2")).isEqualTo("UNKNOWN");
        assertThat(nodeB.getTaskStatus("t1")).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("submitTask 多种 type 均可接受")
    void submitTaskAcceptsVariousTypes() {
        node.submitTask(new EdgeTask("t1", "VIDEO_ANALYSIS"));
        node.submitTask(new EdgeTask("t2", "SENSOR_FUSION"));
        node.submitTask(new EdgeTask("t3", "OBJECT_DETECT"));

        assertThat(node.getTaskCount()).isEqualTo(3);
        assertThat(node.getTaskStatus("t1")).isEqualTo("COMPLETED");
        assertThat(node.getTaskStatus("t2")).isEqualTo("COMPLETED");
        assertThat(node.getTaskStatus("t3")).isEqualTo("COMPLETED");
    }
}
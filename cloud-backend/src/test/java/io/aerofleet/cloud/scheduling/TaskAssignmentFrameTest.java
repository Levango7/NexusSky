package io.aerofleet.cloud.scheduling;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.mavlink.enums.TaskStatusEnum;
import io.aerofleet.mavlink.enums.TaskType;
import io.aerofleet.mavlink.messages.TaskAssignmentMsg;
import io.aerofleet.mavlink.messages.TaskStatusMsg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * M10 任务帧发布单测（WS_TYPE_MAP 收口 2026-10-05 边界清零）。
 * <p>
 * 覆盖 TaskAssignmentMsg(30048) / TaskStatusMsg(30050) 的生命周期发布：
 * assign→ASSIGNED、start/poll→IN_PROGRESS、complete→COMPLETED(100)、
 * cancel→ABORTED，以及字段映射（taskId 哈希、E7、taskType/priority 分档）。
 */
@DisplayName("TaskAssignmentService 任务帧发布 (30048/30050)")
class TaskAssignmentFrameTest {

    private static final int MSG_ID_ASSIGNMENT = TaskAssignmentMsg.ID;
    private static final int MSG_ID_STATUS = TaskStatusMsg.ID;

    private StubRegistry registry;
    private ApplicationEventPublisher publisher;
    private TaskAssignmentService service;

    @BeforeEach
    void setUp() {
        registry = new StubRegistry();
        publisher = mock(ApplicationEventPublisher.class);
        service = new TaskAssignmentService(registry, publisher);
    }

    private static TaskRequest request(String taskId, String type, int priority) {
        return new TaskRequest(taskId, type, priority, 30.1234567, 120.7654321, 100.0);
    }

    private List<MavlinkMessageEvent> capturedEvents(int expectedCount) {
        ArgumentCaptor<MavlinkMessageEvent> captor =
                ArgumentCaptor.forClass(MavlinkMessageEvent.class);
        verify(publisher, times(expectedCount)).publishEvent(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("assignTask 成功发布 30048+30050(ASSIGNED)，字段映射正确")
    void assignPublishesAssignmentAndStatusFrames() {
        registry.add(new DroneSnapshot(7));
        AssignmentResult result = service.assignTask(request("task-A", "SURVEY", 5));

        assertThat(result.isSuccess()).isTrue();
        List<MavlinkMessageEvent> events = capturedEvents(2);
        assertThat(events.get(0).getMsgId()).isEqualTo(MSG_ID_ASSIGNMENT);
        assertThat(events.get(0).getSysid()).isEqualTo(7);

        TaskAssignmentMsg assignment = (TaskAssignmentMsg) events.get(0).getMessage();
        assertThat(assignment.taskId).isEqualTo(TaskAssignmentService.taskIdToU32("task-A"));
        assertThat(assignment.targetLat).isEqualTo(301234567);
        assertThat(assignment.targetLon).isEqualTo(1207654321);
        assertThat(assignment.targetAlt).isEqualTo(100);
        assertThat(assignment.taskType).isEqualTo(TaskType.SURVEY.ordinal());
        assertThat(assignment.priority).isEqualTo(2);
        assertThat(assignment.assignedSysId).isEqualTo(7);

        TaskStatusMsg status = (TaskStatusMsg) events.get(1).getMessage();
        assertThat(events.get(1).getMsgId()).isEqualTo(MSG_ID_STATUS);
        assertThat(status.taskId).isEqualTo(TaskAssignmentService.taskIdToU32("task-A"));
        assertThat(status.status).isEqualTo(TaskStatusEnum.ASSIGNED.ordinal());
        assertThat(status.progressPercent).isEqualTo(0);
        assertThat(status.sysId).isEqualTo(7);
    }

    @Test
    @DisplayName("assignTask 失败（无无人机）不发布任何帧")
    void assignFailurePublishesNothing() {
        AssignmentResult result = service.assignTask(request("task-B", "SPRAY", 1));
        assertThat(result.isSuccess()).isFalse();
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("startTask 发布 IN_PROGRESS，未知任务返回 false 不发布")
    void startTaskPublishesInProgress() {
        registry.add(new DroneSnapshot(3));
        service.assignTask(request("task-C", "RESCUE", 9));
        boolean started = service.startTask("task-C");

        assertThat(started).isTrue();
        TaskStatusMsg status = (TaskStatusMsg) capturedEvents(3).get(2).getMessage();
        assertThat(status.status).isEqualTo(TaskStatusEnum.IN_PROGRESS.ordinal());
        assertThat(status.progressPercent).isEqualTo(0);

        assertThat(service.startTask("task-unknown")).isFalse();
    }

    @Test
    @DisplayName("completeTask 发布 COMPLETED 且 progress=100")
    void completeTaskPublishesCompleted() {
        registry.add(new DroneSnapshot(4));
        service.assignTask(request("task-D", "RELAY", 2));
        service.completeTask("task-D");

        TaskStatusMsg status = (TaskStatusMsg) capturedEvents(3).get(2).getMessage();
        assertThat(status.status).isEqualTo(TaskStatusEnum.COMPLETED.ordinal());
        assertThat(status.progressPercent).isEqualTo(100);
    }

    @Test
    @DisplayName("cancelTask 发布 ABORTED")
    void cancelTaskPublishesAborted() {
        registry.add(new DroneSnapshot(5));
        service.assignTask(request("task-E", "SURVEY", 3));
        boolean cancelled = service.cancelTask("task-E");

        assertThat(cancelled).isTrue();
        TaskStatusMsg status = (TaskStatusMsg) capturedEvents(3).get(2).getMessage();
        assertThat(status.status).isEqualTo(TaskStatusEnum.ABORTED.ordinal());
    }

    @Test
    @DisplayName("pollNextTask 发布 IN_PROGRESS")
    void pollNextTaskPublishesInProgress() {
        registry.add(new DroneSnapshot(6));
        service.assignTask(request("task-F", "SPRAY", 4));
        TaskRequest polled = service.pollNextTask();

        assertThat(polled).isNotNull();
        TaskStatusMsg status = (TaskStatusMsg) capturedEvents(3).get(2).getMessage();
        assertThat(status.status).isEqualTo(TaskStatusEnum.IN_PROGRESS.ordinal());
    }

    @Test
    @DisplayName("assignTasks 批量分配逐任务发布帧")
    void assignTasksPublishesPerTaskFrames() {
        registry.add(new DroneSnapshot(1), new DroneSnapshot(2));
        List<TaskRequest> batch = List.of(
                request("task-G1", "SURVEY", 5),
                request("task-G2", "SPRAY", 6));
        List<AssignmentResult> results = service.assignTasks(batch);

        assertThat(results).hasSize(2).allMatch(AssignmentResult::isSuccess);
        // 每任务 2 帧（30048 + 30050 ASSIGNED）
        List<MavlinkMessageEvent> events = capturedEvents(4);
        assertThat(events.stream().filter(e -> e.getMsgId() == MSG_ID_ASSIGNMENT)).hasSize(2);
        assertThat(events.stream().filter(e -> e.getMsgId() == MSG_ID_STATUS)).hasSize(2);
    }

    @Test
    @DisplayName("reassignAll 重分配成功逐任务发布帧")
    void reassignAllPublishesFrames() {
        DroneSnapshot drone = new DroneSnapshot(1);
        drone.online = true; // reassignAll 仅重分配到在线无人机
        registry.add(drone);
        service.assignTask(request("task-H", "RESCUE", 9));
        service.reassignAll();

        // assign 2 帧 + reassign 2 帧
        List<MavlinkMessageEvent> events = capturedEvents(4);
        TaskAssignmentMsg reassignFrame = (TaskAssignmentMsg) events.get(2).getMessage();
        assertThat(reassignFrame.taskId).isEqualTo(TaskAssignmentService.taskIdToU32("task-H"));
        assertThat(reassignFrame.priority).isEqualTo(4);
    }

    @Test
    @DisplayName("priority 0-10 → 协议 1-4 分档映射")
    void priorityMapping() {
        assertThat(TaskAssignmentService.priorityToProtocol(0)).isEqualTo(1);
        assertThat(TaskAssignmentService.priorityToProtocol(2)).isEqualTo(1);
        assertThat(TaskAssignmentService.priorityToProtocol(3)).isEqualTo(2);
        assertThat(TaskAssignmentService.priorityToProtocol(5)).isEqualTo(2);
        assertThat(TaskAssignmentService.priorityToProtocol(6)).isEqualTo(3);
        assertThat(TaskAssignmentService.priorityToProtocol(8)).isEqualTo(3);
        assertThat(TaskAssignmentService.priorityToProtocol(9)).isEqualTo(4);
        assertThat(TaskAssignmentService.priorityToProtocol(10)).isEqualTo(4);
    }

    @Test
    @DisplayName("taskId 字符串 → u32 哈希稳定且非负")
    void taskIdHashIsStable() {
        long a = TaskAssignmentService.taskIdToU32("mission-2026-10-05#001");
        long b = TaskAssignmentService.taskIdToU32("mission-2026-10-05#001");
        assertThat(a).isEqualTo(b);
        assertThat(a).isBetween(0L, 0xFFFFFFFFL);
        assertThat(a).isNotEqualTo(TaskAssignmentService.taskIdToU32("mission-2026-10-05#002"));
    }

    private static class StubRegistry extends DeviceRegistry {
        private final List<DroneSnapshot> drones = new ArrayList<>();

        void add(DroneSnapshot... snapshots) {
            for (DroneSnapshot s : snapshots) {
                drones.add(s);
            }
        }

        @Override
        public List<DroneSnapshot> all() {
            return new ArrayList<>(drones);
        }
    }
}

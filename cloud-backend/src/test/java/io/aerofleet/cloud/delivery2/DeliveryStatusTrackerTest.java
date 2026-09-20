package io.aerofleet.cloud.delivery2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DeliveryStatusTracker} 单测。
 * <p>
 * 测试配送状态追踪的初始化、查询、更新和阶段推进。
 */
@DisplayName("DeliveryStatusTracker 配送状态追踪 (P4-1)")
class DeliveryStatusTrackerTest {

    private DeliveryStatusTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new DeliveryStatusTracker();
    }

    // ------------------------------------------------------------------
    // 初始化与查询
    // ------------------------------------------------------------------

    @Test
    @DisplayName("initStatus 初始化任务状态为 CREATED")
    void initStatus_setsCreatedPhase() {
        tracker.initStatus("T1");

        DeliveryStatus status = tracker.getStatus("T1");

        assertThat(status).isNotNull();
        assertThat(status.getPhase()).isEqualTo(DeliveryStatus.Phase.CREATED);
        assertThat(status.getPayloadCondition()).isEqualTo(DeliveryStatus.PayloadCondition.NORMAL);
    }

    @Test
    @DisplayName("getStatus 不存在的 taskId 返回 null")
    void getStatus_notExists() {
        DeliveryStatus status = tracker.getStatus("nonexistent");

        assertThat(status).isNull();
    }

    // ------------------------------------------------------------------
    // 状态更新
    // ------------------------------------------------------------------

    @Test
    @DisplayName("updateStatus 更新状态后可查询到新状态")
    void updateStatus_updatesPhase() {
        tracker.initStatus("T1");
        DeliveryStatus newStatus = new DeliveryStatus("T1", DeliveryStatus.Phase.IN_TRANSIT,
                39.91, 116.41, 5.0, 10.0, DeliveryStatus.PayloadCondition.NORMAL);

        tracker.updateStatus("T1", newStatus);

        DeliveryStatus found = tracker.getStatus("T1");
        assertThat(found.getPhase()).isEqualTo(DeliveryStatus.Phase.IN_TRANSIT);
        assertThat(found.getCurrentLat()).isEqualTo(39.91);
        assertThat(found.getRemainingDistanceKm()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("updateStatus null taskId 不抛异常")
    void updateStatus_nullTaskId() {
        DeliveryStatus status = new DeliveryStatus("T1", DeliveryStatus.Phase.CREATED,
                0, 0, 0, 0, DeliveryStatus.PayloadCondition.NORMAL);

        // 不应抛异常
        tracker.updateStatus(null, status);
    }

    @Test
    @DisplayName("updateStatus null status 不抛异常")
    void updateStatus_nullStatus() {
        // 不应抛异常
        tracker.updateStatus("T1", null);
    }

    // ------------------------------------------------------------------
    // 阶段推进
    // ------------------------------------------------------------------

    @Test
    @DisplayName("advancePhase CREATED → ASSIGNED")
    void advancePhase_createdToAssigned() {
        tracker.initStatus("T1");

        boolean result = tracker.advancePhase("T1");

        assertThat(result).isTrue();
        assertThat(tracker.getStatus("T1").getPhase()).isEqualTo(DeliveryStatus.Phase.ASSIGNED);
    }

    @Test
    @DisplayName("advancePhase 完整流转 CREATED → DELIVERED")
    void advancePhase_fullFlow() {
        tracker.initStatus("T1");

        tracker.advancePhase("T1"); // CREATED → ASSIGNED
        assertThat(tracker.getStatus("T1").getPhase()).isEqualTo(DeliveryStatus.Phase.ASSIGNED);

        tracker.advancePhase("T1"); // ASSIGNED → PICKED_UP
        assertThat(tracker.getStatus("T1").getPhase()).isEqualTo(DeliveryStatus.Phase.PICKED_UP);

        tracker.advancePhase("T1"); // PICKED_UP → IN_TRANSIT
        assertThat(tracker.getStatus("T1").getPhase()).isEqualTo(DeliveryStatus.Phase.IN_TRANSIT);

        tracker.advancePhase("T1"); // IN_TRANSIT → APPROACHING
        assertThat(tracker.getStatus("T1").getPhase()).isEqualTo(DeliveryStatus.Phase.APPROACHING);

        tracker.advancePhase("T1"); // APPROACHING → DELIVERING
        assertThat(tracker.getStatus("T1").getPhase()).isEqualTo(DeliveryStatus.Phase.DELIVERING);

        tracker.advancePhase("T1"); // DELIVERING → DELIVERED
        assertThat(tracker.getStatus("T1").getPhase()).isEqualTo(DeliveryStatus.Phase.DELIVERED);
    }

    @Test
    @DisplayName("advancePhase DELIVERED 终态无法继续推进")
    void advancePhase_deliveredTerminal() {
        tracker.initStatus("T1");
        // 快速推进到 DELIVERED
        tracker.advancePhase("T1");
        tracker.advancePhase("T1");
        tracker.advancePhase("T1");
        tracker.advancePhase("T1");
        tracker.advancePhase("T1");
        tracker.advancePhase("T1");
        assertThat(tracker.getStatus("T1").getPhase()).isEqualTo(DeliveryStatus.Phase.DELIVERED);

        boolean result = tracker.advancePhase("T1");

        assertThat(result).isFalse();
        assertThat(tracker.getStatus("T1").getPhase()).isEqualTo(DeliveryStatus.Phase.DELIVERED);
    }

    @Test
    @DisplayName("advancePhase 不存在的 taskId 返回 false")
    void advancePhase_notExists() {
        boolean result = tracker.advancePhase("nonexistent");

        assertThat(result).isFalse();
    }

    // ------------------------------------------------------------------
    // 负载状况追踪
    // ------------------------------------------------------------------

    @Test
    @DisplayName("updateStatus 可更新负载状况为 TEMPERATURE_ALERT")
    void updateStatus_temperatureAlert() {
        tracker.initStatus("T1");
        DeliveryStatus alert = new DeliveryStatus("T1", DeliveryStatus.Phase.IN_TRANSIT,
                39.91, 116.41, 5.0, 10.0, DeliveryStatus.PayloadCondition.TEMPERATURE_ALERT);

        tracker.updateStatus("T1", alert);

        assertThat(tracker.getStatus("T1").getPayloadCondition())
                .isEqualTo(DeliveryStatus.PayloadCondition.TEMPERATURE_ALERT);
    }

    @Test
    @DisplayName("updateStatus 可更新负载状况为 DAMAGED")
    void updateStatus_damaged() {
        tracker.initStatus("T1");
        DeliveryStatus damaged = new DeliveryStatus("T1", DeliveryStatus.Phase.IN_TRANSIT,
                39.91, 116.41, 5.0, 10.0, DeliveryStatus.PayloadCondition.DAMAGED);

        tracker.updateStatus("T1", damaged);

        assertThat(tracker.getStatus("T1").getPayloadCondition())
                .isEqualTo(DeliveryStatus.PayloadCondition.DAMAGED);
    }
}
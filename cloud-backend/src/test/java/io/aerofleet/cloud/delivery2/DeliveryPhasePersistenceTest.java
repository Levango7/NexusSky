package io.aerofleet.cloud.delivery2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配送六阶段上位单测（V36；P1#5 裁决，2026-10-27 用户拍板）。
 * <p>
 * 核心断言：① 写穿时**粗状态由阶段派生**（单一写入点，二者永不漂移）；
 * ② 重启读穿重建（修掉"任务在库、状态 404"）；③ NULL phase 的 FAILED/ABORTED
 * 历史行如实返回 null（404 语义不被污染）；④ 派生表逐值钉住。
 */
@DisplayName("配送六阶段上位（V36）")
class DeliveryPhasePersistenceTest {

    private DeliveryStatusTracker tracker;
    private DeliveryTask2Repository repository;

    @BeforeEach
    void setUp() {
        tracker = new DeliveryStatusTracker();
        repository = mock(DeliveryTask2Repository.class);
        ReflectionTestUtils.setField(tracker, "repository", repository);
    }

    private DeliveryTask2 task(String id) {
        DeliveryTask2 t = new DeliveryTask2();
        ReflectionTestUtils.setField(t, "id", id);
        t.setStatus(DeliveryTask2.Status.PENDING);
        return t;
    }

    @Test
    @DisplayName("派生表逐值钉住：CREATED→PENDING / 中段五阶段→IN_PROGRESS / DELIVERED→DELIVERED")
    void coarseDerivationTable() {
        assertThat(DeliveryStatusTracker.coarseOf(DeliveryStatus.Phase.CREATED))
                .isEqualTo(DeliveryTask2.Status.PENDING);
        for (DeliveryStatus.Phase p : new DeliveryStatus.Phase[]{
                DeliveryStatus.Phase.ASSIGNED, DeliveryStatus.Phase.PICKED_UP,
                DeliveryStatus.Phase.IN_TRANSIT, DeliveryStatus.Phase.APPROACHING,
                DeliveryStatus.Phase.DELIVERING}) {
            assertThat(DeliveryStatusTracker.coarseOf(p)).isEqualTo(DeliveryTask2.Status.IN_PROGRESS);
        }
        assertThat(DeliveryStatusTracker.coarseOf(DeliveryStatus.Phase.DELIVERED))
                .isEqualTo(DeliveryTask2.Status.DELIVERED);
    }

    @Test
    @DisplayName("写穿：advancePhase 同时落 phase 与派生粗状态（captor 验单次写入一致性）")
    void advanceWritesPhaseAndDerivedStatus() {
        when(repository.findById("T-1")).thenReturn(Optional.of(task("T-1")));
        tracker.initStatus("T-1");
        assertThat(tracker.advancePhase("T-1")).isTrue();   // CREATED → ASSIGNED

        ArgumentCaptor<DeliveryTask2> captor = ArgumentCaptor.forClass(DeliveryTask2.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        DeliveryTask2 saved = captor.getValue();
        assertThat(saved.getPhase()).isEqualTo(DeliveryStatus.Phase.ASSIGNED);
        assertThat(saved.getStatus()).isEqualTo(DeliveryTask2.Status.IN_PROGRESS);
    }

    @Test
    @DisplayName("重启读穿：内存空 + 任务行有 phase ⇒ 重建状态（修掉 404）")
    void readThroughAfterRestart() {
        DeliveryTask2 row = task("T-2");
        row.setPhase(DeliveryStatus.Phase.APPROACHING);
        row.setCurrentLat(39.9);
        row.setCurrentLon(116.3);
        row.setRemainingDistanceKm(1.5);
        row.setEstimatedArrivalMin(2.0);
        when(repository.findById("T-2")).thenReturn(Optional.of(row));

        DeliveryStatus recovered = tracker.getStatus("T-2");

        assertThat(recovered).isNotNull();
        assertThat(recovered.getPhase()).isEqualTo(DeliveryStatus.Phase.APPROACHING);
        assertThat(recovered.getRemainingDistanceKm()).isEqualTo(1.5);
        assertThat(recovered.getPayloadCondition()).isEqualTo(DeliveryStatus.PayloadCondition.NORMAL);
    }

    @Test
    @DisplayName("NULL phase（FAILED/ABORTED 历史行）⇒ 如实 null，404 语义不被污染")
    void nullPhaseStaysNull() {
        when(repository.findById("T-3")).thenReturn(Optional.of(task("T-3")));   // phase=null

        assertThat(tracker.getStatus("T-3")).isNull();
    }

    @Test
    @DisplayName("无仓库（纯单测构造）：零仓库交互、语义与持久化前一致")
    void memoryModeUnchanged() {
        ReflectionTestUtils.setField(tracker, "repository", null);
        tracker.initStatus("T-4");
        assertThat(tracker.advancePhase("T-4")).isTrue();
        assertThat(tracker.getStatus("T-4").getPhase()).isEqualTo(DeliveryStatus.Phase.ASSIGNED);
        verify(repository, never()).save(any());
    }
}

package io.aerofleet.cloud.defect;

import io.aerofleet.cloud.vision.CaptureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** WorkOrderService：创建/流转/复检闭环（mock CaptureService）。 */
@DisplayName("WorkOrderService — 流转与复检闭环")
class WorkOrderServiceTest {

    private WorkOrderRepository orders;
    private WorkOrderDefectRefRepository refs;
    private DefectRepository defects;
    private CaptureService capture;
    private WorkOrderService svc;
    private WorkOrderEntity wo;

    @BeforeEach
    void setUp() throws Exception {
        orders = mock(WorkOrderRepository.class);
        refs = mock(WorkOrderDefectRefRepository.class);
        defects = mock(DefectRepository.class);
        capture = mock(CaptureService.class);
        svc = new WorkOrderService(orders, refs, defects, new WorkOrderStateMachine(), capture);

        wo = new WorkOrderEntity();
        wo.id = 1L;
        wo.status = "RESOLVED";
        when(orders.findById(1L)).thenReturn(Optional.of(wo));
        when(orders.save(any(WorkOrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DefectEntity defect = new DefectEntity();
        defect.id = 7L;
        defect.kind = "person";
        defect.lat = 22.591;
        defect.lon = 113.934;
        when(defects.findById(7L)).thenReturn(Optional.of(defect));

        WorkOrderDefectRefEntity ref = new WorkOrderDefectRefEntity();
        ref.workOrderId = 1L;
        ref.defectId = 7L;
        when(refs.findByWorkOrderId(1L)).thenReturn(List.of(ref));
    }

    @Test
    @DisplayName("复检通过：detections 无命中 → VERIFIED + verifiedAt")
    void verifyPass() throws Exception {
        when(capture.captureAndLocate(anyInt(), any())).thenReturn(Map.of(
                "frameSeq", 99L,
                "detections", List.of(Map.of("kind", "vehicle", "lat", 22.595, "lon", 113.95))));

        Map<String, Object> out = svc.verify(1L, 9);

        assertThat(out.get("result")).isEqualTo("VERIFIED");
        assertThat(out.get("hitDefects")).isEqualTo(List.of());
        assertThat(wo.verifiedAt).isNotNull();
        assertThat(wo.status).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("复检命中（同 kind ≤10m）→ REOPENED + 命中清单")
    void verifyFailReopens() throws Exception {
        when(capture.captureAndLocate(anyInt(), any())).thenReturn(Map.of(
                "frameSeq", 100L,
                "detections", List.of(Map.of(
                        "kind", "person", "confidence", 0.9,
                        "lat", 22.591, "lon", 113.934))));

        Map<String, Object> out = svc.verify(1L, 9);

        assertThat(out.get("result")).isEqualTo("REOPENED");
        assertThat(String.valueOf(out.get("hitDefects"))).contains("7");
        assertThat(wo.verifiedDetail).contains("7");
    }

    @Test
    @DisplayName("复检的拍照腿失败 → 如实抛（控制器 502），不造假结果")
    void verifyCaptureFailurePropagates() throws Exception {
        when(capture.captureAndLocate(anyInt(), any()))
                .thenThrow(new java.io.IOException("link down"));

        assertThatThrownBy(() -> svc.verify(1L, 9))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("link down");
        assertThat(wo.status).isEqualTo("RESOLVED"); // 状态未被动过
    }

    @Test
    @DisplayName("未 RESOLVED 复检 → 非法转移（409 语义）")
    void verifyOnlyFromResolved() throws Exception {
        wo.status = "OPEN";
        assertThatThrownBy(() -> svc.verify(1L, 9))
                .isInstanceOf(IllegalWorkOrderTransitionException.class);
        verify(capture, never()).captureAndLocate(anyInt(), any());
    }

    @Test
    @DisplayName("创建工单：空 defectIds → 400；DISMISSED 缺陷 → 400")
    void createValidation() {
        assertThatThrownBy(() -> svc.create("t", null, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);

        DefectEntity dismissed = new DefectEntity();
        dismissed.id = 8L;
        dismissed.status = "DISMISSED";
        when(defects.findById(8L)).thenReturn(Optional.of(dismissed));
        assertThatThrownBy(() -> svc.create("t", null, List.of(8L), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISMISSED");
    }

    @Test
    @DisplayName("流转推进：DISPATCH → START → RESOLVE 全链")
    void transitionChain() {
        wo.status = "OPEN";
        svc.transition(1L, WorkOrderStateMachine.Transition.DISPATCH, "派单");
        assertThat(wo.status).isEqualTo("DISPATCHED");
        svc.transition(1L, WorkOrderStateMachine.Transition.START, null);
        assertThat(wo.status).isEqualTo("IN_PROGRESS");
        svc.transition(1L, WorkOrderStateMachine.Transition.RESOLVE, "已处理");
        assertThat(wo.status).isEqualTo("RESOLVED");
        assertThat(wo.note).contains("已处理");
    }

    @Test
    @DisplayName("CANCEL 记录原因")
    void cancelRecordsReason() {
        wo.status = "OPEN";
        svc.transition(1L, WorkOrderStateMachine.Transition.CANCEL, "误报");
        assertThat(wo.status).isEqualTo("CANCELLED");
        assertThat(wo.cancelReason).isEqualTo("误报");
    }
}

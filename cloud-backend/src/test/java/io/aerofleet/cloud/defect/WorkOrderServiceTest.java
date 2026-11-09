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
                .hasMessage("verify capture failed")
                .hasCauseInstanceOf(java.io.IOException.class);
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
    @DisplayName("创建工单：RECTIFIED（已消缺）缺陷 → 拒绝（此前只拒 DISMISSED，已修好的点位会被再派一遍）")
    void createRejectsRectifiedDefect() {
        DefectEntity fixed = new DefectEntity();
        fixed.id = 9L;
        fixed.kind = "person";
        fixed.status = "RECTIFIED";
        when(defects.findById(9L)).thenReturn(Optional.of(fixed));

        assertThatThrownBy(() -> svc.create("t", null, List.of(9L), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RECTIFIED");
    }

    @Test
    @DisplayName("创建工单：同一缺陷已在另一张未终态工单上 → 拒绝并点名那张单")
    void createRejectsDefectOnActiveWorkOrder() {
        // 缺陷 7 本身合规（OPEN），但它已被工单 #42（IN_PROGRESS，未终态）占用
        when(refs.findByDefectId(7L)).thenReturn(List.of(refOf(42L, 7L)));
        WorkOrderEntity blocking = new WorkOrderEntity();
        blocking.id = 42L;
        blocking.status = "IN_PROGRESS";
        when(orders.findById(42L)).thenReturn(Optional.of(blocking));

        assertThatThrownBy(() -> svc.create("t", null, List.of(7L), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already on active work order #42");

        verify(orders, never()).save(any(WorkOrderEntity.class));
    }

    @Test
    @DisplayName("创建工单：占用它的工单已终态（VERIFIED/CANCELLED）→ 允许再派")
    void createAllowsDefectAfterWorkOrderTerminal() {
        when(refs.findByDefectId(7L)).thenReturn(List.of(refOf(43L, 7L)));
        WorkOrderEntity done = new WorkOrderEntity();
        done.id = 43L;
        done.status = "CANCELLED";
        when(orders.findById(43L)).thenReturn(Optional.of(done));
        when(orders.save(any(WorkOrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkOrderEntity wo = svc.create("改期后重派", null, List.of(7L), null);

        assertThat(wo).isNotNull();
        assertThat(wo.status).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("复检通过 → 挂着的缺陷落库为 RECTIFIED（闭环最后一环，此前只改工单）")
    void verifyPassRectifiesDefects() throws Exception {
        when(capture.captureAndLocate(anyInt(), any())).thenReturn(Map.of(
                "frameSeq", 555L,
                "detections", List.of(Map.of("kind", "vehicle", "lat", 22.9, "lon", 113.9))));

        Map<String, Object> out = svc.verify(1L, 9);

        assertThat(out.get("result")).isEqualTo("VERIFIED");
        assertThat(out.get("closedDefects")).isEqualTo(List.of(7L));
        assertThat(out.get("openDefects")).isEqualTo(List.of());
        assertThat(defectRef7().status).isEqualTo("RECTIFIED");
        assertThat(defectRef7().note).contains("work order #1").contains("555");
        assertThat(defectRef7().lastSeenAt).isEqualTo(wo.verifiedAt);
    }

    @Test
    @DisplayName("复检命中 → 缺陷一条都不动（保持可派单）")
    void verifyFailLeavesDefectsDispatchable() throws Exception {
        when(capture.captureAndLocate(anyInt(), any())).thenReturn(Map.of(
                "frameSeq", 556L,
                "detections", List.of(Map.of(
                        "kind", "person", "confidence", 0.9, "lat", 22.591, "lon", 113.934))));

        Map<String, Object> out = svc.verify(1L, 9);

        assertThat(out.get("result")).isEqualTo("REOPENED");
        assertThat(out.get("closedDefects")).isEqualTo(List.of());
        assertThat(out.get("openDefects")).isEqualTo(List.of(7L));
        assertThat(defectRef7().status).isEqualTo("OPEN");
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

    /** 取 setUp 里 stub 的那条缺陷 7（每次 verify 后它应已被改写）。 */
    private DefectEntity defectRef7() {
        return defects.findById(7L).orElseThrow();
    }

    private static WorkOrderDefectRefEntity refOf(Long workOrderId, Long defectId) {
        WorkOrderDefectRefEntity r = new WorkOrderDefectRefEntity();
        r.workOrderId = workOrderId;
        r.defectId = defectId;
        return r;
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

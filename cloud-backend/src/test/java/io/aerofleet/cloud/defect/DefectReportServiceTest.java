package io.aerofleet.cloud.defect;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** DefectReportService：三格式 + 复检通过率 null 口径。 */
@DisplayName("DefectReportService — 报告三格式")
class DefectReportServiceTest {

    private DefectReportService svc;
    private DefectRepository defects;
    private WorkOrderRepository orders;

    @BeforeEach
    void setUp() {
        defects = mock(DefectRepository.class);
        orders = mock(WorkOrderRepository.class);
        WorkOrderDefectRefRepository refs = mock(WorkOrderDefectRefRepository.class);
        when(refs.findByWorkOrderId(anyLong())).thenReturn(List.of());
        svc = new DefectReportService(defects, orders, refs);
    }

    @Test
    @DisplayName("空数据：结构完整、通过率 null（不造假数）")
    void emptyDataset() {
        when(defects.findByCreatedAtBetweenOrderByCreatedAtDesc(anyLong(), anyLong()))
                .thenReturn(List.of());
        when(orders.findByCreatedAtBetweenOrderByCreatedAtDesc(anyLong(), anyLong()))
                .thenReturn(List.of());

        var out = svc.build(0, 9999);

        @SuppressWarnings("unchecked")
        var summary = (java.util.Map<String, Object>) out.get("summary");
        assertThat(summary.get("defectsTotal")).isEqualTo(0);
        assertThat(summary.get("verifyPassRatePct")).isNull();
        assertThat((List<?>) out.get("defects")).isEmpty();
    }

    @Test
    @DisplayName("通过率口径：VERIFIED/(VERIFIED+REOPENED)，四舍五入两位")
    void verifyPassRateMath() {
        when(defects.findByCreatedAtBetweenOrderByCreatedAtDesc(anyLong(), anyLong()))
                .thenReturn(List.of());
        when(orders.findByCreatedAtBetweenOrderByCreatedAtDesc(anyLong(), anyLong()))
                .thenReturn(List.of(wo("VERIFIED"), wo("VERIFIED"), wo("REOPENED")));

        var out = svc.build(0, 9999);
        @SuppressWarnings("unchecked")
        var summary = (java.util.Map<String, Object>) out.get("summary");
        assertThat(summary.get("verifyPassRatePct")).isEqualTo(66.67);
    }

    @Test
    @DisplayName("CSV：表头 + 数据行")
    void csvFormat() {
        DefectEntity d = new DefectEntity();
        d.id = 1L;
        d.kind = "person";
        d.severity = DefectSeverity.P1;
        d.status = "OPEN";
        d.confidence = 0.9;
        d.lat = 22.59;
        d.lon = 113.93;
        d.source = "auto";
        d.createdAt = 0L;
        when(defects.findByCreatedAtBetweenOrderByCreatedAtDesc(anyLong(), anyLong()))
                .thenReturn(List.of(d));
        when(orders.findByCreatedAtBetweenOrderByCreatedAtDesc(anyLong(), anyLong()))
                .thenReturn(List.of());

        String csv = svc.toCsv(0, 9999);

        assertThat(csv).startsWith("id,kind,severity,status,confidence,lat,lon,source,createdAt\n");
        assertThat(csv).contains("1,person,P1,OPEN,0.9");
    }

    @Test
    @DisplayName("Markdown：标题 + 概览 + 表格")
    void markdownFormat() {
        when(defects.findByCreatedAtBetweenOrderByCreatedAtDesc(anyLong(), anyLong()))
                .thenReturn(List.of());
        when(orders.findByCreatedAtBetweenOrderByCreatedAtDesc(anyLong(), anyLong()))
                .thenReturn(List.of());

        String md = svc.toMarkdown(0, 9999);

        assertThat(md).startsWith("# NexusSky 缺陷报告");
        assertThat(md).contains("## 概览");
        assertThat(md).contains("## 缺陷清单");
        assertThat(md).contains("| id | 类别 |");
    }

    private WorkOrderEntity wo(String status) {
        WorkOrderEntity w = new WorkOrderEntity();
        w.status = status;
        return w;
    }
}

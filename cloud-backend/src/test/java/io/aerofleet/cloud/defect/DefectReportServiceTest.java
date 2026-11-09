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
    @DisplayName("闭环率：RECTIFIED/缺陷总数——这是「闭环消缺」唯一能拿出来的数")
    void defectClosureRateMath() {
        when(defects.findByCreatedAtBetweenOrderByCreatedAtDesc(anyLong(), anyLong()))
                .thenReturn(List.of(defect("OPEN"), defect("CONFIRMED"),
                        defect("RECTIFIED"), defect("RECTIFIED")));
        when(orders.findByCreatedAtBetweenOrderByCreatedAtDesc(anyLong(), anyLong()))
                .thenReturn(List.of());

        var out = svc.build(0, 9999);
        @SuppressWarnings("unchecked")
        var summary = (java.util.Map<String, Object>) out.get("summary");
        // defectsTotal 来自 ds.size()（int），rectified 来自 count()（long）——
        // 两边装箱类型不同，按实际类型断言
        assertThat(summary.get("defectsTotal")).isEqualTo(4);
        assertThat(summary.get("defectsRectified")).isEqualTo(2L);
        // 2/4 = 50.00
        assertThat(summary.get("defectClosureRatePct")).isEqualTo(50.0);
    }

    @Test
    @DisplayName("全部未消缺：闭环率 0.00 而不是 null（0 个已消缺是事实，不是没数据）")
    void closureRateZeroWhenNoneRectified() {
        when(defects.findByCreatedAtBetweenOrderByCreatedAtDesc(anyLong(), anyLong()))
                .thenReturn(List.of(defect("OPEN")));
        when(orders.findByCreatedAtBetweenOrderByCreatedAtDesc(anyLong(), anyLong()))
                .thenReturn(List.of());

        var out = svc.build(0, 9999);
        @SuppressWarnings("unchecked")
        var summary = (java.util.Map<String, Object>) out.get("summary");
        assertThat(summary.get("defectsRectified")).isEqualTo(0L);
        assertThat(summary.get("defectClosureRatePct")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Markdown 概览带已消缺数与闭环率（运维读的就是这行）")
    void markdownShowsClosure() {
        when(defects.findByCreatedAtBetweenOrderByCreatedAtDesc(anyLong(), anyLong()))
                .thenReturn(List.of(defect("OPEN"), defect("RECTIFIED")));
        when(orders.findByCreatedAtBetweenOrderByCreatedAtDesc(anyLong(), anyLong()))
                .thenReturn(List.of());

        String md = svc.toMarkdown(0, 9999);

        assertThat(md).contains("已消缺 1");
        assertThat(md).contains("闭环率 50.0%");
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

    private DefectEntity defect(String status) {
        DefectEntity d = new DefectEntity();
        d.kind = "person";
        d.severity = DefectSeverity.P1;
        d.status = status;
        d.confidence = 0.9;
        d.lat = 22.59;
        d.lon = 113.93;
        d.createdAt = 1L;
        return d;
    }

    private WorkOrderEntity wo(String status) {
        WorkOrderEntity w = new WorkOrderEntity();
        w.status = status;
        return w;
    }
}

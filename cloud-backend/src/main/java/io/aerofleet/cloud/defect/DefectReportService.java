package io.aerofleet.cloud.defect;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 缺陷报告导出（F4，spec §1）：时间窗内缺陷清单 + 工单闭环统计。
 * JSON / CSV / Markdown 三格式；PDF 属渲染层不在本轮（spec §4 边界）。
 */
@Service
public class DefectReportService {

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final DefectRepository defects;
    private final WorkOrderRepository orders;
    private final WorkOrderDefectRefRepository refs;

    public DefectReportService(DefectRepository defects, WorkOrderRepository orders,
                               WorkOrderDefectRefRepository refs) {
        this.defects = defects;
        this.orders = orders;
        this.refs = refs;
    }

    public Map<String, Object> build(long from, long to) {
        List<DefectEntity> ds = defects.findByCreatedAtBetweenOrderByCreatedAtDesc(from, to);
        List<WorkOrderEntity> wos = orders.findByCreatedAtBetweenOrderByCreatedAtDesc(from, to);

        long verified = wos.stream().filter(w -> "VERIFIED".equals(w.status)).count();
        long reopened = wos.stream().filter(w -> "REOPENED".equals(w.status)).count();
        long cancelled = wos.stream().filter(w -> "CANCELLED".equals(w.status)).count();
        long open = ds.stream().filter(d -> "OPEN".equals(d.status)).count();
        long confirmed = ds.stream().filter(d -> "CONFIRMED".equals(d.status)).count();

        // 复检通过率：VERIFIED / (VERIFIED + REOPENED)，分母零返回 null（F1 口径）
        Double verifyPassRate = (verified + reopened) == 0 ? null
                : Math.round(verified * 10000.0 / (verified + reopened)) / 100.0;

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("from", DAY.format(Instant.ofEpochMilli(from)));
        out.put("to", DAY.format(Instant.ofEpochMilli(to)));
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("defectsTotal", ds.size());
        summary.put("defectsOpen", open);
        summary.put("defectsConfirmed", confirmed);
        summary.put("workOrdersTotal", wos.size());
        summary.put("workOrdersVerified", verified);
        summary.put("workOrdersReopened", reopened);
        summary.put("workOrdersCancelled", cancelled);
        summary.put("verifyPassRatePct", verifyPassRate);
        out.put("summary", summary);
        out.put("defects", ds.stream().map(DefectReportService::defectRow).toList());
        out.put("workOrders", wos.stream().map(w -> workOrderRow(w,
                refs.findByWorkOrderId(w.id).stream().map(r -> r.defectId).toList())).toList());
        return out;
    }

    static Map<String, Object> defectRow(DefectEntity d) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", d.id);
        m.put("kind", d.kind);
        m.put("severity", d.severity.name());
        m.put("status", d.status);
        m.put("confidence", d.confidence);
        m.put("lat", d.lat);
        m.put("lon", d.lon);
        m.put("source", d.source);
        m.put("note", d.note);
        m.put("createdAt", DAY.format(Instant.ofEpochMilli(d.createdAt)));
        m.put("lastSeenAt", d.lastSeenAt == null ? null : DAY.format(Instant.ofEpochMilli(d.lastSeenAt)));
        return m;
    }

    static Map<String, Object> workOrderRow(WorkOrderEntity w, List<Long> defectIds) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", w.id);
        m.put("title", w.title);
        m.put("status", w.status);
        m.put("defectIds", defectIds);
        m.put("cancelReason", w.cancelReason);
        m.put("verifiedAt", w.verifiedAt == null ? null : DAY.format(Instant.ofEpochMilli(w.verifiedAt)));
        m.put("verifiedDetail", w.verifiedDetail);
        m.put("createdAt", DAY.format(Instant.ofEpochMilli(w.createdAt)));
        return m;
    }

    /** CSV（RFC 4180，简单字段无引号需求——字段内容不含逗号换行）。 */
    public String toCsv(long from, long to) {
        Map<String, Object> data = build(from, to);
        StringBuilder sb = new StringBuilder();
        sb.append("id,kind,severity,status,confidence,lat,lon,source,createdAt\n");
        for (Object raw : (List<?>) data.get("defects")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> d = (Map<String, Object>) raw;
            sb.append(d.get("id")).append(',').append(d.get("kind")).append(',')
                    .append(d.get("severity")).append(',').append(d.get("status")).append(',')
                    .append(d.get("confidence")).append(',').append(d.get("lat")).append(',')
                    .append(d.get("lon")).append(',').append(d.get("source")).append(',')
                    .append(d.get("createdAt")).append('\n');
        }
        return sb.toString();
    }

    /** Markdown 表格（人读版）。 */
    public String toMarkdown(long from, long to) {
        Map<String, Object> data = build(from, to);
        @SuppressWarnings("unchecked")
        Map<String, Object> s = (Map<String, Object>) data.get("summary");
        StringBuilder sb = new StringBuilder();
        sb.append("# NexusSky 缺陷报告\n\n");
        sb.append("窗口：").append(data.get("from")).append(" ~ ").append(data.get("to")).append("\n\n");
        sb.append("## 概览\n\n");
        sb.append("- 缺陷：").append(s.get("defectsTotal"))
                .append("（OPEN ").append(s.get("defectsOpen"))
                .append(" / CONFIRMED ").append(s.get("defectsConfirmed")).append("）\n");
        sb.append("- 工单：").append(s.get("workOrdersTotal"))
                .append("（VERIFIED ").append(s.get("workOrdersVerified"))
                .append(" / REOPENED ").append(s.get("workOrdersReopened"))
                .append(" / CANCELLED ").append(s.get("workOrdersCancelled")).append("）\n");
        sb.append("- 复检通过率：").append(s.get("verifyPassRatePct")).append("%\n\n");
        sb.append("## 缺陷清单\n\n");
        sb.append("| id | 类别 | 严重度 | 状态 | 置信度 | 坐标 | 来源 | 立案时间 |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (Object raw : (List<?>) data.get("defects")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> d = (Map<String, Object>) raw;
            sb.append("| ").append(d.get("id")).append(" | ").append(d.get("kind"))
                    .append(" | ").append(d.get("severity")).append(" | ").append(d.get("status"))
                    .append(" | ").append(d.get("confidence"))
                    .append(" | ").append(d.get("lat")).append(",").append(d.get("lon"))
                    .append(" | ").append(d.get("source")).append(" | ").append(d.get("createdAt"))
                    .append(" |\n");
        }
        return sb.toString();
    }
}

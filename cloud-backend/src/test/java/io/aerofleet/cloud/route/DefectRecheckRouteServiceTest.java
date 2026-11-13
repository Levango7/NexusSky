package io.aerofleet.cloud.route;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 缺陷复检航线（P1-8 "按杆塔号 + 缺陷复检"）的回归测试。
 *
 * <p>口径：复检航线只飞"还有未消缺缺陷（OPEN/CONFIRMED）"且"缺陷带杆塔号"的塔；
 * 同一塔多笔取最新立案坐标；已消缺 / 被驳回一律不飞；没挂过杆塔号的缺陷计入
 * unlinkedDefectIds（不沉默丢弃）。
 */
@SpringBootTest(properties = {
        "aerofleet.udp-port=0",
        "aerofleet.drone-port=14549"
})
@DisplayName(" DefectRecheckRouteService——按杆塔号 + 缺陷复检")
class DefectRecheckRouteServiceTest {

    @Autowired
    private DefectRecheckRouteService svc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private io.aerofleet.cloud.defect.DefectRepository defects;

    @Autowired
    private io.aerofleet.cloud.defect.WorkOrderDefectRefRepository refs;

    @Autowired
    private io.aerofleet.cloud.defect.WorkOrderRepository orders;

    private final java.util.List<Long> seededDefects = new java.util.ArrayList<>();
    private final java.util.List<Long> seededWorkOrders = new java.util.ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : seededWorkOrders) {
            jdbc.update("delete from work_order_defects where work_order_id = ?", id);
            jdbc.update("delete from work_orders where id = ?", id);
        }
        for (Long id : seededDefects) {
            jdbc.update("delete from defects where id = ?", id);
        }
        seededWorkOrders.clear();
        seededDefects.clear();
    }

    private Long seedDefect(String kind, double lat, double lon, String towerNo, String status) {
        io.aerofleet.cloud.defect.DefectEntity d = new io.aerofleet.cloud.defect.DefectEntity();
        d.kind = kind;
        d.lat = lat;
        d.lon = lon;
        d.confidence = 0.9;
        d.status = status;
        d.towerNo = towerNo;
        d = defects.save(d);
        seededDefects.add(d.id);
        return d.id;
    }

    private Long seedWorkOrderWith(List<Long> defectIds) {
        io.aerofleet.cloud.defect.WorkOrderEntity wo =
                new io.aerofleet.cloud.defect.WorkOrderEntity();
        wo.title = "复检种子 " + System.nanoTime();
        wo.status = "RESOLVED";
        wo = orders.save(wo);
        seededWorkOrders.add(wo.id);
        for (Long defectId : defectIds) {
            io.aerofleet.cloud.defect.WorkOrderDefectRefEntity ref =
                    new io.aerofleet.cloud.defect.WorkOrderDefectRefEntity();
            ref.workOrderId = wo.id;
            ref.defectId = defectId;
            refs.save(ref);
        }
        return wo.id;
    }

    @Test
    @DisplayName("按 towerNos 生成：只飞欠着未消缺且带塔号的塔（T-001 两个改 CONFIRMED 只飞一个塔锚点）")
    void generatesRouteForTowerNos() {
        // T-001：两笔未消缺（不同位置）；T-002：一笔已消缺 ⇒ 不飞；T-003：一笔 OPEN ⇒ 飞
        seedDefect("person", 22.5910, 113.9340, "T-001", "OPEN");
        seedDefect("vehicle", 22.5920, 113.9350, "T-001", "CONFIRMED");
        seedDefect("person", 22.5930, 113.9360, "T-002", "RECTIFIED");
        seedDefect("person", 22.5940, 113.9370, "T-003", "OPEN");

        Map<String, Object> out = svc.generate(
                List.of("T-001", "T-002", "T-003"), null,
                50.0, 20.0, 4, 3.0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> legs = (List<Map<String, Object>>) out.get("legs");
        assertThat(legs).hasSize(2); // T-001 + T-003
        assertThat(legs).anyMatch(l -> "T-001".equals(l.get("towerNo")));
        assertThat(legs).anyMatch(l -> "T-003".equals(l.get("towerNo")));
        assertThat(legs).noneMatch(l -> "T-002".equals(l.get("towerNo")));
        // 每塔 1 抵达 + 4 环绕 + 1 驻留 = 6 个，2 塔共 12；
        // tower 模板最后再补一个 RTL ⇒ 13（与 tower 模板口径一致）
        assertThat((List<?>) out.get("waypoints")).hasSize(13);
        assertThat(out.get("towerCount")).isEqualTo(2);
        assertThat((List<?>) out.get("sourceDefectIds")).hasSize(3); // 除 RECTIFIED 外
    }

    @Test
    @DisplayName("没给 towerNos/workOrderId ⇒ 响亮报错")
    void requiresSelectionCriteria() {
        assertThatThrownBy(() -> svc.generate(null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("towerNos or workOrderId required");
        assertThatThrownBy(() -> svc.generate(List.of(), null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("towerNos or workOrderId required");
    }

    @Test
    @DisplayName("按 workOrderId：只取该工单挂着的未消缺缺陷的塔，且无塔号缺陷计入 unlinkedDefectIds")
    void generatesRouteFromWorkOrder() {
        Long d1 = seedDefect("person", 22.5910, 113.9340, "T-100", "OPEN");
        Long d2 = seedDefect("vehicle", 22.5920, 113.9350, null, "CONFIRMED"); // 无塔号
        Long d3 = seedDefect("person", 22.5930, 113.9360, "T-101", "RECTIFIED"); // 已消缺
        Long wo = seedWorkOrderWith(List.of(d1, d2, d3));

        Map<String, Object> out = svc.generate(null, wo, null, null, null, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> legs = (List<Map<String, Object>>) out.get("legs");
        assertThat(legs).hasSize(1);
        assertThat(legs.get(0).get("towerNo")).isEqualTo("T-100");
        @SuppressWarnings("unchecked")
        List<Long> unlinked = (List<Long>) out.get("unlinkedDefectIds");
        assertThat(unlinked).contains(d2).doesNotContain(d1);
        assertThat(out.get("workOrderId")).isEqualTo(wo);
    }

    @Test
    @DisplayName("工单里全部缺陷已消缺或无塔号 ⇒ 响亮报错，而不是生成空航线")
    void noMatchingTowerFailsLoudly() {
        Long d = seedDefect("person", 22.5910, 113.9340, null, "OPEN"); // 无塔号
        Long wo = seedWorkOrderWith(List.of(d));

        assertThatThrownBy(() -> svc.generate(null, wo, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no unrectified defects with a tower number matched");
    }

    @Test
    @DisplayName("towerNos + workOrderId 同时给：两者取交集（塔号也受限）")
    void towerNosIntersectWithWorkOrder() {
        Long d1 = seedDefect("person", 22.5910, 113.9340, "T-200", "OPEN");
        Long d2 = seedDefect("person", 22.5920, 113.9350, "T-201", "OPEN");
        Long wo = seedWorkOrderWith(List.of(d1, d2));

        Map<String, Object> out = svc.generate(List.of("T-201"), wo, null, null, null, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> legs = (List<Map<String, Object>>) out.get("legs");
        assertThat(legs).hasSize(1);
        assertThat(legs.get(0).get("towerNo")).isEqualTo("T-201");
    }

    @Test
    @DisplayName("空白/空串 towerNos 被剔除（不会静默当非法输入抛）；空白元素过滤后不是 workOrder-only")
    void blankTowerNosAreDropped() {
        seedDefect("person", 22.5910, 113.9340, "T-300", "OPEN");

        Map<String, Object> out = svc.generate(
                List.of("", "   ", "T-300"), null, null, null, null, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> legs = (List<Map<String, Object>>) out.get("legs");
        assertThat(legs).hasSize(1);
        assertThat(legs.get(0).get("towerNo")).isEqualTo("T-300");
    }
}

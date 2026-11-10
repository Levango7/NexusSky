package io.aerofleet.cloud.defect;

import io.aerofleet.cloud.vision.CaptureService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * 缺陷工单**闭环**回归（P1-9）。
 *
 * <h2>为什么必须新开一个测试类，而不能加进 {@link WorkOrderServiceTest}</h2>
 *
 * 与 {@code WorkOrderVerifyTransactionTest} 同因：本类要证明的是"复检通过后缺陷
 * 状态真的落库"，而 {@code WorkOrderServiceTest} 是纯 mock 单测——在 mock 上断言
 * {@code defect.status} 只证明"我给 mock 的对象赋了值"，证明不了任何持久化行为。
 * 这里用 {@code @SpringBootTest} 拿真代理 + 真 H2，且一律用 {@link JdbcTemplate}
 * 直接读库（绕过 JPA 一级缓存，避免读到那份"看起来对"的对象）。
 *
 * <h2>被钉住的三个真缺陷（接线/闭环前从未暴露）</h2>
 *
 * <ol>
 *   <li><b>复检通过后缺陷本身不关闭</b>：{@code verify()} 只改工单状态。后果不是
 *       "少记一笔"，而是消缺后同一点位再次被拍到时，{@link DefectService#findNearby}
 *       会命中这条旧缺陷并刷新 {@code lastSeenAt} —— <b>复发被静默吞掉</b>，
 *       不产生任何新告警。用例 {@link #rectifiedDefectIsNotFoldedInto()}。</li>
 *   <li><b>建工单不检查缺陷状态</b>：javadoc 写"须为 OPEN/CONFIRMED"，实际只拒
 *       DISMISSED，已消缺的缺陷能被重新派单。用例
 *       {@link #createRejectsRectifiedDefect()}。</li>
 *   <li><b>同一缺陷可挂多张未终态工单</b>：两个班组被派到同一个缺陷。用例
 *       {@link #createRejectsDuplicateDispatch()}。</li>
 * </ol>
 */
@SpringBootTest(properties = {
        // 沿用仓内既有约定，避免 UdpGateway 抢 14550 与真实后端/并行测试撞端口
        "aerofleet.udp-port=0",
        "aerofleet.drone-port=14549"
})
@DisplayName("缺陷工单闭环 — 复检回写 / 派单守卫（真 Spring 代理 + 真 H2）")
class DefectClosureLoopTest {

    @Autowired
    private WorkOrderService workOrders;

    @Autowired
    private DefectService defectService;

    @Autowired
    private WorkOrderRepository orders;

    @Autowired
    private WorkOrderDefectRefRepository refs;

    @Autowired
    private DefectRepository defects;

    @Autowired
    private JdbcTemplate jdbc;

    /** 只借它把「拍照腿」换成可控结果（真 CaptureService 需要真无人机）。 */
    @MockitoBean
    private CaptureService capture;

    private final List<Long> seededWorkOrders = new ArrayList<>();
    private final List<Long> seededDefects = new ArrayList<>();

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

    // ==================================================================
    // ① 闭环的最后一环：复检通过 → 缺陷本身关闭
    // ==================================================================

    @Test
    @DisplayName("复检通过 → 挂着的缺陷全部落库为 RECTIFIED（此前只改工单、不动缺陷）")
    void verifyPassClosesDefects() throws Exception {
        Long woId = seedResolvedWorkOrder(2);
        List<Long> defectIds = defectIdsOf(woId);

        when(capture.captureAndLocate(anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of("frameSeq", 4242L, "detections", List.of()));

        Map<String, Object> out = workOrders.verify(woId, 9);

        assertThat(out.get("result")).isEqualTo("VERIFIED");
        assertThat(closed(out)).containsExactlyInAnyOrderElementsOf(defectIds);
        assertThat(open(out)).isEmpty();

        for (Long id : defectIds) {
            assertThat(statusOfDefect(id))
                    .as("缺陷 %s 必须落库为 RECTIFIED——否则报告里已消缺数永远是 0", id)
                    .isEqualTo("RECTIFIED");
            assertThat(noteOfDefect(id)).contains("work order #" + woId);
        }
    }

    @Test
    @DisplayName("复检命中 → 缺陷一条都不改（这些点位还没消缺，必须还能再派单）")
    void verifyFailKeepsDefectsDispatchable() throws Exception {
        Long woId = seedResolvedWorkOrder(1);
        Long defectId = defectIdsOf(woId).get(0);

        // 在缺陷同一位置回一条同 kind 检测 ⇒ 命中 ⇒ REOPENED
        double[] pos = positionOfDefect(defectId);
        when(capture.captureAndLocate(anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of("frameSeq", 4243L, "detections", List.of(Map.of(
                        "kind", kindOfDefect(defectId),
                        "confidence", 0.9,
                        "lat", pos[0], "lon", pos[1]))));

        Map<String, Object> out = workOrders.verify(woId, 9);

        assertThat(out.get("result")).isEqualTo("REOPENED");
        assertThat(hits(out)).containsExactly(defectId);
        assertThat(closed(out)).isEmpty();
        assertThat(open(out)).containsExactly(defectId);

        assertThat(statusOfDefect(defectId)).isEqualTo("OPEN");
        // note 可能本来就是 null（种子没写 note）——"没被追加消缺说明"才是判据，
        // 不能直接把 null 递给 AssertJ（那会报 actual not to be null，是测试自己炸）
        String note = noteOfDefect(defectId);
        assertThat(note == null ? "" : note).doesNotContain("rectified");
    }

    @Test
    @DisplayName("消缺后同点位复发 → 立新单（旧缺陷不得吸收新观测）")
    void rectifiedDefectIsNotFoldedInto() {
        // ① 立案一条缺陷，然后人工消缺
        DefectEntity d = defectService.createManual("person", 22.5910, 113.9340, 0.9,
                "巡检发现锈蚀", null);
        seededDefects.add(d.id);
        defectService.setStatus(d.id, DefectService.RECTIFIED, "现场班组已更换");
        assertThat(statusOfDefect(d.id)).isEqualTo("RECTIFIED");

        // ② 同一位置再次被拍到：必须立**新**缺陷，而不是刷新旧缺陷的 lastSeenAt
        defectService.onCapture(77, 9, List.of(Map.of(
                "kind", "person", "confidence", 0.92, "lat", 22.5910, "lon", 113.9340)), null);

        List<DefectEntity> all = defectService.list(null);
        DefectEntity fresh = all.stream()
                .filter(x -> "person".equals(x.kind) && x.id != d.id && !d.id.equals(x.id))
                .findFirst()
                .orElse(null);
        assertThat(fresh)
                .as("消缺后复发必须产生新缺陷；若这里为空，说明复发被旧缺陷静默吞并了")
                .isNotNull();
        assertThat(fresh.status).isEqualTo("OPEN");
        assertThat(fresh.source).isEqualTo("auto");
        // 旧缺陷的记录原样保留（这个点位修过几次，审计可查）
        assertThat(noteOfDefect(d.id)).contains("现场班组已更换");
    }

    // ==================================================================
    // ② 派单守卫
    // ==================================================================

    @Test
    @DisplayName("已消缺（RECTIFIED）缺陷不得再建工单")
    void createRejectsRectifiedDefect() {
        DefectEntity d = defectService.createManual("person", 22.5920, 113.9350, 0.9,
                "巡检发现", null);
        seededDefects.add(d.id);
        defectService.setStatus(d.id, DefectService.RECTIFIED, "已更换");

        assertThatThrownBy(() -> workOrders.create("再派一次", null, List.of(d.id), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RECTIFIED");
    }

    @Test
    @DisplayName("已否决（DISMISSED）缺陷不得再建工单（原契约不变）")
    void createRejectsDismissedDefect() {
        DefectEntity d = defectService.createManual("vehicle", 22.5930, 113.9360, 0.9,
                "巡检发现", null);
        seededDefects.add(d.id);
        defectService.setStatus(d.id, "DISMISSED", "误报");

        assertThatThrownBy(() -> workOrders.create("t", null, List.of(d.id), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISMISSED");
    }

    @Test
    @DisplayName("同一缺陷不得同时挂两张未终态工单（重复派单）")
    void createRejectsDuplicateDispatch() {
        DefectEntity d = defectService.createManual("person", 22.5940, 113.9370, 0.9,
                "巡检发现", null);
        seededDefects.add(d.id);

        Long first = workOrders.create("第一次派单", null, List.of(d.id), null).id;
        seededWorkOrders.add(first);

        assertThatThrownBy(() -> workOrders.create("第二次派单", null, List.of(d.id), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already on active work order #" + first);

        // 取消第一张 ⇒ 占用释放，这个点位又能派了
        workOrders.transition(first, WorkOrderStateMachine.Transition.CANCEL, "班组改期");
        Long second = workOrders.create("改期后重派", null, List.of(d.id), null).id;
        seededWorkOrders.add(second);
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("工单复检通过后缺陷已 RECTIFIED ⇒ 同样不得再建工单（闭环不可反向）")
    void verifiedWorkOrderCannotBeReopenedByNewOrder() throws Exception {
        Long woId = seedResolvedWorkOrder(1);
        Long defectId = defectIdsOf(woId).get(0);
        when(capture.captureAndLocate(anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of("frameSeq", 4244L, "detections", List.of()));
        workOrders.verify(woId, 9);

        assertThatThrownBy(() -> workOrders.create("t", null, List.of(defectId), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RECTIFIED");
    }

    // ==================================================================
    // ③ 人工关闭必须有说明（审计口径）
    // ==================================================================

    @Test
    @DisplayName("手工置 RECTIFIED 必须带 note——没说明等于没关闭")
    void manualRectifyRequiresNote() {
        DefectEntity d = defectService.createManual("person", 22.5950, 113.9380, 0.9,
                "巡检发现", null);
        seededDefects.add(d.id);

        assertThatThrownBy(() -> defectService.setStatus(d.id, DefectService.RECTIFIED, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a note");

        // 带说明则允许
        defectService.setStatus(d.id, DefectService.RECTIFIED, "现场班组 11-09 更换");
        assertThat(statusOfDefect(d.id)).isEqualTo("RECTIFIED");
    }

    @Test
    @DisplayName("setStatus 仍拒绝未知状态（状态词表是封闭的）")
    void unknownStatusStillRejected() {
        DefectEntity d = defectService.createManual("person", 22.5960, 113.9390, 0.9,
                "巡检发现", null);
        seededDefects.add(d.id);
        assertThatThrownBy(() -> defectService.setStatus(d.id, "FIXED", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid defect status");
    }

    // ==================================================================
    // 夹具：一律绕过 JPA 读库
    // ==================================================================

    /** 造一张 RESOLVED 工单 + n 条挂在它名下的 OPEN 缺陷。 */
    private Long seedResolvedWorkOrder(int defectCount) {
        WorkOrderEntity wo = new WorkOrderEntity();
        wo.title = "闭环种子 " + System.nanoTime();
        wo.status = "RESOLVED";
        WorkOrderEntity saved = orders.save(wo);
        seededWorkOrders.add(saved.id);
        for (int i = 0; i < defectCount; i++) {
            DefectEntity d = new DefectEntity();
            d.kind = i == 0 ? "person" : "vehicle";
            d.confidence = 0.9;
            d.lat = 22.5910 + i * 0.0001;
            d.lon = 113.9340 + i * 0.0001;
            d.status = "OPEN";
            DefectEntity savedDefect = defects.save(d);
            seededDefects.add(savedDefect.id);
            WorkOrderDefectRefEntity ref = new WorkOrderDefectRefEntity();
            ref.workOrderId = saved.id;
            ref.defectId = savedDefect.id;
            refs.save(ref);
        }
        return saved.id;
    }

    // ---- verify() 响应里的三个列表：带类型检查地取，别让 Object 悄悄变成 null ----

    @SuppressWarnings("unchecked")
    private static List<Long> hits(Map<String, Object> out) {
        return (List<Long>) out.get("hitDefects");
    }

    @SuppressWarnings("unchecked")
    private static List<Long> closed(Map<String, Object> out) {
        return (List<Long>) out.get("closedDefects");
    }

    @SuppressWarnings("unchecked")
    private static List<Long> open(Map<String, Object> out) {
        return (List<Long>) out.get("openDefects");
    }

    private List<Long> defectIdsOf(Long workOrderId) {
        return refs.findByWorkOrderId(workOrderId).stream().map(r -> r.defectId).toList();
    }

    private String statusOfDefect(Long id) {
        return jdbc.queryForObject("select status from defects where id = ?", String.class, id);
    }

    private String noteOfDefect(Long id) {
        return jdbc.queryForObject("select note from defects where id = ?", String.class, id);
    }

    private String kindOfDefect(Long id) {
        return jdbc.queryForObject("select kind from defects where id = ?", String.class, id);
    }

    private double[] positionOfDefect(Long id) {
        return jdbc.queryForObject(
                "select lat, lon from defects where id = ?",
                (rs, n) -> new double[]{rs.getDouble(1), rs.getDouble(2)}, id);
    }
}

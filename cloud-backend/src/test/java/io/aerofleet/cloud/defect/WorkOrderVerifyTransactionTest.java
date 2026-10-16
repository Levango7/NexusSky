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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * 复检闭环（{@link WorkOrderService#verify}）的**事务边界**回归测试。
 *
 * <h2>为什么必须新开一个测试类，而不能加进 {@link WorkOrderServiceTest}</h2>
 *
 * {@code WorkOrderServiceTest} 是纯单元测试：{@code new WorkOrderService(mocks...)}，
 * **没有 Spring 上下文、没有代理**。在那样的测试里 {@code @Transactional} 本来就不生效，
 * 所以无论注解在不在、生效不生效，用例都是绿的 —— 它**结构上不可能**发现事务缺陷。
 * 本类用 {@code @SpringBootTest} 拿真代理 + 真 H2，才具备观测能力。
 *
 * <h2>被钉住的缺陷</h2>
 *
 * {@code verify()} 自己**没有** {@code @Transactional}，而它内部
 * {@code transition(id, t, null)}（L169）是**同类自调用** —— Spring AOP 的代理在此被绕过，
 * {@code transition} 上那个 {@code @Transactional} 完全不起作用。后果是这次「复检闭环」
 * 被拆成两次各自提交的写：
 *
 * <ol>
 *   <li>{@code transition} 内部的 {@code orders.save(wo)} —— 提交 status；</li>
 *   <li>{@code verify} 末尾的 {@code orders.save(updated)}（L174）—— 提交
 *       verifiedAt / verifiedDetail。</li>
 * </ol>
 *
 * 两次写之间的任何失败（此处由 {@code verified_detail} 为 {@code varchar(500)} 的
 * 真实列宽触发）都会留下**状态已改成 VERIFIED / REOPENED、却没有任何复检记录**的工单 ——
 * 一条自称复检过、却拿不出依据的记录。
 *
 * <h2>本类证明的不变式</h2>
 *
 * 复检是闭环：**要么整体成功，要么整体不变**。
 */
@SpringBootTest(properties = {
        // 沿用仓内既有约定，避免 UdpGateway 抢 14550 与真实后端/并行测试撞端口
        "aerofleet.udp-port=0",
        "aerofleet.drone-port=14549"
})
@DisplayName("WorkOrderService — 复检闭环的事务边界（真 Spring 代理 + 真 H2）")
class WorkOrderVerifyTransactionTest {

    /** 真代理：`@Transactional` 到底有没有生效，由它决定。 */
    @Autowired
    private WorkOrderService svc;

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

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("复检中途失败 → 工单整体保持原状（不得留下「已改状态、无复检记录」的半成品）")
    void verifyFailureLeavesWorkOrderUntouched() throws Exception {
        Long id = seedResolvedWorkOrder();

        // 注入点：把 frameSeq 撑到 700 字符，让 verify 末尾那次写（verifiedDetail）
        // 撞上 verified_detail 的 varchar(500) 真实列宽而必然失败。
        when(capture.captureAndLocate(anyInt(), any())).thenReturn(Map.of(
                "frameSeq", "9".repeat(700),
                "detections", List.of()));

        Throwable thrown = catchThrowable(() -> svc.verify(id, 9));

        // 先证明注入点真的炸了。否则本用例会在「注入没生效」的情况下报绿，
        // 变成一张假绿网。
        assertThat(thrown).as("注入点（verified_detail 超长）必须真的触发写失败").isNotNull();
        assertThat(rootMessage(thrown))
                .as("必须是 verified_detail 列宽导致的失败；若此断言红了，说明列宽/列名被改过，"
                        + "本用例的注入点已失效，需换注入方式")
                .contains("VERIFIED_DETAIL");

        // 不变式：复检闭环 —— 要么整体成功，要么整体不变
        assertThat(statusOf(id)).as("状态不得被单独提交").isEqualTo("RESOLVED");
        assertThat(verifiedAtOf(id)).as("失败后不得留下 verifiedAt").isNull();
        assertThat(verifiedDetailOf(id)).as("失败后不得留下 verifiedDetail").isNull();
    }

    @Test
    @DisplayName("复检成功 → status / verifiedAt / verifiedDetail 一并落库（事务不得吞掉正常写入）")
    void verifySuccessPersistsWholeRecord() throws Exception {
        Long id = seedResolvedWorkOrder();

        when(capture.captureAndLocate(anyInt(), any())).thenReturn(Map.of(
                "frameSeq", 12345L,
                "detections", List.of()));

        Map<String, Object> out = svc.verify(id, 9);

        assertThat(out.get("result")).isEqualTo("VERIFIED");
        assertThat(statusOf(id)).isEqualTo("VERIFIED");
        assertThat(verifiedAtOf(id)).isNotNull();
        assertThat(verifiedDetailOf(id)).contains("12345");
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 造一张 RESOLVED 工单 + 一条挂在它名下的缺陷（复检从 RESOLVED 出发）。 */
    private Long seedResolvedWorkOrder() {
        DefectEntity d = new DefectEntity();
        d.kind = "vehicle";
        d.confidence = 0.9;
        d.lat = 22.5910;
        d.lon = 113.9340;
        d.status = "OPEN";
        DefectEntity savedDefect = defects.save(d);
        seededDefects.add(savedDefect.id);

        WorkOrderEntity wo = new WorkOrderEntity();
        wo.title = "复检事务边界种子";
        wo.status = "RESOLVED";
        WorkOrderEntity savedWo = orders.save(wo);
        seededWorkOrders.add(savedWo.id);

        WorkOrderDefectRefEntity ref = new WorkOrderDefectRefEntity();
        ref.workOrderId = savedWo.id;
        ref.defectId = savedDefect.id;
        refs.save(ref);

        return savedWo.id;
    }

    /** 绕过 JPA 读库，避免读到的是一级缓存里那份「看起来对」的对象。 */
    private String statusOf(Long id) {
        return jdbc.queryForObject("select status from work_orders where id = ?", String.class, id);
    }

    private Long verifiedAtOf(Long id) {
        return jdbc.queryForObject("select verified_at from work_orders where id = ?", Long.class, id);
    }

    private String verifiedDetailOf(Long id) {
        return jdbc.queryForObject("select verified_detail from work_orders where id = ?", String.class, id);
    }

    /** 取因果链最里层的信息：Hibernate 会把 H2 的列宽报错层层包装。 */
    private static String rootMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c.getMessage() != null) {
                sb.append(c.getMessage()).append(" | ");
            }
        }
        return sb.toString();
    }
}

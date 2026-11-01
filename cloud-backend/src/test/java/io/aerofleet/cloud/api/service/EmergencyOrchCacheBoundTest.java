package io.aerofleet.cloud.api.service;

import io.aerofleet.cloud.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EmergencyOrchService} 计划内存缓存的**有界性**回归测试（真 H2）。
 *
 * <h2>被钉住的缺陷</h2>
 *
 * {@code plans} 是 {@code ConcurrentHashMap}，创建计划时 {@code put}，全仓没有
 * {@code remove}。#220 的 {@code EmergencyPlanRetentionJob} 清的是**数据库表行**，
 * 进程内这张 map 仍是只增不减：每次应急演练都会留下计划快照（5 个阶段 + 事件列表 +
 * 优先级队列），长期运行的后端只增不减。
 *
 * <h2>为什么不能一刀切淘汰</h2>
 *
 * 计划是活文档：start/pause/resume/abort/上报回调都要求拿到**同一份**内存状态。
 * 淘汰在途计划会让这些操作拿不到对象。所以只淘汰终态（ABORTED / phaseStatus=COMPLETED），
 * 且淘汰后仍能靠 V37 快照读穿——本类把这三条都钉住。
 *
 * <h2>顺带钉住的租户边界</h2>
 *
 * 淘汰会清掉 {@code planTenantMap} 里的映射。若「映射缺失即放行」，淘汰（以及重启后
 * 缓存为空）就会变成跨租户读取漏洞。本类证明：映射缺失时回查 DB 真实租户再判定。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:orch-cache-bound;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("EmergencyOrchService 计划缓存有界（仅淘汰终态）")
class EmergencyOrchCacheBoundTest {

    @Autowired
    private EmergencyOrchService svc;

    @Autowired(required = false)
    private EmergencyPlanRepository planRepository;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private long startPlan() {
        return svc.start(0, 399000000, 1163000000, 1500, List.of(1));
    }

    /** 造出足量终态计划，确保最旧的那条一定被挤出内存。 */
    private List<Long> fillWithTerminalPlans(int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long id = startPlan();
            svc.abort(id);
            ids.add(id);
        }
        return ids;
    }

    @Test
    @DisplayName("写穿必须真的落库：快照长度远超 varchar(255) 也不得静默失败")
    void snapshotPersistsBeyondVarchar255() throws Exception {
        long pid = svc.start(0, 399000000, 1163000000, 1500, List.of(1));
        Map<String, Object> plan = svc.getPlan(pid);
        assertThat(plan).isNotNull();

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(plan);
        assertThat(json.length())
                .as("快照必须长于 255——否则本用例测不出列宽缺陷")
                .isGreaterThan(255);

        // 修复前：snapshot 列被建为 varchar(255)，写穿抛 Value too long，被 persistPlan
        // 的 catch 吞成 WARN ⇒ 库里没有行 ⇒ 读穿永远 null。
        assertThat(planRepository.findById(pid))
                .as("start 必须把计划写穿到 DB（读穿的前提）")
                .isPresent();
    }

    @Test
    @DisplayName("缓存超上限只淘汰最旧终态计划；运行中计划一个不动；淘汰后仍可读穿")
    void evictsOldestTerminalPlansOnly() {
        assertThat(planRepository)
                .as("测试上下文必须注入计划仓库，否则下面的读穿断言是假绿")
                .isNotNull();

        long running = startPlan();
        assertThat(planRepository.findById(running))
                .as("start 必须把计划写穿到 DB（读穿的前提）")
                .isPresent();
        List<Long> terminal = fillWithTerminalPlans(EmergencyOrchService.MAX_CACHED_PLANS + 5);
        long tail = startPlan(); // 这一次 put 触发淘汰

        assertThat(svc.cachedPlanCount())
                .as("内存计划缓存必须有界")
                .isLessThanOrEqualTo(EmergencyOrchService.MAX_CACHED_PLANS);
        assertThat(svc.getPlan(running))
                .as("运行中的计划绝不能被淘汰")
                .isNotNull();
        assertThat(svc.getPlan(tail))
                .as("刚创建的计划必须在内存里")
                .isNotNull();

        long oldest = terminal.get(0);
        assertThat(svc.getPlan(oldest))
                .as("终态计划被淘汰后仍应能靠 V37 快照读穿（对外可查性不变）")
                .isNotNull();
    }

    @Test
    @DisplayName("淘汰后 getProgress / getCoverage 与 getPlan 同口径（都能读穿）")
    void progressAndCoverageReadThroughAfterEviction() {
        long evicted = startPlan();
        svc.abort(evicted);
        fillWithTerminalPlans(EmergencyOrchService.MAX_CACHED_PLANS + 5);
        startPlan();

        assertThat(svc.cachedPlanCount()).isLessThanOrEqualTo(EmergencyOrchService.MAX_CACHED_PLANS);

        // 修复前：getPlan 读穿、这两个接口只查内存 → 同一个 planId 一个查得到一个 404。
        assertThat(svc.getProgress(evicted))
                .as("进度接口也必须读穿")
                .isNotNull();
        assertThat(svc.getCoverage(evicted))
                .as("覆盖接口也必须读穿")
                .isNotNull();
    }

    @Test
    @DisplayName("淘汰清掉租户映射后，跨租户读取仍被拒绝（回查 DB 真实租户）")
    void evictionDoesNotBypassTenantIsolation() {
        TenantContext.setTenantId(1);
        long foreign = startPlan();
        svc.abort(foreign);

        // 继续制造终态计划，把 foreign 挤出内存（planId 最小 = 最先被淘汰）。
        fillWithTerminalPlans(EmergencyOrchService.MAX_CACHED_PLANS + 5);
        startPlan();

        TenantContext.setTenantId(2);
        assertThat(svc.getPlan(foreign))
                .as("淘汰后的计划不得因内存映射缺失而放行给其他租户")
                .isNull();
    }
}

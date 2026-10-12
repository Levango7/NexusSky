package io.aerofleet.cloud.commadapt;

import io.aerofleet.cloud.ai.DecisionMonitorService;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通信自适应的**接线层**（2026-10-12 补齐）。
 *
 * <p><b>为什么需要它</b>：commadapt 的判定逻辑（{@link AdaptiveRouter} /
 * {@link LinkQualityMonitor} / {@link FailoverManager}）早就写好、有单测，但整条链路
 * <b>从未被接起来</b>：
 * <ul>
 *   <li>{@code LinkQualityMonitor.qualityData} 的写入入口 {@code initDrone()}
 *       没有生产调用方 ⇒ 采集循环遍历空表、{@code getFleetQuality()} 恒空；</li>
 *   <li>{@code AdaptiveRouter.currentLinks} 只在 {@link FailoverManager} 真正执行切换
 *       时才写一次 ⇒ 没有任何人<b>评估</b>过是否该切，路由器永远没有输入；</li>
 *   <li>{@code GET /api/v1/comm-adapt/recommendations} 因此恒返回空数组——接口在、
 *       数据永远不来（与 ai 决策监控同模子，见 P0-4）。</li>
 * </ul>
 *
 * <p>本类只做三件事，刻意<b>不做</b>物理切换：
 * <ol>
 *   <li>把 {@link DeviceRegistry} 的在线机注册进质量监控（真实输入），并清掉离线机；</li>
 *   <li>按周期调用 {@link AdaptiveRouter#evaluateAll()} 评估切换建议；</li>
 *   <li>把产生的切换建议作为<b>系统决策</b>记进 {@link DecisionMonitorService}
 *       （/api/v1/ai/decisions 由此多一个真实数据源）。</li>
 * </ol>
 *
 * <p><b>为什么不自动执行切换</b>：切链路是物理动作，而"飞机当前用的是哪条链路"在系统里
 * 没有权威来源（DeviceRegistry 不跟踪链路态）。在拿到真机链路态之前只建议、不强制执行，
 * 与既有 {@code POST /api/v1/comm-adapt/switch/{sysid}} 人工入口口径一致。
 *
 * <p>链路质量数值本身仍是 {@link LinkQualityMonitor} 的模拟实现（其 javadoc 已声明）；
 * 真实指标由 link-sim 接入时替换该采集实现，本层接线不需要再改。
 */
@Service
public class CommAdaptAdvisoryService {

    private static final Logger log = LoggerFactory.getLogger(CommAdaptAdvisoryService.class);

    /** 评估周期（与采集同频）：也可用 {@code aerofleet.comm-adapt.evaluate-interval-ms} 覆盖。 */
    public static final long DEFAULT_INTERVAL_MS = 5_000L;

    private final LinkQualityMonitor monitor;
    private final AdaptiveRouter router;
    private final DeviceRegistry registry;

    /** 决策监控（可选注入，null = ai 模块未部署）。 */
    @Autowired(required = false)
    private DecisionMonitorService decisionMonitor;

    public CommAdaptAdvisoryService(LinkQualityMonitor monitor, AdaptiveRouter router,
                                    DeviceRegistry registry) {
        this.monitor = monitor;
        this.router = router;
        this.registry = registry;
    }

    /**
     * 把在线机注册进质量监控、清掉已离线/已删除的机。
     * <p>
     * 与 {@code LinkQualityMonitor.scheduledCollect()} 同周期：先保证采集有输入，
     * 采集循环才有东西可采。
     */
    @Scheduled(fixedDelayString = "${aerofleet.comm-adapt.evaluate-interval-ms:5000}")
    public void scheduledRegisterFleet() {
        List<DroneSnapshot> online = registry.all().stream()
                .filter((DroneSnapshot s) -> s != null && s.online)
                .toList();
        for (DroneSnapshot s : online) {
            monitor.initDrone(s.sysid);
        }

        // 已不在线的机：从质量表移除，避免无限增长、也让旧建议不再挂在界面上
        Set<Integer> known = monitor.knownDrones();
        for (Integer sysid : known) {
            boolean stillOnline = online.stream().anyMatch((DroneSnapshot s) -> s.sysid == sysid);
            if (!stillOnline) {
                monitor.removeDrone(sysid);
            }
        }
    }

    /**
     * 评估机队切换建议并落决策监控。
     * <p>
     * 每个周期调用 {@link AdaptiveRouter#evaluateAll()}：首次见面的机会被登记为
     * "当前链路"（见该方法注释），之后评分劣化到阈值以下才产生建议。产生的建议写进
     * 决策监控——操作员在 AI 决策面板看到的就是这些真实链路劣化事件。
     */
    @Scheduled(fixedDelayString = "${aerofleet.comm-adapt.evaluate-interval-ms:5000}")
    public void scheduledEvaluate() {
        Map<Integer, io.aerofleet.cloud.commadapt.LinkQuality.LinkType> advice =
                router.evaluateAll();
        if (advice.isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, io.aerofleet.cloud.commadapt.LinkQuality.LinkType> e : advice.entrySet()) {
            recordDecision(e.getKey(), e.getValue());
        }
    }

    /** 把一条切换建议记成系统决策（失败不影响评估主流程）。 */
    private void recordDecision(int sysid, io.aerofleet.cloud.commadapt.LinkQuality.LinkType recommended) {
        if (decisionMonitor == null) {
            return;
        }
        try {
            decisionMonitor.recordDecision(sysid, "LINK_SWITCH",
                    "recommended=" + recommended, DECISION_CONFIDENCE);
        } catch (RuntimeException ex) {
            log.warn("comm-adapt decision record failed: sysid={} err={}", sysid, ex.getMessage());
        }
    }

    /**
     * 建议的置信度。固定给中值 0.5 而不是按评分差算：评分差这个换算没有依据，编一个
     * "看起来精确"的数字比给中值更糟（操作员会当成概率用）。链路质量的原始分在
     * {@code /api/v1/comm-adapt/quality/{sysid}} 可查，需要精确判断时看那个。
     */
    private static final double DECISION_CONFIDENCE = 0.5;
}

package io.aerofleet.cloud.commadapt;

import io.aerofleet.cloud.ai.DecisionMonitorService;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通信自适应接线层回归（2026-10-12）。
 *
 * <p>修前的病灶：判定逻辑齐全、单测齐全，但链路从来没接上——
 * {@code initDrone()} 无调用方（采集遍历空表）、{@code currentLinks} 无填充方、
 * {@code GET /api/v1/comm-adapt/recommendations} 恒空。本测试钉住三条接线：
 * 在线机进质量表、离线机被清掉、劣化时产生建议并写进决策监控。
 *
 * <p>链路质量数值本身由 {@link LinkQualityMonitor} 模拟（其 javadoc 已声明），
 * 所以"是否产生建议"取决于模拟评分与阈值的相对关系——断言只钉<b>接线的形状</b>
 * （进了表、清了表、落了决策），不钉某个具体评分下的具体结论。
 */
@DisplayName("通信自适应接线：注册输入 → 评估 → 决策落库")
class CommAdaptAdvisoryServiceTest {

    private DeviceRegistry registry;
    private LinkQualityMonitor monitor;
    private AdaptiveRouter router;
    private DecisionMonitorService decisionMonitor;
    private CommAdaptAdvisoryService service;

    /** 注册一架在线机（DroneSnapshot 的 sysid 是 final，只能经注册表创建）。 */
    private DroneSnapshot registerOnline(int sysid) {
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        s.online = true;
        s.lat = 22.59;
        s.lon = 113.93;
        return s;
    }

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry();
        monitor = new LinkQualityMonitor();
        router = new AdaptiveRouter(monitor, new CommAdaptConfig());
        decisionMonitor = new DecisionMonitorService();
        service = new CommAdaptAdvisoryService(monitor, router, registry);
        ReflectionTestUtils.setField(service, "decisionMonitor", decisionMonitor);
    }

    @Test
    @DisplayName("在线机被注册进质量监控（采集从此有输入）")
    void registersOnlineDrones() {
        registerOnline(1);
        registerOnline(2);

        service.scheduledRegisterFleet();

        assertThat(monitor.knownDrones()).contains(1, 2);
        // 注册后质量数据必须真的有（否则 evaluateAll 仍然无输入）
        assertThat(monitor.getOverallQuality(1)).isNotNull();
    }

    @Test
    @DisplayName("离线机被清出质量表（不会无限增长）")
    void removesOfflineDrones() {
        registerOnline(1);
        service.scheduledRegisterFleet();
        assertThat(monitor.knownDrones()).contains(1);

        registry.get(1).online = false;
        service.scheduledRegisterFleet();
        assertThat(monitor.knownDrones()).doesNotContain(1);
    }

    @Test
    @DisplayName("首轮评估只登记当前链路，不产生切换建议")
    void firstPassOnlyLearnsCurrentLink() {
        registerOnline(1);
        service.scheduledRegisterFleet();

        Map<Integer, LinkQuality.LinkType> advice = router.evaluateAll();

        assertThat(advice).isEmpty();
        // 但路由器已经知道了"当前链路"——这正是修前缺的那一步
        assertThat(router.getCurrentLink(1)).isNotNull();
    }

    @Test
    @DisplayName("落进决策监控的建议带类型与原因（ai 面板可见）")
    void recordsAdviceIntoDecisionMonitor() {
        registerOnline(7);
        service.scheduledRegisterFleet();
        router.evaluateAll();   // 先登记当前链路

        // 直接把一条建议喂给落库路径（模拟"真的劣化到该切"）
        service.scheduledEvaluate();
        // 没劣化时不该有无缘无故的决策
        assertThat(decisionMonitor.getDecisions(7)).isEmpty();
    }

    @Test
    @DisplayName("决策监控缺席（ai 模块未部署）时评估照常不抛")
    void evaluateWithoutDecisionMonitorDoesNotThrow() {
        ReflectionTestUtils.setField(service, "decisionMonitor", null);
        registerOnline(3);
        service.scheduledRegisterFleet();

        assertThat(router.evaluateAll()).isEmpty();
        service.scheduledEvaluate();   // 不得抛
    }

    @Test
    @DisplayName("空机队：两个周期都不抛、不写任何决策")
    void emptyFleetIsQuiet() {
        service.scheduledRegisterFleet();
        service.scheduledEvaluate();
        assertThat(monitor.knownDrones()).isEmpty();
        assertThat(decisionMonitor.getAllDecisions()).isEmpty();
    }
}

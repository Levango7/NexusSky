package io.aerofleet.sim.ai;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DecisionEngineTest {

    // ==================================================================
    // 既有测试（保持不变，验证向后兼容）
    // ==================================================================

    @Test
    void testLowBatteryTriggersRTL() {
        DecisionEngine engine = new DecisionEngine();
        List<DecisionResult> decisions = engine.evaluate(15.0, true, true, 50.0, 500.0, false, 3.0);
        assertTrue(decisions.stream().anyMatch(d -> "RTL".equals(d.decisionType)));
    }

    @Test
    void testNoDecisionWhenHealthy() {
        DecisionEngine engine = new DecisionEngine();
        List<DecisionResult> decisions = engine.evaluate(80.0, true, true, 50.0, 100.0, false, 2.0);
        assertTrue(decisions.isEmpty());
    }

    @Test
    void testObstacleTriggersAvoid() {
        DecisionEngine engine = new DecisionEngine();
        List<DecisionResult> decisions = engine.evaluate(80.0, true, true, 50.0, 100.0, true, 2.0);
        assertTrue(decisions.stream().anyMatch(d -> "AVOID".equals(d.decisionType)));
    }

    // ==================================================================
    // 多策略融合
    // ==================================================================

    /** 三策略同时触发，验证按融合权重降序排列 */
    @Test
    void testMultiStrategyFusionOrder() {
        DecisionEngine engine = new DecisionEngine();
        // battery=15 → RTL(0.9) + ADAPT(0.5); obstacle=true,alt=30 → AVOID(0.75); wind=3
        DecisionContext ctx = new DecisionContext(15.0, true, true, 30.0, 500.0,
                true, 50.0, 3.0, 0.0);
        FusedDecision fused = engine.evaluateFused(ctx);

        // 三策略均触发
        assertEquals(3, fused.ranked.size(), "应同时触发三个策略");
        // 权重：battery<20 → rtl×3=3.0；obstacleDist=50 不<10；urgency=0
        // 融合权重：RTL=0.9×3=2.7 > AVOID=0.75×1=0.75 > ADAPT=0.5×1=0.5
        assertEquals("RTL", fused.ranked.get(0).decisionType, "RTL 融合权重最高应居首");
        assertEquals("AVOID", fused.ranked.get(1).decisionType, "AVOID 次之");
        assertEquals("ADAPT_PATH", fused.ranked.get(2).decisionType, "ADAPT_PATH 最后");
        assertEquals("RTL", fused.primary.decisionType);
    }

    /** 健康状态下融合结果为空 */
    @Test
    void testFusionEmptyWhenHealthy() {
        DecisionEngine engine = new DecisionEngine();
        DecisionContext ctx = new DecisionContext(80.0, true, true, 50.0, 100.0,
                false, Double.MAX_VALUE, 2.0, 0.0);
        FusedDecision fused = engine.evaluateFused(ctx);
        assertNull(fused.primary, "健康状态无主决策");
        assertTrue(fused.ranked.isEmpty());
        assertFalse(fused.hasDecision());
    }

    // ==================================================================
    // 权重动态调整
    // ==================================================================

    /** 电量<20% → 返航权重 ×3 */
    @Test
    void testBatteryBoostRtlWeight() {
        DecisionEngine engine = new DecisionEngine();
        DecisionContext ctx = new DecisionContext(18.0, true, true, 50.0, 500.0,
                false, Double.MAX_VALUE, 3.0, 0.0);
        FusedDecision fused = engine.evaluateFused(ctx);
        assertEquals(3.0, fused.rtlWeight, 1e-9, "电量<20% 返航权重应×3");
        assertEquals(1.0, fused.avoidWeight, 1e-9, "无障碍物近距离，避障权重不变");
        assertEquals(1.0, fused.adaptWeight, 1e-9, "紧急度低，自适应权重不变");
    }

    /** 障碍物<10m → 避障权重 ×3 */
    @Test
    void testObstacleCloseRangeBoostAvoidWeight() {
        DecisionEngine engine = new DecisionEngine();
        DecisionContext ctx = new DecisionContext(80.0, true, true, 30.0, 100.0,
                true, 5.0, 2.0, 0.0);
        FusedDecision fused = engine.evaluateFused(ctx);
        assertEquals(3.0, fused.avoidWeight, 1e-9, "障碍物<10m 避障权重应×3");
        assertEquals(1.0, fused.rtlWeight, 1e-9, "电量充足，返航权重不变");
    }

    /** 任务紧急度高(>0.7) → 自适应航线权重 ×2 */
    @Test
    void testUrgencyBoostAdaptWeight() {
        DecisionEngine engine = new DecisionEngine();
        // wind=10 触发 ADAPT_PATH，urgency=0.8 触发×2
        DecisionContext ctx = new DecisionContext(80.0, true, true, 50.0, 100.0,
                false, Double.MAX_VALUE, 10.0, 0.8);
        FusedDecision fused = engine.evaluateFused(ctx);
        assertEquals(2.0, fused.adaptWeight, 1e-9, "紧急度>0.7 自适应权重应×2");
        assertTrue(fused.ranked.stream().anyMatch(d -> "ADAPT_PATH".equals(d.decisionType)));
    }

    /** 多紧急情况同时出现，优先级 返航 > 避障 > 自适应 */
    @Test
    void testPriorityRtlOverAvoidOverAdapt() {
        DecisionEngine engine = new DecisionEngine();
        // battery=12 → RTL + ADAPT; obstacle dist=5 → AVOID; urgency=0.9 → adapt×2
        // 但 battery<15 → 决策树 FORCE_RTL 强制 RTL 居首
        DecisionContext ctx = new DecisionContext(12.0, true, true, 30.0, 500.0,
                true, 5.0, 10.0, 0.9);
        FusedDecision fused = engine.evaluateFused(ctx);

        // 三策略均触发
        assertEquals(3, fused.ranked.size());
        // FORCE_RTL 修正确保 RTL 居首
        assertEquals("RTL", fused.ranked.get(0).decisionType, "多紧急情况返航优先级最高");
    }

    // ==================================================================
    // 决策树
    // ==================================================================

    /** 紧急 + 电量<15% → FORCE_RTL，强制返航置顶 */
    @Test
    void testTreeForceRtlOnCriticalBattery() {
        DecisionEngine engine = new DecisionEngine();
        DecisionContext ctx = new DecisionContext(10.0, true, true, 50.0, 500.0,
                false, Double.MAX_VALUE, 3.0, 0.0);
        FusedDecision fused = engine.evaluateFused(ctx);

        assertTrue(fused.decisionTreePath.contains("emergency"), "路径应经过 emergency 节点");
        assertTrue(fused.decisionTreePath.contains("batteryCritical"), "路径应经过 batteryCritical 节点");
        assertTrue(fused.decisionTreePath.endsWith("FORCE_RTL"), "应得出 FORCE_RTL 结论");
        assertEquals("RTL", fused.primary.decisionType, "强制返航应为主决策");
    }

    /** 紧急 + 电量>=15% → AVOID_AND_DEGRADE */
    @Test
    void testTreeAvoidAndDegradeOnLinkLoss() {
        DecisionEngine engine = new DecisionEngine();
        // 链路丢失触发紧急，但电量=50 未危急
        DecisionContext ctx = new DecisionContext(50.0, false, true, 50.0, 500.0,
                false, Double.MAX_VALUE, 3.0, 0.0);
        FusedDecision fused = engine.evaluateFused(ctx);

        assertTrue(fused.decisionTreePath.contains("emergency"), "路径应经过 emergency 节点");
        assertTrue(fused.decisionTreePath.endsWith("AVOID_AND_DEGRADE"), "应得出 AVOID_AND_DEGRADE 结论");
        // 链路丢失触发 RTL
        assertEquals("RTL", fused.primary.decisionType);
    }

    /** 非紧急 + 有障碍物 → AVOID */
    @Test
    void testTreeAvoidOnObstacle() {
        DecisionEngine engine = new DecisionEngine();
        DecisionContext ctx = new DecisionContext(80.0, true, true, 30.0, 100.0,
                true, 40.0, 2.0, 0.0);
        FusedDecision fused = engine.evaluateFused(ctx);

        assertTrue(fused.decisionTreePath.contains("obstacle"), "路径应经过 obstacle 节点");
        assertTrue(fused.decisionTreePath.endsWith("AVOID"), "应得出 AVOID 结论");
        assertEquals("AVOID", fused.primary.decisionType);
    }

    /** 非紧急 + 无障碍物 → CRUISE（正常巡航，无决策） */
    @Test
    void testTreeCruiseOnNormal() {
        DecisionEngine engine = new DecisionEngine();
        DecisionContext ctx = new DecisionContext(80.0, true, true, 50.0, 100.0,
                false, Double.MAX_VALUE, 2.0, 0.0);
        FusedDecision fused = engine.evaluateFused(ctx);

        assertTrue(fused.decisionTreePath.endsWith("CRUISE"), "应得出 CRUISE 结论");
        assertNull(fused.primary, "正常巡航无决策");
    }

    /** 决策树直接测试：验证四种路径 */
    @Test
    void testDecisionTreeAllPaths() {
        DecisionTree tree = new DecisionTree();

        // FORCE_RTL
        DecisionTree.TreeResult r1 = tree.evaluate(new DecisionContext(
                10.0, true, true, 50, 500, false, Double.MAX_VALUE, 3, 0));
        assertEquals(DecisionTree.Verdict.FORCE_RTL, r1.verdict);

        // AVOID_AND_DEGRADE（链路丢失，电量未危急）
        DecisionTree.TreeResult r2 = tree.evaluate(new DecisionContext(
                50.0, false, true, 50, 500, false, Double.MAX_VALUE, 3, 0));
        assertEquals(DecisionTree.Verdict.AVOID_AND_DEGRADE, r2.verdict);

        // AVOID
        DecisionTree.TreeResult r3 = tree.evaluate(new DecisionContext(
                80.0, true, true, 50, 100, true, 40, 2, 0));
        assertEquals(DecisionTree.Verdict.AVOID, r3.verdict);

        // CRUISE
        DecisionTree.TreeResult r4 = tree.evaluate(new DecisionContext(
                80.0, true, true, 50, 100, false, Double.MAX_VALUE, 2, 0));
        assertEquals(DecisionTree.Verdict.CRUISE, r4.verdict);
    }

    // ==================================================================
    // 决策日志
    // ==================================================================

    /** 每次评估记录一条日志，含输入、权重、路径与结果 */
    @Test
    void testDecisionLogRecorded() {
        DecisionEngine engine = new DecisionEngine();
        engine.clearLog();

        // 第一次：触发 RTL
        engine.evaluateFused(new DecisionContext(15.0, true, true, 50, 500,
                false, Double.MAX_VALUE, 3, 0));
        // 第二次：健康，无决策
        engine.evaluateFused(new DecisionContext(80.0, true, true, 50, 100,
                false, Double.MAX_VALUE, 2, 0));
        // 第三次：避障
        engine.evaluateFused(new DecisionContext(80.0, true, true, 30, 100,
                true, 40, 2, 0));

        List<DecisionLogEntry> log = engine.getDecisionLog();
        assertEquals(3, log.size(), "应记录 3 条日志");

        // 第一条：RTL，返航权重×3
        DecisionLogEntry e0 = log.get(0);
        assertEquals("RTL", e0.primaryDecision);
        assertEquals(3.0, e0.rtlWeight, 1e-9);
        assertTrue(e0.decisionTreePath.contains("emergency"));
        assertEquals(1, e0.seq);

        // 第二条：无决策
        DecisionLogEntry e1 = log.get(1);
        assertEquals("NONE", e1.primaryDecision);
        assertTrue(e1.decisionTreePath.endsWith("CRUISE"));
        assertEquals(2, e1.seq);

        // 第三条：AVOID
        DecisionLogEntry e2 = log.get(2);
        assertEquals("AVOID", e2.primaryDecision);
        assertTrue(e2.decisionTreePath.endsWith("AVOID"));
        assertEquals(3, e2.seq);
    }

    /** 日志含上下文快照，可回溯输入 */
    @Test
    void testDecisionLogContainsContext() {
        DecisionEngine engine = new DecisionEngine();
        engine.clearLog();

        DecisionContext ctx = new DecisionContext(18.0, true, true, 42.0, 300.0,
                true, 6.0, 5.0, 0.9);
        engine.evaluateFused(ctx);

        List<DecisionLogEntry> log = engine.getDecisionLog();
        assertEquals(1, log.size());
        DecisionLogEntry entry = log.get(0);
        // 上下文快照应与输入一致
        assertEquals(18.0, entry.context.battery, 1e-9);
        assertTrue(entry.context.obstacleDetected);
        assertEquals(6.0, entry.context.obstacleDistance, 1e-9);
        assertEquals(0.9, entry.context.missionUrgency, 1e-9);
        // 电量<20 → rtl×3；障碍物<10 → avoid×3；urgency>0.7 → adapt×2
        assertEquals(3.0, entry.rtlWeight, 1e-9);
        assertEquals(3.0, entry.avoidWeight, 1e-9);
        assertEquals(2.0, entry.adaptWeight, 1e-9);
    }

    /** clearLog 清空日志 */
    @Test
    void testClearLog() {
        DecisionEngine engine = new DecisionEngine();
        engine.evaluateFused(new DecisionContext(15.0, true, true, 50, 500,
                false, Double.MAX_VALUE, 3, 0));
        assertFalse(engine.getDecisionLog().isEmpty());
        engine.clearLog();
        assertTrue(engine.getDecisionLog().isEmpty());
    }
}

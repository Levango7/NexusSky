package io.aerofleet.sim.ai;

/**
 * M11 决策树：用于复杂场景的决策路径选择。
 * <pre>
 * 根节点：是否有紧急情况？
 * ├─ 是 → 电量是否 &lt;15%？
 * │   ├─ 是 → 强制返航 (FORCE_RTL)
 * │   └─ 否 → 避障 + 降级 (AVOID_AND_DEGRADE)
 * └─ 否 → 是否有障碍物？
 *     ├─ 是 → 避障 (AVOID)
 *     └─ 否 → 正常巡航 (CRUISE)
 * </pre>
 * 决策树评估结果会与多策略融合结果结合：当判定为 FORCE_RTL 时，
 * 决策引擎将强制把返航决策置于列表首位，覆盖纯权重排序。
 */
public final class DecisionTree {

    /** 决策树叶子结论 */
    public enum Verdict {
        /** 强制返航（电量危急） */
        FORCE_RTL,
        /** 避障 + 降级（紧急但电量未危急） */
        AVOID_AND_DEGRADE,
        /** 避障（非紧急但有障碍物） */
        AVOID,
        /** 正常巡航 */
        CRUISE
    }

    /** 决策树评估结果 */
    public static final class TreeResult {
        /** 人类可读路径，如 root→emergency→batteryCritical→FORCE_RTL */
        public final String path;
        /** 叶子结论 */
        public final Verdict verdict;

        public TreeResult(String path, Verdict verdict) {
            this.path = path;
            this.verdict = verdict;
        }

        @Override
        public String toString() {
            return path + " ⇒ " + verdict;
        }
    }

    // ---- 节点抽象 ----
    private abstract static class Node {
        abstract TreeResult evaluate(DecisionContext ctx, StringBuilder path);
    }

    /** 判断节点：根据条件走左/右子树 */
    private static final class BranchNode extends Node {
        private final String label;
        private final java.util.function.Predicate<DecisionContext> condition;
        private final Node yes; // 条件成立
        private final Node no;  // 条件不成立

        BranchNode(String label, java.util.function.Predicate<DecisionContext> condition,
                   Node yes, Node no) {
            this.label = label;
            this.condition = condition;
            this.yes = yes;
            this.no = no;
        }

        @Override
        TreeResult evaluate(DecisionContext ctx, StringBuilder path) {
            path.append(label);
            boolean cond = condition.test(ctx);
            path.append(cond ? "=Y" : "=N").append("→");
            return (cond ? yes : no).evaluate(ctx, path);
        }
    }

    /** 叶子节点：返回结论 */
    private static final class LeafNode extends Node {
        private final Verdict verdict;

        LeafNode(Verdict verdict) {
            this.verdict = verdict;
        }

        @Override
        TreeResult evaluate(DecisionContext ctx, StringBuilder path) {
            path.append(verdict.name());
            return new TreeResult(path.toString(), verdict);
        }
    }

    // 构建决策树结构
    private final Node root;

    public DecisionTree() {
        // 叶子
        LeafNode forceRtl = new LeafNode(Verdict.FORCE_RTL);
        LeafNode avoidAndDegrade = new LeafNode(Verdict.AVOID_AND_DEGRADE);
        LeafNode avoid = new LeafNode(Verdict.AVOID);
        LeafNode cruise = new LeafNode(Verdict.CRUISE);

        // 第二层：紧急 → 电量是否<15%
        BranchNode batteryCheck = new BranchNode(
                "batteryCritical", DecisionContext::batteryCritical, forceRtl, avoidAndDegrade);

        // 第二层：非紧急 → 是否有障碍物
        BranchNode obstacleCheck = new BranchNode(
                "obstacle", ctx -> ctx.obstacleDetected, avoid, cruise);

        // 根：是否有紧急情况
        root = new BranchNode(
                "emergency", DecisionContext::hasEmergency, batteryCheck, obstacleCheck);
    }

    /** 评估决策树，返回路径与结论 */
    public TreeResult evaluate(DecisionContext ctx) {
        StringBuilder path = new StringBuilder("root→");
        return root.evaluate(ctx, path);
    }
}
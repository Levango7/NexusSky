package io.aerofleet.sim;

import io.aerofleet.sim.ai.DecisionEngine;
import io.aerofleet.sim.ai.ReturnToHomeStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 第六轮审查：M11「自主决策」与 M12「边缘融合」的真实状态守卫。
 *
 * <p><b>为什么需要这组测试</b>：这两块的算法实现是扎实的（A*、RRT、EKF、能耗模型、
 * 帧差+连通域），单元测试也全绿——但历史上它们<b>从未被生产代码调用</b>。
 * 真正生效的应急链路是 {@link FailsafeController}。一个只看测试通过率的读者
 * 会得出「自主决策已完成」的结论，与事实相反。
 *
 * <p>本测试把四件事钉死：
 * <ol>
 *   <li>failsafe 阈值只有一个真相源（此前同一参数有三个值：22/25/20）；</li>
 *   <li>DecisionEngine <b>已接线</b>：通过 AutonomyAdvisor 接入
 *       VirtualDrone.tickOnce（1Hz 评估 + STATUSTEXT 建议 + DECISION_EVENT(30051)
 *       下发；执行动作经 AutonomyExecutor 门控：默认关闭、failsafe 优先、
 *       仅 ARMED/MISSION 可执行）——这条接线不得静默消失；</li>
 *   <li>其余 ai 策略/路径规划器与 edge 包仍<b>无生产调用方</b>——这是
 *       <b>期望状态</b>，不是缺陷；一旦有人把它们接进飞行路径，本测试判红
 *       并提示同步 README 与产品文档的声称；</li>
 *   <li>drone-sim 不引入 ML 运行时依赖（「自主决策」是规则+排序+搜索）。</li>
 * </ol>
 */
class AiAutonomyWiringTest {

    // ------------------------------------------------------------------
    // 1. 阈值唯一真相源
    // ------------------------------------------------------------------

    @Test
    @DisplayName("FailsafeController 的默认阈值取自 FailsafeThresholds，不再就地写死")
    void failsafeUsesSharedThresholds() {
        FailsafeController fs = FailsafeController.defaults();
        assertEquals(FailsafeThresholds.BATTERY_CRIT_PCT, batteryCritOf(fs),
                "batteryCritPct 应等于 FailsafeThresholds.BATTERY_CRIT_PCT");
        assertEquals(FailsafeThresholds.LINK_LOSS_AFTER_MS, linkLossOf(fs),
                "linkLossAfterMs 应等于 FailsafeThresholds.LINK_LOSS_AFTER_MS");
    }

    @Test
    @DisplayName("ReturnToHomeStrategy 的低电量阈值与 FailsafeController 一致")
    void rtlStrategySharesBatteryThreshold() throws Exception {
        Field f = ReturnToHomeStrategy.class.getDeclaredField("BATTERY_THRESHOLD");
        f.setAccessible(true);
        double rtlThreshold = f.getDouble(null);
        assertEquals((double) FailsafeThresholds.BATTERY_CRIT_PCT, rtlThreshold,
                "低电量返航阈值曾分裂成 22(FailsafeController)/25(ReturnToHomeStrategy)/"
                        + "20(DecisionEngine 权重放大)。前两个必须同源；第三个语义不同，见下条。");
    }

    @Test
    @DisplayName("DecisionEngine 的权重放大阈值刻意不同于硬触发阈值，且不得反向收敛")
    void boostThresholdStaysBelowCritThreshold() throws Exception {
        Field f = DecisionEngine.class.getDeclaredField("BATTERY_BOOST_THRESHOLD");
        f.setAccessible(true);
        double boost = f.getDouble(null);
        assertTrue(boost < FailsafeThresholds.BATTERY_CRIT_PCT,
                "权重放大阈值(" + boost + ")应低于硬触发阈值("
                        + FailsafeThresholds.BATTERY_CRIT_PCT + ")："
                        + "两者语义不同，20 是「提前放大权重」、22 是「触发返航」。"
                        + "若这条失败，说明有人把二者强行相等了，[20,22) 的提前量会消失。");
    }

    // ------------------------------------------------------------------
    // 2 & 3. 接线状态（DecisionEngine 已接线为断言；其余未接线是期望状态，
    //        接线时判红并提示同步文档）
    // ------------------------------------------------------------------

    private static final String[] UNWIRED_CLASSES = {
            "io.aerofleet.sim.ai.ReturnToHomeStrategy",
            "io.aerofleet.sim.ai.AdaptivePathStrategy",
            "io.aerofleet.sim.ai.ObstacleAvoidanceStrategy",
            "io.aerofleet.sim.ai.AutoAvoidanceStrategy",
            "io.aerofleet.sim.ai.SwarmCoordinationStrategy",
            "io.aerofleet.sim.ai.EmergencyReturnStrategy",
            "io.aerofleet.sim.ai.PathPlanner",
            "io.aerofleet.sim.ai.DecisionTree",
            "io.aerofleet.sim.edge.SensorFusionEngine",
            "io.aerofleet.sim.edge.VideoStreamAnalyzer",
    };

    @Test
    @DisplayName("DecisionEngine 已通过 AutonomyAdvisor 接入 VirtualDrone（advisory + 门控执行），不得静默退线")
    void decisionEngineIsWired() {
        assertTrue(hasProductionReference("io.aerofleet.sim.ai.DecisionEngine"),
                "DecisionEngine 应被 ai 包外的生产代码引用——接线载体是"
                        + " AutonomyAdvisor（io.aerofleet.sim），由 VirtualDrone.tickOnce 以"
                        + " 1Hz 驱动：STATUSTEXT 建议 + DECISION_EVENT(30051) 下发，"
                        + " 执行动作经 AutonomyExecutor 门控（--autonomy-exec 开启、"
                        + " failsafe 优先、仅 ARMED/MISSION 可执行）。"
                        + "若这条失败，说明接线被拆掉了：请要么恢复接线，"
                        + "要么同步回滚 README/ROADMAP/competitive-analysis 中"
                        + " M11 的状态表述，并把本测试改回未接线断言。");
    }

    @Test
    @DisplayName("其余 ai 策略/规划器与 edge 包仍无生产调用方（期望状态；接线后本测试会判红提示更新文档）")
    void aiAndEdgePackagesAreNotYetWired() {
        List<String> nowWired = new ArrayList<>();
        for (String className : UNWIRED_CLASSES) {
            if (hasProductionReference(className)) {
                nowWired.add(simpleName(className));
            }
        }
        if (!nowWired.isEmpty()) {
            fail("这些类现在有了生产调用方，说明 M11/M12 已接入飞行路径："
                    + String.join(", ", nowWired)
                    + "。请同步更新：(1) README「已知边界」中「未接入生产路径」的表述；"
                    + "(2) docs/competitive-analysis.md 对应行的能力标注；"
                    + "(3) ROADMAP.md M11 的完成状态；"
                    + "(4) 本测试的 UNWIRED_CLASSES 列表。");
        }
    }

    @Test
    @DisplayName("未接线的类必须在自身 Javadoc 里写明这一点")
    void unwiredClassesDocumentTheirStatus() throws IOException {
        for (String className : UNWIRED_CLASSES) {
            Path p = sourcePathOf(className);
            if (!Files.exists(p)) {
                continue;   // 该类可能位于 drone-sim 之外，交给其它守卫覆盖
            }
            String src = Files.readString(p, StandardCharsets.UTF_8);
            // Javadoc 可能把一句话折行，匹配前先压掉空白，否则 "只被自己的\n * 单元测试引用" 匹配不上
            String flat = src.replaceAll("\\s+", "");
            boolean documented = flat.contains("未接入生产路径")
                    || flat.contains("尚未接入飞行路径")
                    || flat.contains("只被自己的单元测试引用");
            assertTrue(documented,
                    className + " 未接入生产路径这件事必须写进它的 Javadoc，"
                            + "否则读者看到「实现完整 + 测试全绿」会误以为它在运行");
        }
    }

    @Test
    @DisplayName("drone-sim 的 pom 不含任何 ML 运行时依赖")
    void noMlRuntimeDependency() throws IOException {
        Path pom = Path.of("pom.xml");
        String src = Files.readString(pom, StandardCharsets.UTF_8).toLowerCase();
        for (String artifact : Stream.of("onnxruntime", "tensorflow", "deeplearning4j",
                "pytorch", "ai.onnx", "opencv", "djl").toList()) {
            assertTrue(!src.contains("<artifactid>" + artifact),
                    "出现了 ML/CV 运行时依赖 " + artifact
                            + "——若确有意的，「自主决策/边缘 AI」的诚实性声明需要一并更新");
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static int batteryCritOf(FailsafeController fs) {
        try {
            Field f = FailsafeController.class.getDeclaredField("batteryCritPct");
            f.setAccessible(true);
            return f.getInt(fs);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("FailsafeController 字段改名了？", e);
        }
    }

    private static long linkLossOf(FailsafeController fs) {
        try {
            Field f = FailsafeController.class.getDeclaredField("linkLossAfterMs");
            f.setAccessible(true);
            return f.getLong(fs);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("FailsafeController 字段改名了？", e);
        }
    }

    /**
     * 该类是否被<b>本包之外</b>的生产代码引用。
     *
     * <p>刻意排除同包引用：{@code DecisionEngine} 引用 {@code ReturnToHomeStrategy}
     * 是包内实现细节，不构成「接入系统」。只有包外有消费者才算真正接线。
     */
    private static boolean hasProductionReference(String className) {
        String simple = simpleName(className);
        String ownPackage = className.substring(0, className.lastIndexOf('.'));
        String ownPackagePath = ownPackage.replace('.', '/');
        Path mainRoot = Path.of("src", "main", "java");
        if (!Files.isDirectory(mainRoot)) {
            mainRoot = Path.of("drone-sim", "src", "main", "java");
        }
        if (!Files.isDirectory(mainRoot)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(mainRoot)) {
            java.util.regex.Pattern word = java.util.regex.Pattern
                    .compile("\\b" + java.util.regex.Pattern.quote(simple) + "\\b");
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().replace('\\', '/').contains(ownPackagePath + "/"))
                    .filter(p -> !simpleName(p.getFileName().toString()).equals(simple))
                    .anyMatch(p -> {
                        try {
                            // 去掉块注释与行注释：Javadoc 里"提到"某个类不等于调用它
                            // （FailsafeThresholds 的说明文字就提到了 DecisionEngine）。
                            // 同时用词边界匹配，避免 PathPlanner 命中 CoveragePathPlanner。
                            String code = Files.readString(p, StandardCharsets.UTF_8)
                                    .replaceAll("(?s)/\\*.*?\\*/", " ")
                                    .replaceAll("(?m)//.*$", " ");
                            return word.matcher(code).find();
                        } catch (IOException e) {
                            return false;
                        }
                    });
        } catch (IOException e) {
            return false;
        }
    }

    private static Path sourcePathOf(String className) {
        String rel = className.replace('.', '/') + ".java";
        Path direct = Path.of("src", "main", "java", rel);
        return Files.exists(direct) ? direct : Path.of(rel);
    }

    private static String simpleName(String className) {
        int i = className.lastIndexOf('.');
        return i < 0 ? className : className.substring(i + 1);
    }
}

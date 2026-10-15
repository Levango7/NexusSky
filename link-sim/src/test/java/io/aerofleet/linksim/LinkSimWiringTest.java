package io.aerofleet.linksim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * link-sim 接线状态的显式账本。
 *
 * <p><b>为什么需要这组测试</b>：link-sim 的测试全绿，读者容易得出"链路仿真器
 * （跨网段中继、LoRa 网关、异构链路管理）都工作正常"的结论。但 `LinkSimMain`
 * ——本模块<b>唯一</b>的 {@code main()} 入口——只装配
 * {@code RelayNode} / {@code ImpairedEngine} 家族 / {@code LinkProfile} / {@code RelayConfig}。
 * 下面 {@link #UNWIRED_CLASSES} 里的 5 个类<b>一次都没被入口装配过</b>，
 * 它们身上却挂着 60 余处测试直接 {@code new}，合计覆盖 link-sim 107 个用例中的 78 个。
 *
 * <p>也就是说：这些用例验证的代码路径在产品里不存在。它们全绿，
 * 集体变红也不会改变任何生产行为。
 *
 * <p><b>本测试把"未接线"钉成期望状态</b>（同 {@code AiAutonomyWiringTest} 的做法），
 * 而不是让它沉默：
 * <ol>
 *   <li>入口唯一性 —— 守卫的前提。若将来新增第二个 {@code main()}，先改这里；</li>
 *   <li>{@link #UNWIRED_CLASSES} 里每个类都<b>不得</b>出现在 {@code LinkSimMain} 里。
 *       一旦有人把它接进飞行路径，本测试判红，并要求同步 README 的能力宣称、
 *       把该类移出本清单；</li>
 *   <li>正向对照 —— 已接线的类<b>必须</b>出现在入口里。
 *       没有这条，2 的判据有可能因为探测器写坏而恒真，变成又一处假绿；</li>
 *   <li>账本规模可见 —— 报出每个未接线类身上的测试引用数，
 *       让"这些绿灯值多少钱"随时可查。</li>
 * </ol>
 *
 * <p>同包说明：这几个类与 {@code LinkSimMain} <b>同包</b>，
 * 所以不能沿用 {@code AiAutonomyWiringTest} 的"包外引用"判据
 * （那样会把 {@code HeterogeneousLinkManager -> LinkBridgeNode} 这类包内实现细节
 * 误判为已接线）。link-sim 的真实边界是<b>入口装配</b>。
 */
@DisplayName("link-sim 接线账本：78 个绿灯挂在 5 个从未被入口装配的类上")
class LinkSimWiringTest {

    /** 模块唯一入口。判定"是否接线"的锚点。 */
    private static final String ENTRY_POINT = "LinkSimMain.java";

    /**
     * 未接入生产路径的类。这是<b>期望状态</b>，不是缺陷清单的永久冻结：
     * 一旦有人接线，本测试判红并要求同步文档声称。
     */
    private static final String[] UNWIRED_CLASSES = {
            "CrossSegmentForwarder",   // 跨网段 NAT 穿透（FR-19）
            "HeterogeneousLinkManager", // 异构链路管理
            "LinkBridgeNode",          // 桥接节点（仅被上一行构造）
            "LoRaRelayChannel",        // LoRa 告警中继
            "LinkQualityMonitor",      // 链路质量评分
    };

    /**
     * 已接入生产路径的类（正向对照），及其"接入形态"。
     *
     * <p>判据刻意比 {@link #UNWIRED_CLASSES} 更严 —— 名字出现过不算数，必须命中
     * 下面指定的接入形态。实测踩过的坑：最初用"名字出现即算接线"，把
     * {@code RelayNode r0 = new RelayNode(cfg); r0.run();} 判成已接线，
     * 可变异只改写写法、名字照样在场，断言恒真、形同虚设。
     *
     * <p>而"一律要求 {@code new Xxx(}"也不行：{@code ImpairmentEngine} 在入口里是
     * 通过 {@code profile.engine()} 拿到的（{@code LinkProfile} 才是真正的工厂），
     * 入口只写类型没有 {@code new}。所以这里按类分别指定它实际该出现的形态。
     */
    private enum Wiring {
        /** 入口必须出现 {@code new Xxx(}。 */
        NEW,
        /** 入口只需引用该类型（工厂在别处，例如 LinkProfile.engine()）。 */
        REFERENCED,
    }

    private static final Map<String, Wiring> WIRED_CLASSES = Map.of(
            "RelayNode", Wiring.NEW,
            "ImpairmentEngine", Wiring.REFERENCED,
            "EnvAwareImpairmentEngine", Wiring.NEW,
            "SecurityImpairmentEngine", Wiring.NEW);

    @Test
    @DisplayName("前提：LinkSimMain 是本模块唯一 main() 入口")
    void entryPointIsUnique() throws IOException {
        List<String> mains;
        try (Stream<Path> files = Files.walk(mainRoot())) {
            mains = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> stripComments(p).contains("static void main"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
        assertThat(mains)
                .as("link-sim 若出现第二个 main()，本测试的\"是否接线\"判据就要改成新的边界")
                .containsExactly(ENTRY_POINT);
    }

    @Test
    @DisplayName("未接线类不得出现在入口里 —— 一旦接线，本测试要求同步文档并移出清单")
    void unwiredClassesAreNotAssembledByEntryPoint() throws IOException {
        String entry = codeOf(ENTRY_POINT);
        for (String cn : UNWIRED_CLASSES) {
            assertThat(entryMentions(entry, cn))
                    .as("%s 已被 LinkSimMain 引用了 —— 它接线成功了%n%n"
                            + "请同步做三件事：%n"
                            + "  (1) 把 %s 从本测试的 UNWIRED_CLASSES 移出；%n"
                            + "  (2) 在其 Javadoc 里删掉「未接入生产路径」说明；%n"
                            + "  (3) 更新 README 里与之相关的能力宣称 —— "
                            + "在接线之前，那些宣称对 link-sim 并不成立。", cn, cn)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("正向对照：已接线类必须真的被入口接入（按各自的接入形态判定）")
    void wiredClassesAreInstantiatedByEntryPoint() throws IOException {
        String entry = codeOf(ENTRY_POINT);
        WIRED_CLASSES.forEach((cn, form) -> {
            boolean wired = form == Wiring.NEW
                    ? instantiatedIn(entry, cn)
                    : entryMentions(entry, cn);
            assertThat(wired)
                    .as("%s 应被 LinkSimMain 接入（形态=%s）", cn, form)
                    .isTrue();
        });
    }

    /**
     * 探测器自测：用合成输入验证判据真的会区分。
     *
     * <p><b>为什么必须有这条</b>：原先的"正向对照"是拿真实入口文件断言
     * {@code RelayNode} 出现。可变异测试立刻戳穿它 —— 把
     * {@code new RelayNode(cfg).run()} 改写成 {@code RelayNode r = new RelayNode(cfg); r.run();}
     * 之后，名字照样在场，断言恒绿。这条自测用合成字符串，正/负例都由本测试直接给出，
     * 因此判据一旦写坏（忘了剥注释、漏了词边界）立刻会红。
     */
    @Test
    @DisplayName("探测器自测：剥注释 + 词边界 + 实例化 三条判据都真的会区分")
    void detectorDistinguishesWiringFromMentions() {
        String mentionsOnly = """
                // 计划接入 LoRaRelayChannel（布控球告警回传）
                /* 将来可能用 CrossSegmentForwarder 做跨网段 */
                class Note { String s = "HeterogeneousLinkManager"; }
                """;

        assertThat(entryMentions(stripComments(mentionsOnly), "LoRaRelayChannel"))
                .as("注释里提到不算接线").isFalse();
        assertThat(entryMentions(stripComments(mentionsOnly), "CrossSegmentForwarder"))
                .as("块注释里提到不算接线").isFalse();
        // 注：stripComments 只剥注释、不动字符串字面量，所以这里用"仅实例化"判据来否定。
        assertThat(instantiatedIn(stripComments(mentionsOnly), "HeterogeneousLinkManager"))
                .as("字符串字面量里提到不算接线").isFalse();

        // 词边界：CrossSegmentForwarder 不应被 CrossSegmentForwarderX 命中，反之亦然
        assertThat(entryMentions("new CrossSegmentForwarderX();", "CrossSegmentForwarder"))
                .as("词边界：不应被更长的类名命中").isFalse();
        assertThat(entryMentions("new CrossSegmentForwarder();", "CrossSegmentForwarderX"))
                .as("词边界：不应命中更短的类名").isFalse();

        // 真正接线：实例化
        assertThat(instantiatedIn("new RelayNode(cfg).run();", "RelayNode")).isTrue();
        assertThat(instantiatedIn("new RelayNode(cfg);", "RelayNode")).isTrue();
        assertThat(instantiatedIn("RelayNode.class.getName();", "RelayNode"))
                .as("只引用 .class 不算实例化").isFalse();
        // 工厂模式：入口引用类型但自己 new 不到，也是合法接线（ImpairmentEngine 走这条路）
        assertThat(instantiatedIn("ImpairmentEngine up = profile.engine();", "ImpairmentEngine"))
                .as("入口拿工厂产物，不含 new，不应被 NEW 判据命中").isFalse();
        assertThat(entryMentions("ImpairmentEngine up = profile.engine();", "ImpairmentEngine"))
                .as("但 REFERENCED 判据应命中").isTrue();
    }

    @Test
    @DisplayName("账本可见：每个未接线类身上的测试引用数（这些绿灯目前不产生任何生产行为）")
    void unwiredDebtIsVisible() throws IOException {
        StringBuilder rows = new StringBuilder();
        for (String cn : UNWIRED_CLASSES) {
            int usages = countInTests(cn);
            rows.append(String.format("      %-26s %d 处测试引用%n", cn, usages));
            assertThat(usages)
                    .as("%s 既然未接线，测试里就不该还留着它 —— 若测试已删除，"
                            + "请把它从 UNWIRED_CLASSES 一并移除，别让清单变成僵尸条目。", cn)
                    .isGreaterThan(0);
        }
        System.out.println("[link-sim 接线账本] 入口未装配的类及其测试引用数：");
        System.out.print(rows);
        System.out.println("      这些用例全绿，但对应代码路径在产品里不存在。");
    }

    // ------------------------------------------------------------------
    // 探测器
    // ------------------------------------------------------------------

    private static Path mainRoot() {
        Path direct = Path.of("src", "main", "java", "io", "aerofleet", "linksim");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        return Path.of("link-sim", "src", "main", "java", "io", "aerofleet", "linksim");
    }

    private static Path testRoot() {
        Path direct = Path.of("src", "test", "java", "io", "aerofleet", "linksim");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        return Path.of("link-sim", "src", "test", "java", "io", "aerofleet", "linksim");
    }

    private static String codeOf(String fileName) {
        Path p = mainRoot().resolve(fileName);
        if (!Files.exists(p)) {
            throw new IllegalStateException("找不到 " + p + "（测试的工作目录不对？）");
        }
        return stripComments(p);
    }

    /** 去掉块注释与行注释：Javadoc 里"提到"某个类不等于装配它。 */
    private static String stripComments(Path p) {
        try {
            return stripComments(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 剥掉块注释与行注释。
     * <p>刻意<b>不</b>剥字符串字面量：探测器自测里
     * {@code String s = "HeterogeneousLinkManager"} 必须仍被识别为"非接线"，
     * 靠的正是不去动引号内内容。
     */
    private static String stripComments(String code) {
        return code.replaceAll("(?s)/\\*.*?\\*/", " ")
                   .replaceAll("(?m)//.*$", " ");
    }

    /** 词边界匹配，避免 LinkBridgeNode 命中 CrossSegmentForwarder 之类的误伤。 */
    private static boolean entryMentions(String entryCode, String className) {
        return Pattern.compile("\\b" + Pattern.quote(className) + "\\b")
                .matcher(entryCode).find();
    }

    /**
     * 是否以<b>实例化</b>形态出现（{@code new Xxx(}）。
     * <p>比 {@link #entryMentions} 更严：只被引用、被 import、被 {@code X.class} 指到，
     * 都不算接入生产路径 —— 那说明有人写过代码但没装配。
     */
    private static boolean instantiatedIn(String entryCode, String className) {
        return Pattern.compile("\\bnew\\s+" + Pattern.quote(className) + "\\s*\\(")
                .matcher(entryCode).find();
    }

    /** 该类名在测试源码里被引用的次数（去掉注释后统计）。 */
    private static int countInTests(String className) {
        Pattern word = Pattern.compile("\\b" + Pattern.quote(className) + "\\b");
        int n = 0;
        try (Stream<Path> files = Files.walk(testRoot())) {
            for (Path p : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                Matcher m0 = word.matcher(stripComments(p));
                while (m0.find()) {
                    n++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return n;
    }
}
package io.aerofleet.linksim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 损伤引擎的语义测试。
 *
 * <h2>为什么补这个类</h2>
 *
 * <p>link-sim 原有 8 个测试类，但<b>没有一个是针对 {@link ImpairmentEngine} 的</b>：
 * 其余测试断言的是各处理链路上算出来的<b>模型值</b>（{@code totalDelayMs == 100} 之类），
 * 而"这个引擎按它声明的参数到底怎么判每一包"从来没被直接钉过。
 *
 * <p>后果是实测出来才发现的一类偏差可以长期无人察觉。本机复测（Windows，
 * 见 {@code scripts/e2e-linksim-timing.py}）：
 *
 * <pre>
 *   画像        声明区间       空载 p50     有负载 p50   落在声明区间内
 *   wifi5       [2, 6]         5~6ms        12ms         86% -> 15%
 *   starlink    [35, 55]       46~47ms      53ms         81~85% -> 62%
 *   lte         [45, 80]       —            72ms         78%
 *   lte-edge    [120, 210]     —            176ms        93%
 * </pre>
 *
 * <p>注意超出量<b>不是恒定值</b>：第一版只跑了一轮（有负载那次），据此写成
 * "恒定 +10ms"是错的，复测后才发现空载时 wifi5 的 p50 只有 5~6ms。
 *
 * <p>那部分偏差出在 {@code LinkSimMain.wirePump} 用 {@code Thread.sleep} 实现延迟
 * —— 它的精度上限是操作系统的调度粒度（Windows ~15.6ms），不是本引擎的判决。
 * 两个问题应该分开看，所以这里<b>只钉判决语义</b>：{@code verdict()} 返回什么，
 * 必须与 {@link LinkProfile} 声明的含义一致。真实投递精度是另一个话题，
 * 由上面那个脚本量化。
 *
 * <h2>为什么可以不用等待真实时间</h2>
 *
 * <p>分区判定用的是 {@code t % cycle}，而 cycle 来自构造参数。这里用
 * 0.1s 级的周期构造，几秒内就能跨过十来个周期，既快又不靠运气。
 */
@DisplayName("ImpairmentEngine：判决语义与 LinkProfile 声明一致")
class ImpairmentEngineTest {

    /** 只保留延迟：其余损伤全关，便于单独验延迟。 */
    private static ImpairmentEngine delayOnly(double baseMs, double jitterMs) {
        // 桶/速率给到"紧密循环也耗不干"的量级：20k 次调用在毫秒级内完成，
        // 桶一旦偏小，丢包就会来自限速而不是延迟 —— 那是测量装置的问题
        return new ImpairmentEngine(baseMs, jitterMs,
                0, 0, 0, 0,                     // GE 全关
                1e9, 1e9,                       // 桶与速率都远超需求
                0, 0);                          // 无分区
    }

    // ===== 延迟 =====

    /**
     * 声明的延迟语义是 {@code base + uniform(0, jitter)}，所以返回值必须落在
     * <b>闭区间</b> {@code [base, base+jitter]} 内（实现里是强转 long 截断，
     * 所以上界实际到不了，用闭区间是更宽的、更安全的断言）。
     */
    @Test
    @DisplayName("延迟落在声明区间 [base, base+jitter] 内，且两端都被取到过")
    void delayWithinDeclaredBand() {
        double base = 35, jitter = 20;
        ImpairmentEngine e = delayOnly(base, jitter);

        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (int i = 0; i < 20_000; i++) {
            long d = e.verdict(64);
            assertThat(d).isGreaterThanOrEqualTo((long) base);
            assertThat(d).isLessThanOrEqualTo((long) (base + jitter));
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        // 20k 次采样下，均匀分布的两端应当在 1 以内被触到；
        // 若实现把 jitter 丢掉或写成固定值，这里会立刻红
        assertThat(min).isLessThanOrEqualTo((long) base + 1);
        assertThat(max).isGreaterThanOrEqualTo((long) (base + jitter) - 1);
    }

    @Test
    @DisplayName("jitter=0 时延迟恒定等于 base（不是'接近'）")
    void zeroJitterIsExact() {
        ImpairmentEngine e = delayOnly(120, 0);
        for (int i = 0; i < 1000; i++) {
            assertThat(e.verdict(64)).isEqualTo(120L);
        }
    }

    // ===== 周期分区 =====

    /**
     * 分区必须是**连续黑洞**：整段窗口内每一包都丢，而不是把丢包率摊成零星丢。
     *
     * <p>这条是实测出来才意识到要钉的：真实测里 starlink 60s 丢 101 包，
     * 其中两段 2.45s/2.40s 吃掉了 96%，真正零星的只有 4 包。
     * 若实现改成"按占空比随机丢"，总丢包率一样，但协议韧性测试的意义完全不同
     * —— 一个是在考"能不能扛住 2.5 秒全断"，另一个只是"偶尔掉一包"。
     *
     * <h3>占空比必须按时间统计，不能按调用次数</h3>
     *
     * <p>第一版这条测试按下"丢弃调用数/总调用数"算，量出 71% 而不是 50%，
     * 差点被判成实现缺陷。真实原因是<b>量具有偏</b>：分区判定在令牌桶与延迟计算
     * <b>之前</b>就 return，于是黑洞期每次调用更便宜、紧密循环跑得更快、
     * 落进去的调用次数更多 —— 按调用次数统计会把 50% 的时间放大成 62% 的调用。
     * 按 1ms 时间桶取多数状态后实测 49.8%，引擎本身没问题。
     */
    @Test
    @DisplayName("分区是连续黑洞：时间占空比≈50%，且丢失段数≈周期数")
    void partitionIsContiguousBlackhole() {
        // cycle = 0.2s（好 0.1s + 黑洞 0.1s）→ 时间占空比 50%
        ImpairmentEngine e = new ImpairmentEngine(0, 0,
                0, 0, 0, 0,
                1e9, 1e9,        // 不限速：丢包只能来自分区，否则测的不是分区
                0.1, 0.1);

        long t0 = System.currentTimeMillis();
        java.util.TreeMap<Long, int[]> buckets = new java.util.TreeMap<>();
        java.util.List<Boolean> seq = new ArrayList<>();
        long end = t0 + 2000;
        while (System.currentTimeMillis() < end) {
            long r = e.verdict(64);
            boolean lost = r < 0;
            seq.add(lost);
            int[] b = buckets.computeIfAbsent(System.currentTimeMillis() - t0, k -> new int[2]);
            b[1]++;
            if (lost) b[0]++;
        }

        assertThat(seq.size()).as("2 秒紧密循环应当产生足够样本").isGreaterThan(1000);

        // 1) 时间占空比：每个 1ms 桶取多数状态
        long dropBuckets = 0, totBuckets = 0;
        for (int[] b : buckets.values()) {
            totBuckets++;
            if (b[0] * 2 > b[1]) dropBuckets++;
        }
        double duty = 100.0 * dropBuckets / totBuckets;
        assertThat(duty).as("黑洞时间占比（实测基准 49.8%%）").isBetween(40.0, 60.0);

        // 2) 连续性：连续丢失段数 ≈ 周期数（2s / 0.2s = 10）
        //    若实现是零星随机丢，段数会是几十万量级，这条必红。
        int runs = 0;
        for (int i = 0; i < seq.size(); i++) {
            if (seq.get(i) && (i == 0 || !seq.get(i - 1))) {
                runs++;
            }
        }
        assertThat(runs).as("连续丢失段数应≈周期数(10)，而不是被摊开").isBetween(4, 22);
    }

    @Test
    @DisplayName("分区的丢包返回 -1（丢弃），不是 0（零延迟放行）")
    void partitionReturnsMinusOne() {
        ImpairmentEngine e = new ImpairmentEngine(0, 0,
                0, 0, 0, 0, 1e9, 1e9, 0.05, 0.15);
        boolean sawDrop = false, sawForward = false;
        long end = System.currentTimeMillis() + 1200;
        while (System.currentTimeMillis() < end) {
            long v = e.verdict(64);
            if (v < 0) sawDrop = true;
            else sawForward = true;
        }
        assertThat(sawDrop).isTrue();
        assertThat(sawForward).isTrue();
    }

    // ===== Gilbert-Elliot =====

    /**
     * GE 的"有记忆"是它存在的理由（独立随机丢包测不出重传韧性）。
     * 把 pGoodToBad 设为 1：第一包之后立即进坏态，而 pBadDrop=1 则此后全丢。
     */
    @Test
    @DisplayName("GE 坏态全丢 + 好→坏转移率为 1 时，进入坏态后不再放行")
    void geBadStateIsStickyAndTotal() {
        ImpairmentEngine e = new ImpairmentEngine(0, 0,
                0.0, 1.0, 1.0, 0.0,     // 好态不丢、坏态全丢、好→坏必定、坏→好永不
                1e9, 1e9, 0, 0);

        // 第一包：判完即转移进坏态
        e.verdict(64);
        for (int i = 0; i < 200; i++) {
            assertThat(e.verdict(64)).as("进入坏态后第 %d 包", i).isEqualTo(-1L);
        }
    }

    @Test
    @DisplayName("GE 好态丢包率为 0 时全部放行（对照）")
    void geGoodStateForwardsEverything() {
        ImpairmentEngine e = new ImpairmentEngine(7, 0,
                0.0, 0.0, 0.0, 1.0,
                1e9, 1e9, 0, 0);
        for (int i = 0; i < 500; i++) {
            assertThat(e.verdict(64)).isEqualTo(7L);
        }
    }

    // ===== 令牌桶 =====

    /**
     * {@code rateBytesPerSec == 0} 时令牌桶<b>整体跳过</b> —— 语义是"不限速"，
     * 不是"不补充所以很快耗尽"。
     *
     * <p>这条容易读错（第一版测试就按"不补充"写了，结果绿变红才发现）。
     * 各画像都给了正的速率值，所以 0 这条路径平时不走，但读代码的人会踩。
     */
    @Test
    @DisplayName("令牌桶：速率为 0 = 不限速（不是'不补充'）")
    void zeroRateMeansNoLimit() {
        ImpairmentEngine e = new ImpairmentEngine(0, 0,
                0, 0, 0, 0,
                64, 0,          // 桶只有 64B，速率为 0
                0, 0);
        for (int i = 0; i < 500; i++) {
            assertThat(e.verdict(64))
                    .as("速率为 0 表示不限速，第 %d 包不该因桶被丢", i)
                    .isNotEqualTo(-1L);
        }
    }

    /**
     * 桶空即丢（返回 -1），而不是排一个无限长的队 —— 无线链路拥塞的真实行为。
     * 构造：桶 128B、速率 1B/s（补充可忽略）→ 只能放行前两包，之后全丢。
     */
    @Test
    @DisplayName("令牌桶：桶空即丢，不排队")
    void tokenBucketDropsWhenEmpty() {
        ImpairmentEngine e = new ImpairmentEngine(0, 0,
                0, 0, 0, 0,
                128, 1,          // 桶 128B、速率 1B/s
                0, 0);

        assertThat(e.verdict(64)).isEqualTo(0L);   // 128 -> 64
        assertThat(e.verdict(64)).isEqualTo(0L);   // 64  -> 0
        for (int i = 0; i < 10; i++) {
            assertThat(e.verdict(64)).as("桶空后第 %d 包", i).isEqualTo(-1L);
        }
    }

    /** 速率充足时不应因为桶丢包 —— 防止"把限速写死成丢包"。 */
    @Test
    @DisplayName("令牌桶：速率充足时长时间不丢")
    void tokenBucketDoesNotDropWhenRateSufficient() {
        ImpairmentEngine e = new ImpairmentEngine(0, 0,
                0, 0, 0, 0,
                1e9, 1e9, 0, 0);
        for (int i = 0; i < 2000; i++) {
            assertThat(e.verdict(64)).isNotEqualTo(-1L);
        }
    }

    // ===== 与 LinkProfile 的常量对齐 =====

    /**
     * 画像常量本身也要有人守：改错了数字（比如把 starlink 的分区时长从 2.5
     * 写成 0.25）不会有任何编译错误，但会让全部 starlink 韧性测试的意义变掉。
     * 这里只钉**语义与量级**，不钉具体数字 —— 数字是产品调参，不是契约。
     */
    @Test
    @DisplayName("LinkProfile：lan 干净、starlink 分区为秒级、lte-edge 延迟量级正确")
    void profileConstantsHaveSaneSemantics() {
        assertThat(LinkProfile.LAN.pGoodDrop).isZero();
        assertThat(LinkProfile.LAN.pBadDrop).isZero();
        assertThat(LinkProfile.LAN.partitionDownSec).isZero();
        assertThat(LinkProfile.LAN.delayMs).isZero();

        // starlink：注释声明"周期性 2-3s 中断"，所以黑洞时长必须落在秒级
        assertThat(LinkProfile.STARLINK.partitionDownSec).isBetween(1.0, 5.0);
        assertThat(LinkProfile.STARLINK.partitionUpSec)
                .as("黑洞占空比应当在 5%~20% 之间（实测长期均值≈9%）")
                .isBetween(LinkProfile.STARLINK.partitionDownSec * 4,
                           LinkProfile.STARLINK.partitionDownSec * 20);

        // lte-edge 是"弱信号小区边缘"，延迟必须显著高于 lte
        assertThat(LinkProfile.LTE_EDGE.delayMs).isGreaterThan(LinkProfile.LTE.delayMs);
        assertThat(LinkProfile.LTE_EDGE.pGoodDrop).isGreaterThan(LinkProfile.LTE.pGoodDrop);

        // 安全画像不叠加基础链路损伤（损伤由签名层施加）
        assertThat(LinkProfile.SIGNING_TAMPER.delayMs).isZero();
        assertThat(LinkProfile.SIGNING_TAMPER.pGoodDrop).isZero();
        assertThat(LinkProfile.SIGNING_UNSIGNED.pGoodDrop).isZero();
    }
}
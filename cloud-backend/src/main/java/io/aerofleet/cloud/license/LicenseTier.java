package io.aerofleet.cloud.license;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 商业授权档位定义（全仓唯一真相源）。
 * <p>
 * <b>为什么需要它</b>：2026-10-05 核查发现，"三档定价"与"模块授权"之间**没有
 * 任何机器可校验的绑定**——定价文档写"基础版 = 20-50 万/年"，但代码里
 * {@link LicenseService#ALL_MODULES} 只是一个扁平集合，签发时由调用方手工传
 * {@code Set<String> modules}。结果是：
 * <ul>
 *   <li>签一份"基础版"授权，若签发脚本手滑多传一个 {@code emergency}，
 *       客户就以基础版价格拿到应急模块——<b>定价技术上不可执行</b>；</li>
 *   <li>新增模块时，三档的档位边界没有任何地方需要同步更新，
 *       漂移无人发现。</li>
 * </ul>
 * 本类把"档位 → 模块集合"固化成常量表，并提供签发侧的档位化入口，
 * 使"卖了哪一档"与"客户能开哪些模块"在代码层强一致。
 * <p>
 * <b>与定价文档的对应关系</b>（{@code docs/PRODUCT-POSITIONING.md} §5.1）：
 * <pre>
 *   基础版 (BASIC)     20–50  万/年  → core + fleet
 *   应急版 (EMERGENCY) 50–100 万/年  → core + fleet + emergency
 *   完整版 (FULL)      100–200 万/年 → core + fleet + emergency + network + advanced
 * </pre>
 * <p>
 * <b>维护契约</b>：新增模块（{@link LicenseService#ALL_MODULES} 扩容）时，
 * 必须同时决定它属于哪些档位；{@code LicenseTierTest} 会断言每个模块
 * 至少被一个档位覆盖，漏了即红。
 * <p>
 * <b>设计取舍</b>：为什么用"累进档位"（后档包含前档全部模块）而非"互斥组合"？
 * 因为定价文档明确写"应急版 = 基础版 + ..."、"完整版 = 全模块"——是累进语义。
 * 若将来要支持"只买 network 不买 emergency"这类自由组合，应在签发侧引入
 * "自定义档位"入口（见 {@link #custom}），而不是修改本类的累进定义。
 *
 * @author AeroFleet Cloud Team
 */
public final class LicenseTier {

    /** 基础版：飞起来所需的核心能力 + 机队作业。 */
    public static final String BASIC = "basic";

    /** 应急版：基础版 + 应急指挥闭环 + 安防联动。 */
    public static final String EMERGENCY = "emergency";

    /** 完整版：全部模块。 */
    public static final String FULL = "full";

    /**
     * 档位 → 模块集合（累进）。key 为档位常量，value 为该档包含的模块。
     * <p>
     * 用 {@link LinkedHashSet} 保证迭代顺序稳定（便于日志与测试断言可读）。
     */
    private static final Map<String, Set<String>> TIER_TO_MODULES = build();

    /**
     * 档位 → 中文显示名，用于日志 / 签发记录 / 对外 API。
     */
    private static final Map<String, String> TIER_DISPLAY_NAME = Map.of(
            BASIC, "基础版",
            EMERGENCY, "应急版",
            FULL, "完整版"
    );

    private LicenseTier() {
    }

    private static Map<String, Set<String>> build() {
        Set<String> basic = new LinkedHashSet<>(List.of("core", "fleet"));
        Set<String> emergency = new LinkedHashSet<>(basic);
        emergency.add("emergency");
        Set<String> full = new LinkedHashSet<>(emergency);
        full.add("network");
        full.add("advanced");
        return Map.of(
                BASIC, Set.copyOf(basic),
                EMERGENCY, Set.copyOf(emergency),
                FULL, Set.copyOf(full)
        );
    }

    /** 全部合法档位名。 */
    public static Set<String> allTiers() {
        return TIER_TO_MODULES.keySet();
    }

    /** 档位是否为已知档位。 */
    public static boolean isKnownTier(String tier) {
        return tier != null && TIER_TO_MODULES.containsKey(tier);
    }

    /**
     * 取某档位包含的模块集合（不可变副本）。
     *
     * @param tier 档位名（{@link #BASIC} / {@link #EMERGENCY} / {@link #FULL}）
     * @return 该档模块集合；未知档位返回 {@code null}（由调用方决定如何拒绝）
     */
    public static Set<String> modulesOf(String tier) {
        Set<String> got = TIER_TO_MODULES.get(tier);
        return got == null ? null : Set.copyOf(got);
    }

    /** 档位中文名；未知档位原样返回。 */
    public static String displayName(String tier) {
        return TIER_DISPLAY_NAME.getOrDefault(tier, tier);
    }

    /**
     * 校验一组模块是否**恰好等于**某档位的模块集合。
     * <p>
     * 用途：签发后自检，防止"签出的授权与声明档位不符"。
     *
     * @return 相符返回 {@code null}；不符返回人类可读的差异描述
     */
    public static String mismatchOf(String tier, Set<String> actualModules) {
        Set<String> expected = modulesOf(tier);
        if (expected == null) {
            return "未知档位: " + tier;
        }
        if (actualModules == null) {
            return "档位 " + tier + " 期望模块 " + expected + "，实际为 null";
        }
        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(actualModules);
        Set<String> extra = new LinkedHashSet<>(actualModules);
        extra.removeAll(expected);
        if (missing.isEmpty() && extra.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("档位 ").append(tier).append(" 与模块集合不符");
        if (!missing.isEmpty()) {
            sb.append("；缺少 ").append(missing);
        }
        if (!extra.isEmpty()) {
            sb.append("；多出 ").append(extra);
        }
        return sb.toString();
    }

    /**
     * 推断一组模块**属于的最高档位**（用于把已有授权归类）。
     * <p>
     * 判定：从高档到低档，返回第一个"该档模块全部被包含"的档位。
     * 不匹配任何档位（如自定义组合）返回 {@code null}。
     */
    public static String inferTier(Set<String> modules) {
        if (modules == null) {
            return null;
        }
        for (String tier : List.of(FULL, EMERGENCY, BASIC)) {
            if (modules.containsAll(TIER_TO_MODULES.get(tier))) {
                return tier;
            }
        }
        return null;
    }
}

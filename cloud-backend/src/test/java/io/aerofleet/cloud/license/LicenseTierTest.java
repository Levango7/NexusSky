package io.aerofleet.cloud.license;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商业档位 ↔ 模块绑定守卫（2026-10-05 建立）。
 * <p>
 * <b>背景</b>：定价文档（{@code docs/PRODUCT-POSITIONING.md} §5.1）定义三档：
 * 基础版 20–50 万 / 应急版 50–100 万 / 完整版 100–200 万（每年）。
 * 但在此之前，"档位"只存在于文档，代码里签发授权时由调用方手传模块集合——
 * 两者可以不一致且无人察觉，等于<b>定价技术上不可执行</b>。
 * {@link LicenseTier} 把绑定固化，本测试是它的守卫。
 * <p>
 * 本测试做四件事：
 * <ol>
 *   <li><b>累进结构断言</b>：应急版 ⊃ 基础版，完整版 ⊃ 应急版（与定价文档
 *       "应急版 = 基础版 + ..."的累进语义一致）。</li>
 *   <li><b>全覆盖断言</b>：每个模块（{@link LicenseService#ALL_MODULES}）
 *       必须至少被一个档位覆盖——新增模块却忘了归入档位 → 本测试红。</li>
 *   <li><b>无越界断言</b>：档位引用的模块名必须都在 ALL_MODULES 内
 *       （防止档位引用了已废弃/拼错的模块名）。</li>
 *   <li><b>签发自检断言</b>：{@link LicenseTier#mismatchOf} 能识别
 *       "档位与模块集合不符"，是签发侧拒绝漂移授权的依据。</li>
 * </ol>
 */
@DisplayName("License 商业档位：档位与模块的绑定必须强一致")
class LicenseTierTest {

    @Test
    @DisplayName("累进结构：应急版 ⊇ 基础版，完整版 ⊇ 应急版")
    void tiersAreCumulative() {
        Set<String> basic = LicenseTier.modulesOf(LicenseTier.BASIC);
        Set<String> emergency = LicenseTier.modulesOf(LicenseTier.EMERGENCY);
        Set<String> full = LicenseTier.modulesOf(LicenseTier.FULL);

        assertThat(basic).as("基础版模块不得为空").isNotEmpty();

        assertThat(emergency)
                .as("应急版应包含基础版全部模块（定价文档：逃生版 = 基础版 + ...）")
                .containsAll(basic);
        assertThat(emergency)
                .as("应急版应在基础版之外新增模块，否则档位无差异、无法定价")
                .isNotEqualTo(basic);

        assertThat(full)
                .as("完整版应包含应急版全部模块（定价文档：完整版 = 全模块）")
                .containsAll(emergency);
        assertThat(full)
                .as("完整版应等于 ALL_MODULES —— 完整版就是全模块，不得有遗漏")
                .containsExactlyInAnyOrderElementsOf(LicenseService.ALL_MODULES);
    }

    @Test
    @DisplayName("全覆盖：每个已定义模块至少属于一个档位（新增模块忘记归档 → 红）")
    void everyModuleIsCoveredBySomeTier() {
        Set<String> covered = new LinkedHashSet<>();
        for (String tier : LicenseTier.allTiers()) {
            covered.addAll(LicenseTier.modulesOf(tier));
        }
        assertThat(covered)
                .as("以下模块不属于任何档位 —— 它们永远不会被签发给客户，"
                        + "请把它们归入 LicenseTier 的某个档位：%s",
                        minus(LicenseService.ALL_MODULES, covered))
                .containsAll(LicenseService.ALL_MODULES);
    }

    @Test
    @DisplayName("无越界：档位引用的模块名必须都在 ALL_MODULES 内")
    void tierModulesAreWithinAllModules() {
        for (String tier : LicenseTier.allTiers()) {
            assertThat(LicenseTier.modulesOf(tier))
                    .as("档位 %s 引用了未定义模块名（拼写错误或已废弃）", tier)
                    .isSubsetOf(LicenseService.ALL_MODULES);
        }
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            // 基础版 = GCS OEM + 机队作业 + M5 mesh + M9 编排（§5.1 承诺项）
            "basic,     core",
            "basic,     fleet",
            "basic,     mesh",
            "basic,     orch",
            // 应急版 = 基础版 + 4a 空地一体化 + ONVIF 安防
            "emergency, core",
            "emergency, fleet",
            "emergency, mesh",
            "emergency, orch",
            "emergency, emergency",
            // 完整版 = 全模块
            "full,      core",
            "full,      fleet",
            "full,      mesh",
            "full,      orch",
            "full,      emergency",
            "full,      network",
            "full,      advanced",
    })
    @DisplayName("档位内容抽样：与定价文档 §5.1 的档位描述逐字对齐")
    void tierContentFollowsPricingDoc(String tier, String module) {
        assertThat(LicenseTier.modulesOf(tier))
                .as("档位 %s 应包含模块 %s（依据 PRODUCT-POSITIONING §5.1）", tier, module)
                .contains(module);
    }

    @Test
    @DisplayName("档位差异：基础版含 mesh/orch 但不含 emergency/network/advanced；应急版不含 network/advanced")
    void tiersAreMutuallyDistinguishable() {
        assertThat(LicenseTier.modulesOf(LicenseTier.BASIC))
                .as("基础版（20-50 万）必须含 §5.1 承诺的 M5 mesh 与 M9 编排")
                .contains("mesh", "orch");
        assertThat(LicenseTier.modulesOf(LicenseTier.BASIC))
                .as("基础版不应包含应急版付费点（4a 空地一体化 / ONVIF 安防）"
                        + "与完整版付费点（基站卫星 / 孪生 AI）")
                .doesNotContain("emergency", "network", "advanced");
        assertThat(LicenseTier.modulesOf(LicenseTier.EMERGENCY))
                .as("应急版（50-100 万）应含 4a 空地一体化与 ONVIF 安防")
                .contains("emergency");
        assertThat(LicenseTier.modulesOf(LicenseTier.EMERGENCY))
                .as("应急版不应包含完整版付费点 network/advanced")
                .doesNotContain("network", "advanced");
    }

    @Test
    @DisplayName("未知档位：modulesOf 返回 null，isKnownTier 返回 false")
    void unknownTierIsRejected() {
        assertThat(LicenseTier.isKnownTier("basic")).isTrue();
        assertThat(LicenseTier.isKnownTier("platinum")).isFalse();
        assertThat(LicenseTier.isKnownTier(null)).isFalse();
        assertThat(LicenseTier.modulesOf("platinum"))
                .as("未知档位必须返回 null，由调用方拒绝签发（fail-closed）")
                .isNull();
    }

    @Test
    @DisplayName("签发自检：mismatchOf 能识别多签/少签的模块")
    void mismatchDetectionCatchesDrift() {
        // 相符 → null
        assertThat(LicenseTier.mismatchOf(LicenseTier.BASIC, LicenseTier.modulesOf(LicenseTier.BASIC)))
                .as("档位与其自身模块集合必然相符")
                .isNull();

        // 多签：基础版却带了 emergency
        Set<String> tooMany = new LinkedHashSet<>(LicenseTier.modulesOf(LicenseTier.BASIC));
        tooMany.add("emergency");
        assertThat(LicenseTier.mismatchOf(LicenseTier.BASIC, tooMany))
                .as("基础版多出 emergency 必须被识别 —— 这正是'低价拿高配'的漏洞形态")
                .isNotNull()
                .contains("多出");

        // 少签：完整版却缺 advanced
        Set<String> tooFew = new LinkedHashSet<>(LicenseTier.modulesOf(LicenseTier.FULL));
        tooFew.remove("advanced");
        assertThat(LicenseTier.mismatchOf(LicenseTier.FULL, tooFew))
                .as("完整版缺少 advanced 必须被识别")
                .isNotNull()
                .contains("缺少");

        // 未知档位
        assertThat(LicenseTier.mismatchOf("nonexistent", Set.of("core")))
                .as("未知档位必须报错而非静默通过")
                .isNotNull()
                .contains("未知档位");
    }

    @Test
    @DisplayName("档位推断：把已有模块集合归类到最高匹配档位")
    void inferTierFromModules() {
        assertThat(LicenseTier.inferTier(LicenseTier.modulesOf(LicenseTier.BASIC)))
                .isEqualTo(LicenseTier.BASIC);
        assertThat(LicenseTier.inferTier(LicenseTier.modulesOf(LicenseTier.EMERGENCY)))
                .isEqualTo(LicenseTier.EMERGENCY);
        assertThat(LicenseTier.inferTier(LicenseTier.modulesOf(LicenseTier.FULL)))
                .isEqualTo(LicenseTier.FULL);

        // 超集（比完整版还多）应推断为完整版（最高档）
        Set<String> superset = new LinkedHashSet<>(LicenseTier.modulesOf(LicenseTier.FULL));
        superset.add("future-module");
        assertThat(LicenseTier.inferTier(superset))
                .as("超集应归类到最高档，而非返回 null")
                .isEqualTo(LicenseTier.FULL);

        // 自定义组合（不满足任何档位）：仅 core
        assertThat(LicenseTier.inferTier(Set.of("core")))
                .as("不满足任何档位全部模块的集合，应返回 null（自定义授权）")
                .isNull();

        assertThat(LicenseTier.inferTier(null)).isNull();
    }

    @Test
    @DisplayName("档位显示名：三档中文名正确，未知档位原样返回")
    void displayNames() {
        assertThat(LicenseTier.displayName(LicenseTier.BASIC)).isEqualTo("基础版");
        assertThat(LicenseTier.displayName(LicenseTier.EMERGENCY)).isEqualTo("应急版");
        assertThat(LicenseTier.displayName(LicenseTier.FULL)).isEqualTo("完整版");
        assertThat(LicenseTier.displayName("custom-x")).isEqualTo("custom-x");
    }

    @Test
    @DisplayName("档位总数基线：合法档位为 3（防被误删/误改）")
    void tierCountBaseline() {
        assertThat(LicenseTier.allTiers())
                .as("档位数变化意味着定价方案变更 —— 应同步 PRODUCT-POSITIONING §5.1")
                .hasSize(3)
                .containsExactlyInAnyOrder(LicenseTier.BASIC, LicenseTier.EMERGENCY, LicenseTier.FULL);
    }

    @Test
    @DisplayName("不可变性：modulesOf 返回的集合不可被外部修改")
    void modulesOfIsImmutable() {
        Set<String> got = LicenseTier.modulesOf(LicenseTier.BASIC);
        assertThat(got).isNotNull();
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> got.add("emergency"))
                .as("modulesOf 必须返回不可变副本 —— 否则调用方可篡改档位定义")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ===== 辅助 =====

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> r = new LinkedHashSet<>(a);
        r.removeAll(b);
        return r;
    }
}

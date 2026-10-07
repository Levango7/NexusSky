package io.aerofleet.cloud.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块拆分迁移的兼容性与语义守卫（2026-10-07 定价裁决配套）。
 *
 * <p><b>背景</b>：为让"基础版扎实"（对齐定价文档 §5.1 承诺的 M5 mesh + M9 编排 + GCS OEM），
 * {@link LicenseService#ALL_MODULES} 由 5 个扩为 7 个：
 * {@code network} 拆出 {@code mesh}，{@code emergency} 拆出 {@code orch}。
 *
 * <p><b>为什么这类守卫不可省</b>：模块名进签名载荷（{@link LicenseIssuer}），
 * 改名即换授权语义。三种失效形态都"没有任何信号会变红"：
 * <ol>
 *   <li><b>旧授权静默失效</b>：拆分前签发的授权含 {@code network}/{@code emergency} 但无
 *       {@code mesh}/{@code orch}，升级后既不等于任何档位（启动期被拒），也可能因
 *       {@code hasModule} 语义被误判。本测试把"旧集合不再是任一档位"钉死，
 *       迫使升级走显式重签发，而不是让存量客户在升级当天起不来。</li>
 *   <li><b>拆分做一半</b>：mesh 留在 network、orch 留在 emergency，基础版拿不到
 *       §5.1 承诺的能力 —— 定价与交付再次错位，且没有任何测试会红。</li>
 *   <li><b>拆分过头</b>：把 5G 基站 / 卫星中继 / ONVIF 安防也挪进基础版，
 *       应急版与基础版同集合 ⇒ 档位梯子塌掉、无法定价。</li>
 * </ol>
 */
@DisplayName("模块拆分（network→mesh、emergency→orch）：授权语义与定价边界必须守住")
class LicenseModuleSplitMigrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 拆分前的 5 模块集合。 */
    private static final Set<String> LEGACY_MODULES =
            Set.of("core", "fleet", "emergency", "network", "advanced");

    /** 拆分后的 7 模块集合。 */
    private static final Set<String> CURRENT_MODULES = LicenseService.ALL_MODULES;

    @Test
    @DisplayName("ALL_MODULES 由 5 扩为 7：mesh / orch 是新增，其余 5 个名字保持不变")
    void moduleSetGrewByExactlyTwo() {
        assertThat(CURRENT_MODULES)
                .as("模块集合应为 7 个（2026-10-07 拆分后）")
                .hasSize(7);
        assertThat(CURRENT_MODULES)
                .as("新拆出的 mesh（M5 自愈组网）与 orch（M9 应急编排）必须在集合内")
                .contains("mesh", "orch");
        assertThat(CURRENT_MODULES)
                .as("原有 5 个模块名不得被改名 —— 改名会让存量授权静默换语义")
                .containsAll(LEGACY_MODULES);
    }

    @Test
    @DisplayName("旧授权集合不再对应任何档位 ⇒ 升级需显式重签发，不得静默沿用")
    void legacyModuleSetMatchesNoTier() {
        // 这是本次拆分**故意**造成的不兼容：存量授权必须重签发。
        // 钉死它的意义在于 —— 若将来有人"贴心地"给旧集合加一条兼容映射，
        // 本测试会红并追问：旧集合里 network 含 mesh、emergency 含 M9 编排，
        // 与新档位边界不再等价，兼容映射等于把定价边界改回错的那一侧。
        assertThat(LicenseTier.inferTier(LEGACY_MODULES))
                .as("拆分前的 5 模块集合不得匹配任何档位 —— 它必须走显式重签发")
                .isNull();
        LicenseService svc = new LicenseService("", "s", true, "", MAPPER);
        assertThat(svc.tierBindingViolation(LEGACY_MODULES))
                .as("旧集合必须被判为非档位组合，迫使升级走重签发")
                .isNotNull();
    }

    @Test
    @DisplayName("拆分边界：mesh / orch 进基础版，基站卫星与 ONVIF 安防留在上档")
    void splitBoundariesMatchPricingDoc() {
        Set<String> basic = LicenseTier.modulesOf(LicenseTier.BASIC);
        assertThat(basic)
                .as("§5.1 承诺基础版含 M5 mesh + M9 编排 + GCS OEM")
                .contains("mesh", "orch", "core", "fleet");

        assertThat(basic)
                .as("基础版不得含 4a 空地一体化 / ONVIF 安防（应急版付费点）")
                .doesNotContain("emergency");
        assertThat(basic)
                .as("基础版不得含 5G 基站 / 卫星中继（完整版付费点）")
                .doesNotContain("network");
        assertThat(basic)
                .as("基础版不得含数字孪生 / AI 决策（完整版付费点）")
                .doesNotContain("advanced");

        assertThat(LicenseTier.modulesOf(LicenseTier.EMERGENCY))
                .as("应急版应含 4a 空地一体化 + ONVIF 安防")
                .contains("emergency");
        assertThat(LicenseTier.modulesOf(LicenseTier.EMERGENCY))
                .as("应急版不得含基站 / 卫星 / 孪生 AI")
                .doesNotContain("network", "advanced");
    }

    @Test
    @DisplayName("前缀归属：/api/v1/mesh → mesh、/api/v1/emergency|orch → orch")
    void prefixesFollowSplitModules() {
        assertThat(LicenseModuleMap.resolve("/api/v1/mesh/probe"))
                .as("M5 mesh 自愈组网是基础版能力，不再归属 network（完整版）")
                .isEqualTo(LicenseModuleMap.MESH);
        assertThat(LicenseModuleMap.resolve("/api/v1/emergency/orch/probe"))
                .as("M9 应急任务编排在基础版")
                .isEqualTo(LicenseModuleMap.ORCH);
        assertThat(LicenseModuleMap.resolve("/api/v1/orch/plans"))
                .as("编排计划在基础版")
                .isEqualTo(LicenseModuleMap.ORCH);

        // 反向：重资产链路与安防联动必须留在上档，否则基础版被白送
        assertThat(LicenseModuleMap.resolve("/api/v1/celltowers/probe"))
                .isEqualTo(LicenseModuleMap.NETWORK);
        assertThat(LicenseModuleMap.resolve("/api/v1/sat-link/probe"))
                .isEqualTo(LicenseModuleMap.NETWORK);
        assertThat(LicenseModuleMap.resolve("/api/v1/surveillance/devices"))
                .isEqualTo(LicenseModuleMap.EMERGENCY);
        assertThat(LicenseModuleMap.resolve("/api/v1/air-ground/probe"))
                .isEqualTo(LicenseModuleMap.EMERGENCY);
    }

    @Test
    @DisplayName("拆分必须真的做到：把 mesh 塞回 network，基础版就名不副实")
    void revertingMeshSplitBreaksBasicTier() {
        // 变异验证：若有人把 /api/v1/mesh 改回 NETWORK（即撤销拆分），
        // 基础版虽然名义上仍含 mesh 模块，却打不开任何 mesh 端点 ——
        // 客户付了钱、license 也认了、请求却 403。这是"拆分做一半"的失效形态，
        // 没有任何其他测试会红。
        Set<String> basic = LicenseTier.modulesOf(LicenseTier.BASIC);
        assertThat(basic)
                .as("前置：基础版声明含 mesh")
                .contains("mesh");
        assertThat(LicenseModuleMap.resolve("/api/v1/mesh/topology"))
                .as("/api/v1/mesh/* 必须能被基础版的 mesh 模块授权覆盖")
                .isEqualTo(LicenseModuleMap.MESH);
    }

    @Test
    @DisplayName("每个新模块都有前缀：授权 mesh / orch 才不会形同虚设")
    void newModulesHavePrefixes() {
        var perModule = LicenseModuleMap.prefixesPerModule();
        assertThat(perModule.getOrDefault(LicenseModuleMap.MESH, 0))
                .as("mesh 模块无前缀 ⇒ 基础版付了 mesh 的钱却打不开 /api/v1/mesh/*")
                .isPositive();
        assertThat(perModule.getOrDefault(LicenseModuleMap.ORCH, 0))
                .as("orch 模块无前缀 ⇒ 基础版付了 M9 编排的钱却打不开 /api/v1/emergency/*")
                .isPositive();
    }

    @Test
    @DisplayName("档位互异：三档必须是三个不同集合，否则无法定价")
    void tiersRemainDistinguishable() {
        Set<String> basic = LicenseTier.modulesOf(LicenseTier.BASIC);
        Set<String> emergency = LicenseTier.modulesOf(LicenseTier.EMERGENCY);
        Set<String> full = LicenseTier.modulesOf(LicenseTier.FULL);

        Set<String> basicPlus = new LinkedHashSet<>(basic);
        basicPlus.add("network");

        assertThat(emergency).isNotEqualTo(basic);
        assertThat(full).isNotEqualTo(emergency);
        assertThat(full).containsAll(basicPlus);
    }
}

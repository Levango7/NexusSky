package io.aerofleet.cloud.license;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 档位→模块绑定的**验证期**强制守卫（2026-10-06 建立）。
 *
 * <h2>补的是哪个洞</h2>
 * {@code LicenseTier}（2026-10-05）已把"档位 → 模块集合"固化成常量表，
 * {@code LicenseService.generateLicenseKeyByTier} 签发时也用
 * {@code mismatchOf} 自检。但那只保护**走档位入口签发**的路径，而
 * {@code validateLicense} 此前**只判 active + expiry，完全不看模块集合**。
 * 于是三条绕过路径全部畅通：
 * <ul>
 *   <li>走原始 {@code generateLicenseKey(...)} 直接传 {@code modules}；</li>
 *   <li>{@code LicenseTier} 引入之前签出的授权（模块集合是自由文本）；</li>
 *   <li>拿到签发密钥的一方构造任意模块集合。</li>
 * </ul>
 * 结果：一份"基础版 + emergency"的授权全程有效 ⇒ <b>三档定价不可执行</b>。
 *
 * <h2>本测试钉的不变式</h2>
 * {@code validateLicense} 在 enforce-tier-binding 开启（默认）时，
 * 要求模块集合<strong>恰好等于</strong>某一档：不多（超集也判红）、不少、不含未知模块。
 *
 * <h2>为什么"超集也判红"是关键一条</h2>
 * 若实现改用 {@code LicenseTier.inferTier}（"包含某档全部模块"的子集语义）判定，
 * 那么 {@code FULL + 一个未知模块} 会被认成 FULL —— 也就是<b>多签一个模块反而更宽松</b>。
 * {@link #supersetIsRejected()} 专门钉这一条。
 */
@DisplayName("License 档位绑定：验证期强制（定价可执行）")
class LicenseTierBindingEnforcementTest {

    private static final String HMAC_SECRET = "test-hmac-secret-at-least-32-characters-long";

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    // ===== 测试夹具 =====

    private LicenseInfo licenseWithModules(Set<String> modules) {
        LicenseInfo info = new LicenseInfo();
        info.setTenantId("acme");
        info.setProductName("AeroFleet Cloud");
        info.setMaxDevices(50);
        info.setModules(modules);
        info.setActive(true);
        info.setIssuedAt(Instant.parse("2026-01-01T00:00:00Z"));
        info.setExpiryDate(Instant.parse("2030-01-01T00:00:00Z"));
        info.setIssuedTo("Acme Drone Co");
        info.setLicenseId("LIC-2026-0002");
        return info;
    }

    private Set<String> modules(String... names) {
        return new LinkedHashSet<>(Set.of(names));
    }

    /** 直接构造 LicenseService 并把 currentLicense 反射替换为目标 license（绕开签名）。 */
    private LicenseService serviceWith(LicenseInfo license, boolean enforceTierBinding) throws Exception {
        java.lang.reflect.Field f = LicenseService.class.getDeclaredField("currentLicense");
        f.setAccessible(true);
        // 先造一个合法实例以走过构造期的 key 校验，再用反射换成目标 license
        LicenseSigner issuer = new LicenseSigner(mapper);
        LicenseInfo seed = licenseWithModules(modules("core", "fleet"));
        String key = Base64.getEncoder().encodeToString(mapper.writeValueAsString(seed).getBytes(StandardCharsets.UTF_8))
                + "." + issuer.sign(seed);
        LicenseService svc = new LicenseService(key, HMAC_SECRET, false,
                issuer.getPublicKeyBase64(), enforceTierBinding, mapper);
        f.set(svc, license);
        return svc;
    }

    // ===== ① 合法档位必须通过 =====

    @Nested
    @DisplayName("① 三个合法档位的模块集合必须被接受（不误伤正常授权）")
    class LegitimateTiersPass {

        @ParameterizedTest(name = "{0} → {1}")
        @DisplayName("逐档接受：模块集合恰好等于该档")
        @MethodSource("tierModuleCombinations")
        void eachTierAccepted(String tier, Set<String> mods) throws Exception {
            LicenseService svc = serviceWith(licenseWithModules(mods), true);
            assertThat(svc.tierBindingViolation(mods)).isNull();
            assertThat(svc.validateLicense(licenseWithModules(mods)))
                    .as("档位 %s 的合法授权不应被档位绑定校验拒绝", tier)
                    .isTrue();
            assertThat(LicenseTier.inferTier(mods)).isEqualTo(tier);
        }

        /**
         * 用 MethodSource 而非 CsvSource：模块清单本身含逗号
         * （"core,fleet,emergency"），CsvSource 会按逗号切列，把清单截断成 "core"。
         */
        static Stream<Arguments> tierModuleCombinations() {
            return Stream.of(
                    Arguments.of("basic", Set.of("core", "fleet")),
                    Arguments.of("emergency", Set.of("core", "fleet", "emergency")),
                    Arguments.of("full", Set.of("core", "fleet", "emergency", "network", "advanced"))
            );
        }

        @Test
        @DisplayName("ALL_MODULES（开发版全模块）= 完整版，通过")
        void allModulesEqualsFullTier() {
            assertThat(LicenseTier.modulesOf(LicenseTier.FULL))
                    .containsExactlyInAnyOrderElementsOf(LicenseService.ALL_MODULES);
            assertThat(new LicenseService("", HMAC_SECRET, true, "", mapper).validateLicense(
                    new LicenseService("", HMAC_SECRET, true, "", mapper).getLicenseInfo()))
                    .as("开发版 license（全模块）必须仍然可用，否则 dev/CI 全部起不来")
                    .isTrue();
        }

        @Test
        @DisplayName("esforce 关闭时，非档位组合被放行（逃生阀本身要可用且可自报）")
        void escapeHatchAllowsNonTierSets() throws Exception {
            Set<String> odd = modules("core", "fleet", "network");   // 跳过 emergency 的怪组合
            LicenseService svc = serviceWith(licenseWithModules(odd), false);
            assertThat(svc.isTierBindingEnforced()).isFalse();
            assertThat(svc.validateLicense(licenseWithModules(odd)))
                    .as("显式关闭绑定后应恢复旧行为（逃生阀必须真的能用，否则出问题无法回退）")
                    .isTrue();
        }
    }

    // ===== ② 非档位组合必须被拒（这是本测试存在的理由）=====

    @Nested
    @DisplayName("② 非档位的模块组合必须被拒绝 —— 修复前这些全部放行")
    class NonTierSetsRejected {

        @Test
        @DisplayName("基础版 + network / advanced：定价越权必须判红")
        void basicTierWithExtraModuleRejected() {
            LicenseService svc = new LicenseService("", HMAC_SECRET, true, "", mapper);
            for (String extra : new String[] { "network", "advanced" }) {
                Set<String> mods = modules("core", "fleet", extra);
                assertThat(svc.tierBindingViolation(mods))
                        .as("基础版 core+fleet 多出 %s 必须判红", extra)
                        .isNotNull();
                assertThat(svc.validateLicense(licenseWithModules(mods)))
                        .as("越权授权必须使 validateLicense 返回 false")
                        .isFalse();
            }
        }

        @Test
        @DisplayName("⚠️ 「基础版 + emergency」不是越权——它恰好等于应急版，应被接受并归类为应急版")
        void basicPlusEmergencyIsTheEmergencyTierNotAnEscalation() {
            // 刻意把这条单列：写"多塞一个模块必红"会误把 core+fleet+emergency 判红，
            // 而它正是应急版的合法集合。这里的正确期望是"接受 + 归类为 emergency"。
            Set<String> mods = modules("core", "fleet", "emergency");
            LicenseService svc = new LicenseService("", HMAC_SECRET, true, "", mapper);
            assertThat(svc.validateLicense(licenseWithModules(mods)))
                    .as("core+fleet+emergency 是应急版的精确集合，必须放行")
                    .isTrue();
            assertThat(LicenseTier.inferTier(mods)).isEqualTo(LicenseTier.EMERGENCY);
        }

        @Test
        @DisplayName("缺模块（应急版少 emergency）：也不该放行 —— 少给是配置错误，不是宽容的理由")
        void missingModuleRejected() {
            Set<String> mods = modules("core", "fleet", "network");
            LicenseService svc = new LicenseService("", HMAC_SECRET, true, "", mapper);
            assertThat(svc.validateLicense(licenseWithModules(mods))).isFalse();
        }

        @Test
        @DisplayName("⚠️ 超集必须被拒：FULL + 一个未知模块不得被 inferTier 误判为 FULL")
        void supersetIsRejected() {
            Set<String> mods = modules("core", "fleet", "emergency", "network", "advanced", "godmode");
            LicenseService svc = new LicenseService("", HMAC_SECRET, true, "", mapper);
            // 前置：若用 inferTier（子集语义）判定，这里会被误判为 FULL 而放行
            assertThat(LicenseTier.inferTier(mods))
                    .as("本用例的价值在于 inferTier 的子集语义：它确实会把超集认成 FULL")
                    .isEqualTo(LicenseTier.FULL);
            assertThat(svc.validateLicense(licenseWithModules(mods)))
                    .as("超集必须判红：多签一个未知模块反而更宽松是漏洞")
                    .isFalse();
        }

        @Test
        @DisplayName("未知模块名单独出现：判红并点名")
        void unknownModuleNamedInViolation() {
            Set<String> mods = modules("core", "typo_module");
            LicenseService svc = new LicenseService("", HMAC_SECRET, true, "", mapper);
            assertThat(svc.tierBindingViolation(mods))
                    .contains("typo_module")
                    .contains("不对应任何可售档位");
        }

        @Test
        @DisplayName("空集 / null 集合：判红（等价于'什么模块都没有'，与已签发授权语义矛盾）")
        void emptyOrNullModulesRejected() {
            LicenseService svc = new LicenseService("", HMAC_SECRET, true, "", mapper);
            assertThat(svc.validateLicense(licenseWithModules(new HashSet<>()))).isFalse();
            assertThat(svc.validateLicense(licenseWithModules(null))).isFalse();
            assertThat(svc.tierBindingViolation(null)).contains("为空");
        }
    }

    // ===== ③ 诊断信息必须可操作 =====

    @Nested
    @DisplayName("③ 失败原因必须可操作（运维不必翻日志）")
    class DiagnosticsActionable {

        @Test
        @DisplayName("违规消息同时含实际集合与全部合法组合")
        void violationMessageListsBothSides() {
            LicenseService svc = new LicenseService("", HMAC_SECRET, true, "", mapper);
            String msg = svc.tierBindingViolation(modules("core", "typo"));
            assertThat(msg).contains("core", "typo");           // 实际
            assertThat(msg).contains("基础版");                  // 合法组合摘要
            assertThat(msg).contains("应急版").contains("完整版");
        }

        @Test
        @DisplayName("LicenseTier.displayName(null) 返回 null 而不是 NPE")
        void displayNameIsTotalOverNull() {
            // 回归：Map.of(...).getOrDefault(null, x) 走 MapN.probe(pk) → pk.hashCode() → NPE。
            // "档位反查不到"是完全正常的路径（非档位组合），不能因此炸掉调用方。
            assertThat(LicenseTier.displayName(null)).isNull();
            assertThat(LicenseTier.displayName("不存在的档位")).isEqualTo("不存在的档位");
            assertThat(LicenseTier.displayName(LicenseTier.BASIC)).isEqualTo("基础版");
        }

        @Test
        @DisplayName("inferTier(null) / modulesOf(null) 也是全函数（不抛）")
        void tierLookupsAreTotal() {
            assertThat(LicenseTier.inferTier(null)).isNull();
            assertThat(LicenseTier.modulesOf(null)).isNull();
            assertThat(LicenseTier.isKnownTier(null)).isFalse();
        }
    }

    // ===== ④ 签发入口与验证入口必须一致 =====

    @Nested
    @DisplayName("④ 签发期与验证期判据必须一致（不能一个严一个松）")
    class IssuanceAndValidationAgree {

        @Test
        @DisplayName("三档签发出的 key，其模块集合必然通过验证期校验")
        void tierIssuedKeysPassValidation() {
            LicenseService issuer = new LicenseService("", HMAC_SECRET, true, "", mapper);
            for (String tier : LicenseTier.allTiers()) {
                Set<String> mods = LicenseTier.modulesOf(tier);
                assertThat(issuer.tierBindingViolation(mods))
                        .as("签发侧允许的档位 %s，验证侧也必须允许", tier)
                        .isNull();
            }
        }

        @Test
        @DisplayName("档位表与 ALL_MODULES 同集合（加模块必须同时决定档位归属）")
        void tierUnionEqualsAllModules() {
            Set<String> union = new HashSet<>();
            LicenseTier.allTiers().forEach(t -> union.addAll(LicenseTier.modulesOf(t)));
            assertThat(union)
                    .as("有模块不属于任何档位 → 该模块的授权无处可卖")
                    .containsExactlyInAnyOrderElementsOf(LicenseService.ALL_MODULES);
        }
    }

    // ===== ⑤ 启动期也必须拒绝（本组 2026-10-06 由 Qoder 会话补）=====

    /**
     * 存在的理由：只判 {@code validateLicense}（每请求）会得到一个更难排查的失效形态——
     * 装了一份模块集合不对应任何可售档位的授权，进程**启动成功**、随后每个请求 403，
     * 运维会先去查认证/网关而不是授权本身。{@code loadLicense()} 里那两条「验签失败/解析为空
     * 即拒启」已经确立了同一风格，档位绑定必须并入。
     */
    @Nested
    @DisplayName("⑤ 非档位授权在启动期即被拒（不是「启动成功、全站 403」）")
    class StartupRejectedToo {

        @Test
        @DisplayName("非档位模块集合：构造函数抛 IllegalStateException，消息点名逃生阀")
        void nonTierLicenseFailsStartup() throws Exception {
            LicenseSigner signer = new LicenseSigner(mapper);
            String key = signWith(signer, modules("core", "fleet", "network")); // 基础版 + 超集
            IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> new LicenseService(key, HMAC_SECRET, false, signer.getPublicKeyBase64(), true, mapper));
            assertThat(e.getMessage())
                    .contains("档位")
                    .contains("aerofleet.license.enforce-tier-binding=false");
        }

        @Test
        @DisplayName("合法档位授权：启动正常（本判定不误伤正常授权）")
        void legalTierLicenseStarts() throws Exception {
            LicenseSigner signer = new LicenseSigner(mapper);
            String key = signWith(signer, LicenseTier.modulesOf(LicenseTier.EMERGENCY));
            LicenseService svc = new LicenseService(key, HMAC_SECRET, false,
                    signer.getPublicKeyBase64(), mapper);
            assertThat(svc.getLicenseInfo().getModules())
                    .containsExactlyInAnyOrderElementsOf(LicenseTier.modulesOf(LicenseTier.EMERGENCY));
        }

        @Test
        @DisplayName("逃生阀：enforce=false 时非档位授权可启动，但违规原因仍可自报")
        void escapeHatchAllowsNonTierLicense() throws Exception {
            LicenseSigner signer = new LicenseSigner(mapper);
            String key = signWith(signer, modules("core", "fleet", "network"));
            LicenseService svc = new LicenseService(key, HMAC_SECRET, false,
                    signer.getPublicKeyBase64(), false, mapper);
            assertThat(svc.isTierBindingEnforced()).isFalse();
            assertThat(svc.tierBindingViolation(svc.getLicenseInfo().getModules())).isNotNull();
        }

        /** 用同一 signer 签一份带指定模块集合的授权 key（签名与公钥必须同源）。 */
        private String signWith(LicenseSigner signer, Set<String> modules) throws Exception {
            LicenseInfo info = licenseWithModules(modules);
            return Base64.getEncoder().encodeToString(
                    mapper.writeValueAsString(info).getBytes(StandardCharsets.UTF_8))
                    + "." + signer.sign(info);
        }
    }
}
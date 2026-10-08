package io.aerofleet.cloud.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移预检工具的行为守卫。
 *
 * <p><b>为什么这个工具本身也要有测试</b>：它的输出直接决定运维"照着签还是重签"——
 * 若它把一份该重签的 license 报成"一致"，运维就会带着旧 key 升级，部署当场拒启；
 * 若它把一致的 license 报成"要重签"，运维会无谓地重新走一遍签发流程。
 * 两种错法都只有这一个信号来源，所以它必须被测。
 */
@DisplayName("License 迁移预检：档位推断与一致性判定")
class LicenseMigrationPreflightTest {

    private static final ObjectMapper MAPPER = LicenseIssuer.issuerMapper();

    /** 拆分前的三种合法授权形态。 */
    private static final Set<String> LEGACY_BASIC = Set.of("core", "fleet");
    private static final Set<String> LEGACY_EMERGENCY = Set.of("core", "fleet", "emergency");
    private static final Set<String> LEGACY_FULL =
            Set.of("core", "fleet", "emergency", "network", "advanced");

    @Test
    @DisplayName("端到端：main() 对一份旧基础版授权，输出需含重签发建议与可粘贴的签发命令")
    void mainPrintsActionableReissueAdvice() throws Exception {
        LicenseSigner signer = new LicenseSigner(MAPPER);
        String key = signKey(signer, LEGACY_BASIC);

        Path dir = Files.createTempDirectory("preflight-e2e");
        Path keyFile = dir.resolve("legacy.key");
        Path pubFile = dir.resolve("pub.key");
        Files.writeString(keyFile, key);
        Files.writeString(pubFile,
                LicenseKeyGenerator.encodePublicKey(signer.getPublicKey()));

        String out = captureStdout(() ->
                LicenseMigrationPreflight.run(new String[] {
                        "--license-key-file", keyFile.toString(),
                        "--public-key-file", pubFile.toString()}));

        // 运维真正要看的四件事：判定、原因、目标档位、可直接粘贴的命令
        assertThat(out).contains("❌ 结论：不对应任何可售档位");
        assertThat(out).contains("建议重签档位: 基础版");
        assertThat(out).contains("io.aerofleet.cloud.license.LicenseIssuer");
        assertThat(out).contains("验签            : ✅ 通过");
        // --modules 必须逐字等于档位定义：顺序也要对，运维照抄的就是这一行
        assertThat(out).contains("--modules " + String.join(",", LicenseTier.modulesOf(LicenseTier.BASIC)));
        assertThat(out)
                .as("拆分后的基础版应含 mesh 与 orch（§5.1 承诺项）")
                .contains("--modules core,fleet,mesh,orch");
        // 原因必须说人话：不能把 mismatchOf 拿占位档位名去调（会输出 "未知档位: ?"）
        assertThat(out)
                .as("原因不得出现无意义的占位档位名")
                .doesNotContain("未知档位");
        assertThat(out).contains("该模块集合与三个可售档位都不相等");
        // 三档都列出来，运维才能自己看出差在哪
        for (String tier : List.of(LicenseTier.BASIC, LicenseTier.EMERGENCY, LicenseTier.FULL)) {
            assertThat(out)
                    .as("报告应列出 %s 的模块集合供人工比对", tier)
                    .contains(String.join(",", LicenseTier.modulesOf(tier)));
        }
        // 报告是给人读的文本，SLF4J 日志行不得插进中间
        assertThat(out)
                .as("LicenseSigner 的启动日志不应污染报告")
                .doesNotContain("LicenseSigner 初始化");

        Files.deleteIfExists(keyFile);
        Files.deleteIfExists(pubFile);
        Files.deleteIfExists(dir);
    }

    @Test
    @DisplayName("端到端：main() 对当前合法档位授权，输出需为『无需重签发』")
    void mainAcceptsCurrentTierLicense() throws Exception {
        LicenseSigner signer = new LicenseSigner(MAPPER);
        String key = signKey(signer, LicenseTier.modulesOf(LicenseTier.EMERGENCY));

        Path dir = Files.createTempDirectory("preflight-ok");
        Path keyFile = dir.resolve("cur.key");
        Files.writeString(keyFile, key);

        String out = captureStdout(() ->
                LicenseMigrationPreflight.run(new String[] {
                        "--license-key-file", keyFile.toString()}));

        assertThat(out).contains("✅ 结论：与新档位一致，无需重签发");
        assertThat(out).contains("应急版 (emergency)");
        // 未提供公钥时必须显式声明"未验签"，不能让运维误以为已验签
        assertThat(out).contains("已跳过");
        assertThat(out).contains("仅供参考");

        Files.deleteIfExists(keyFile);
        Files.deleteIfExists(dir);
    }

    @Test
    @DisplayName("main() 对残缺 key 必须给出明确原因，而不是抛栈或静默通过")
    void mainRejectsGarbageKey() throws Exception {
        Path dir = Files.createTempDirectory("preflight-bad");
        Path keyFile = dir.resolve("bad.key");
        Files.writeString(keyFile, "not-a-license-key");

        String out = captureStdout(() ->
                LicenseMigrationPreflight.run(new String[] {
                        "--license-key-file", keyFile.toString()}));

        assertThat(out).contains("❌ 无法解析 license key");
        assertThat(out).contains("签名分隔符");
        // 关键：明确排除"这是模块拆分问题"的误判，把运维引到正确方向
        assertThat(out).contains("这不是模块拆分迁移问题");

        Files.deleteIfExists(keyFile);
        Files.deleteIfExists(dir);
    }

    /** 捕获 stdout —— 迁移预检的全部价值都在它的输出里。 */
    private static String captureStdout(Runnable action) {
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setOut(original);
        }
        return buf.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("稳定性：modulesOf 的迭代顺序必须等于档位声明顺序（多次调用一致）")
    void moduleIterationOrderIsStable() {
        // 变异验证：若把 modulesOf 改回 Set.copyOf(got)（无序不可变集），
        // 元素顺序由 hash 分布决定，同一档位两次调用可能给出不同顺序 ——
        // 面向运维的 `--modules` 输出就会不稳定、日志 diff 失去意义。
        // 单次运行看不出问题，所以这里对同一档位连取多次并比对。
        for (String tier : List.of(LicenseTier.BASIC, LicenseTier.EMERGENCY, LicenseTier.FULL)) {
            List<String> first = List.copyOf(LicenseTier.modulesOf(tier));
            assertThat(first)
                    .as("档位 %s 的模块顺序应与声明顺序一致", tier)
                    .containsExactlyElementsOf(expectedOrder(tier));
            for (int i = 0; i < 20; i++) {
                assertThat(List.copyOf(LicenseTier.modulesOf(tier)))
                        .as("档位 %s 第 %d 次取用顺序发生变化", tier, i)
                        .isEqualTo(first);
            }
        }
    }

    /** 档位定义的声明顺序（build() 里累进添加的顺序）。 */
    private static List<String> expectedOrder(String tier) {
        List<String> coreFleetMeshOrch =
                List.of("core", "fleet", "mesh", "orch");
        return switch (tier) {
            case LicenseTier.BASIC -> coreFleetMeshOrch;
            case LicenseTier.EMERGENCY -> {
                List<String> l = new java.util.ArrayList<>(coreFleetMeshOrch);
                l.add("emergency");
                yield List.copyOf(l);
            }
            default -> {
                List<String> l = new java.util.ArrayList<>(coreFleetMeshOrch);
                l.add("emergency");
                l.add("network");
                l.add("advanced");
                yield List.copyOf(l);
            }
        };
    }

    private static String signKey(LicenseSigner signer, Set<String> modules) throws Exception {
        LicenseInfo info = new LicenseInfo();
        info.setTenantId("acme");
        info.setIssuedTo("Acme Drone Co");
        info.setProductName("AeroFleet Cloud Standard");
        info.setMaxDevices(50);
        info.setLicenseId("LIC-PREFLIGHT-TEST");
        info.setModules(new LinkedHashSet<>(modules));
        String json = MAPPER.writeValueAsString(info);
        return Base64.getEncoder().encodeToString(json.getBytes("UTF-8"))
                + LicenseIssuer.KEY_SEPARATOR + signer.sign(info);
    }

    @Test
    @DisplayName("拆分前的三档全部需要重签（含完整版：旧 5 模块 ≠ 新 7 模块）")
    void legacyTiersMapToTargets() {
        // 注意：完整版**也要重签**。曾误以为旧完整版集合恰好仍合法——不是：
        // 新完整版 = 基础版(core,fleet,mesh,orch) + emergency + network + advanced = 7 个，
        // 旧完整版只有 5 个（无 mesh/orch）⇒ 不等于任何档位。
        // 三档无一幸免，这正是"必须重签发"而非"部分兼容"的含义。
        assertThat(LicenseMigrationPreflight.suggestTargetTier(LEGACY_FULL))
                .as("旧完整版应重签为完整版")
                .isEqualTo(LicenseTier.FULL);
        assertThat(LicenseTier.mismatchOf(LicenseTier.FULL, LEGACY_FULL))
                .as("前提自检：旧完整版集合不等于新完整版（缺 mesh/orch），故需重签")
                .asString()
                .contains("缺少");

        assertThat(LicenseMigrationPreflight.suggestTargetTier(LEGACY_EMERGENCY))
                .as("旧应急版（core+fleet+emergency）应重签为应急版")
                .isEqualTo(LicenseTier.EMERGENCY);
        assertThat(LicenseMigrationPreflight.suggestTargetTier(LEGACY_BASIC))
                .as("旧基础版（core+fleet）应重签为基础版")
                .isEqualTo(LicenseTier.BASIC);
    }

    @Test
    @DisplayName("旧基础版 / 旧应急版集合确实不再等于任何档位 —— 这是要重签的根本原因")
    void legacyNonFullSetsMatchNoTier() {
        assertThat(LicenseTier.inferTier(LEGACY_BASIC))
                .as("拆分前的基础版集合已不是任何档位的精确集合")
                .isNull();
        assertThat(LicenseTier.inferTier(LEGACY_EMERGENCY))
                .as("拆分前的应急版集合已不是任何档位的精确集合")
                .isNull();
    }

    @Test
    @DisplayName("含未知模块名时不猜档位（返回 null），交人工裁决")
    void unknownModuleBlocksGuess() {
        Set<String> bogus = new LinkedHashSet<>(LEGACY_FULL);
        bogus.add("godmode");
        assertThat(LicenseMigrationPreflight.suggestTargetTier(bogus))
                .as("含未知模块名时必须返回 null —— 签发侧会拒绝，瞎猜会给出错误的签发命令")
                .isNull();
    }

    @Test
    @DisplayName("当前新档位集合应原样通过预检（不制造无谓的重签发）")
    void currentTiersPassUnchanged() {
        for (String tier : List.of(LicenseTier.BASIC, LicenseTier.EMERGENCY, LicenseTier.FULL)) {
            Set<String> mods = LicenseTier.modulesOf(tier);
            String inferred = LicenseTier.inferTier(mods);
            assertThat(inferred).as("档位 %s 应被识别为自身", tier).isEqualTo(tier);
            assertThat(LicenseTier.mismatchOf(inferred, mods))
                    .as("档位 %s 的模块集合应无绑定违规", tier)
                    .isNull();
        }
    }

    @Test
    @DisplayName("端到端：解析并验签一份旧授权，验签通过但档位判定为需重签")
    void parsesAndVerifiesLegacyKey() throws Exception {
        LicenseSigner signer = new LicenseSigner(MAPPER);
        String key = signKey(signer, LEGACY_BASIC);

        int sep = key.indexOf(LicenseIssuer.KEY_SEPARATOR);
        assertThat(sep).isPositive();
        byte[] payload = Base64.getDecoder().decode(key.substring(0, sep));
        LicenseInfo info = MAPPER.readValue(new String(payload, "UTF-8"), LicenseInfo.class);

        assertThat(info.getTenantId()).isEqualTo("acme");
        assertThat(new LinkedHashSet<>(info.getModules()))
                .isEqualTo(LEGACY_BASIC);
        // 签名本身有效 —— 问题不在签名，而在于模块集合已不对应任何档位
        assertThat(signer.verify(info, key.substring(sep + LicenseIssuer.KEY_SEPARATOR.length())))
                .as("旧 key 的签名应仍然有效")
                .isTrue();
        assertThat(LicenseTier.inferTier(new LinkedHashSet<>(info.getModules())))
                .as("但它已不对应任何档位 ⇒ 必须重签发")
                .isNull();
    }

    @Test
    @DisplayName("推断出的目标档位必须自洽：其模块集合能被 inferTier 反推回同一档")
    void suggestedTierIsSelfConsistent() {
        for (Set<String> legacy : List.of(LEGACY_BASIC, LEGACY_EMERGENCY, LEGACY_FULL)) {
            String target = LicenseMigrationPreflight.suggestTargetTier(legacy);
            Set<String> targetModules = LicenseTier.modulesOf(target);
            assertThat(LicenseTier.inferTier(targetModules))
                    .as("预检给出的签发命令若照抄，其模块集合必须真对应 %s", target)
                    .isEqualTo(target);
            assertThat(LicenseService.ALL_MODULES.containsAll(targetModules))
                    .as("签发命令里的模块名必须全部合法，否则 LicenseIssuer 会拒绝")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("预检输出的签发模块清单必须覆盖拆分后的基础版承诺项")
    void suggestedBasicTierIncludesSplitModules() {
        Set<String> basic = LicenseTier.modulesOf(
                LicenseMigrationPreflight.suggestTargetTier(LEGACY_BASIC));
        assertThat(basic)
                .as("旧基础版重签后应拿到 §5.1 承诺的 mesh 与 orch")
                .contains("mesh", "orch", "core", "fleet");
        // 签发命令的 --modules 必须与档位定义同一集合（无序比较：
        // ALL_MODULES 是 Set，迭代顺序不保证，拿来做字符串比较会得到假失败）
        assertThat(new LinkedHashSet<>(LicenseService.ALL_MODULES))
                .as("签发命令的 --modules 值应与档位定义同一集合，避免手抄漂移")
                .containsAll(basic);
    }
}
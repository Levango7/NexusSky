package io.aerofleet.cloud.license;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * License 模块门禁覆盖率测试（2026-10-05 建立）。
 * <p>
 * <b>背景</b>：核查发现 {@code LicenseInterceptor} 内联的路径映射表只覆盖 5 个前缀，
 * 实测只约束 35/343 = 10.2% 的端点。买了「基础版」（M5 mesh + M9 编排，20–50 万/年）
 * 的客户可访问全部应急指挥 / 安防联动 / 卫星中继 / 5G 基站等未付费模块 ——
 * <b>三档定价在技术上不可执行</b>。
 * <p>
 * 本测试做三件事：
 * <ol>
 *   <li><b>全覆盖断言</b>：扫描全仓 {@code *Controller.java} 的类级
 *       {@code @RequestMapping}，逐个过 {@link LicenseModuleMap#resolve}，
 *       断言不存在「既未登记映射、也不在白名单」的前缀。
 *       新增控制器忘记登记模块 → 本测试红。</li>
 *   <li><b>fail-closed 断言</b>：未登记路径必须解析为
 *       {@link LicenseModuleMap#MODULE_UNCLASSIFIED}（而非 null），
 *       且该哨兵不在 {@link LicenseService#ALL_MODULES} 中
 *       —— 保证守卫「未登记 = 永不授权」成立。</li>
 *   <li><b>映射正确性断言</b>：抽样关键路径验证其归属模块符合商业分档。</li>
 * </ol>
 */
@DisplayName("License 模块门禁：全控制器前缀必须可归类（fail-closed）")
class LicenseModuleCoverageTest {

    /** 仓库内 cloud-backend 控制器源码根（测试工作目录 = cloud-backend/）。 */
    private static final Path CONTROLLER_ROOT =
            Path.of("src", "main", "java", "io", "aerofleet", "cloud");

    /** 提取类级 @RequestMapping("...") 的前缀。 */
    private static final Pattern REQUEST_MAPPING =
            Pattern.compile("@RequestMapping\\(\\s*\"([^\"]*)\"\\s*\\)");

    /**
     * 提取方法级子路径（@GetMapping/@PostMapping/@PutMapping/@DeleteMapping/@PatchMapping）。
     * <p>
     * 仅在「类级前缀为裸基路径」时用于推导真实资源前缀，见
     * {@link #scanAllControllerPrefixes()} 的说明。
     */
    private static final Pattern METHOD_MAPPING =
            Pattern.compile("@(?:Get|Post|Put|Delete|Patch)Mapping\\(\\s*\"([^\"]*)\"");

    /**
     * 视为「裸基路径」的类级前缀：控制器把 /api/v1 直接当类级前缀，真实资源名
     * 藏在方法级路径里。此值本身不可登记（登记它会把整个 API 面吞进一个模块）。
     */
    private static final String BARE_API_BASE = "/api/v1";

    /**
     * 扫描全部 *Controller.java，返回「源码路径 → 类级前缀」的映射。
     * <p>
     * 用 {@code Files.walk} 而非 shell，保证跨平台且不依赖 grep 实现。
     * <p>
     * <b>裸基路径处理</b>：{@code HardwareDataController} 的类级注解是
     * {@code @RequestMapping("/api/v1")}，真实资源前缀（radar/rotor/lidar/imu）
     * 写在方法级路径上。若直接把 {@code "/api/v1"} 当作待归类前缀，测试会要求
     * 登记 {@code "/api/v1"} —— 而这恰好是最危险的登记方式（会把全部 API 面
     * 纳入同一模块）。因此这里对裸基路径控制器，回溯其方法级首段作为真实前缀。
     */
    private static List<String> scanAllControllerPrefixes() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(CONTROLLER_ROOT)) {
            files = s.filter(p -> p.getFileName().toString().endsWith("Controller.java"))
                    .toList();
        }
        assertThat(files)
                .as("未扫描到任何 *Controller.java —— 测试工作目录应为 cloud-backend/，"
                        + "当前 cwd=" + Path.of("").toAbsolutePath())
                .isNotEmpty();

        Set<String> prefixes = new LinkedHashSet<>();
        for (Path f : files) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            Matcher m = REQUEST_MAPPING.matcher(src);
            if (m.find()) {
                String classLevel = m.group(1);
                if (BARE_API_BASE.equals(classLevel) || (BARE_API_BASE + "/").equals(classLevel)) {
                    prefixes.addAll(resourcePrefixesFromMethodLevel(src));
                } else {
                    prefixes.add(classLevel);
                }
            } else {
                // 无类级 @RequestMapping 的控制器：其方法级路径落在 /api/v1 根下
                prefixes.addAll(resourcePrefixesFromMethodLevel(src));
            }
        }
        return new ArrayList<>(prefixes);
    }

    /**
     * 从方法级映射推导真实资源前缀：取每条子路径的第一段，拼回 /api/v1/&lt;seg&gt;。
     * <p>
     * 例：{@code @PostMapping("/radar/config")} → {@code /api/v1/radar}。
     * 子路径为 "" 或 "/" 时跳过（无资源名可归类）。
     */
    private static Set<String> resourcePrefixesFromMethodLevel(String src) {
        Set<String> out = new LinkedHashSet<>();
        Matcher mm = METHOD_MAPPING.matcher(src);
        while (mm.find()) {
            String sub = mm.group(1).trim();
            if (sub.isEmpty() || sub.equals("/")) {
                continue;
            }
            String seg = sub.startsWith("/") ? sub.substring(1) : sub;
            int slash = seg.indexOf('/');
            if (slash > 0) {
                seg = seg.substring(0, slash);
            }
            if (!seg.isEmpty()) {
                out.add(BARE_API_BASE + "/" + seg);
            }
        }
        return out;
    }

    @Test
    @DisplayName("全仓控制器前缀扫描非空（防扫描本身失效）")
    void scanFindsControllers() throws IOException {
        List<String> prefixes = scanAllControllerPrefixes();
        // 实测 66+ 个前缀；给下限防「扫描路径写错导致 0 命中」的假绿
        assertThat(prefixes)
                .as("控制器前缀数量异常偏少，疑似扫描路径错误")
                .hasSizeGreaterThan(50);
    }

    @Test
    @DisplayName("fail-closed：每个真实控制器前缀都必须能归类或命中白名单")
    void everyControllerPrefixIsClassified() throws IOException {
        List<String> unclassified = new ArrayList<>();
        for (String prefix : scanAllControllerPrefixes()) {
            String module = LicenseModuleMap.resolve(prefix + "/probe");
            if (LicenseModuleMap.MODULE_UNCLASSIFIED.equals(module)) {
                unclassified.add(prefix);
            }
        }
        assertThat(unclassified)
                .as("以下控制器前缀既未登记进 LicenseModuleMap.PREFIX_TO_MODULE，"
                        + "也不在白名单中 —— 它们在 prod（license.enabled=true）下会被 403。"
                        + "请为每个前缀指定所属模块（core/fleet/emergency/network/advanced），"
                        + "或若确属「无需授权」则在 UNMAPPED_POLICY 中写明理由：%s",
                        unclassified)
                .isEmpty();
    }

    @Test
    @DisplayName("fail-closed：未登记路径解析为未归类哨兵，且该哨兵不在 ALL_MODULES 中")
    void unregisteredPathFailsClosed() {
        String module = LicenseModuleMap.resolve("/api/v1/totally-new-feature/x");
        assertThat(module)
                .as("未登记路径必须返回未归类哨兵（而非 null=放行）")
                .isEqualTo(LicenseModuleMap.MODULE_UNCLASSIFIED);

        assertThat(LicenseService.ALL_MODULES)
                .as("未归类哨兵绝不能出现在已授权模块集合中，否则 guard 失效")
                .doesNotContain(LicenseModuleMap.MODULE_UNCLASSIFIED);

        // 反向验证：哨兵不在 ALL_MODULES ⇒ hasModule 恒 false ⇒ 拦截器必然 403
        LicenseInfo anyLicense = new LicenseInfo(
                null, "t1", "p", 1, null, java.time.Instant.now(), "x", true);
        anyLicense.setModules(new java.util.HashSet<>(LicenseService.ALL_MODULES));
        assertThat(anyLicense.hasModule(LicenseModuleMap.MODULE_UNCLASSIFIED))
                .as("全模块授权也不应包含未归类哨兵")
                .isFalse();
    }

    @Test
    @DisplayName("白名单路径解析为 null（放行），且不误伤同名前缀")
    void whitelistPathsResolveNull() {
        assertThat(LicenseModuleMap.resolve("/api/v1/auth/login")).isNull();
        assertThat(LicenseModuleMap.resolve("/api/v1/license/status")).isNull();
        assertThat(LicenseModuleMap.resolve("/api/v1/health")).isNull();
        assertThat(LicenseModuleMap.resolve("/api/v1/openapi/json")).isNull();

        // 关键：/api/v1/healthz 不得被 /api/v1/health 前缀误吸
        assertThat(LicenseModuleMap.resolve("/api/v1/healthz"))
                .as("前缀匹配必须按路径段，不能按字符串开头")
                .isEqualTo(LicenseModuleMap.MODULE_UNCLASSIFIED);
    }

    @Test
    @DisplayName("路径归一化：查询串与末尾斜杠不影响模块判定")
    void pathNormalization() {
        assertThat(LicenseModuleMap.resolve("/api/v1/drones?limit=10"))
                .isEqualTo(LicenseModuleMap.CORE);
        assertThat(LicenseModuleMap.resolve("/api/v1/drones/"))
                .isEqualTo(LicenseModuleMap.CORE);
        assertThat(LicenseModuleMap.resolve("/api/v1/drones/1/telemetry"))
                .isEqualTo(LicenseModuleMap.CORE);
    }

    @Test
    @DisplayName("最长前缀优先：/api/v1/city-twin/models 命中 advanced 而非退化")
    void longestPrefixWins() {
        assertThat(LicenseModuleMap.resolve("/api/v1/city-twin/models"))
                .isEqualTo(LicenseModuleMap.ADVANCED);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("商业分档抽样：收费模块归属正确")
    @CsvSource({
            // 基础版能力（core / fleet）
            "/api/v1/drones,           core",
            "/api/v1/flightlog,        core",
            "/api/v1/scheduling,       fleet",
            "/api/v1/spray,            fleet",
            // 应急版核心付费点（emergency）
            "/api/v1/emergency,        emergency",
            "/api/v1/emergency-command,emergency",
            "/api/v1/surveillance,     emergency",
            "/api/v1/alarms,           emergency",
            "/api/v1/vision,           emergency",
            // 完整版付费点（network / advanced）
            "/api/v1/mesh,             network",
            "/api/v1/sat-link,         network",
            "/api/v1/celltowers,       network",
            "/api/v1/twin,             advanced",
            "/api/v1/city-twin,        advanced",
            "/api/v1/ai,               advanced",
    })
    void moduleAssignmentFollowsCommercialTiers(String prefix, String expectedModule) {
        assertThat(LicenseModuleMap.resolve(prefix + "/probe"))
                .as("前缀 %s 的模块归属不符合商业分档", prefix)
                .isEqualTo(expectedModule);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/emergency-command",
            "/api/v1/surveillance",
            "/api/v1/sat-link",
            "/api/v1/celltowers",
            "/api/v1/alarms",
            "/api/v1/vision",
            "/api/v1/city-twin",
    })
    @DisplayName("回归守卫：这 7 个前缀在修复前是「未覆盖」，现必须受模块约束")
    void previouslyUncoveredPrefixesNowGuarded(String prefix) {
        // 这 7 个前缀代表了修复前的漏洞面：应急指挥、安防、卫星、基站、孪生
        // ——全是收费卖点，此前只校验有效期、不校验模块授权。
        String module = LicenseModuleMap.resolve(prefix + "/probe");
        assertThat(module)
                .as("回归：%s 必须受模块门禁约束，不能再返回 null（放行）", prefix)
                .isNotNull()
                .isNotEqualTo(LicenseModuleMap.MODULE_UNCLASSIFIED);
    }

    @Test
    @DisplayName("每个模块名下至少有一个前缀（防模块名写错导致永不命中）")
    void everyModuleHasAtLeastOnePrefix() {
        var perModule = LicenseModuleMap.prefixesPerModule();
        for (String module : LicenseService.ALL_MODULES) {
            assertThat(perModule.getOrDefault(module, 0))
                    .as("模块 %s 在 LicenseModuleMap 中无任何前缀 —— 该模块授权将永不生效",
                            module)
                    .isPositive();
        }
        assertThat(perModule.keySet())
                .as("LicenseModuleMap 出现了 ALL_MODULES 之外的模块名")
                .isSubsetOf(LicenseService.ALL_MODULES);
    }

    @Test
    @DisplayName("覆盖率基线：已登记前缀数不得低于修复时的 53（防被误删）")
    void registeredPrefixCountBaseline() {
        assertThat(LicenseModuleMap.registeredPrefixes())
                .as("已登记前缀数下降说明有人删了映射条目 —— 会导致端点重新变成免费功能")
                .hasSizeGreaterThanOrEqualTo(49);
    }

    @Test
    @DisplayName("裸基路径控制器：HardwareDataController 的 4 个真实资源前缀已归类（core）")
    void bareBaseControllerResourcesAreClassified() {
        // HardwareDataController 类级注解为 @RequestMapping("/api/v1")，真实资源在
        // 方法级路径：/radar/*、/rotor/*、/lidar/*、/imu/*。
        // 若把它们登记成 "/api/v1" 会把整个 API 面吞进 core（危险），故按第二段登记。
        for (String seg : List.of("radar", "rotor", "lidar", "imu")) {
            assertThat(LicenseModuleMap.resolve(BARE_API_BASE + "/" + seg + "/probe"))
                    .as("硬件抽象前缀 /api/v1/%s 必须归入 core，而非未归类", seg)
                    .isEqualTo(LicenseModuleMap.CORE);
        }

        // 反向约束：裸 "/api/v1" 本身绝不能被登记，否则全部端点都会退回单一模块
        assertThat(LicenseModuleMap.registeredPrefixes())
                .as("不得登记裸 /api/v1 —— 会把整个 API 面纳入同一模块，使分档失效")
                .doesNotContain(BARE_API_BASE);
    }

    @Test
    @DisplayName("扫描器正确性：方法级子路径回溯出的前缀必须带资源段")
    void methodLevelScanDerivesResourcePrefixes() {
        Set<String> derived = resourcePrefixesFromMethodLevel(
                "@PostMapping(\"/radar/config\")\n@GetMapping(\"/rotor/telemetry/{sysid}\")");
        assertThat(derived)
                .as("方法级扫描应回溯出 /api/v1/radar 与 /api/v1/rotor")
                .containsExactlyInAnyOrder("/api/v1/radar", "/api/v1/rotor");

        // 空/根子路径不应产出前缀（避免凭空造出 "/api/v1" 这种危险值）
        assertThat(resourcePrefixesFromMethodLevel("@GetMapping(\"/\")\n@GetMapping(\"\")"))
                .as("空子路径不得产出前缀")
                .isEmpty();
    }
}

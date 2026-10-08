package io.aerofleet.cloud.license;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * License 模块拆分迁移预检 CLI（2026-10-07 配套）。
 *
 * <p><b>为什么需要这个工具</b>：模块由 5 扩为 7（{@code network} 拆出 {@code mesh}、
 * {@code emergency} 拆出 {@code orch}）是 BREAKING CHANGE——拆分前签发的授权不再对应
 * 任何档位，部署时会 fail-closed 拒启。但那时的报错只告诉运维"模块集合不对应任何
 * 可售档位"，<b>不告诉他该重签成哪一档</b>。运维面对的是一整段报错 + 一个合法的
 * 老 license key，无从下手。
 *
 * <p>本工具做的事：读入一把 license key → 验签 → 解析出原模块集合 → 打印
 * 「原授权是什么、对应新档位吗、若不对应该重签成哪档、给出可直接粘贴的签发命令」。
 *
 * <p><b>为什么不走 {@link LicenseService} 构造器</b>：构造器本身就会因非档位组合
 * fail-closed 抛 {@link IllegalStateException}——正是我们要诊断的那个情况。
 * 迁移工具必须能在"启动会失败"的前提下把信息取出来，所以这里只复用
 * {@link LicenseSigner#verify}（验签）与 {@link LicenseTier}（档位定义）。
 *
 * <p><b>安全边界</b>：本工具<b>不需要私钥</b>，也不签发任何东西——
 * 它只做只读诊断与建议输出。私钥仅在运维执行 {@link LicenseIssuer} 时才需要。
 *
 * <p>用法：
 * <pre>
 *   java -cp cloud-backend.jar io.aerofleet.cloud.license.LicenseMigrationPreflight \
 *        --license-key-file &lt;path&gt; [--public-key-file &lt;path&gt;]
 * </pre>
 * 不提供 {@code --public-key-file} 时<b>跳过验签</b>并显式打印告警——
 * 这时结论仅供参考，不可作为"这份 license 确实是我们签发的"的凭据。
 */
public final class LicenseMigrationPreflight {

    private static final ObjectMapper MAPPER = issuerMapper();

    /** 拆分前的 5 模块集合（2026-10-07 之前的所有授权都落在这个形态或其子集）。 */
    private static final Set<String> LEGACY_MODULES =
            Set.of("core", "fleet", "emergency", "network", "advanced");

    private LicenseMigrationPreflight() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            usage();
            return;
        }
        System.exit(run(args));
    }

    /**
     * 实际逻辑，返回进程退出码；不调用 {@link System#exit}。
     * <p>
     * 拆出 {@code run} 是为了让这段逻辑可被单测直接调用：若 {@code main} 内部
     * 直接 {@code System.exit}，测试走到"key 残缺"分支时会把 surefire 的 fork
     * 一起杀掉（表现为 "The forked VM terminated without properly saying goodbye"），
     * 报错信息还完全指不到真正的原因。
     *
     * @return 0 = 无需重签发；1 = 需重签发或 key 无法解析；2 = 用法/IO 错误
     */
    static int run(String[] args) {
        String keyFile = null;
        String publicKeyFile = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--license-key-file" -> keyFile = requireValue(args, ++i, "--license-key-file");
                case "--public-key-file" -> publicKeyFile = requireValue(args, ++i, "--public-key-file");
                default -> {
                    System.err.println("未知参数: " + args[i]);
                    usage();
                    return 2;
                }
            }
        }
        if (keyFile == null) {
            System.err.println("缺少必填参数 --license-key-file");
            usage();
            return 2;
        }

        String rawKey;
        try {
            rawKey = Files.readString(Path.of(keyFile), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            System.err.println("读取失败: " + e.getMessage());
            return 2;
        }
        // 容忍运维误把 key 直接粘进文件时带的换行/引号
        rawKey = rawKey.replace("\r", "").replace("\n", "").trim();
        if (rawKey.startsWith("\"") && rawKey.endsWith("\"") && rawKey.length() > 1) {
            rawKey = rawKey.substring(1, rawKey.length() - 1);
        }

        LicenseInfo info;
        try {
            info = parse(rawKey);
        } catch (Exception e) {
            System.out.println("❌ 无法解析 license key");
            System.out.println("   原因: " + e.getMessage());
            System.out.println();
            System.out.println("这不是模块拆分迁移问题——请先确认 key 本身完整、未被换行符或引号截断。");
            return 1;
        }

        return report(info, rawKey, publicKeyFile);
    }

    /** 解析 license key 并（若提供公钥）验签。不走 LicenseService 构造器，见类注释。 */
    private static LicenseInfo parse(String key) throws Exception {
        int sep = key.indexOf(LicenseIssuer.KEY_SEPARATOR);
        if (sep <= 0) {
            throw new IllegalArgumentException(
                    "key 不含签名分隔符 '" + LicenseIssuer.KEY_SEPARATOR
                            + "'：看起来是 dev 模式的无签名旧格式，或 key 被截断");
        }
        String payloadB64 = key.substring(0, sep);
        String sigB64 = key.substring(sep + LicenseIssuer.KEY_SEPARATOR.length());
        byte[] payload = Base64.getDecoder().decode(payloadB64);
        LicenseInfo info = MAPPER.readValue(new String(payload, StandardCharsets.UTF_8), LicenseInfo.class);
        info.setSignature(sigB64);
        info.setLicenseKey(key);
        return info;
    }

    /**
     * 打印诊断报告。
     *
     * @return 0 = 无需重签发；1 = 需重签发
     */
    private static int report(LicenseInfo info, String rawKey, String publicKeyFile) {
        Set<String> modules = info.getModules() == null
                ? Set.of()
                : new TreeSet<>(info.getModules());

        System.out.println("═══ License 模块拆分迁移预检 ═══");
        System.out.println();
        System.out.println("租户 / 被授权方 : " + nvl(info.getTenantId()) + " / " + nvl(info.getIssuedTo()));
        System.out.println("License ID      : " + nvl(info.getLicenseId()));
        System.out.println("产品            : " + nvl(info.getProductName()));
        System.out.println("有效期至        : " + (info.getExpiryDate() == null ? "(无)" : info.getExpiryDate()));
        System.out.println("设备上限        : " + info.getMaxDevices());
        System.out.println("原模块集合      : " + (modules.isEmpty() ? "(空)" : String.join(", ", modules)));

        // 验签：提供公钥才做，否则显式声明结论仅供参考。
        // 三种结果都必须有输出 —— 尤其"验签失败"：若只在抛异常时打印，
        // verify() 返回 false 的情形会**一行都不打**，运维会以为验过了。
        boolean signatureOk;
        boolean signatureAttempted = publicKeyFile != null;
        if (publicKeyFile != null) {
            try {
                // 抑制 LicenseSigner 构造器的 INFO 日志：它会插进报告中间，
                // 把"验签"那一行挤到日志行后面，运维读起来像坏了。
                // 这里只做只读验签，不需要 signer 的启动横幅。
                LicenseSigner verifier = quietSigner(
                        Files.readString(Path.of(publicKeyFile), StandardCharsets.UTF_8).trim(), MAPPER);
                signatureOk = verifier.verify(info, info.getSignature());
                System.out.println("验签            : " + (signatureOk ? "✅ 通过" : "❌ 失败（签名与公钥不匹配）"));
            } catch (Exception e) {
                System.out.println("验签            : ❌ 无法完成（" + e.getMessage() + "）");
            }
        } else {
            System.out.println("验签            : ⚠️  未提供 --public-key-file，已跳过");
            System.out.println("                   ⇒ 下方结论仅供参考，不能证明该 key 确由本方签发。");
        }
        System.out.println();

        // 先判 inferred 是否为 null，再算 mismatch —— mismatchOf(null, ...) 会返回
        // "未知档位"而非 null，虽然结论恰好相同，但把 null 检查放在前面才读得通。
        String inferred = LicenseTier.inferTier(modules);
        boolean matchesTier = inferred != null && LicenseTier.mismatchOf(inferred, modules) == null;

        if (matchesTier) {
            System.out.println("✅ 结论：与新档位一致，无需重签发");
            System.out.println();
            System.out.println("   对应档位: " + LicenseTier.displayName(inferred) + " (" + inferred + ")");
            System.out.println("   模块集合: " + String.join(", ", LicenseTier.modulesOf(inferred)));
            System.out.println();
            System.out.println("直接部署即可。若启动仍失败，请排查签名/密钥配置，而非档位问题。");
            return 0;
        }

        // 不匹配：给出可执行的重签发建议
        String target = suggestTargetTier(modules);
        System.out.println("❌ 结论：不对应任何可售档位 —— 这份 license 必须重签发");
        System.out.println();
        // 原因要说的是"这份授权的模块集合与三档都不相等"，而不是把 mismatchOf
        // 拿一个占位档位名去调（那会输出 "未知档位: ?"，对运维毫无意义）。
        System.out.println("   原因: 该模块集合与三个可售档位都不相等");
        System.out.println("         基础版 = " + String.join(",", LicenseTier.modulesOf(LicenseTier.BASIC)));
        System.out.println("         应急版 = " + String.join(",", LicenseTier.modulesOf(LicenseTier.EMERGENCY)));
        System.out.println("         完整版 = " + String.join(",", LicenseTier.modulesOf(LicenseTier.FULL)));
        System.out.println();

        Set<String> unknown = new TreeSet<>(modules);
        unknown.removeAll(LicenseService.ALL_MODULES);
        if (!unknown.isEmpty()) {
            System.out.println("   ⚠️ 含未知模块名（不在合法模块表内）: " + String.join(", ", unknown));
            System.out.println("      签发时会被 LicenseIssuer 拒绝，请先核对 license 来源。");
            System.out.println();
        }

        if (target != null) {
            System.out.println("   建议重签档位: " + LicenseTier.displayName(target) + " (" + target + ")");
            System.out.println("   应含模块    : " + String.join(", ", LicenseTier.modulesOf(target)));
            System.out.println();
            System.out.println("   签发命令（需私钥；请按实际租户与有效期替换）:");
            System.out.println("     java -cp cloud-backend.jar io.aerofleet.cloud.license.LicenseIssuer \\");
            System.out.println("        --private-key-file <私钥文件> \\");
            if (publicKeyFile != null) {
                System.out.println("        --public-key-file <公钥文件> \\");
            }
            System.out.println("        --tenant-id " + nvl(info.getTenantId()) + " \\");
            System.out.println("        --issued-to " + nvl(info.getIssuedTo()) + " \\");
            System.out.println("        --max-devices " + info.getMaxDevices() + " \\");
            System.out.println("        --expiry " + (info.getExpiryDate() == null
                    ? "2030-01-01T00:00:00Z" : info.getExpiryDate().toString()) + " \\");
            System.out.println("        --modules " + String.join(",", LicenseTier.modulesOf(target)));
        } else {
            System.out.println("   ⚠️ 无法自动推断目标档位：该模块组合在拆分前就不是任一档位的精确集合。");
            System.out.println("      请人工按 PRODUCT-POSITIONING §5.1.1 决定档位后重签发。");
        }
        System.out.println();
        System.out.println("   注：不要试图给旧模块集合加兼容映射——旧集合里 network 含 mesh、");
        System.out.println("       emergency 含 M9 编排，与新边界不再等价，兼容等于把定价边界改回错的一侧。");
        return 1;
    }

    /**
     * 从旧模块集合推断"应当重签成哪一档"。
     * <p>
     * 规则（按拆分前后的对应关系）：
     * <ul>
     *   <li>含 {@code network} 或 {@code advanced} → 完整版（拆分前这两个只在完整版）；</li>
     *   <li>否则含 {@code emergency} → 应急版（拆分前 emergency 在应急版起）；</li>
     *   <li>否则 → 基础版（拆分前只有 core+fleet）。</li>
     * </ul>
     * 含未知模块名时返回 null（不能瞎猜，签发侧会直接拒绝）。
     */
    static String suggestTargetTier(Set<String> legacyModules) {
        Set<String> mods = new LinkedHashSet<>(legacyModules);
        if (!LicenseService.ALL_MODULES.containsAll(mods)) {
            return null;
        }
        if (mods.contains("network") || mods.contains("advanced")) {
            return LicenseTier.FULL;
        }
        if (mods.contains("emergency")) {
            return LicenseTier.EMERGENCY;
        }
        return LicenseTier.BASIC;
    }

    /**
     * 构造一个不打印启动横幅的 {@link LicenseSigner}。
     * <p>
     * {@code LicenseSigner} 的构造器会 {@code log.info("...已加载外部公钥")}。
     * 本工具的产物是一份要给人读的文本报告，中途冒出一行日志会破坏可读性；
     * 但给全局 SLF4J 配置加过滤器会影响同一 JVM 内的其他组件，副作用更大。
     * 故临时把该 logger 的级别抬到 OFF，构造完立即恢复。
     */
    private static LicenseSigner quietSigner(String publicKeyBase64, ObjectMapper mapper) {
        // setLevel/getEffectiveLevel 不在 org.slf4j.Logger 接口上（那是 slf4j-api 的
        // API 面），而在 logback 的 ch.qos.logback.classic.Logger 上。Spring Boot 默认
        // 绑定 logback，故此处取其实例；若运行期换成了别的绑定，安静降级为不静音。
        Object maybeLogback = org.slf4j.LoggerFactory.getLogger(LicenseSigner.class);
        if (!(maybeLogback instanceof ch.qos.logback.classic.Logger log)) {
            return new LicenseSigner(publicKeyBase64, mapper);
        }
        ch.qos.logback.classic.Level previous = log.getEffectiveLevel();
        log.setLevel(ch.qos.logback.classic.Level.OFF);
        try {
            return new LicenseSigner(publicKeyBase64, mapper);
        } finally {
            log.setLevel(previous);
        }
    }

    private static String requireValue(String[] args, int i, String flag) {
        if (i >= args.length) {
            System.err.println("参数 " + flag + " 缺少值");
            throw new IllegalArgumentException("参数 " + flag + " 缺少值");
        }
        return args[i];
    }

    private static String nvl(String s) {
        return s == null || s.isBlank() ? "(未设置)" : s;
    }

    private static void usage() {
        System.out.println("LicenseMigrationPreflight —— license 模块拆分迁移预检（只读诊断，不签发）");
        System.out.println();
        System.out.println("用途: 2026-10-07 模块由 5 扩为 7（network 拆出 mesh、emergency 拆出 orch）");
        System.out.println("      是 BREAKING CHANGE。本工具读入一把老 license key，告诉你它对应新档位吗、");
        System.out.println("      若不对应该重签成哪一档，并打印可直接使用的签发命令。");
        System.out.println();
        System.out.println("必填:");
        System.out.println("  --license-key-file <path>   license key 文件（内容即 key 本身）");
        System.out.println();
        System.out.println("可选:");
        System.out.println("  --public-key-file <path>    公钥文件（PKIX Base64）。提供则验签；");
        System.out.println("                              不提供则跳过验签并显式告警（结论仅供参考）");
        System.out.println();
        System.out.println("注意: 本工具不需要私钥，也不签发任何东西。真正签发用 LicenseIssuer。");
    }

    /** 与 {@link LicenseIssuer} 共用同一套 mapper，避免两侧解析口径分叉。 */
    private static ObjectMapper issuerMapper() {
        return LicenseIssuer.issuerMapper();
    }
}
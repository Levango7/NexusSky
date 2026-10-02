package io.aerofleet.cloud.license;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

/**
 * License 端到端签发工具：生产私钥进，可部署的 license key 出。
 * <p>
 * 补齐商业授权链条的最后一环。此前仓库里只有两半：
 * <ul>
 *   <li>{@link LicenseKeyGenerator}——生成 RSA-2048 密钥对（公钥给部署端，
 *       私钥给签发方），但<b>不出 key</b>；</li>
 *   <li>{@link LicenseService#generateLicenseKey}——能组装 key，但
 *       <b>仅开发模式可用</b>（其 {@link LicenseSigner} 在生产模式下没有私钥），
 *       且失败静默返回 null。</li>
 * </ul>
 * 于是一个真实的运营问题无解：<b>拿着生产私钥，怎么给客户签一份部署端能验过的 key？</b>
 * 本类就是答案：加载私钥（PKCS#8 Base64，{@link LicenseKeyGenerator} 的输出格式）
 * → 组装 {@link LicenseInfo} → 签名 → 输出可直接粘贴进
 * {@code aerofleet.license.key} 的 key 串。
 * <p>
 * <b>组装路径唯一</b>：{@link #issue} 是全仓唯一的 payload+signature 拼接实现，
 * {@code LicenseService#generateLicenseKey} 委托到这里。签发/解析两侧若各自手拼
 * 一份格式，一旦漂移就会出现「签出的 key 部署端验不过」——这类问题只在真实签发时
 * 爆，测试环境永远复现不了。
 * <p>
 * <b>签出即自验</b>：每次签发后立刻按 {@code LicenseService#parseSignedLicense}
 * 的验签路径（反序列化 payload → setSignature → verify）用配套公钥验一遍，
 * 保证输出的 key 一定能被配置了该公钥的部署接受。私钥与公钥不配对在这里就地暴露，
 * 而不是等到客户部署失败。
 * <p>
 * 典型用法：
 * <pre>
 *   # 1. 生成密钥对（一次），公钥配置到所有部署端
 *   mvn -pl cloud-backend exec:java -Dexec.mainClass=io.aerofleet.cloud.license.LicenseKeyGenerator
 *
 *   # 2. 签发（私钥文件 + 授权参数）
 *   mvn -pl cloud-backend exec:java -Dexec.mainClass=io.aerofleet.cloud.license.LicenseIssuer \
 *       -Dexec.args="--private-key-file private.key --tenant-id acme --max-devices 50 --valid-days 365"
 * </pre>
 *
 * @author AeroFleet Cloud Team
 */
public final class LicenseIssuer {

    /**
     * License key 中 payload 与 signature 的分隔符。
     * <p>
     * wire format 的唯一定义：{@code Base64(JSON(payload)) + KEY_SEPARATOR + Base64(sig)}。
     * 签发端（本类）与解析端（{@code LicenseService#parseSignedLicense}）共用，
     * 不允许各写一份字面量——格式分裂是这一域最贵的故障类型。
     */
    static final String KEY_SEPARATOR = ".";

    private static final String KEY_ALGORITHM = "RSA";
    private static final String DEFAULT_PRODUCT_NAME = "AeroFleet Cloud";

    private LicenseIssuer() {
        // 工具类，禁止实例化
    }

    // ===== 核心签发 =====

    /**
     * 将 {@link LicenseInfo} 组装为可部署的签名版 license key。
     * <p>
     * 格式与 {@code LicenseService#parseSignedLicense} 严格对偶：
     * {@code Base64(JSON(payload)) + "." + Base64(RSA-SHA256(SignJson(payload)))}，
     * 其中 SignJson 排除 {@code signature/signerCert/licenseKey} 三字段
     * （见 {@link LicenseSigner#sign}）。
     * <p>
     * 签出后立即自验（反序列化 payload → setSignature → 用签名器配套公钥 verify），
     * 自验不过直接抛异常，绝不让一份「自己都验不过」的 key 离开签发工具。
     *
     * @param info         待签发的 License 信息（不含 signature/signerCert/licenseKey）
     * @param signer       持有私钥的签名器（签发模式构造器或开发模式构造器）
     * @param objectMapper 用于 payload 序列化的 ObjectMapper（需支持 Instant，
     *                      建议 {@link #issuerMapper()}）
     * @return 可直接配置到 {@code aerofleet.license.key} 的 key 串
     * @throws IllegalArgumentException 参数为空或 payload 序列化失败
     * @throws IllegalStateException    签名器无私钥，或自验失败（私钥/公钥不配对）
     */
    public static String issue(LicenseInfo info, LicenseSigner signer, ObjectMapper objectMapper) {
        if (info == null || signer == null || objectMapper == null) {
            throw new IllegalArgumentException("info / signer / objectMapper 均不能为空");
        }
        final String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(info);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("License payload 序列化失败: " + e.getMessage(), e);
        }
        final String signature = signer.sign(info);

        // 自验：与 parseSignedLicense 的验签路径逐步一致——
        // readValue(payloadJson) → setSignature(signature) → verify。
        // 签名输入是 serializeForSigning(info)，自验输入是 serializeForSigning(roundTrip)：
        // 两者都等于「payloadJson 移除三字段」，字节级一致，verify 必须通过。
        try {
            LicenseInfo roundTrip = objectMapper.readValue(payloadJson, LicenseInfo.class);
            roundTrip.setSignature(signature);
            if (!signer.verify(roundTrip, signature)) {
                throw new IllegalStateException("License 自验失败：签出的 key 无法通过配套公钥验签"
                        + "（私钥与公钥不配对？）——拒绝输出");
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("License 自验失败：payload 无法反序列化: " + e.getMessage(), e);
        }

        String payloadBase64 = Base64.getEncoder().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return payloadBase64 + KEY_SEPARATOR + signature;
    }

    /**
     * 签发用 ObjectMapper：与 Spring Boot 自动配置的序列化行为对齐
     * （注册 jsr310 模块、Instant 写成 ISO-8601 字符串而非数值时间戳）。
     * <p>
     * 部署端反序列化 payload 用的就是 Spring 的 mapper——签发端若用裸
     * {@code new ObjectMapper()}，Instant 直接序列化失败；若开着时间戳模式，
     * payload 里的日期变成数值。两端格式对齐从源头保证 key 的可部署性。
     *
     * @return 签发专用 ObjectMapper
     */
    public static ObjectMapper issuerMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // ===== CLI =====

    /**
     * CLI 入口。
     * <p>
     * 成功：stdout 输出 key 串与部署端配置片段；失败：stderr 输出原因并退出码 1。
     *
     * @param args 命令行参数，见 {@link #usage()}
     */
    public static void main(String[] args) {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            usage();
            return;
        }
        try {
            IssuerParams params = parseArgs(args);
            PrivateKey privateKey = loadPrivateKey(params.privateKeyFile());
            PublicKey publicKey = params.publicKeyFile() != null
                    ? loadPublicKey(params.publicKeyFile())
                    : derivePublicKey(privateKey);
            if (publicKey == null) {
                throw new IllegalArgumentException(
                        "无法从私钥推导公钥（非 CRT 格式的私钥）：请同时提供 --public-key-file");
            }
            ObjectMapper mapper = issuerMapper();
            String key = issue(buildInfo(params), new LicenseSigner(privateKey, publicKey, mapper), mapper);
            printResult(params, key, publicKey);
        } catch (Exception e) {
            System.err.println("签发失败: " + e.getMessage());
            System.err.println("运行 --help 查看用法");
            System.exit(1);
        }
    }

    /**
     * CLI 参数（{@link #parseArgs} 的产物，全部已校验）。
     *
     * @param privateKeyFile     私钥文件（PKCS#8 Base64，必填）
     * @param publicKeyFile      公钥文件（X.509 Base64，可空=由私钥 CRT 参数推导）
     * @param tenantId           租户 ID
     * @param productName        产品名
     * @param issuedTo           被授权方（可空）
     * @param maxDevices         设备上限（0=无限制）
     * @param maxApiCallsPerDay  每日 API 调用上限（0=无限制）
     * @param maxConcurrentDrones 最大并发无人机数（0=无限制）
     * @param modules            授权模块集合
     * @param expiryDate         过期时间（必填之一：--expiry / --valid-days）
     * @param licenseId          License ID（可空=自动生成 lic-&lt;tenant&gt;-&lt;ts&gt;）
     */
    record IssuerParams(
            Path privateKeyFile,
            Path publicKeyFile,
            String tenantId,
            String productName,
            String issuedTo,
            int maxDevices,
            int maxApiCallsPerDay,
            int maxConcurrentDrones,
            Set<String> modules,
            Instant expiryDate,
            String licenseId) {
    }

    /**
     * 解析并校验 CLI 参数。
     * <p>
     * 必填项缺失直接抛异常，不给「危险的缺省」：
     * <ul>
     *   <li>{@code --max-devices} 不设默认值——漏配时静默签出无限设备授权，
     *       正是本仓反复修的 fail-open 模式；</li>
     *   <li>{@code --expiry} / {@code --valid-days} 必须二选一——同理，
     *       永久授权不能是「忘了写」的结果，只能是运营方的明确决定。</li>
     * </ul>
     *
     * @param args 命令行参数
     * @return 校验通过的参数
     * @throws IllegalArgumentException 参数缺失、互斥冲突或取值非法
     */
    static IssuerParams parseArgs(String[] args) {
        Path privateKeyFile = null;
        Path publicKeyFile = null;
        String tenantId = null;
        String productName = DEFAULT_PRODUCT_NAME;
        String issuedTo = null;
        Integer maxDevices = null;
        int maxApiCallsPerDay = 0;
        int maxConcurrentDrones = 0;
        Set<String> modules = new HashSet<>(LicenseService.ALL_MODULES);
        Instant expiryDate = null;
        Long validDays = null;
        String licenseId = null;

        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            if ("--private-key-file".equals(flag)) {
                privateKeyFile = Path.of(nextValue(args, flag, ++i));
            } else if ("--public-key-file".equals(flag)) {
                publicKeyFile = Path.of(nextValue(args, flag, ++i));
            } else if ("--tenant-id".equals(flag)) {
                tenantId = nextValue(args, flag, ++i);
            } else if ("--product-name".equals(flag)) {
                productName = nextValue(args, flag, ++i);
            } else if ("--issued-to".equals(flag)) {
                issuedTo = nextValue(args, flag, ++i);
            } else if ("--max-devices".equals(flag)) {
                maxDevices = parseInt(nextValue(args, flag, ++i), flag);
            } else if ("--max-api-calls-per-day".equals(flag)) {
                maxApiCallsPerDay = parseInt(nextValue(args, flag, ++i), flag);
            } else if ("--max-concurrent-drones".equals(flag)) {
                maxConcurrentDrones = parseInt(nextValue(args, flag, ++i), flag);
            } else if ("--modules".equals(flag)) {
                modules = parseModules(nextValue(args, flag, ++i));
            } else if ("--expiry".equals(flag)) {
                expiryDate = parseInstant(nextValue(args, flag, ++i), flag);
            } else if ("--valid-days".equals(flag)) {
                validDays = parseLong(nextValue(args, flag, ++i), flag);
            } else if ("--license-id".equals(flag)) {
                licenseId = nextValue(args, flag, ++i);
            } else {
                throw new IllegalArgumentException("未知参数: " + flag + "（运行 --help 查看用法）");
            }
        }

        if (privateKeyFile == null) {
            throw new IllegalArgumentException("缺少 --private-key-file（LicenseKeyGenerator 输出的 PKCS#8 Base64 私钥文件）");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("缺少 --tenant-id");
        }
        if (maxDevices == null) {
            throw new IllegalArgumentException("缺少 --max-devices（0=无限制；不给默认值，"
                    + "避免漏配时静默签出无限设备授权）");
        }
        if (maxDevices < 0 || maxApiCallsPerDay < 0 || maxConcurrentDrones < 0) {
            throw new IllegalArgumentException("设备/API/并发上限不能为负数");
        }
        if (expiryDate != null && validDays != null) {
            throw new IllegalArgumentException("--expiry 与 --valid-days 只能二选一");
        }
        if (expiryDate == null && validDays == null) {
            throw new IllegalArgumentException("必须指定 --expiry <ISO-8601> 或 --valid-days <n> 之一"
                    + "（不指定=永久授权，风险过高，不做缺省）");
        }
        if (validDays != null) {
            if (validDays <= 0) {
                throw new IllegalArgumentException("--valid-days 必须为正整数");
            }
            expiryDate = Instant.now().plus(Duration.ofDays(validDays));
        }
        return new IssuerParams(privateKeyFile, publicKeyFile, tenantId, productName, issuedTo,
                maxDevices, maxApiCallsPerDay, maxConcurrentDrones, modules, expiryDate, licenseId);
    }

    /**
     * 由 CLI 参数构造待签发的 {@link LicenseInfo}。
     * <p>
     * 固定填两个字段：
     * <ul>
     *   <li>{@code active=true}——{@code LicenseService#validateLicense} 要求
     *       active 才有效，签发即激活；</li>
     *   <li>{@code issuedAt=now}——签发时刻由工具记录，不接受外部指定，
     *       避免「签发时间」被倒填成任意值参与签名。</li>
     * </ul>
     */
    static LicenseInfo buildInfo(IssuerParams params) {
        LicenseInfo info = new LicenseInfo();
        info.setTenantId(params.tenantId());
        info.setProductName(params.productName());
        info.setIssuedTo(params.issuedTo());
        info.setMaxDevices(params.maxDevices());
        info.setMaxApiCallsPerDay(params.maxApiCallsPerDay());
        info.setMaxConcurrentDrones(params.maxConcurrentDrones());
        info.setModules(new HashSet<>(params.modules()));
        info.setExpiryDate(params.expiryDate());
        info.setIssuedAt(Instant.now());
        info.setActive(true);
        info.setLicenseId(params.licenseId() != null ? params.licenseId()
                : "lic-" + params.tenantId() + "-" + System.currentTimeMillis());
        return info;
    }

    // ===== 密钥加载 =====

    /**
     * 从文件加载 PKCS#8 私钥（{@link LicenseKeyGenerator} 的输出格式）。
     *
     * @param file 私钥文件（Base64，容忍 PEM 头尾与换行）
     * @return RSA 私钥
     * @throws IllegalArgumentException 文件不可读或格式非法
     */
    static PrivateKey loadPrivateKey(Path file) {
        try {
            byte[] der = Base64.getDecoder().decode(readBase64File(file));
            return KeyFactory.getInstance(KEY_ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("私钥文件不是合法的 Base64: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalArgumentException("私钥解析失败（需 PKCS#8 Base64，"
                    + "即 LicenseKeyGenerator 的输出格式）: " + e.getMessage(), e);
        }
    }

    /**
     * 从文件加载 X.509 公钥。
     *
     * @param file 公钥文件（Base64，容忍 PEM 头尾与换行）
     * @return RSA 公钥
     * @throws IllegalArgumentException 文件不可读或格式非法
     */
    static PublicKey loadPublicKey(Path file) {
        try {
            byte[] der = Base64.getDecoder().decode(readBase64File(file));
            return KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(new X509EncodedKeySpec(der));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("公钥文件不是合法的 Base64: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalArgumentException("公钥解析失败（需 X.509 Base64）: " + e.getMessage(), e);
        }
    }

    /**
     * 从 CRT 私钥推导配套公钥（modulus + publicExponent）。
     * <p>
     * {@code KeyPairGenerator} 产出的 PKCS#8 私钥必含 CRT 参数，所以只给私钥文件
     * 也能自验；非 CRT 私钥返回 null，调用方要求补 {@code --public-key-file}。
     *
     * @param privateKey RSA 私钥
     * @return 配套公钥；私钥不含 CRT 参数时返回 null
     */
    static PublicKey derivePublicKey(PrivateKey privateKey) {
        if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
            return null;
        }
        try {
            return KeyFactory.getInstance(KEY_ALGORITHM)
                    .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读取密钥文件并清洗为纯 Base64：剥掉可选的 PEM 头尾行、压缩全部空白。
     * {@link LicenseKeyGenerator} 输出的是裸 Base64，但密钥文件经人工/邮件/编辑器
     * 经手后常被包上 PEM 头或折行——签发工具不该因为这种无害的包装而失败。
     */
    private static String readBase64File(Path file) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取密钥文件失败: " + file + ": " + e.getMessage(), e);
        }
        String cleaned = content
                .replaceAll("-----(BEGIN|END)[^-]*-----", "")
                .replaceAll("\\s+", "");
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("密钥文件为空: " + file);
        }
        return cleaned;
    }

    // ===== CLI 辅助 =====

    private static String nextValue(String[] args, String flag, int valueIndex) {
        if (valueIndex >= args.length) {
            throw new IllegalArgumentException("参数 " + flag + " 缺少取值");
        }
        return args[valueIndex];
    }

    private static int parseInt(String value, String flag) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 " + flag + " 的取值必须是整数: " + value, e);
        }
    }

    private static long parseLong(String value, String flag) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 " + flag + " 的取值必须是整数: " + value, e);
        }
    }

    private static Instant parseInstant(String value, String flag) {
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("参数 " + flag + " 的取值必须是 ISO-8601 时间"
                    + "（如 2030-01-01T00:00:00Z）: " + value, e);
        }
    }

    /**
     * 解析逗号分隔的模块列表，并对照 {@link LicenseService#ALL_MODULES} 校验。
     * <p>
     * 模块名打错字（"fleett"）若放行，签出的 License 在线上表现为「模块被静默拒绝」
     * （hasModule=false → 拦截器不放行），客户侧极难排查——在签发时即报错最便宜。
     */
    private static Set<String> parseModules(String value) {
        Set<String> result = new HashSet<>();
        for (String module : value.split(",")) {
            String trimmed = module.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!LicenseService.ALL_MODULES.contains(trimmed)) {
                throw new IllegalArgumentException("未知模块: " + trimmed
                        + "（合法模块: " + String.join(", ", LicenseService.ALL_MODULES) + "）");
            }
            result.add(trimmed);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("--modules 至少需要一个模块名");
        }
        return result;
    }

    private static void printResult(IssuerParams params, String key, PublicKey publicKey) {
        System.out.println("=== License 签发成功 ===");
        System.out.println(buildInfo(params));
        System.out.println();
        System.out.println("License Key（配置到部署端 aerofleet.license.key）:");
        System.out.println(key);
        System.out.println();
        System.out.println("=== 部署端 Spring Boot 配置 ===");
        System.out.println("aerofleet.license.key=" + key);
        System.out.println("aerofleet.license.public-key=" + LicenseKeyGenerator.encodePublicKey(publicKey));
        System.out.println("aerofleet.security.dev-mode=false");
    }

    private static void usage() {
        System.out.println("LicenseIssuer —— 用生产私钥签发可部署的 license key");
        System.out.println();
        System.out.println("必填:");
        System.out.println("  --private-key-file <path>   PKCS#8 Base64 私钥文件（LicenseKeyGenerator 输出）");
        System.out.println("  --tenant-id <id>            租户 ID");
        System.out.println("  --max-devices <n>           设备上限（0=无限制；必填，防止漏配时静默签出无限授权）");
        System.out.println("  --expiry <ISO-8601> | --valid-days <n>   二选一（不做「永久」缺省）");
        System.out.println();
        System.out.println("可选:");
        System.out.println("  --public-key-file <path>    X.509 Base64 公钥文件（缺省由私钥 CRT 参数推导，用于签出后自验）");
        System.out.println("  --product-name <name>       产品名（默认 " + DEFAULT_PRODUCT_NAME + "）");
        System.out.println("  --issued-to <name>          被授权方名称");
        System.out.println("  --modules <a,b,...>         授权模块，逗号分隔（默认全模块: "
                + String.join(",", LicenseService.ALL_MODULES) + "）");
        System.out.println("  --max-api-calls-per-day <n> 每日 API 调用上限（默认 0=无限制）");
        System.out.println("  --max-concurrent-drones <n> 最大并发无人机数（默认 0=无限制）");
        System.out.println("  --license-id <id>           License ID（默认 lic-<tenant>-<时间戳>）");
    }
}

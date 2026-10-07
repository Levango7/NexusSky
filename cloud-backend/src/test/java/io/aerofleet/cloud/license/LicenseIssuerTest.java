package io.aerofleet.cloud.license;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LicenseIssuer} 端到端签发测试：生产私钥进，可部署 key 出。
 * <p>
 * 收口 license 轮未闭合项 ①——此前仓库里只有「密钥对生成」（{@link LicenseKeyGenerator}，
 * 不出 key）和「开发模式组装」（{@code LicenseService#generateLicenseKey}，生产签名器
 * 无私钥），<b>「拿着生产私钥签一份部署端能验过的 key」这条真实运营路径无解</b>。
 * 本类钉住它：
 * <ol>
 *   <li><b>端到端</b>：issue() 输出的 key 直接喂给生产模式 {@link LicenseService}
 *       （构造器 fail-closed 加载路径），必须解析、验签、字段全对——签发端用
 *       {@link LicenseIssuer#issuerMapper()}（CLI 同款），部署端用 Spring 对齐
 *       mapper，跨 mapper 验证 wire format 的真实兼容性；</li>
 *   <li><b>fail-closed 复验</b>：篡改真实签发物 → 部署端必须拒绝启动；</li>
 *   <li><b>CLI 参数 fail-fast</b>：缺必填项/互斥冲突/模块打错字就地报错，
 *       不给「静默签出无限设备/永久授权」的缺省；</li>
 *   <li><b>委托回归</b>：{@code generateLicenseKey} 已收敛到 {@code LicenseIssuer.issue}
 *       （唯一组装路径），dev 签发可用性与其 null 契约不得回归。</li>
 * </ol>
 */
@DisplayName("LicenseIssuer：端到端签发——生产私钥进，可部署 key 出")
class LicenseIssuerTest {

    private static final String HMAC_SECRET = "test-hmac-secret-at-least-32-characters-long";

    /**
     * 部署端 mapper：故意保持 Jackson 原生默认（WRITE_DATES_AS_TIMESTAMPS=开）——
     * 与签发端（ISO）日期模式相反。这是刻意的硬化钉子：签名规范化
     * （LicenseSigner#CANONICAL_MAPPER）必须与部署端 mapper 配置无关，
     * 否则「ISO 签发 + 时间戳模式部署」的合法 License 会验签必败、fail-closed
     * 拒绝启动——2026-10-03 本测试首跑即暴露此缺陷。
     * FAIL_ON_UNKNOWN_PROPERTIES 关闭与 Spring Boot 自动配置对齐。
     */
    private ObjectMapper deployMapper;

    /** 签发端 mapper：CLI 同款（Instant 写 ISO-8601 字符串） */
    private ObjectMapper issueMapper;

    private KeyPair keyPair;

    @BeforeEach
    void setUp() {
        deployMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        deployMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        issueMapper = LicenseIssuer.issuerMapper();
        keyPair = LicenseKeyGenerator.generateKeyPair();
    }

    /** 一份标准的待签发授权：acme 租户、50 设备、**基础版**模块、一年有效期 */
    private LicenseInfo sampleInfo() {
        LicenseInfo info = new LicenseInfo();
        info.setTenantId("acme");
        info.setProductName("AeroFleet Cloud Standard");
        info.setMaxDevices(50);
        // 用基础版的精确集合（2026-10-07 拆分后 = core+fleet+mesh+orch），
        // 否则部署端档位绑定会 fail-closed 拒启，签发-部署往返用例无法成立。
        info.setModules(new HashSet<>(LicenseTier.modulesOf(LicenseTier.BASIC)));
        info.setActive(true);
        info.setIssuedAt(Instant.now());
        info.setExpiryDate(Instant.now().plus(Duration.ofDays(365)));
        info.setIssuedTo("Acme Drone Co");
        info.setLicenseId("LIC-2026-0002");
        return info;
    }

    /** 用外部密钥对（签发模式构造器）+ CLI 同款 mapper 签发 */
    private String issueKey(LicenseInfo info) {
        return LicenseIssuer.issue(info,
                new LicenseSigner(keyPair.getPrivate(), keyPair.getPublic(), issueMapper), issueMapper);
    }

    /** 用配套公钥构造生产模式部署端（构造器内含 fail-closed 加载） */
    private LicenseService productionDeploy(String licenseKey) {
        return new LicenseService(licenseKey, HMAC_SECRET, false,
                LicenseKeyGenerator.encodePublicKey(keyPair.getPublic()), deployMapper);
    }

    // ────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("① issue()：签发 → 部署端解析/验签（生产路径端到端）")
    class IssueEndToEnd {

        @Test
        @DisplayName("签出的 key 被生产模式 LicenseService 接受，字段一致、验签通过、licenseKey 回填为整串")
        void issuedKeyAcceptedByProductionDeploy() {
            String key = issueKey(sampleInfo());

            assertNotNull(key);
            assertTrue(key.contains(LicenseIssuer.KEY_SEPARATOR));

            LicenseService deploy = productionDeploy(key);
            LicenseInfo deployed = deploy.getLicenseInfo();

            assertEquals("acme", deployed.getTenantId());
            assertEquals("AeroFleet Cloud Standard", deployed.getProductName());
            assertEquals(50, deployed.getMaxDevices());
            assertEquals("Acme Drone Co", deployed.getIssuedTo());
            assertEquals("LIC-2026-0002", deployed.getLicenseId());
            assertTrue(deployed.hasModule("core"));
            assertTrue(deployed.hasModule("fleet"));
            assertFalse(deployed.hasModule("advanced"));
            assertTrue(deployed.isActive());
            assertFalse(deployed.isExpired());
            // parseSignedLicense 在验签通过后把整串 key 回填进 licenseKey（信封字段）
            assertEquals(key, deployed.getLicenseKey());
            assertTrue(deploy.validateLicense(deployed));
            assertFalse(deploy.isDevLicense());
        }

        @Test
        @DisplayName("篡改 payload（maxDevices 50→5000）→ 部署端拒绝启动（fail-closed 经由真实签发物复验）")
        void tamperedPayloadRejectedAtStartup() {
            String key = issueKey(sampleInfo());
            int separatorIndex = key.indexOf(LicenseIssuer.KEY_SEPARATOR);
            String payloadJson = new String(
                    Base64.getDecoder().decode(key.substring(0, separatorIndex)), StandardCharsets.UTF_8);
            String tamperedJson = payloadJson.replace("\"maxDevices\":50", "\"maxDevices\":5000");
            String tamperedKey = Base64.getEncoder()
                    .encodeToString(tamperedJson.getBytes(StandardCharsets.UTF_8))
                    + key.substring(separatorIndex);

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> productionDeploy(tamperedKey));
            assertTrue(e.getMessage().contains("拒绝"));
        }

        @Test
        @DisplayName("私钥与公钥不配对 → 自验失败，拒绝输出（而不是等客户部署失败才发现）")
        void mismatchedKeyPairFailsSelfVerify() {
            KeyPair other = LicenseKeyGenerator.generateKeyPair();
            LicenseSigner mismatched = new LicenseSigner(
                    keyPair.getPrivate(), other.getPublic(), issueMapper);

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> LicenseIssuer.issue(sampleInfo(), mismatched, issueMapper));
            assertTrue(e.getMessage().contains("自验失败"));
        }

        @Test
        @DisplayName("过期时间已过也可签出（工具只保证密码学正确，不做商业判断），部署端解析成功但判定无效")
        void expiredLicenseIssuableButInvalidAtDeploy() {
            LicenseInfo expired = sampleInfo();
            expired.setExpiryDate(Instant.now().minus(Duration.ofDays(1)));

            LicenseService deploy = productionDeploy(issueKey(expired));
            LicenseInfo deployed = deploy.getLicenseInfo();

            assertTrue(deployed.isExpired());
            assertFalse(deploy.validateLicense(deployed));
        }
    }

    // ────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("② CLI 参数解析：必填不缺省、冲突就报错")
    class CliParsing {

        private String[] minimalArgs() {
            return new String[]{"--private-key-file", "private.key",
                    "--tenant-id", "acme", "--max-devices", "50", "--valid-days", "365"};
        }

        @Test
        @DisplayName("最小合法参数集 + 默认值（产品名/全模块/licenseId 生成规则/active=true）")
        void minimalArgsAndDefaults() {
            LicenseIssuer.IssuerParams params = LicenseIssuer.parseArgs(minimalArgs());

            assertEquals(Path.of("private.key"), params.privateKeyFile());
            assertEquals("acme", params.tenantId());
            assertEquals(50, params.maxDevices());
            assertEquals("AeroFleet Cloud", params.productName());
            assertEquals(LicenseService.ALL_MODULES, params.modules());
            assertNull(params.publicKeyFile());
            assertNull(params.issuedTo());
            assertNull(params.licenseId());
            assertEquals(0, params.maxApiCallsPerDay());
            assertEquals(0, params.maxConcurrentDrones());
            assertNotNull(params.expiryDate());

            LicenseInfo info = LicenseIssuer.buildInfo(params);
            assertEquals("acme", info.getTenantId());
            assertTrue(info.isActive());
            assertNotNull(info.getIssuedAt());
            assertTrue(info.getLicenseId().startsWith("lic-acme-"));
            assertEquals(LicenseService.ALL_MODULES, info.getModules());
        }

        @Test
        @DisplayName("缺 --private-key-file → 报错")
        void missingPrivateKeyFileRejected() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> LicenseIssuer.parseArgs(new String[]{
                            "--tenant-id", "acme", "--max-devices", "50", "--valid-days", "365"}));
            assertTrue(e.getMessage().contains("--private-key-file"));
        }

        @Test
        @DisplayName("缺 --tenant-id → 报错")
        void missingTenantIdRejected() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> LicenseIssuer.parseArgs(new String[]{
                            "--private-key-file", "private.key", "--max-devices", "50", "--valid-days", "365"}));
            assertTrue(e.getMessage().contains("--tenant-id"));
        }

        @Test
        @DisplayName("缺 --max-devices → 报错（不做 0=无限制 的缺省，防漏配签出无限授权）")
        void missingMaxDevicesRejected() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> LicenseIssuer.parseArgs(new String[]{
                            "--private-key-file", "private.key", "--tenant-id", "acme", "--valid-days", "365"}));
            assertTrue(e.getMessage().contains("--max-devices"));
        }

        @Test
        @DisplayName("--expiry 与 --valid-days 同时给 → 报错")
        void expiryAndValidDaysMutuallyExclusive() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> LicenseIssuer.parseArgs(new String[]{
                            "--private-key-file", "private.key", "--tenant-id", "acme", "--max-devices", "50",
                            "--expiry", "2030-01-01T00:00:00Z", "--valid-days", "365"}));
            assertTrue(e.getMessage().contains("二选一"));
        }

        @Test
        @DisplayName("既无 --expiry 也无 --valid-days → 报错（永久授权不能是「忘了写」的结果）")
        void expiryRequired() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> LicenseIssuer.parseArgs(new String[]{
                            "--private-key-file", "private.key", "--tenant-id", "acme", "--max-devices", "50"}));
            assertTrue(e.getMessage().contains("必须指定"));
        }

        @Test
        @DisplayName("--valid-days 365 → 过期时间 ≈ now + 365 天")
        void validDaysComputesExpiry() {
            Instant before = Instant.now();
            LicenseIssuer.IssuerParams params = LicenseIssuer.parseArgs(minimalArgs());
            Instant after = Instant.now();

            assertTrue(params.expiryDate().isAfter(before.plus(Duration.ofDays(364))));
            assertTrue(params.expiryDate().isBefore(after.plus(Duration.ofDays(366))));
        }

        @Test
        @DisplayName("--modules 打错字 → 就地报错并列出合法模块（防「模块被静默拒绝」排障黑洞）")
        void unknownModuleRejected() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> LicenseIssuer.parseArgs(new String[]{
                            "--private-key-file", "private.key", "--tenant-id", "acme", "--max-devices", "50",
                            "--valid-days", "365", "--modules", "core,fleett"}));
            assertTrue(e.getMessage().contains("fleett"));
            assertTrue(e.getMessage().contains("合法模块"));
        }
    }

    // ────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("③ 密钥文件加载与公钥推导（PEM 容忍 + CRT 推导）")
    class KeyLoading {

        @Test
        @DisplayName("PEM 头尾/折行可读，CRT 私钥推导出等值公钥，且从文件签出的 key 被生产部署接受")
        void pemTolerantLoadAndCrtDerivationEndToEnd() throws Exception {
            Path privateKeyFile = tempDir.resolve("private.key");
            Files.writeString(privateKeyFile, "-----BEGIN PRIVATE KEY-----\n"
                    + LicenseKeyGenerator.encodePrivateKey(keyPair.getPrivate())
                            .replaceAll("(.{64})", "$1\n")
                    + "\n-----END PRIVATE KEY-----\n");

            PrivateKey loaded = LicenseIssuer.loadPrivateKey(privateKeyFile);
            assertEquals("RSA", loaded.getAlgorithm());

            PublicKey derived = LicenseIssuer.derivePublicKey(loaded);
            assertNotNull(derived);
            assertArrayEquals(keyPair.getPublic().getEncoded(), derived.getEncoded());

            String key = LicenseIssuer.issue(sampleInfo(),
                    new LicenseSigner(loaded, derived, issueMapper), issueMapper);
            LicenseService deploy = new LicenseService(key, HMAC_SECRET, false,
                    LicenseKeyGenerator.encodePublicKey(derived), deployMapper);
            assertEquals("acme", deploy.getLicenseInfo().getTenantId());
        }

        @TempDir
        Path tempDir;
    }

    // ────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("④ generateLicenseKey 委托回归（组装路径收敛到 LicenseIssuer.issue 后不得漂移）")
    class DelegationRegression {

        @Test
        @DisplayName("dev 模式服务签出的 key → 生产模式部署端（配套公钥）解析、验签、字段全对")
        void devServiceIssuanceStillRoundTrips() {
            LicenseService devService = new LicenseService("", HMAC_SECRET, true, "", deployMapper);
            String devKey = devService.generateLicenseKey("acme", "AeroFleet Cloud Standard", 50,
                    Instant.now().plus(Duration.ofDays(365)), "Acme Drone Co");

            assertNotNull(devKey);

            // 生产模式部署：公钥取自 dev 服务的签名器（getLicenseSigner() 公开），
            // 构造器内走 fail-closed 加载 + 真实验签
            LicenseService deploy = new LicenseService(devKey, HMAC_SECRET, false,
                    devService.getLicenseSigner().getPublicKeyBase64(), deployMapper);
            LicenseInfo deployed = deploy.getLicenseInfo();

            assertEquals("acme", deployed.getTenantId());
            assertEquals("AeroFleet Cloud Standard", deployed.getProductName());
            assertEquals(50, deployed.getMaxDevices());
            assertTrue(deployed.hasModule("advanced"));
            assertFalse(deploy.isDevLicense());
        }

        @Test
        @DisplayName("生产模式服务（签名器无私钥）→ generateLicenseKey 保持 null 契约")
        void prodServiceWithoutPrivateKeyStillReturnsNull() {
            LicenseService prodOnly = new LicenseService("", HMAC_SECRET, false,
                    LicenseKeyGenerator.encodePublicKey(keyPair.getPublic()), deployMapper);

            assertNull(prodOnly.generateLicenseKey("acme", "AeroFleet Cloud Standard", 50,
                    Instant.now().plus(Duration.ofDays(365)), "Acme Drone Co"));
        }
    }
}

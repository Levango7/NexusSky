package io.aerofleet.cloud.license;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * License 模块的首批测试（本仓此前 0 例覆盖）。
 * <p>
 * 钉住两个此前**同时存在、互相掩盖**的缺陷：
 * <ol>
 *   <li><b>验签失败降级为 dev license</b>（fail-open）——dev license 是全模块、
 *       设备无限制、永不过期，等于「被篡改或损坏的 key 反而拿到最宽松授权」，
 *       商业门禁形同虚设。</li>
 *   <li><b>签名覆盖了 licenseKey</b>，而 {@code parseSignedLicense} 又在验签前把
 *       licenseKey 覆写成完整 key 串 → 签方与验签方的 licenseKey 必然不同 →
 *       <b>任何合法签名的 License 都验不过</b>。这个缺陷被上一条掩盖：验不过就降级，
 *       于是「签名功能从未成功过一次」这件事没有任何信号。</li>
 * </ol>
 * 两条一起修才自洽：只修 ① 会让系统拿着合法 License 也拒绝启动；只修 ② 会继续放行坏 key。
 */
@DisplayName("License：fail-closed 加载语义 + 签名信封解耦")
class LicenseServiceFailClosedTest {

    private static final String HMAC_SECRET = "test-hmac-secret-at-least-32-characters-long";

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        // 与 Spring Boot 自动配置的 ObjectMapper 对齐：FAIL_ON_UNKNOWN_PROPERTIES 关闭。
        // 用裸 new ObjectMapper() 会让本测试依赖一个生产环境并不存在的严格设置。
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** 构造一份待签发的 License */
    private LicenseInfo sampleLicense() {
        LicenseInfo info = new LicenseInfo();
        info.setTenantId("acme");
        info.setProductName("AeroFleet Cloud Standard");
        info.setMaxDevices(50);
        // 用**基础版的精确集合**（2026-10-07 拆分后 = core+fleet+mesh+orch）。
        // 本测试要验的是"签名有效即放行"，不是"模块集合随便填"——
        // 非档位集合会在构造器被档位绑定 fail-closed 拒启，那条由
        // LicenseTierBindingEnforcementTest 覆盖。
        info.setModules(new HashSet<>(LicenseTier.modulesOf(LicenseTier.BASIC)));
        info.setActive(true);
        info.setIssuedAt(Instant.parse("2026-01-01T00:00:00Z"));
        info.setExpiryDate(Instant.parse("2030-01-01T00:00:00Z"));
        info.setIssuedTo("Acme Drone Co");
        info.setLicenseId("LIC-2026-0001");
        return info;
    }

    /**
     * 用指定签发者签发一份 License key，格式与 LicenseService#parseSignedLicense 一致：
     * {@code Base64(JSON(payload)) + "." + Base64(signature)}
     */
    private String issueKey(LicenseSigner issuer, LicenseInfo info) throws Exception {
        String payload = mapper.writeValueAsString(info);
        String signature = issuer.sign(info);
        return Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + signature;
    }

    private LicenseService service(String licenseKey, String publicKeyBase64, boolean devMode) {
        return new LicenseService(licenseKey, HMAC_SECRET, devMode, publicKeyBase64, mapper);
    }

    // ────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("① 未配置 key = 开发版（开发/CI 的正常路径，不受影响）")
    class UnconfiguredKey {

        @Test
        @DisplayName("空 key → dev license，不抛异常")
        void blankKeyYieldsDevLicense() {
            LicenseService svc = service("", "", true);
            assertTrue(svc.isDevLicense());
        }

        @Test
        @DisplayName("null key → dev license，不抛异常")
        void nullKeyYieldsDevLicense() {
            LicenseService svc = service(null, "", true);
            assertTrue(svc.isDevLicense());
        }

        @Test
        @DisplayName("dev license 语义：全模块、设备无限制、永不过期")
        void devLicenseSemantics() {
            LicenseService svc = service("", "", true);
            LicenseInfo info = svc.getLicenseInfo();
            assertTrue(info.hasModule("core"));
            assertTrue(info.hasModule("advanced"));
            assertEquals(0, info.getMaxDevices());
            assertEquals(null, info.getExpiryDate());
            assertTrue(svc.validateLicense(info));
        }
    }

    // ────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("② 已配置 key 但无效 = fail-closed，拒绝启动（不再降级）")
    class InvalidKeyFailsClosed {

        @Test
        @DisplayName("验签失败 → 抛 IllegalStateException，而不是给出 dev license")
        void signatureMismatchFailsClosed() {
            // 用 A 签发者签 key，却让服务用 B 的公钥验签
            LicenseSigner issuer = new LicenseSigner(mapper);
            LicenseSigner other = new LicenseSigner(mapper);
            String key = assertDoesNotThrow(() -> issueKey(issuer, sampleLicense()));

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> service(key, other.getPublicKeyBase64(), true));
            assertTrue(e.getMessage().contains("fail-closed"));
        }

        @Test
        @DisplayName("payload 被篡改（设备上限 50 → 9999）→ 拒绝启动")
        void tamperedPayloadFailsClosed() throws Exception {
            LicenseSigner issuer = new LicenseSigner(mapper);
            LicenseInfo info = sampleLicense();
            String signature = issuer.sign(info);
            String payload = mapper.writeValueAsString(info);

            // 攻击者保留签名，只改 payload 里的 maxDevices
            LicenseInfo tampered = mapper.readValue(payload, LicenseInfo.class);
            tampered.setMaxDevices(9999);
            String tamperedPayload = mapper.writeValueAsString(tampered);

            String key = Base64.getEncoder()
                    .encodeToString(tamperedPayload.getBytes(StandardCharsets.UTF_8)) + "." + signature;

            assertThrows(IllegalStateException.class,
                    () -> service(key, issuer.getPublicKeyBase64(), true));
        }

        @Test
        @DisplayName("非 dev 模式下的旧格式（无签名）→ 拒绝启动")
        void legacyUnsignedKeyFailsClosedInProdMode() throws Exception {
            String payload = mapper.writeValueAsString(sampleLicense());
            String key = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));

            assertThrows(IllegalStateException.class, () -> service(key, "", false));
        }

        @Test
        @DisplayName("结构不合法（纯垃圾串）→ 拒绝启动")
        void garbageKeyFailsClosed() {
            assertThrows(IllegalStateException.class, () -> service("!!!not-a-license!!!", "", true));
        }

        @Test
        @DisplayName("失败信息可操作：点名配置项与排查方向")
        void failureMessageIsActionable() {
            LicenseSigner issuer = new LicenseSigner(mapper);
            LicenseSigner other = new LicenseSigner(mapper);
            String key;
            try {
                key = issueKey(issuer, sampleLicense());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> service(key, other.getPublicKeyBase64(), true));
            assertTrue(e.getMessage().contains("aerofleet.license.key"),
                    "报错应点名 aerofleet.license.key，实际: " + e.getMessage());
            assertTrue(e.getMessage().contains("public-key"),
                    "报错应提示检查 aerofleet.license.public-key，实际: " + e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("③ 合法签名的 License 必须能通过（缺陷②的回归防线）")
    class ValidSignedLicense {

        @Test
        @DisplayName("签发方签的 key + 配对的公钥 → 加载为真实 License（非 dev）")
        void validSignedLicenseIsAccepted() throws Exception {
            LicenseSigner issuer = new LicenseSigner(mapper);
            String key = issueKey(issuer, sampleLicense());

            LicenseService svc = service(key, issuer.getPublicKeyBase64(), true);

            assertFalse(svc.isDevLicense(), "合法签名 License 不应被判为 dev 版");
            LicenseInfo info = svc.getLicenseInfo();
            assertEquals("acme", info.getTenantId());
            assertEquals("AeroFleet Cloud Standard", info.getProductName());
            assertEquals(50, info.getMaxDevices());
            assertTrue(info.hasModule("core"));
            assertTrue(info.hasModule("fleet"));
            assertFalse(info.hasModule("emergency"), "未授权模块不应出现");
            assertNotNull(info.getLicenseKey());
        }

        @Test
        @DisplayName("回归：licenseKey 不参与签名计算（否则合法 key 永远验不过）")
        void licenseKeyIsExcludedFromSignature() throws Exception {
            // 直接验签：同一份 payload，无论 licenseKey 字段为何，签名都应通过。
            // 修复前这里必然失败——这正是「任何合法签名都验不过」的最小复现。
            LicenseSigner issuer = new LicenseSigner(mapper);
            LicenseInfo signed = sampleLicense();
            String signature = issuer.sign(signed);

            LicenseInfo asDeserialized = mapper.readValue(mapper.writeValueAsString(signed), LicenseInfo.class);
            asDeserialized.setSignature(signature);
            assertTrue(issuer.verify(asDeserialized, signature),
                    "反序列化后应仍能验签通过");

            // 把 licenseKey 改成完全不同的值，签名仍应通过（信封与内容解耦）
            LicenseInfo withOtherKey = mapper.readValue(mapper.writeValueAsString(signed), LicenseInfo.class);
            withOtherKey.setLicenseKey("some-totally-different-envelope");
            withOtherKey.setSignature(signature);
            assertTrue(issuer.verify(withOtherKey, signature),
                    "licenseKey 是信封，不应影响内容签名——修复前这条会红");
        }

        @Test
        @DisplayName("内容被改动则签名失效（解耦不等于放松）")
        void contentTamperingStillDetected() throws Exception {
            LicenseSigner issuer = new LicenseSigner(mapper);
            LicenseInfo info = sampleLicense();
            String signature = issuer.sign(info);

            info.setTenantId("attacker");
            assertFalse(issuer.verify(info, signature), "改动 tenantId 必须验不过");
        }

        @Test
        @DisplayName("过期 License 仍能验签通过（签名不得覆盖随时间变化的派生量）")
        void expiredLicenseStillVerifiesButFailsValidation() throws Exception {
            LicenseSigner issuer = new LicenseSigner(mapper);
            LicenseInfo expired = sampleLicense();
            expired.setExpiryDate(Instant.parse("2020-01-01T00:00:00Z"));
            String key = issueKey(issuer, expired);

            LicenseService svc = service(key, issuer.getPublicKeyBase64(), true);

            // 关键区分：必须是「加载成功 + 判定过期」，而不是「验签失败」。
            // 修复前 isExpired() 参与签名，签发时与验签时取值不同 → 验签失败 →
            // 旧实现再降级成全模块 dev license（过期即提权）。
            assertFalse(svc.isDevLicense(), "过期 License 不得被降级/提权成 dev license");
            assertTrue(svc.getLicenseInfo().isExpired(), "该 License 应判定为已过期");
            assertFalse(svc.validateLicense(svc.getLicenseInfo()),
                    "过期 License 必须判为无效（这由拦截器转 403）");
        }

        @Test
        @DisplayName("回归：派生量 expired 不得进入序列化（时间炸弹的最小复现）")
        void derivedExpiredIsNotSerialized() throws Exception {
            // isExpired() 依赖 Instant.now()，若被序列化进 payload，它就会在
            // 「签发 → 到期」之间改变，导致合法 License 在到期瞬间验签失败。
            String json = mapper.writeValueAsString(sampleLicense());
            assertFalse(json.contains("\"expired\""),
                    "expired 是派生量，不应被序列化——否则签名覆盖了随时间变化的值，实际: " + json);
        }
    }
}

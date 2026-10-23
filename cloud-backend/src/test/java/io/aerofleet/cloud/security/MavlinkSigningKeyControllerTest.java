package io.aerofleet.cloud.security;

import io.aerofleet.mavlink.security.MavlinkSignatureConfig;
import io.aerofleet.mavlink.security.SigningKeyManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MavlinkSigningKeyController} 单元测试。
 *
 * <p><b>验证重点是安全属性，不是"能返回 200"</b>：
 * <ol>
 *   <li><b>响应体永不含密钥材料</b>——成功、状态查询两条路都逐字断言；</li>
 *   <li><b>门禁顺序</b>——未启用 503 / 单机 409 / 无 JWT 401 必须各自独立生效，
 *       不能因为前一道放过就裸奔；</li>
 *   <li><b>坏输入不碰文件</b>——sysid 越界、密钥库损坏时原文件逐字节不变。</li>
 * </ol>
 *
 * <p>风格沿用仓内控制器测试惯例：直接 new + 反射注入（{@code @Autowired(required=false)}
 * 字段无 setter），不起 Spring 上下文。
 */
@DisplayName("MavlinkSigningKeyController: 签名密钥轮换端点")
class MavlinkSigningKeyControllerTest {

    private MavlinkSigningKeyController controller;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        controller = new MavlinkSigningKeyController();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void inject(String field, Object value) throws Exception {
        Field f = MavlinkSigningKeyController.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(controller, value);
    }

    /** 装配"签名已启用 + 多机模式"。 */
    private SigningKeyManager enableMultiMode(Path store) throws Exception {
        MavlinkSignatureConfig cfg = new MavlinkSignatureConfig();
        cfg.setEnabled(true);
        cfg.setKeyStorePath(store.toString());
        cfg.setRejectUnsigned(true);
        SigningKeyManager km = new SigningKeyManager(store, null);
        inject("signatureConfig", cfg);
        inject("signingKeyManager", km);
        return km;
    }

    private Path storeWith(String json) throws Exception {
        Path p = tempDir.resolve("signing-keys.json");
        Files.writeString(p, json);
        return p;
    }

    private void loginAsAdmin() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .claim("preferred_username", "operator-a")
                .subject("operator-a")
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(jwt, "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
    }

    private Map<String, Object> bodyOfKey(String key) {
        Map<String, Object> b = new HashMap<>();
        b.put("key", key);
        return b;
    }

    // ==================== 状态查询 ====================

    @Test
    @DisplayName("1. 签名未启用：查询返回 enabled=false（状态应始终可读，不 503）")
    void listKeys_whenDisabled_returnsEnabledFalse() {
        ResponseEntity<Map<String, Object>> resp = controller.listKeys();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("enabled", false);
        assertThat(resp.getBody().get("entries")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("2. 多机模式：列出指纹与 linkId，响应体不含任何密钥")
    void listKeys_multiMode_listsFingerprintsWithoutKeyMaterial() throws Exception {
        Path store = storeWith("""
                {
                  "version": 1,
                  "sysids": {
                    "7": {"key": "secret-key-for-seven", "linkId": 7}
                  }
                }
                """);
        enableMultiMode(store);

        ResponseEntity<Map<String, Object>> resp = controller.listKeys();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("enabled", true);
        assertThat(resp.getBody()).containsEntry("multiKeyMode", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> entries = (Map<String, Object>) resp.getBody().get("entries");
        assertThat(entries).containsKey("7");
        @SuppressWarnings("unchecked")
        Map<String, Object> seven = (Map<String, Object>) entries.get("7");
        assertThat(seven).containsEntry("linkId", 7);
        assertThat(seven.get("fingerprint")).isEqualTo(
                io.aerofleet.mavlink.security.SigningKeyStoreWriter.fingerprint("secret-key-for-seven"));

        // 整个响应体不得出现密钥本体
        assertThat(resp.getBody().toString()).doesNotContain("secret-key-for-seven");
    }

    @Test
    @DisplayName("3. 单机模式：multiKeyMode=false，entries 为空")
    void listKeys_singleKeyMode_notMulti() throws Exception {
        MavlinkSignatureConfig cfg = new MavlinkSignatureConfig();
        cfg.setEnabled(true);
        cfg.setSecretKey("single-secret");
        inject("signatureConfig", cfg);
        inject("signingKeyManager", new SigningKeyManager("single-secret"));

        ResponseEntity<Map<String, Object>> resp = controller.listKeys();

        assertThat(resp.getBody()).containsEntry("enabled", true);
        assertThat(resp.getBody()).containsEntry("multiKeyMode", false);
        assertThat(((Map<?, ?>) resp.getBody().get("entries"))).isEmpty();
    }

    // ==================== 轮换门禁 ====================

    @Test
    @DisplayName("4. 签名未启用：轮换 503（不替一个未生效的特性做运维操作）")
    void rotate_whenDisabled_returns503() {
        ResponseEntity<Map<String, Object>> resp =
                controller.rotateKey(7, bodyOfKey("whatever"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resp.getBody().get("error").toString()).contains("未启用");
    }

    @Test
    @DisplayName("5. 单机模式：轮换 409（该模式密钥来自配置，需重新部署）")
    void rotate_singleKeyMode_returns409() throws Exception {
        MavlinkSignatureConfig cfg = new MavlinkSignatureConfig();
        cfg.setEnabled(true);
        cfg.setSecretKey("single-secret");
        inject("signatureConfig", cfg);
        inject("signingKeyManager", new SigningKeyManager("single-secret"));
        loginAsAdmin();

        ResponseEntity<Map<String, Object>> resp =
                controller.rotateKey(7, bodyOfKey("whatever"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody().get("error").toString()).contains("单密钥模式");
    }

    @Test
    @DisplayName("6. 未登录：轮换 401（@RequireRole 之外再查一次 JWT，与 API Key 同口径）")
    void rotate_withoutJwt_returns401() throws Exception {
        Path store = storeWith("""
                {
                  "version": 1,
                  "sysids": {"7": {"key": "old", "linkId": 7}}
                }
                """);
        enableMultiMode(store);
        // 不设置 SecurityContext

        ResponseEntity<Map<String, Object>> resp = controller.rotateKey(7, bodyOfKey("new-key"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody().get("error").toString()).contains("JWT");
    }

    @Test
    @DisplayName("7. 请求体缺 key：400")
    void rotate_missingKey_returns400() throws Exception {
        Path store = storeWith("""
                {
                  "version": 1,
                  "sysids": {"7": {"key": "old", "linkId": 7}}
                }
                """);
        enableMultiMode(store);
        loginAsAdmin();

        assertThat(controller.rotateKey(7, new HashMap<>()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.rotateKey(7, bodyOfKey("   ")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.rotateKey(7, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ==================== 成功路径 ====================

    @Test
    @DisplayName("8. 轮换成功：返回新旧指纹，响应体不含密钥，并提示硬切换无宽限期")
    void rotate_success_returnsFingerprintsAndWarnsHardCutover() throws Exception {
        Path store = storeWith("""
                {
                  "version": 1,
                  "sysids": {"7": {"key": "old-key-7", "linkId": 7}}
                }
                """);
        enableMultiMode(store);
        loginAsAdmin();

        ResponseEntity<Map<String, Object>> resp = controller.rotateKey(7, bodyOfKey("new-key-7"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("sysid", 7);
        assertThat(resp.getBody()).containsEntry("linkId", 7);
        assertThat(resp.getBody().get("oldFingerprint")).isEqualTo(
                io.aerofleet.mavlink.security.SigningKeyStoreWriter.fingerprint("old-key-7"));
        assertThat(resp.getBody().get("newFingerprint")).isEqualTo(
                io.aerofleet.mavlink.security.SigningKeyStoreWriter.fingerprint("new-key-7"));
        // 协议边界必须写在响应里，别让运维误以为有宽限期
        assertThat(resp.getBody().get("warning").toString()).contains("硬切换");

        // 新旧密钥都不得出现在响应体
        assertThat(resp.getBody().toString())
                .doesNotContain("new-key-7").doesNotContain("old-key-7");
        // 文件里确实写入了新密钥
        assertThat(Files.readString(store)).contains("new-key-7");
    }

    // ==================== 坏输入不碰文件 ====================

    @Test
    @DisplayName("9. sysid 越界：409，且文件逐字节不变")
    void rotate_badSysid_returns409AndFileUntouched() throws Exception {
        Path store = storeWith("""
                {
                  "version": 1,
                  "sysids": {"7": {"key": "old", "linkId": 7}}
                }
                """);
        enableMultiMode(store);
        loginAsAdmin();
        byte[] before = Files.readAllBytes(store);

        assertThat(controller.rotateKey(0, bodyOfKey("new")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(controller.rotateKey(999, bodyOfKey("new")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(Files.readAllBytes(store)).isEqualTo(before);
    }

    @Test
    @DisplayName("10. 密钥库损坏：409，且文件逐字节不变（不覆盖看不懂的文件）")
    void rotate_corruptStore_returns409AndFileUntouched() throws Exception {
        Path store = storeWith("not-json-at-all");
        enableMultiMode(store);
        loginAsAdmin();
        byte[] before = Files.readAllBytes(store);

        ResponseEntity<Map<String, Object>> resp = controller.rotateKey(7, bodyOfKey("new"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(Files.readAllBytes(store)).isEqualTo(before);
    }

    // ==================== 发放账：V28 指纹列的写入方 ====================

    @Test
    @DisplayName("11. 轮换成功：指纹真的写进发放账（此前 recordSigningKeyFingerprint 零调用方）")
    void rotate_success_recordsFingerprintIntoLedger() throws Exception {
        Path store = storeWith("""
                {
                  "version": 1,
                  "sysids": {"7": {"key": "old-key-7", "linkId": 7}}
                }
                """);
        enableMultiMode(store);
        io.aerofleet.cloud.gateway.DeviceRegistry registry = new io.aerofleet.cloud.gateway.DeviceRegistry();
        inject("deviceRegistry", registry);
        loginAsAdmin();

        ResponseEntity<Map<String, Object>> resp = controller.rotateKey(7, bodyOfKey("new-key-7"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(registry.signingKeyFingerprint(7)).isEqualTo(
                io.aerofleet.mavlink.security.SigningKeyStoreWriter.fingerprint("new-key-7"));
        // persist=false 是出厂默认：账只能进内存，响应必须如实这么写，不能含糊成"记上了"
        assertThat(resp.getBody()).containsEntry("provisioningLedger", "MEMORY_ONLY");
    }

    @Test
    @DisplayName("12. persist=true 时响应改口 PERSISTED，且写入调用确实发生（交互断言）")
    void rotate_persisting_reportsPersistedAndCallsRegistry() throws Exception {
        Path store = storeWith("""
                {
                  "version": 1,
                  "sysids": {"7": {"key": "old-key-7", "linkId": 7}}
                }
                """);
        enableMultiMode(store);
        io.aerofleet.cloud.gateway.DeviceRegistry registry =
                mock(io.aerofleet.cloud.gateway.DeviceRegistry.class);
        when(registry.isPersisting()).thenReturn(true);
        inject("deviceRegistry", registry);
        loginAsAdmin();

        ResponseEntity<Map<String, Object>> resp = controller.rotateKey(7, bodyOfKey("new-key-7"));

        assertThat(resp.getBody()).containsEntry("provisioningLedger", "PERSISTED");
        verify(registry).recordSigningKeyFingerprint(eq(7),
                eq(io.aerofleet.mavlink.security.SigningKeyStoreWriter.fingerprint("new-key-7")));
    }

    @Test
    @DisplayName("13. 轮换被任何一道门拒掉时，发放账一个字节都不许写（反向守卫）")
    void rotate_rejectedByAnyGate_writesNothingIntoLedger() throws Exception {
        Path store = storeWith("""
                {
                  "version": 1,
                  "sysids": {"7": {"key": "old-key-7", "linkId": 7}}
                }
                """);

        // 503：签名未启用（连 signingKeyManager 都不注入）
        io.aerofleet.cloud.gateway.DeviceRegistry r1 =
                mock(io.aerofleet.cloud.gateway.DeviceRegistry.class);
        inject("deviceRegistry", r1);
        loginAsAdmin();
        assertThat(controller.rotateKey(7, bodyOfKey("new")).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(r1, never()).recordSigningKeyFingerprint(anyInt(), any());

        // 409：单密钥模式
        enableMultiMode(store);
        MavlinkSignatureConfig single = new MavlinkSignatureConfig();
        single.setEnabled(true);
        single.setSecretKey("single-secret");
        inject("signatureConfig", single);
        inject("signingKeyManager", new SigningKeyManager("single-secret"));
        assertThat(controller.rotateKey(7, bodyOfKey("new")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        verify(r1, never()).recordSigningKeyFingerprint(anyInt(), any());

        // 401：没有 JWT
        enableMultiMode(store);
        SecurityContextHolder.clearContext();
        assertThat(controller.rotateKey(7, bodyOfKey("new")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(r1, never()).recordSigningKeyFingerprint(anyInt(), any());

        // 400：请求体缺 key / 空白
        loginAsAdmin();
        assertThat(controller.rotateKey(7, new HashMap<>()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.rotateKey(7, bodyOfKey("  ")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(r1, never()).recordSigningKeyFingerprint(anyInt(), any());

        // 409：sysid 越界 + 密钥库损坏（写盘都失败，更不能记账）
        assertThat(controller.rotateKey(0, bodyOfKey("new")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        inject("signingKeyManager", new SigningKeyManager(storeWith("not-json"), null));
        assertThat(controller.rotateKey(7, bodyOfKey("new")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        verify(r1, never()).recordSigningKeyFingerprint(anyInt(), any());
    }

    @Test
    @DisplayName("14. registry 不可用时轮换仍成功，但响应明说 UNAVAILABLE（不假装记上了）")
    void rotate_withoutRegistry_stillSucceedsButSaysUnavailable() throws Exception {
        Path store = storeWith("""
                {
                  "version": 1,
                  "sysids": {"7": {"key": "old-key-7", "linkId": 7}}
                }
                """);
        enableMultiMode(store);
        // 不注入 deviceRegistry
        loginAsAdmin();

        ResponseEntity<Map<String, Object>> resp = controller.rotateKey(7, bodyOfKey("new-key-7"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("provisioningLedger", "UNAVAILABLE");
    }

    // ==================== 发放配置单读面 ====================

    @Test
    @DisplayName("15. 配置单把「配过但没观测到」与「真看到过签名帧」分成两态")
    void provisioning_splitsIssuedUnverifiedFromVerified() throws Exception {
        Path store = storeWith("""
                {
                  "version": 1,
                  "sysids": {
                    "7": {"key": "stale-7", "linkId": 7},
                    "8": {"key": "secret-8", "linkId": 8}
                  }
                }
                """);
        enableMultiMode(store);
        io.aerofleet.cloud.gateway.DeviceRegistry registry = new io.aerofleet.cloud.gateway.DeviceRegistry();
        inject("deviceRegistry", registry);
        loginAsAdmin();
        controller.rotateKey(7, bodyOfKey("secret-7"));   // 7：地面配过（写到与 keystore 一致的新密钥）
        registry.markSigningVerified(8);                  // 8：真见过它的签名帧（密钥只来自 keystore）

        ResponseEntity<Map<String, Object>> resp = controller.provisioningSheet();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> devices = (List<Map<String, Object>>) resp.getBody().get("devices");
        assertThat(devices).hasSize(2);
        Map<String, Object> seven = devices.get(0);
        Map<String, Object> eight = devices.get(1);
        assertThat(seven).containsEntry("sysid", 7).containsEntry("state", "ISSUED_UNVERIFIED");
        assertThat(seven.get("issuedFingerprint")).isEqualTo(
                io.aerofleet.mavlink.security.SigningKeyStoreWriter.fingerprint("secret-7"));
        assertThat(eight).containsEntry("sysid", 8).containsEntry("state", "VERIFIED");
        // 8 从没被轮换过 ⇒ 发放账里没有它，只有 keystore 里有：issuedFingerprint 必须诚实为 null
        assertThat(eight).containsEntry("issuedFingerprint", null);
        assertThat(resp.getBody().get("summary").toString())
                .contains("devices=2").contains("verified=1").contains("issuedUnverified=1");
    }

    @Test
    @DisplayName("16. 发放账里的指纹与 keystore 当前值不一致 ⇒ 单独标出来（配过但后来换过）")
    void provisioning_flagsFingerprintMismatch() throws Exception {
        Path store = storeWith("""
                {
                  "version": 1,
                  "sysids": {"7": {"key": "key-current", "linkId": 7}}
                }
                """);
        enableMultiMode(store);
        io.aerofleet.cloud.gateway.DeviceRegistry registry = new io.aerofleet.cloud.gateway.DeviceRegistry();
        registry.recordSigningKeyFingerprint(7, "deadbeef");   // 账上记的是另一把
        inject("deviceRegistry", registry);

        ResponseEntity<Map<String, Object>> resp = controller.provisioningSheet();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> devices = (List<Map<String, Object>>) resp.getBody().get("devices");
        assertThat(devices.get(0)).containsEntry("fingerprintMismatch", true);
        assertThat(devices.get(0)).containsEntry("issuedFingerprint", "deadbeef");
        assertThat(devices.get(0).get("keyStoreFingerprint")).isEqualTo(
                io.aerofleet.mavlink.security.SigningKeyStoreWriter.fingerprint("key-current"));
        assertThat(resp.getBody().get("summary").toString()).contains("fingerprintMismatch=1");
    }

    @Test
    @DisplayName("17. 配置单响应体逐字不含密钥材料")
    void provisioning_neverLeaksKeyMaterial() throws Exception {
        Path store = storeWith("""
                {
                  "version": 1,
                  "sysids": {"7": {"key": "top-secret-key-7", "linkId": 7}}
                }
                """);
        enableMultiMode(store);
        io.aerofleet.cloud.gateway.DeviceRegistry registry = new io.aerofleet.cloud.gateway.DeviceRegistry();
        registry.recordSigningKeyFingerprint(7, "issued-fp-7");
        inject("deviceRegistry", registry);

        ResponseEntity<Map<String, Object>> resp = controller.provisioningSheet();

        assertThat(resp.getBody().toString()).doesNotContain("top-secret-key-7");
    }

    @Test
    @DisplayName("18. 新端点的门禁：配置单是管理面（ADMIN），读状态仍是 OBSERVER")
    void provisioning_isGatedAsAdminSurface() throws Exception {
        assertThat(MavlinkSigningKeyController.class
                .getMethod("provisioningSheet")
                .getAnnotation(RequireRole.class).value())
                .isEqualTo(Role.ADMIN);
        assertThat(MavlinkSigningKeyController.class
                .getMethod("listKeys")
                .getAnnotation(RequireRole.class).value())
                .isEqualTo(Role.OBSERVER);
    }
}

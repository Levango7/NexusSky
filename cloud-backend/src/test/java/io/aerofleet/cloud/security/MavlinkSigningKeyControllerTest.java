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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}

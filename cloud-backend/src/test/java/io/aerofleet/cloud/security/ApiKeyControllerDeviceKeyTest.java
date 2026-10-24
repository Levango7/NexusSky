package io.aerofleet.cloud.security;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ApiKeyController} 设备 Key 签发/轮换路径的行为测试。
 * <p>
 * 收口"整部署一把共享摄取 key"轮的核心用例：按设备（sysid）签发、绑定设备租户、
 * role 固定 OPERATOR、轮换宽限期（新旧并存）与立即失效、撤销/轮换后的缓存失效
 * （本 JVM 即时生效的依据）、跨租户防护。哈希口径与 {@link ApiKeyFilter} 一致性
 * 用独立复算钉住——签出的 Key 必须能被认证侧认出来。
 */
@DisplayName("ApiKeyController 设备 Key 签发/轮换/撤销")
class ApiKeyControllerDeviceKeyTest {

    private static final int SYSID = 42;
    private static final int DEVICE_TENANT = 7;

    private ApiKeyRepository apiKeyRepository;
    private UserRepository userRepository;
    private DeviceRegistry deviceRegistry;
    private ApiKeyCache apiKeyCache;
    private ApiKeyController controller;

    @BeforeEach
    void setUp() {
        apiKeyRepository = mock(ApiKeyRepository.class);
        userRepository = mock(UserRepository.class);
        deviceRegistry = mock(DeviceRegistry.class);
        apiKeyCache = mock(ApiKeyCache.class);
        controller = new ApiKeyController();
        ReflectionTestUtils.setField(controller, "apiKeyRepository", apiKeyRepository);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "deviceRegistry", deviceRegistry);
        ReflectionTestUtils.setField(controller, "apiKeyCache", apiKeyCache);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    // --- 工具 ---

    private void loginAs(String username, Integer tenantId, String role) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(username);
        if (tenantId != null) {
            builder.claim("tenant_id", tenantId);
        }
        if (role != null) {
            builder.claim("role", role);
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(builder.build(), null));
    }

    /** 注册表口径的设备桩：已登记 + 指定归属租户（null=未归属）。 */
    private void knownDevice(Integer tenantId) {
        when(deviceRegistry.isKnownDevice(SYSID)).thenReturn(true);
        when(deviceRegistry.tenantOf(SYSID)).thenReturn(tenantId);
    }

    private ApiKeyEntity existingKey(Integer tenantId, Integer sysid, Instant expiresAt) {
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setKeyId("nsk_7_oldkey01");
        entity.setKeyHash("old-key-hash");
        entity.setTenantId(tenantId);
        entity.setUserId(null);
        entity.setSysid(sysid);
        entity.setName("device-key");
        entity.setScopes("[ingest]");
        entity.setRole("OPERATOR");
        entity.setCreatedAt(Instant.now().minus(30, ChronoUnit.DAYS));
        entity.setExpiresAt(expiresAt);
        entity.setRevoked(false);
        entity.setMaskedKey("nsk_****old1");
        return entity;
    }

    private Map<String, Object> body(Object... pairs) {
        Map<String, Object> body = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            body.put((String) pairs[i], pairs[i + 1]);
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseBody(org.springframework.http.ResponseEntity<Map<String, Object>> response) {
        return response.getBody();
    }

    // --- 设备 Key 签发 ---

    @Test
    @DisplayName("设备 Key：绑定设备租户、role 固定 OPERATOR、哈希与认证侧口径一致")
    void mintsDeviceKeyBoundToTenant() {
        loginAs("admin", DEVICE_TENANT, "ADMIN");
        knownDevice(DEVICE_TENANT);

        var response = controller.createApiKey(body("name", "drone-42", "sysid", SYSID));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        ArgumentCaptor<ApiKeyEntity> captor = ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(apiKeyRepository).save(captor.capture());
        ApiKeyEntity saved = captor.getValue();

        assertThat(saved.getSysid()).isEqualTo(SYSID);
        assertThat(saved.getTenantId()).isEqualTo(DEVICE_TENANT);
        assertThat(saved.getRole()).isEqualTo("OPERATOR");
        assertThat(saved.getUserId()).isNull();
        assertThat(saved.isRevoked()).isFalse();
        assertThat(saved.getExpiresAt()).isAfter(Instant.now().plus(360, ChronoUnit.DAYS));

        // 明文只出现一次（响应里），库里的哈希必须能被 ApiKeyFilter 认出来
        Map<String, Object> body = responseBody(response);
        String plaintext = (String) body.get("apiKey");
        assertThat(plaintext).startsWith("nsk_").hasSizeGreaterThan(40);
        assertThat(saved.getKeyHash()).isEqualTo(sha256Hex(plaintext));
        assertThat(body.get("sysid")).isEqualTo(SYSID);
        assertThat(body.get("role")).isEqualTo("OPERATOR");
        // maskedKey 不含明文中段
        assertThat((String) body.get("maskedKey")).doesNotContain(plaintext.substring(8, plaintext.length() - 4));
        // 变异收口：keyId/name/scopes 落库与响应双侧都要钉住——
        // 此前只断言了 hash/sysid/role，mintKey 里的 setKeyId/setName/setScopes
        // 任何一行被删掉，字段停在 null 而测试全绿。
        assertThat(saved.getKeyId()).matches("nsk_" + DEVICE_TENANT + "_[0-9a-f]{8}");
        assertThat(body.get("keyId")).isEqualTo(saved.getKeyId());
        assertThat(saved.getName()).isEqualTo("drone-42");
        // 设备 Key 未显式给 scopes 时的默认档位（来源只有一处，删掉就变 "[]"）
        assertThat(saved.getScopes()).isEqualTo("[ingest]");
        assertThat(body.get("scopes")).isEqualTo("[ingest]");
    }

    @Test
    @DisplayName("设备 Key：设备未归属租户 → 400（fail-closed，防数据写进去谁都看不见）")
    void rejectsDeviceWithoutTenant() {
        loginAs("admin", null, "ADMIN");
        knownDevice(null);

        var response = controller.createApiKey(body("name", "drone-42", "sysid", SYSID));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    @DisplayName("设备 Key：sysid 未登记 → 404")
    void rejectsUnknownDevice() {
        loginAs("admin", DEVICE_TENANT, "ADMIN");
        when(deviceRegistry.isKnownDevice(SYSID)).thenReturn(false);

        var response = controller.createApiKey(body("name", "drone-42", "sysid", SYSID));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    @DisplayName("设备 Key：sysid 越界（0/255/非数字）→ 400")
    void rejectsOutOfRangeSysid() {
        loginAs("admin", DEVICE_TENANT, "ADMIN");

        assertThat(controller.createApiKey(body("name", "d", "sysid", 0)).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.createApiKey(body("name", "d", "sysid", 255)).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.createApiKey(body("name", "d", "sysid", "abc")).getStatusCode().value()).isEqualTo(400);
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    @DisplayName("设备 Key：租户级非 ADMIN 签发者不能给别家租户的设备签 Key → 403")
    void rejectsCrossTenantIssuer() {
        loginAs("operator-of-tenant-8", 8, "OPERATOR");
        knownDevice(DEVICE_TENANT);

        var response = controller.createApiKey(body("name", "drone-42", "sysid", SYSID));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    @DisplayName("设备 Key：ADMIN 角色本身即特权，跨租户设备也可签（与撤销同口径）")
    void adminRoleIsPrivilegedAcrossTenants() {
        loginAs("admin-of-8", 8, "ADMIN");
        knownDevice(DEVICE_TENANT);

        var response = controller.createApiKey(body("name", "drone-42", "sysid", SYSID));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    @DisplayName("普通 Key（无 sysid）：行为不变——role 继承签发者、userId 落库")
    void plainKeyStillInheritsIssuer() {
        loginAs("admin", DEVICE_TENANT, "ADMIN");
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", 5);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));

        var response = controller.createApiKey(body("name", "integration-key"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        ArgumentCaptor<ApiKeyEntity> captor = ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(apiKeyRepository).save(captor.capture());
        assertThat(captor.getValue().getSysid()).isNull();
        assertThat(captor.getValue().getRole()).isEqualTo("ADMIN");
        assertThat(captor.getValue().getUserId()).isEqualTo(5);
        assertThat(captor.getValue().getTenantId()).isEqualTo(DEVICE_TENANT);
    }

    // --- 轮换 ---

    @Test
    @DisplayName("轮换（宽限 24h）：旧 Key 缩短到宽限期、不撤销；新 Key 继承绑定与剩余有效期；缓存失效")
    void rotateWithGraceKeepsOldKeyAlive() {
        loginAs("admin", DEVICE_TENANT, "ADMIN");
        Instant perpetual = null;
        ApiKeyEntity old = existingKey(DEVICE_TENANT, SYSID, perpetual);
        when(apiKeyRepository.findByKeyId("nsk_7_oldkey01")).thenReturn(Optional.of(old));

        var response = controller.rotateApiKey("nsk_7_oldkey01", body("graceHours", 24));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        // 旧 Key：宽限到 ~24h 后，未撤销
        assertThat(old.isRevoked()).isFalse();
        assertThat(old.getExpiresAt()).isAfter(Instant.now().plus(23, ChronoUnit.HOURS));
        assertThat(old.getExpiresAt()).isBefore(Instant.now().plus(25, ChronoUnit.HOURS));
        // 新 Key：继承绑定，永久 Key 保持永久，哈希口径一致
        ArgumentCaptor<ApiKeyEntity> captor = ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(apiKeyRepository, times(2)).save(captor.capture());
        ApiKeyEntity fresh = captor.getAllValues().get(0);
        assertThat(fresh.getSysid()).isEqualTo(SYSID);
        assertThat(fresh.getTenantId()).isEqualTo(DEVICE_TENANT);
        assertThat(fresh.getRole()).isEqualTo("OPERATOR");
        assertThat(fresh.getExpiresAt()).isNull();
        Map<String, Object> body = responseBody(response);
        assertThat(fresh.getKeyHash()).isEqualTo(sha256Hex((String) body.get("apiKey")));
        assertThat(body.get("oldKeyExpiresAt")).isNotNull();
        assertThat(body.get("oldKeyRevoked")).isNull();
        // 旧 Key 哈希被失效：本 JVM 内立即不再认旧 Key
        verify(apiKeyCache).invalidate("old-key-hash");
    }

    @Test
    @DisplayName("轮换（立即）：旧 Key 撤销，响应 oldKeyRevoked=true")
    void rotateImmediateRevokesOldKey() {
        loginAs("admin", DEVICE_TENANT, "ADMIN");
        ApiKeyEntity old = existingKey(DEVICE_TENANT, SYSID, Instant.now().plus(300, ChronoUnit.DAYS));
        when(apiKeyRepository.findByKeyId("nsk_7_oldkey01")).thenReturn(Optional.of(old));

        var response = controller.rotateApiKey("nsk_7_oldkey01", null);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(old.isRevoked()).isTrue();
        Map<String, Object> body = responseBody(response);
        assertThat(body.get("oldKeyRevoked")).isEqualTo(true);
        verify(apiKeyCache).invalidate("old-key-hash");
    }

    @Test
    @DisplayName("轮换：继承剩余有效期（未来到期时间原样带到新 Key）")
    void rotateInheritsRemainingLifetime() {
        loginAs("admin", DEVICE_TENANT, "ADMIN");
        Instant remaining = Instant.now().plus(2, ChronoUnit.HOURS);
        ApiKeyEntity old = existingKey(DEVICE_TENANT, SYSID, remaining);
        when(apiKeyRepository.findByKeyId("nsk_7_oldkey01")).thenReturn(Optional.of(old));

        controller.rotateApiKey("nsk_7_oldkey01", body("graceHours", 1));

        ArgumentCaptor<ApiKeyEntity> captor = ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(apiKeyRepository, times(2)).save(captor.capture());
        // 新 Key（第 1 次 save）继承剩余 2h 原样
        assertThat(captor.getAllValues().get(0).getExpiresAt()).isEqualTo(remaining);
        // 旧 Key 原有 2h，宽限 1h 更早 → 截短到 ~now+1h（而非保持 2h）
        Instant graceStart = Instant.now();
        assertThat(old.getExpiresAt())
                .isAfter(graceStart.plus(59, ChronoUnit.MINUTES))
                .isBefore(graceStart.plus(61, ChronoUnit.MINUTES));
        assertThat(captor.getAllValues().get(1).getExpiresAt()).isEqualTo(old.getExpiresAt());
        assertThat(old.isRevoked()).isFalse();
    }

    @Test
    @DisplayName("轮换：跨租户非特权 → 403")
    void rotateRejectsCrossTenant() {
        loginAs("operator-of-8", 8, "OPERATOR");
        when(apiKeyRepository.findByKeyId("nsk_7_oldkey01"))
                .thenReturn(Optional.of(existingKey(DEVICE_TENANT, SYSID, null)));

        var response = controller.rotateApiKey("nsk_7_oldkey01", null);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    @DisplayName("轮换：Key 不存在 → 404；graceHours 非法（-1/abc）→ 400")
    void rotateValidatesInput() {
        loginAs("admin", DEVICE_TENANT, "ADMIN");

        assertThat(controller.rotateApiKey("missing", null).getStatusCode().value()).isEqualTo(404);

        when(apiKeyRepository.findByKeyId("nsk_7_oldkey01"))
                .thenReturn(Optional.of(existingKey(DEVICE_TENANT, SYSID, null)));
        assertThat(controller.rotateApiKey("nsk_7_oldkey01", body("graceHours", -1)).getStatusCode().value())
                .isEqualTo(400);
        assertThat(controller.rotateApiKey("nsk_7_oldkey01", body("graceHours", "abc")).getStatusCode().value())
                .isEqualTo(400);
    }

    // --- 撤销 ---

    @Test
    @DisplayName("撤销：旧 Key 失效缓存（本 JVM 即时不认）")
    void revokeInvalidatesCache() {
        loginAs("admin", DEVICE_TENANT, "ADMIN");
        ApiKeyEntity old = existingKey(DEVICE_TENANT, SYSID, null);
        when(apiKeyRepository.findByKeyId("nsk_7_oldkey01")).thenReturn(Optional.of(old));

        var response = controller.revokeApiKey("nsk_7_oldkey01");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(old.isRevoked()).isTrue();
        verify(apiKeyCache).invalidate("old-key-hash");
    }

    @Test
    @DisplayName("设备 Key：显式 scopes 原样保留（默认 [ingest] 不许反向覆盖显式值）")
    void deviceKeyKeepsExplicitScopes() {
        loginAs("admin", DEVICE_TENANT, "ADMIN");
        knownDevice(DEVICE_TENANT);

        var response = controller.createApiKey(body(
                "name", "drone-42", "sysid", SYSID, "scopes", List.of("ingest", "read")));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        ArgumentCaptor<ApiKeyEntity> captor = ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(apiKeyRepository).save(captor.capture());
        assertThat(captor.getValue().getScopes()).isEqualTo("[ingest, read]");
        assertThat(responseBody(response).get("scopes")).isEqualTo("[ingest, read]");
    }

    @Test
    @DisplayName("轮换：graceHours=0 立即失效且放行；=8760（上限）放行；8761 拒绝（边界三点）")
    void rotateGraceBoundaries() {
        loginAs("admin", DEVICE_TENANT, "ADMIN");
        when(apiKeyRepository.findByKeyId("nsk_7_oldkey01"))
                .thenReturn(Optional.of(existingKey(DEVICE_TENANT, SYSID, null)));

        // 0：显式传 0 = 合法（立即失效路径），不是「非正即非法」
        var zero = controller.rotateApiKey("nsk_7_oldkey01", body("graceHours", 0));
        assertThat(zero.getStatusCode().value()).as("graceHours=0 必须放行").isEqualTo(201);
        assertThat(responseBody(zero)).containsEntry("oldKeyRevoked", true);

        // 8760 = MAX_GRACE_HOURS 本身合法（上限包含）
        var max = controller.rotateApiKey("nsk_7_oldkey01", body("graceHours", 8760));
        assertThat(max.getStatusCode().value()).as("graceHours=8760（上限）必须放行").isEqualTo(201);

        // 8761 越界拒绝
        var over = controller.rotateApiKey("nsk_7_oldkey01", body("graceHours", 8761));
        assertThat(over.getStatusCode().value()).as("graceHours=8761 必须拒绝").isEqualTo(400);
    }

    @Test
    @DisplayName("轮换：同租户 OPERATOR 轮换自家 Key → 放行（跨租户守卫不许过紧误拒）")
    void rotateSameTenantOperatorAllowed() {
        loginAs("operator-of-7", DEVICE_TENANT, "OPERATOR");
        when(apiKeyRepository.findByKeyId("nsk_7_oldkey01"))
                .thenReturn(Optional.of(existingKey(DEVICE_TENANT, SYSID, null)));

        var response = controller.rotateApiKey("nsk_7_oldkey01", null);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    @DisplayName("撤销：跨租户非特权 → 403，且不落库（与轮换同一口径）")
    void revokeRejectsCrossTenant() {
        loginAs("operator-of-8", 8, "OPERATOR");
        when(apiKeyRepository.findByKeyId("nsk_7_oldkey01"))
                .thenReturn(Optional.of(existingKey(DEVICE_TENANT, SYSID, null)));

        var response = controller.revokeApiKey("nsk_7_oldkey01");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    @DisplayName("撤销：同租户 OPERATOR 撤销本租户 Key → 放行（守卫只拦跨租户，不许误伤同租户）")
    void revokeSameTenantOperatorAllowed() {
        loginAs("operator-7", DEVICE_TENANT, "OPERATOR");
        ApiKeyEntity key = existingKey(DEVICE_TENANT, SYSID, null);
        when(apiKeyRepository.findByKeyId("nsk_7_oldkey01")).thenReturn(Optional.of(key));

        var response = controller.revokeApiKey("nsk_7_oldkey01");

        // 变异收口（PIT 第五轮）：守卫里 entity.getTenantId()==null → !=null 的
        // 取反在跨租户（403 用例）与 null-tenant（两路都拦）下不分叉，唯独把
        // 「同租户放行」翻成 403——同租户成功用例是它唯一的现形面。
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(key.isRevoked()).as("撤销必须真的落到实体上").isTrue();
        verify(apiKeyRepository).save(key);
    }

    @Test
    @DisplayName("撤销：无 tenant_id claim 的 OPERATOR 属全局特权上下文 → 放行（守卫不许把 null 判反）")
    void revokeNullTenantOperatorAllowed() {
        loginAs("svc-global", null, "OPERATOR");
        when(apiKeyRepository.findByKeyId("nsk_7_oldkey01"))
                .thenReturn(Optional.of(existingKey(DEVICE_TENANT, SYSID, null)));

        // 变异收口：租户守卫移位/取反时这里可能抛 NPE——转换成断言失败，
        // 不让 PIT 把「测试抛异常」记成 RUN_ERROR（既不算杀也不算活）。
        org.springframework.http.ResponseEntity<Map<String, Object>> response;
        try {
            response = controller.revokeApiKey("nsk_7_oldkey01");
        } catch (Exception e) {
            throw new AssertionError("撤销不该抛异常（租户守卫误触发）: " + e, e);
        }

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("撤销：租户 8 的 ADMIN 跨租户撤销 → 放行（角色本身即特权，与签发同口径）")
    void revokeCrossTenantAdminAllowed() {
        loginAs("admin-of-8", 8, "ADMIN");
        when(apiKeyRepository.findByKeyId("nsk_7_oldkey01"))
                .thenReturn(Optional.of(existingKey(DEVICE_TENANT, SYSID, null)));

        var response = controller.revokeApiKey("nsk_7_oldkey01");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("设备 Key：合法边界 sysid=1 / sysid=254 均放行（越界筛查不许误伤边界）")
    void sysidBoundariesAllowed() {
        loginAs("admin", DEVICE_TENANT, "ADMIN");

        for (int boundary : new int[]{1, 254}) {
            when(deviceRegistry.isKnownDevice(boundary)).thenReturn(true);
            when(deviceRegistry.tenantOf(boundary)).thenReturn(DEVICE_TENANT);

            var response = controller.createApiKey(body("name", "d-" + boundary, "sysid", boundary));

            assertThat(response.getStatusCode().value())
                    .as("sysid=%d 是合法边界，必须放行", boundary).isEqualTo(201);
        }
    }

    /** 与 ApiKeyFilter/DeviceIngestKeyBootstrapRunner 同口径的 SHA-256 hex（独立复算）。 */
    private static String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

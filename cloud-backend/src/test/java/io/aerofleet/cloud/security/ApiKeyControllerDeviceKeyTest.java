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

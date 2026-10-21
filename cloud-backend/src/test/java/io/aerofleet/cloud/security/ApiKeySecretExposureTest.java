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

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * API Key 的**密文边界**测试：明文 Key 绝不能以任何形式留在库里，且必须真的是随机的。
 *
 * <p><b>为什么单独一个类</b>：2026-10-21 用 PIT 变异测试审 security 包时，
 * {@code ApiKeyController} 是**存活变异体最多**的类（21 / 138，变异分 62%）。
 * 其中两条特别要命，而且都能被现有测试放过：
 * <ul>
 *   <li>{@code maskKey} 的 {@code return key.substring(0,4) + "****" + ...}
 *       被变异成 {@code return null} —— <b>存活</b>。也就是说：哪天掩码函数退化成
 *       返回明文、返回空、或者返回完整密钥，<b>没有任何测试会红</b>。
 *   <li>{@code generateRandomHex} 里的 {@code secureRandom.nextBytes(bytes)}
 *       被删掉 —— <b>存活</b>。也就是说：哪天随机源退化成全 0，签发出去的 Key
 *       会是<b>可预测的常量</b>，而没有任何测试会红。
 * </ul>
 *
 * <p>这两条都是"声明≠行为"的极端形态：代码注释写着"明文只在返回值里出现一次，
 * 任何地方不留存"，但那句话当时没有任何机器检查。
 */
@DisplayName("ApiKeyController 密钥密文边界（掩码 / 随机性）")
class ApiKeySecretExposureTest {

    private static final int SYSID = 77;
    private static final int DEVICE_TENANT = 5;

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
        when(deviceRegistry.isKnownDevice(SYSID)).thenReturn(true);
        when(deviceRegistry.tenantOf(SYSID)).thenReturn(DEVICE_TENANT);
        when(userRepository.findByUsername(any())).thenReturn(java.util.Optional.empty());
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    private void loginAsAdmin() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("admin")
                .claim("tenant_id", DEVICE_TENANT)
                .claim("role", "ADMIN")
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(jwt, null));
    }

    private Map<String, Object> body(Object... pairs) {
        Map<String, Object> b = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            b.put((String) pairs[i], pairs[i + 1]);
        }
        return b;
    }

    private String mintApiKey() {
        var response = controller.createApiKey(body("name", "cam-" + SYSID, "sysid", SYSID));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return (String) response.getBody().get("apiKey");
    }

    @Test
    @DisplayName("落库的掩码串绝不是完整密钥：只留前4后4，中间是 ****")
    void maskedKey_neverEqualsTheFullSecret() {
        loginAsAdmin();

        var response = controller.createApiKey(body("name", "cam-mask", "sysid", SYSID));
        assertThat(response.getStatusCode().value()).isEqualTo(201);

        String plaintext = (String) response.getBody().get("apiKey");
        assertThat(plaintext).isNotBlank();

        ArgumentCaptor<ApiKeyEntity> captor = ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(apiKeyRepository).save(captor.capture());
        String masked = captor.getValue().getMaskedKey();

        assertThat(masked)
                .as("掩码串不能等于完整密钥（否则库里就留着明文）")
                .isNotNull()
                .isNotEqualTo(plaintext)
                .doesNotContain(plaintext);
        assertThat(masked)
                .as("掩码必须是前4 + **** + 后4 的形式")
                .contains("****")
                .isEqualTo(plaintext.substring(0, 4) + "****" + plaintext.substring(plaintext.length() - 4));
        assertThat(masked.length()).isLessThan(plaintext.length());
    }

    @Test
    @DisplayName("响应里的 maskedKey 也不是完整密钥")
    void responseMaskedKey_isNotTheFullSecret() {
        loginAsAdmin();

        var response = controller.createApiKey(body("name", "cam-resp", "sysid", SYSID));
        String plaintext = (String) response.getBody().get("apiKey");
        String masked = (String) response.getBody().get("maskedKey");

        assertThat(masked).isNotNull().isNotEqualTo(plaintext).contains("****");
    }

    @Test
    @DisplayName("两次签发的密钥不同（随机源没退化）")
    void mintedKeys_differBetweenCalls() {
        loginAsAdmin();

        String first = mintApiKey();
        String second = mintApiKey();

        assertThat(first).isNotEqualTo(second);
        assertThat(first.substring("nsk_".length()))
                .as("随机段不能是全 0（随机源被删掉就会这样）")
                .isNotEqualTo("0".repeat(first.length() - "nsk_".length()));
    }

    @Test
    @DisplayName("随机段是十六进制且长度固定")
    void mintedKey_randomPart_isHexWithFixedLength() {
        loginAsAdmin();

        String key = mintApiKey();
        assertThat(key).startsWith("nsk_");
        String random = key.substring("nsk_".length());
        assertThat(random).matches("[0-9a-f]+");
        assertThat(random.length()).isGreaterThanOrEqualTo(32);
    }

    @Test
    @DisplayName("库里存的是哈希，不是明文（落库对象任何字段都不含完整密钥）")
    void savedEntity_neverStoresPlaintextKey() {
        loginAsAdmin();

        var response = controller.createApiKey(body("name", "cam-store", "sysid", SYSID));
        String plaintext = (String) response.getBody().get("apiKey");

        ArgumentCaptor<ApiKeyEntity> captor = ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(apiKeyRepository).save(captor.capture());
        ApiKeyEntity saved = captor.getValue();

        assertThat(saved.getKeyHash())
                .as("库里必须是 SHA-256 哈希，不是明文")
                .isNotNull()
                .isNotEqualTo(plaintext)
                .doesNotContain(plaintext)
                .hasSize(64);
        assertThat(saved.getMaskedKey()).doesNotContain(plaintext);
    }
}

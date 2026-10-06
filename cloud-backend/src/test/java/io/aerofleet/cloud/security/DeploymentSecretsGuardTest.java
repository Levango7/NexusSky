package io.aerofleet.cloud.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 占位凭据门禁：既验规则本身，也验**接线**——只测 {@link PlaceholderSecrets} 的话，
 * 「有函数无调用」这一仓内反复出现的失效形态照样能过。
 */
@DisplayName("部署占位凭据门禁")
class DeploymentSecretsGuardTest {

    /** deploy/k8s/secret.yaml:16 与 helm values:104 里给运营方抄的字面量。 */
    private static final String K8S_JWT_PLACEHOLDER =
            "REPLACE_WITH_STRONG_JWT_SECRET_AT_LEAST_32_CHARS";
    private static final String K8S_ENC_PLACEHOLDER =
            "REPLACE_WITH_STRONG_ENCRYPTION_KEY_AT_LEAST_32_CHARS";
    private static final String K8S_DB_PLACEHOLDER = "REPLACE_WITH_DB_PASSWORD";

    // ==================== 规则本身 ====================

    @Test
    @DisplayName("仓库内公开的占位串一律拒绝")
    void placeholderLiteralsAreRejected() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PlaceholderSecrets.rejectUnsubstituted(K8S_JWT_PLACEHOLDER, "jwt.secret"));
        assertTrue(e.getMessage().contains("jwt.secret"), "报错必须点名属性，否则运营无从定位");
        assertTrue(e.getMessage().contains("伪造"), "报错必须说明后果，否则看起来像格式校验");
    }

    @Test
    @DisplayName("真实密钥与未设置（null/空）都不触发本判定")
    void realAndAbsentValuesPass() {
        assertDoesNotThrow(() -> PlaceholderSecrets.rejectUnsubstituted(
                "a-real-deployment-secret-value-32b+", "jwt.secret"));
        assertDoesNotThrow(() -> PlaceholderSecrets.rejectUnsubstituted(null, "jwt.secret"));
        assertDoesNotThrow(() -> PlaceholderSecrets.rejectUnsubstituted("", "jwt.secret"));
        // 前后空白不能绕过前缀匹配
        assertThrows(IllegalStateException.class,
                () -> PlaceholderSecrets.rejectUnsubstituted("  " + K8S_DB_PLACEHOLDER + " ", "x"));
    }

    // ==================== 接线：启动真的会失败 ====================

    /**
     * {@code @PostConstruct} 抛出的异常会被 Spring 包成 {@code BeanCreationException}，
     * 所以断言沿 cause 链找原始异常：只看顶层类型会把「真的拦住了」误判成「没拦住」；
     * 反过来只断言「启动失败」又不点名原因，等于接受任何一次无关的启动崩溃。
     */
    private static Throwable rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c;
    }

    private void assertStartupRejects(String property, String value) {
        new ApplicationContextRunner()
                .withUserConfiguration(DeploymentSecretsGuard.class)
                .withPropertyValues(property + "=" + value)
                .run(ctx -> {
                    Throwable failure = ctx.getStartupFailure();
                    assertTrue(failure != null, "占位值上岗必须让启动失败: " + property);
                    Throwable root = rootCause(failure);
                    assertInstanceOf(IllegalStateException.class, root,
                            "启动失败应源自本守卫的 IllegalStateException，实际: " + root);
                    assertTrue(root.getMessage().contains(property),
                            "失败信息必须点名属性，实际: " + root.getMessage());
                });
    }

    @Test
    @DisplayName("占位 jwt.secret 上岗时 context 启动失败")
    void contextFailsToStartWithPlaceholderJwtSecret() {
        assertStartupRejects("jwt.secret", K8S_JWT_PLACEHOLDER);
    }

    @Test
    @DisplayName("legacy 属性名同样在 watch 列表里（k8s 两个变量都注入）")
    void legacyJwtSecretPropertyIsWatched() {
        assertStartupRejects("aerofleet.security.jwt-secret", K8S_JWT_PLACEHOLDER);
    }

    @Test
    @DisplayName("占位加密密钥与 DB 口令同样拦（三类凭据共用一条规则）")
    void contextFailsForEncryptionKeyAndDatasourcePlaceholders() {
        assertStartupRejects("aerofleet.encryption.key", K8S_ENC_PLACEHOLDER);
        assertStartupRejects("spring.datasource.password", K8S_DB_PLACEHOLDER);
    }

    @Test
    @DisplayName("dev/test profile 的字面量不受影响（门禁不能把本地开发拦死）")
    void devAndTestLiteralsStillStart() {
        new ApplicationContextRunner()
                .withUserConfiguration(DeploymentSecretsGuard.class)
                .withPropertyValues(
                        "jwt.secret=aerofleet-dev-encryption-key-32bytes-long",
                        "aerofleet.encryption.key=aerofleet-dev-encryption-key",
                        "spring.datasource.password=")
                .run(ctx -> assertTrue(ctx.getStartupFailure() == null,
                        "启动不应失败: " + ctx.getStartupFailure()));
    }
}

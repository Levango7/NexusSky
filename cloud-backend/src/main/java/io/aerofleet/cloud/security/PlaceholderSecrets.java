package io.aerofleet.cloud.security;

/**
 * 未替换的部署占位凭据在启动时即拒绝。
 *
 * <p>{@code deploy/k8s/secret.yaml} 与 {@code deploy/helm/.../values.yaml} 里给运营方抄的
 * 占位串（{@code REPLACE_WITH_STRONG_JWT_SECRET_AT_LEAST_32_CHARS} 等）本身长度达标、
 * 能通过既有的「≥32 字节」强度校验，于是一次忘替换的部署会**安静地**把仓库里公开的
 * 字符串当 HMAC-SHA256 密钥上岗：任何读过本仓库的人都能自造任意租户/任意角色（含 ADMIN）
 * 的 JWT，凭据加密（{@code aerofleet.encryption.key}）同理变成可解的密文。
 *
 * <p>刻意不挂在 {@code dev-mode} 开关下：dev/test profile 用的是自己的字面量
 * （{@code aerofleet-dev-encryption-key}、{@code test-encryption-key-for-integration-tests}），
 * 不含本前缀，因此这道判定对本地与 CI 零影响；而「prod 才检查」等于把判定挂在
 * 一个可能被忘设的 profile 上——正是这次要修的失效类型。
 */
public final class PlaceholderSecrets {

    /** 仓库内所有占位凭据的共同前缀（k8s Secret 与 helm values 两侧一致）。 */
    public static final String PLACEHOLDER_PREFIX = "REPLACE_WITH";

    private PlaceholderSecrets() {
    }

    /**
     * 校验取到的配置值不是未替换的占位串。
     *
     * @param value    实际取到的密钥值；null / 空由调用方既有的必填校验处理
     * @param property 属性名（如 {@code jwt.secret}），用于报错可定位
     * @throws IllegalStateException 值以 {@link #PLACEHOLDER_PREFIX} 开头
     */
    public static void rejectUnsubstituted(String value, String property) {
        if (value == null) {
            return;
        }
        if (value.trim().startsWith(PLACEHOLDER_PREFIX)) {
            throw new IllegalStateException("部署凭据未替换：属性 '" + property
                    + "' 仍是仓库内公开的占位串（前缀 " + PLACEHOLDER_PREFIX + "）。"
                    + "该值长度达标，会骗过既有的 ≥32 字节强度校验，但它写在 "
                    + "deploy/k8s/secret.yaml 与 deploy/helm/nexussky/values.yaml 里，"
                    + "任何人据此可伪造任意租户/ADMIN 的 JWT 或解密已存凭据。"
                    + "请用部署侧的密钥管理生成真实值后重启（helm 侧：--set cloudBackend.jwtSecret=…）。");
        }
    }
}

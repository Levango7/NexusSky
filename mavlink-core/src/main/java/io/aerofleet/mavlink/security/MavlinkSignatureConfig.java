package io.aerofleet.mavlink.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * MAVLink 签名配置：通过 application.properties 控制签名开关与密钥。
 * <p>
 * 配置项：
 * <ul>
 *   <li>{@code mavlink.signing.enabled} — 是否启用 MAVLink v2 链路签名（默认 false）</li>
 *   <li>{@code mavlink.signing.secret-key} — 签名口令（默认空字符串）</li>
 *   <li>{@code mavlink.signing.key-store-path} — 多密钥存储路径（默认空字符串）</li>
 *   <li>{@code mavlink.signing.reject-unsigned} — 是否拒绝未签名消息（默认 true，fail-closed）</li>
 * </ul>
 */
@Component
public class MavlinkSignatureConfig implements InitializingBean {

    @Value("${mavlink.signing.enabled:false}")
    private boolean enabled;

    @Value("${mavlink.signing.secret-key:}")
    private String secretKey;

    @Value("${mavlink.signing.key-store-path:}")
    private String keyStorePath;

    // fail-closed: 默认拒绝未签名消息（来源: 2026-09-19-security-switch-default-fail-closed-annotation-value）
    @Value("${mavlink.signing.reject-unsigned:true}")
    private boolean rejectUnsigned;

    public boolean isEnabled() {
        return enabled;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public String getKeyStorePath() {
        return keyStorePath;
    }

    public boolean isRejectUnsigned() {
        return rejectUnsigned;
    }

    /**
     * 是否为多密钥模式（keyStorePath 非空）。
     */
    public boolean isMultiMode() {
        return keyStorePath != null && !keyStorePath.isEmpty();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public void setKeyStorePath(String keyStorePath) {
        this.keyStorePath = keyStorePath;
    }

    public void setRejectUnsigned(boolean rejectUnsigned) {
        this.rejectUnsigned = rejectUnsigned;
    }

    // P1-fix: 启动时校验，enabled=true 且密钥为空则应用启动失败，避免运行时不安全
    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            return;
        }
        // enabled=true 时，secretKey 和 keyStorePath 至少需要一个
        if ((secretKey == null || secretKey.isEmpty()) && (keyStorePath == null || keyStorePath.isEmpty())) {
            throw new IllegalStateException(
                    "mavlink.signing.enabled=true but neither mavlink.signing.secret-key nor mavlink.signing.key-store-path is set");
        }
        // keyStorePath 非空时校验文件存在
        if (keyStorePath != null && !keyStorePath.isEmpty()) {
            Path path = Paths.get(keyStorePath);
            if (!Files.exists(path)) {
                throw new IllegalStateException(
                        "mavlink.signing.key-store-path points to non-existent file: " + keyStorePath);
            }
        }
    }
}
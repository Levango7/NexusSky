package io.aerofleet.mavlink.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * MAVLink 签名配置：通过 application.properties 控制签名开关与密钥。
 * <p>
 * 配置项：
 * <ul>
 *   <li>{@code mavlink.signing.enabled} — 是否启用 HMAC-SHA256 消息签名（默认 false）</li>
 *   <li>{@code mavlink.signing.secret-key} — HMAC 密钥（默认空字符串）</li>
 * </ul>
 */
@Component
public class MavlinkSignatureConfig implements InitializingBean {

    @Value("${mavlink.signing.enabled:false}")
    private boolean enabled;

    @Value("${mavlink.signing.secret-key:}")
    private String secretKey;

    public boolean isEnabled() {
        return enabled;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    // P1-fix: 启动时校验，enabled=true 且密钥为空则应用启动失败，避免运行时不安全
    @Override
    public void afterPropertiesSet() {
        if (enabled && (secretKey == null || secretKey.isEmpty())) {
            throw new IllegalStateException(
                    "mavlink.signing.enabled=true but mavlink.signing.secret-key is empty");
        }
    }
}
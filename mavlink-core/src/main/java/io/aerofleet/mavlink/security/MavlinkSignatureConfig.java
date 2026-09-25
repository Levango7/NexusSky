package io.aerofleet.mavlink.security;

import org.springframework.beans.factory.annotation.Value;
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
public class MavlinkSignatureConfig {

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
}
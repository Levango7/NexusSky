package io.aerofleet.cloud.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 启动时检查部署凭据里没有被未替换的占位串上岗（规则见 {@link PlaceholderSecrets}）。
 *
 * <p>放在单一组件而不是散在 {@code JwtTokenProvider}/{@code PasswordConverter}/
 * {@code WebhookService} 里：三处读的是同一批属性，各自写一份判定就会各自漂移，
 * 而这类缺陷的形态正是「某一处漏了」。启动失败（{@code @PostConstruct} 抛异常会中断
 * context refresh）比「运行到第一次加解密才发现密钥是公开的」有用。
 */
@Component
public class DeploymentSecretsGuard {

    private static final Logger log = LoggerFactory.getLogger(DeploymentSecretsGuard.class);

    /** 属性名 → 实际取到的值；空值由各自既有的必填校验负责，本守卫只拦占位串。 */
    private final java.util.Map<String, String> watched;

    public DeploymentSecretsGuard(
            @Value("${jwt.secret:}") String jwtSecret,
            @Value("${aerofleet.security.jwt-secret:}") String legacyJwtSecret,
            @Value("${aerofleet.encryption.key:}") String encryptionKey,
            @Value("${spring.datasource.password:}") String datasourcePassword) {
        this.watched = new java.util.LinkedHashMap<>();
        this.watched.put("jwt.secret", jwtSecret);
        this.watched.put("aerofleet.security.jwt-secret", legacyJwtSecret);
        this.watched.put("aerofleet.encryption.key", encryptionKey);
        this.watched.put("spring.datasource.password", datasourcePassword);
    }

    @PostConstruct
    void rejectUnsubstitutedSecrets() {
        for (java.util.Map.Entry<String, String> e : watched.entrySet()) {
            PlaceholderSecrets.rejectUnsubstituted(e.getValue(), e.getKey());
        }
        log.info("部署凭据占位检查通过（{} 项）", watched.size());
    }
}

package io.aerofleet.cloud.security;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.mavlink.security.MavlinkSignatureConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MavlinkSigningPostureController} 的门禁测试。
 *
 * <p><b>验的是安全属性而不是"能返回 200"</b>：姿态端点能不能收紧，决定整支机队的遥测
 * 会不会静默消失，所以这里钉三件事——
 * <ol>
 *   <li>API Key（机器凭据）**不能**改变安全姿态，且是在"翻牌动作之前"就被挡住；</li>
 *   <li>不就绪时 409 必须把缺的 sysid 结构化交出来（GCS 要能直接渲染"该去配哪几台"），
 *       并且被拒之后姿态绝不能已经翻了；</li>
 *   <li>成功收紧时审计里必须留得下"是谁翻的"（JWT 主体，不是 API Key 的 keyId）。</li>
 * </ol>
 *
 * <p>风格沿用 {@code MavlinkSigningKeyControllerTest}：直接 new + 手动装配 SecurityContext，
 * 不起 Spring 上下文（服务层语义在 {@code SigningPostureServiceTest}，这里只验入口编排）。
 */
@DisplayName("签名姿态端点：谁能翻、翻不动时说什么")
class MavlinkSigningPostureControllerTest {

    private static ObjectProvider<MavlinkSignatureConfig> providerOf(MavlinkSignatureConfig cfg) {
        return new ObjectProvider<>() {
            @Override
            public MavlinkSignatureConfig getObject(Object... args) {
                return cfg;
            }

            @Override
            public MavlinkSignatureConfig getIfAvailable() {
                return cfg;
            }

            @Override
            public MavlinkSignatureConfig getObject() {
                if (cfg == null) {
                    throw new IllegalStateException("no bean");
                }
                return cfg;
            }
        };
    }

    private static MavlinkSignatureConfig signingOn() {
        MavlinkSignatureConfig cfg = new MavlinkSignatureConfig();
        cfg.setEnabled(true);
        cfg.setSecretKey("unit-test-key-32-bytes-long!!!!!");
        cfg.setRejectUnsigned(false);
        return cfg;
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAsAdmin(String username) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .claim("preferred_username", username)
                .subject(username)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(jwt, "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
    }

    /** API Key 认证形态与生产一致：principal 是 keyId 字符串，不是 Jwt。 */
    private void loginAsApiKey(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new ApiKeyAuthenticationToken("ci-key-01", 1, "[]", role));
    }

    private MavlinkSigningPostureController controllerWith(MavlinkSignatureConfig cfg,
                                                           DeviceRegistry registry) {
        return new MavlinkSigningPostureController(new SigningPostureService(providerOf(cfg), registry));
    }

    private static Map<String, Object> reason(String r) {
        Map<String, Object> b = new HashMap<>();
        b.put("reason", r);
        return b;
    }

    @Test
    @DisplayName("1. API Key 即使带 ADMIN 角色也不能收紧，且翻牌动作没有发生")
    void apiKeyCannotChangePosture() {
        MavlinkSignatureConfig cfg = signingOn();
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(71);
        registry.markSigningVerified(71);
        MavlinkSigningPostureController controller = controllerWith(cfg, registry);

        loginAsApiKey("ADMIN");
        ResponseEntity<Map<String, Object>> resp = controller.tighten(reason("脚本调用"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).containsEntry("posture", "PERMISSIVE");
        // 关键：被挡在门禁外时，配置绝不能已经被改了
        assertThat(cfg.isRejectUnsigned()).isFalse();
    }

    @Test
    @DisplayName("2. 不就绪 ⇒ 409，缺失名单结构化给出，姿态保持 PERMISSIVE")
    void notReadyReturns409WithMissingList() {
        MavlinkSignatureConfig cfg = signingOn();
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(71);
        registry.registerIfAbsent(72);
        registry.markSigningVerified(71);   // 72 从未有过签名帧
        MavlinkSigningPostureController controller = controllerWith(cfg, registry);

        loginAsAdmin("ops@aerofleet");
        ResponseEntity<Map<String, Object>> resp = controller.tighten(reason("第 2 步收紧"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).containsEntry("missingSigningEvidence", List.of(72));
        assertThat(resp.getBody()).containsEntry("posture", "PERMISSIVE");
        // 消息里也要带名单：非 HTTP 调用方（定时任务/运维脚本）拿不到结构化字段
        assertThat(String.valueOf(resp.getBody().get("error"))).contains("72");
        assertThat(cfg.isRejectUnsigned()).isFalse();
    }

    @Test
    @DisplayName("3. 就绪收紧 ⇒ 200，审计记下 JWT 主体而不是凭据 id")
    void readyTightenAttributesJwtPrincipal() {
        MavlinkSignatureConfig cfg = signingOn();
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(71);
        registry.markSigningVerified(71);
        MavlinkSigningPostureController controller = controllerWith(cfg, registry);

        loginAsAdmin("ops@aerofleet");
        ResponseEntity<Map<String, Object>> resp = controller.tighten(reason("第 2 步收紧"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("posture", "STRICT");
        assertThat(cfg.isRejectUnsigned()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events =
                (List<Map<String, Object>>) resp.getBody().get("recentEvents");
        assertThat(events).isNotEmpty();
        assertThat(events.get(0)).containsEntry("actor", "ops@aerofleet");
    }

    @Test
    @DisplayName("4. 状态查询把「该去配哪几台」摊开，且 readyToTighten 不被零设备骗过")
    void statusExposesReadiness() {
        MavlinkSignatureConfig cfg = signingOn();
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(71);
        registry.registerIfAbsent(72);
        registry.markSigningVerified(71);
        MavlinkSigningPostureController controller = controllerWith(cfg, registry);

        loginAsAdmin("viewer@aerofleet");
        ResponseEntity<Map<String, Object>> resp = controller.status();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> b = resp.getBody();
        assertThat(b).containsEntry("onlineDevices", 2);
        assertThat(b).containsEntry("coveredDevices", 1);
        assertThat(b).containsEntry("missingSigningEvidence", List.of(72));
        assertThat(b).containsEntry("readyToTighten", false);
        assertThat(b).containsEntry("autoRollback", true);
        // 姿态端点的响应里没有密钥材料
        assertThat(b.values().stream().map(String::valueOf).toList())
                .noneMatch(v -> v.contains("unit-test-key"));
    }

    @Test
    @DisplayName("5. 签名未启用时退回是 409，绝不「200 成功」却什么都没发生")
    void permissiveWhenSigningDisabledIsRefused() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(71);
        // cfg 为 null：prod 默认（@ConditionalOnProperty 不满足）下 bean 根本不存在
        MavlinkSigningPostureController controller = controllerWith(null, registry);

        loginAsAdmin("ops@aerofleet");
        ResponseEntity<Map<String, Object>> resp = controller.loosen(reason("想退回"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(resp.getBody().get("error"))).contains("签名未启用");
        assertThat(resp.getBody()).containsEntry("posture", "OFF");
    }
}

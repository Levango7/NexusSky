package io.aerofleet.cloud.security;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.mavlink.security.MavlinkSignatureConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SigningPostureService} 的门禁与兜底语义。
 *
 * <p>这批测试要钉住的是"收紧不能靠人自觉"这件事：不就绪必须拒、缺哪几台必须说得出、
 * 翻完出问题必须自己退回来。签名帧本身的验签语义在
 * {@code UdpGatewaySigningTest} / {@code UdpGatewaySigningPermissiveTest}，不在此重复。
 */
@DisplayName("C5 第三步：签名姿态的受控切换与自动退回")
class SigningPostureServiceTest {

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

    private static MavlinkSignatureConfig signingEnabled(boolean rejectUnsigned) {
        MavlinkSignatureConfig cfg = new MavlinkSignatureConfig();
        cfg.setEnabled(true);
        cfg.setSecretKey("unit-test-key-32-bytes-long!!!!!");
        cfg.setRejectUnsigned(rejectUnsigned);
        return cfg;
    }

    @Test
    @DisplayName("1. 有在线设备缺签名证据 ⇒ 收紧被拒，且点名是哪几台")
    void tighten_refusedListingMissingDevices() {
        MavlinkSignatureConfig cfg = signingEnabled(false);
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(11);
        registry.registerIfAbsent(12);
        registry.markSigningVerified(11);   // 12 没配密钥 → 没有任何签名帧通过

        SigningPostureService svc = new SigningPostureService(providerOf(cfg), registry);
        SigningPostureService.Readiness r = svc.evaluateReadiness();
        assertThat(r.onlineDevices()).isEqualTo(2);
        assertThat(r.covered()).isEqualTo(1);
        assertThat(r.missing()).containsExactly(12);
        assertThat(r.ready()).isFalse();

        assertThatThrownBy(() -> svc.requestStrict("tester", "演练"))
                .isInstanceOf(SigningPostureService.PostureRejectedException.class)
                .hasMessageContaining("12")
                .hasMessageContaining("静默消失")
                // 名单既进消息（给脚本/定时任务看）也进结构化字段（给 GCS 渲染按钮用）
                .extracting(t -> ((SigningPostureService.PostureRejectedException) t).missing())
                .asList().containsExactly(12);
        // 被拒之后绝不能已经翻了
        assertThat(cfg.isRejectUnsigned()).isFalse();
        assertThat(svc.currentPosture()).isEqualTo(SigningPostureService.Posture.PERMISSIVE);
    }

    @Test
    @DisplayName("2. 全部在线设备都有证据 ⇒ 收紧成功，姿态转 STRICT")
    void tighten_succeedsWhenAllOnlineDevicesHaveEvidence() {
        MavlinkSignatureConfig cfg = signingEnabled(false);
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(21);
        registry.registerIfAbsent(22);
        registry.markSigningVerified(21);
        registry.markSigningVerified(22);

        SigningPostureService svc = new SigningPostureService(providerOf(cfg), registry);
        svc.requestStrict("ops@aerofleet", "第 2 步收紧");

        assertThat(cfg.isRejectUnsigned()).isTrue();
        assertThat(svc.currentPosture()).isEqualTo(SigningPostureService.Posture.STRICT);
        assertThat(svc.recentEvents()).isNotEmpty();
        assertThat(svc.recentEvents().get(0).actor()).isEqualTo("ops@aerofleet");
    }

    @Test
    @DisplayName("3. 离线设备不算敞口：不阻塞收紧，但上线后会被看门狗兜住")
    void offlineDevicesDoNotBlockTightening() {
        MavlinkSignatureConfig cfg = signingEnabled(false);
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(31);
        registry.markSigningVerified(31);
        registry.get(31).online = true;
        // 一台注册过但当前离线的机器：现在不影响任何事
        registry.registerIfAbsent(32);
        registry.get(32).online = false;

        SigningPostureService svc = new SigningPostureService(providerOf(cfg), registry);
        assertThat(svc.evaluateReadiness().ready()).isTrue();
        svc.requestStrict("tester", "离线不计");
        assertThat(svc.currentPosture()).isEqualTo(SigningPostureService.Posture.STRICT);
    }

    @Test
    @DisplayName("4. 收紧后设备掉出签名姿态 ⇒ 看门狗自动退回 PERMISSIVE 并留审计")
    void watchdog_autoRollsBackWhenEvidenceLost() {
        MavlinkSignatureConfig cfg = signingEnabled(false);
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(41);
        registry.markSigningVerified(41);

        SigningPostureService svc = new SigningPostureService(providerOf(cfg), registry);
        svc.setEvidenceWindow(Duration.ofMinutes(15));
        svc.setAutoRollbackGrace(Duration.ZERO);   // 测试里不等保护窗
        svc.requestStrict("tester", "自动退回演练");
        assertThat(cfg.isRejectUnsigned()).isTrue();

        // 换件/重刷固件后该机不再发签名帧：证据停止刷新，时间继续走。
        // 用推进时钟而不是把窗口缩到纳秒——见 SigningPostureService#clock 的注释。
        svc.setClock(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(16)));
        // 先证明"这一步确实制造了缺失"，否则后面的断言可能是空跑
        assertThat(svc.evaluateReadiness().missing()).containsExactly(41);

        svc.watchdog();

        assertThat(cfg.isRejectUnsigned()).isFalse();
        assertThat(svc.currentPosture()).isEqualTo(SigningPostureService.Posture.PERMISSIVE);
        assertThat(svc.recentEvents().get(0).actor()).isEqualTo("watchdog");
    }

    @Test
    @DisplayName("5. 关掉自动退回 ⇒ 只告警不翻回（明确接受风险才关）")
    void autoRollbackCanBeDisabledButThenNoFallback() {
        MavlinkSignatureConfig cfg = signingEnabled(false);
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(51);
        registry.markSigningVerified(51);

        SigningPostureService svc = new SigningPostureService(providerOf(cfg), registry);
        svc.setEvidenceWindow(Duration.ofMinutes(15));
        svc.setAutoRollback(false);
        svc.setAutoRollbackGrace(Duration.ZERO);
        svc.requestStrict("tester", "关闭兜底");

        svc.setClock(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(16)));
        // 与用例 4 同样的"该退了"前提，唯一差别是开关关了
        assertThat(svc.evaluateReadiness().ready()).isFalse();
        svc.watchdog();

        assertThat(cfg.isRejectUnsigned()).isTrue();
        assertThat(svc.currentPosture()).isEqualTo(SigningPostureService.Posture.STRICT);
    }

    @Test
    @DisplayName("6. 签名未启用时收紧/退回都必须被拒：绝不「设置成功」却什么都没发生")
    void refusesWhenSigningNotEnabled() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(61);
        registry.markSigningVerified(61);

        // bean 缺席（prod 默认：@ConditionalOnProperty 不满足）
        SigningPostureService absent = new SigningPostureService(providerOf(null), registry);
        assertThat(absent.currentPosture()).isEqualTo(SigningPostureService.Posture.OFF);
        assertThatThrownBy(() -> absent.requestStrict("tester", "prod 默认状态"))
                .isInstanceOf(SigningPostureService.PostureRejectedException.class)
                .hasMessageContaining("签名未启用");
        assertThatThrownBy(() -> absent.requestPermissive("tester", "空操作"))
                .isInstanceOf(SigningPostureService.PostureRejectedException.class)
                .hasMessageContaining("无需退回");

        // bean 在但 enabled=false，同样拒
        MavlinkSignatureConfig off = new MavlinkSignatureConfig();
        off.setEnabled(false);
        SigningPostureService disabled = new SigningPostureService(providerOf(off), registry);
        assertThatThrownBy(() -> disabled.requestStrict("tester", "仍关闭"))
                .isInstanceOf(SigningPostureService.PostureRejectedException.class);
    }

    @Test
    @DisplayName("7. 没有在线设备时「就绪」为真，但读数必须能看出是零敞口")
    void emptyFleetIsReadyButVisible() {
        MavlinkSignatureConfig cfg = signingEnabled(false);
        SigningPostureService svc = new SigningPostureService(providerOf(cfg), new DeviceRegistry());
        SigningPostureService.Readiness r = svc.evaluateReadiness();
        assertThat(r.onlineDevices()).isZero();
        assertThat(r.ready()).isTrue();      // 没有敞口
        assertThat(r.covered()).isZero();    // 但别把它读成"全部已配"
    }

    // ===== 变异收口（P1 #7）：loosen 成功路径 / 审计环容量 / 自动退回开关 =====

    @Test
    @DisplayName("8. loosen 成功路径返回 readiness（返回 null = 端点 500，而现有用例只测了 409 拒）")
    void permissiveSucceedsWhenEnabled() {
        MavlinkSignatureConfig cfg = signingEnabled(true);
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(71);
        registry.markSigningVerified(71);
        SigningPostureService svc = new SigningPostureService(providerOf(cfg), registry);

        SigningPostureService.Readiness r = svc.requestPermissive("ops@aerofleet", "演练退场");

        assertThat(r).as("requestPermissive 必须返回 readiness（endpoint 的成功分支全靠这个返回值）").isNotNull();
        assertThat(r.current()).isEqualTo(SigningPostureService.Posture.PERMISSIVE);
        assertThat(cfg.isRejectUnsigned()).isFalse();
        assertThat(svc.recentEvents().get(0).actor()).isEqualTo("ops@aerofleet");
    }

    @Test
    @DisplayName("9. 审计环恰好存 50 条（> 50 才淘汰，>= 会少留一条）")
    void eventsRingHoldsExactlyFifty() {
        MavlinkSignatureConfig cfg = signingEnabled(false);
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(71);
        registry.markSigningVerified(71);
        SigningPostureService svc = new SigningPostureService(providerOf(cfg), registry);

        for (int i = 0; i < 50; i++) {
            svc.setAutoRollback(true);
            svc.requestStrict("ops@aerofleet", "第 " + i + " 次收紧");
            svc.requestPermissive("ops@aerofleet", "第 " + i + " 次退回");
        }

        assertThat(svc.recentEvents())
                .as("环的容量边界：第 50 条后仍在；>= 50 的变异会在第 50 条就开始淘汰")
                .hasSize(50);
    }

    @Test
    @DisplayName("10. setAutoRollback(false) 后 isAutoRollback 如实回显（getter 恒真的变异会让开关失灵）")
    void autoRollbackFlagRoundTrips() {
        SigningPostureService svc = new SigningPostureService(providerOf(null), new DeviceRegistry());

        assertThat(svc.isAutoRollback()).isTrue();   // 默认开
        svc.setAutoRollback(false);
        assertThat(svc.isAutoRollback())
                .as("关掉自动退回必须如实回显——这个值直接进状态响应，也是看门狗的总开关")
                .isFalse();
        svc.setAutoRollback(true);
        assertThat(svc.isAutoRollback()).isTrue();
    }
}

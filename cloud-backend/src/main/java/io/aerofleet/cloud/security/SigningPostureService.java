package io.aerofleet.cloud.security;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.mavlink.security.MavlinkSignatureConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * 签名收紧姿态的**受控切换**：把"能不能翻 strict"从人的 checklist 变成代码里的门禁。
 *
 * <p><b>为什么需要它</b>（见 {@code docs/signing-prod-rollout.md}）：prod 打开
 * {@code mavlink.signing.enabled} 并收紧 {@code reject-unsigned} 之后，唯一失败模式是
 * "某台设备没配密钥"，而它的表现是**那台机遥测静默消失**——没有错误码、没有掉线事件。
 * 原先这一步只能靠"翻牌时有人盯着"。本服务把它换成两件事：
 * <ol>
 *   <li><b>翻牌前</b>：要求所有在线设备近期都有"签名帧通过验签"的证据（{@link DeviceRegistry#signingVerifiedAt(int)}），
 *       缺任何一台就直接拒绝切换并列出名单；</li>
 *   <li><b>翻牌后</b>：看门狗发现仍有在线设备拿不到签名证据 ⇒ <b>自动退回 permissive</b> 并留审计记录。</li>
 * </ol>
 *
 * <p><b>为什么用"有没有签名帧"而不是"库里配没配密钥"当判据</b>：地面写密钥 ≠ 现场把它写进了飞控
 * （本仓没有任何向飞控下发配置的通路——{@code MavlinkSigningKeyController} 只写地面密钥库，
 * 设备侧密钥是进程启动参数）。只看地面侧记录会把"我以为配了"当成"配好了"，正是要防的那类假象。
 *
 * <p><b>依赖为什么是 {@code ObjectProvider}</b>：{@code MavlinkSignatureConfig} 带
 * {@code @ConditionalOnProperty(enabled=true)}，prod 默认（关闭）时这个 bean 根本不存在；
 * 直接字段注入会让上下文起不来。缺席即"签名未启用"，此时任何收紧请求都必须被拒绝——
 * 绝不能默默"设置成功"却什么都没发生。
 */
@Service
@ConfigurationProperties(prefix = "aerofleet.signing.posture")
public class SigningPostureService {

    private static final Logger log = LoggerFactory.getLogger(SigningPostureService.class);

    /** 三态：未启用签名 / 开了签名但不拒未签名 / 收紧。 */
    public enum Posture { OFF, PERMISSIVE, STRICT }

    /** 一次姿态变更的审计记录（内存环形 + 日志；持久审计面走既有 audit_log 通道，不在这里重复造）。 */
    public record PostureEvent(Instant at, Posture from, Posture to, String actor, String reason) {
    }

    /** 就绪判定结果。{@link #ready()} 为 false 时禁止收紧。 */
    public record Readiness(Posture current, int onlineDevices, int covered, List<Integer> missing,
                           boolean ready, Instant evaluatedAt, Duration evidenceWindow) {
    }

    /** 证据有效窗口：超过这么久没有签名帧，视为"当前没在签名"（关机/链路中断也算）。 */
    private Duration evidenceWindow = Duration.ofMinutes(15);

    /** 收紧后多久开始自动退回检查（刚翻完时链路里可能还有未签名的旧帧，给一个保护窗）。 */
    private Duration autoRollbackGrace = Duration.ofMinutes(2);

    /** 自动退回开关。关掉它就退化成"只校验不兜底"，风险重新落回值守人员身上。 */
    private boolean autoRollback = true;

    private final ObjectProvider<MavlinkSignatureConfig> signatureConfig;
    private final DeviceRegistry deviceRegistry;

    private final Deque<PostureEvent> events = new ArrayDeque<>();
    private volatile Instant lastStrictAt;

    /**
     * 判据时钟。生产固定用系统时钟；测试用它把"现在"推进证据窗口之外——
     * <b>不能靠把 {@code evidenceWindow} 缩到纳秒来制造过期</b>：本机（Windows + JDK 17）实测
     * 20 万次连续 {@code Instant.now()} 只出现 17 个不同读数，最长一次连续 28822 次同值，
     * 同一读数内 {@code isBefore} 恒 false ⇒ 那样写的用例会静默不生效（本用例第一版就这样假红过）。
     */
    private volatile Clock clock = Clock.systemUTC();

    public SigningPostureService(ObjectProvider<MavlinkSignatureConfig> signatureConfig,
                                 DeviceRegistry deviceRegistry) {
        this.signatureConfig = signatureConfig;
        this.deviceRegistry = deviceRegistry;
    }

    private Instant now() {
        return clock.instant();
    }

    /** 测试接缝：包私有，故不会被 {@code @ConfigurationProperties} 当成可绑定属性。 */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    public Posture currentPosture() {
        MavlinkSignatureConfig cfg = signatureConfig.getIfAvailable();
        if (cfg == null || !cfg.isEnabled()) {
            return Posture.OFF;
        }
        return cfg.isRejectUnsigned() ? Posture.STRICT : Posture.PERMISSIVE;
    }

    /**
     * 收紧前的就绪判定：每台**在线**设备都要在 {@code evidenceWindow} 内有签名通过的证据。
     *
     * <p>只看在线设备：离线的机器现在不影响任何事，等它上线时由看门狗兜住，
     * 不然后续维修换件的机器会永久卡住收紧（那等于把这个开关变成"永远打不开"）。
     */
    public Readiness evaluateReadiness() {
        Posture current = currentPosture();
        Instant cutoff = now().minus(evidenceWindow);
        Map<Integer, Instant> evidence = deviceRegistry.signingVerifiedSnapshot();
        List<Integer> missing = new ArrayList<>();
        int online = 0;
        for (DroneSnapshot s : deviceRegistry.all()) {
            if (!s.online) {
                continue;
            }
            online++;
            Instant seen = evidence.get(s.sysid);
            if (seen == null || seen.isBefore(cutoff)) {
                missing.add(s.sysid);
            }
        }
        // 一台在线设备都没有时 ready=true 是**故意**的：收紧没有风险敞口。
        // 但调用方要看得懂 onlineDevices=0，别把"没有设备"读成"全部就绪"。
        return new Readiness(current, online, online - missing.size(), List.copyOf(missing),
                missing.isEmpty(), now(), evidenceWindow);
    }

    /**
     * 收紧为 STRICT。<b>不就绪就抛 {@link PostureRejectedException}</b>，不"部分收紧"。
     */
    public Readiness requestStrict(String actor, String reason) {
        MavlinkSignatureConfig cfg = signatureConfig.getIfAvailable();
        if (cfg == null || !cfg.isEnabled()) {
            throw new PostureRejectedException(
                    "签名未启用（mavlink.signing.enabled=false 或该 bean 不存在），收紧无意义。"
                            + "请先完成启用步骤并确认设备侧密钥已下发。", List.of());
        }
        Readiness r = evaluateReadiness();
        if (!r.ready()) {
            // 名单进消息（不只进结构化字段）：调用方可能是定时任务或运维脚本，它拿不到 JSON 响应体
            throw new PostureRejectedException(
                    "有 " + r.missing().size() + " 台在线设备近 " + evidenceWindow.toMinutes()
                            + " 分钟内没有签名通过的证据，收紧会导致其遥测静默消失；缺失 sysid=" + r.missing(),
                    r.missing());
        }
        Posture from = r.current();
        cfg.setRejectUnsigned(true);
        lastStrictAt = now();
        record(from, Posture.STRICT, actor, reason);
        return evaluateReadiness();
    }

    /** 退回 PERMISSIVE（人工或看门狗都走这里，保证审计路径只有一条）。 */
    public Readiness requestPermissive(String actor, String reason) {
        MavlinkSignatureConfig cfg = signatureConfig.getIfAvailable();
        if (cfg == null || !cfg.isEnabled()) {
            // 本来就没启用签名：退回是空操作，但要说清楚，别让调用方以为"退回了 permissive"
            throw new PostureRejectedException("签名未启用，无需退回（当前就是 OFF）", List.of());
        }
        Posture from = currentPosture();
        cfg.setRejectUnsigned(false);
        lastStrictAt = null;
        record(from, Posture.PERMISSIVE, actor, reason);
        return evaluateReadiness();
    }

    /**
     * 看门狗：STRICT 且过了保护窗仍有在线设备拿不到签名证据 ⇒ 自动退回。
     *
     * <p><b>为什么值得自动退</b>：换件/重刷固件会让一台机器悄悄掉出签名姿态，人工发现的平均时间是
     * "下一次有人看那台机的界面"。而收紧的代价（失联）明显大于收益（少一层未签名放行）。
     * 关掉自动退回就退化成"只有门禁没有兜底"，需要接受这一点的人才关。
     */
    @Scheduled(fixedDelayString = "${aerofleet.signing.posture.watchdog-interval-ms:30000}")
    public void watchdog() {
        if (!autoRollback || currentPosture() != Posture.STRICT || lastStrictAt == null) {
            return;
        }
        if (now().isBefore(lastStrictAt.plus(autoRollbackGrace))) {
            return;
        }
        Readiness r = evaluateReadiness();
        if (r.ready()) {
            return;
        }
        requestPermissive("watchdog", "收紧后仍有无签名证据的在线设备：" + r.missing());
        log.error("签名姿态自动退回 PERMISSIVE：sysid 缺失签名证据 {}（窗口 {} 分钟）。"
                + "请核查这些设备的密钥下发/最近是否维修换件。", r.missing(), evidenceWindow.toMinutes());
    }

    private void record(Posture from, Posture to, String actor, String reason) {
        PostureEvent e = new PostureEvent(now(), from, to, actor, reason);
        synchronized (events) {
            events.addFirst(e);
            while (events.size() > 50) {
                events.removeLast();
            }
        }
        log.info("签名姿态变更 {} -> {} actor={} reason={}", from, to, actor, reason);
    }

    /** 最近的姿态变更（最多 50 条），供端点回显"是谁在什么时候因为什么翻的"。 */
    public List<PostureEvent> recentEvents() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    public void setEvidenceWindow(Duration evidenceWindow) {
        this.evidenceWindow = evidenceWindow;
    }

    public void setAutoRollbackGrace(Duration autoRollbackGrace) {
        this.autoRollbackGrace = autoRollbackGrace;
    }

    public void setAutoRollback(boolean autoRollback) {
        this.autoRollback = autoRollback;
    }

    public boolean isAutoRollback() {
        return autoRollback;
    }

    /** 收紧被拒：携带缺失名单，让调用方能直接显示"该去配哪几台"。 */
    public static class PostureRejectedException extends RuntimeException {
        private final List<Integer> missing;

        public PostureRejectedException(String message, List<Integer> missing) {
            super(message);
            this.missing = missing;
        }

        public List<Integer> missing() {
            return missing;
        }
    }
}

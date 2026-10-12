package io.aerofleet.regulatorsim;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UOM 激活状态机（serialNo 维度）——C1「高保真模拟」的核心。
 *
 * <p><b>为什么加它</b>：真实 UOM 平台的 activate/cancel/telemetry 是
 * <b>有状态、有幂等、有关联校验</b>的链路——未实名不可激活、未激活不可上报、
 * 注销后一切拒绝、重试返回同一 ID。旧的 MockUomServer 对这些一律
 * {@code success:true}，联调时上述语义一条都暴露不了。本类把语义补上，
 * 让 cloud-backend 的上报链路在 PoC 环境就能按真实规则被验收。
 *
 * <p>状态图（每 serialNo）：
 * <pre>
 *   NONE ──activate(VERIFIED)──► ACTIVATED ──telemetry(ACCEPTED)──► ACTIVATED
 *     │                              │
 *     │                              └──cancel──► CANCELLED
 *     └──cancel──► CANCELLED                （此后 activate/telemetry 一律拒绝）
 *   ACTIVATED ──activate──► 幂等返回原 ID（不新发）
 * </pre>
 *
 * <p>线程安全：方法全部 {@code synchronized}——HTTP 每请求一线程、监管上报
 * 频率低（分钟级），粗粒度锁足够，也避免引入并发复杂度。
 */
public final class ActivationStateMachine {

    /** 激活生命周期状态。 */
    public enum State { NONE, ACTIVATED, CANCELLED }

    /** activate 裁决。 */
    public enum Verdict { GRANTED, IDEMPOTENT, NOT_FOUND, NOT_VERIFIED, CANCELLED }

    /** 遥测接收裁决：仅 ACTIVATED 接收并累计计数/最后时间；其他拒绝。 */
    public enum TelemVerdict { ACCEPTED, NOT_ACTIVATED, NOT_IDENTIFIED, CANCELLED }

    /** activate 结果（id 在 GRANTED/IDEMPOTENT 时有值）。 */
    public record VerdictOutcome(Verdict verdict, String id, String reason) {
    }

    /** telemetry 结果。 */
    public record TelemOutcome(TelemVerdict verdict, String reason) {
    }

    /** 每 serialNo 的状态快照（不可变）。 */
    public record Status(State state, String activationId, String cancellationId,
                         long activatedAtMs, long lastTelemetryMs, long telemetryCount) {
        static Status none() {
            return new Status(State.NONE, null, null, 0, 0, 0);
        }
    }

    private final Map<String, Status> bySerial = new ConcurrentHashMap<>();

    /**
     * 归一化 serialNo：null/空白视为"未标识设备"。
     * <p>
     * 必须做：{@link ConcurrentHashMap} 不接受 null key，直接 {@code get/put(null)}
     * 会抛 NPE，而 handler 一炸 com.sun.net.httpserver 就静默关闭连接——客户端只看到
     * "无响应"，比一条明确的拒绝难查得多。空白串按同一 key 归一，避免 " TEST-001"
     * 与 "TEST-001" 被当成两架设备。
     */
    private static String normalize(String serialNo) {
        if (serialNo == null || serialNo.isBlank()) {
            return null;
        }
        return serialNo.trim();
    }

    /** 查询（无记录返回 NONE 快照，不抛）。 */
    public Status statusOf(String serialNo) {
        String key = normalize(serialNo);
        return key == null ? Status.none() : bySerial.getOrDefault(key, Status.none());
    }

    /**
     * 激活裁决。
     *
     * @param serialNo       产品序列号（null/空白 = 未标识，直接拒绝）
     * @param verifiedStatus 实名注册状态（null = 未注册；"VERIFIED" 才可激活）
     * @param nowMs          当前时间（epoch ms，由调用方注入以保证可测）
     * @param ids            ID 生成器（注入以便测试确定性）
     */
    public synchronized VerdictOutcome activate(String serialNo, String verifiedStatus,
                                                long nowMs, IdGenerator ids) {
        String key = normalize(serialNo);
        if (key == null) {
            return new VerdictOutcome(Verdict.NOT_FOUND, null, "未提供产品序列号");
        }
        Status cur = statusOf(key);
        if (cur.state() == State.ACTIVATED) {
            // 幂等：客户端重试/重复上报不产生第二个激活
            return new VerdictOutcome(Verdict.IDEMPOTENT, cur.activationId(), null);
        }
        if (cur.state() == State.CANCELLED) {
            return new VerdictOutcome(Verdict.CANCELLED, null, "已注销，不可重新激活");
        }
        if (verifiedStatus == null) {
            return new VerdictOutcome(Verdict.NOT_FOUND, null, "未实名注册");
        }
        if (!"VERIFIED".equalsIgnoreCase(verifiedStatus)) {
            return new VerdictOutcome(Verdict.NOT_VERIFIED, null, "实名状态 " + verifiedStatus + "，不可激活");
        }
        String id = ids.nextActivation(key, nowMs);
        bySerial.put(key, new Status(State.ACTIVATED, id, null, nowMs, 0, 0));
        return new VerdictOutcome(Verdict.GRANTED, id, null);
    }

    /** 遥测接收裁决：仅 ACTIVATED 接收并累计计数/最后时间；其他拒绝。 */
    public synchronized TelemOutcome recordTelemetry(String serialNo, long nowMs) {
        String key = normalize(serialNo);
        if (key == null) {
            return new TelemOutcome(TelemVerdict.NOT_IDENTIFIED, "未提供产品序列号");
        }
        Status cur = statusOf(key);
        if (cur.state() == State.ACTIVATED) {
            bySerial.put(key, new Status(cur.state(), cur.activationId(), cur.cancellationId(),
                    cur.activatedAtMs(), nowMs, cur.telemetryCount() + 1));
            return new TelemOutcome(TelemVerdict.ACCEPTED, null);
        }
        if (cur.state() == State.CANCELLED) {
            return new TelemOutcome(TelemVerdict.CANCELLED, "已注销，遥测拒绝");
        }
        return new TelemOutcome(TelemVerdict.NOT_ACTIVATED, "未激活，遥测拒绝");
    }

    /** 注销裁决：幂等（已注销返回原 ID）；未激活也可注销（登记即可）。 */
    public synchronized VerdictOutcome cancel(String serialNo, long nowMs, IdGenerator ids) {
        String key = normalize(serialNo);
        if (key == null) {
            return new VerdictOutcome(Verdict.NOT_FOUND, null, "未提供产品序列号");
        }
        Status cur = statusOf(key);
        if (cur.state() == State.CANCELLED) {
            return new VerdictOutcome(Verdict.IDEMPOTENT, cur.cancellationId(), null);
        }
        String id = ids.nextCancellation(key, nowMs);
        bySerial.put(key, new Status(State.CANCELLED, cur.activationId(), id,
                cur.activatedAtMs(), cur.lastTelemetryMs(), cur.telemetryCount()));
        return new VerdictOutcome(Verdict.GRANTED, id, null);
    }

    /** 清空全部状态（{@code POST /api/reset} 用：e2e 用例间隔离）。 */
    public synchronized void reset() {
        bySerial.clear();
    }

    /** ID 生成器（默认实现由 MockUomServer 提供：带时间戳与序号）。 */
    public interface IdGenerator {
        String nextActivation(String serialNo, long nowMs);

        String nextCancellation(String serialNo, long nowMs);
    }
}

package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.ai.DecisionMonitorService;
import io.aerofleet.cloud.regulator.TelemetryReportService;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.enums.DecisionType;
import io.aerofleet.mavlink.messages.DecisionEventMsg;
import io.aerofleet.mavlink.messages.GlobalPositionInt;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Central MAVLink frame decoder: decodes each CRC-valid frame and publishes a
 * {@link MavlinkMessageEvent} via {@link ApplicationEventPublisher}. All business
 * modules listen for the event types they care about via {@code @EventListener},
 * eliminating the 14 @Lazy cross-package dependencies of the previous design.
 * <p>
 * This service is intentionally thin — it only decodes and publishes. Every
 * onXxx() handler that previously lived here has moved to the owning module.
 * <p>
 * After decoding, if the frame is a {@link GlobalPositionInt} and the optional
 * {@link TelemetryReportService} is available, the extracted position / speed /
 * heading / airborne state is forwarded to the regulator via
 * {@code telemetryReportService.onTelemetry()}. A DecisionEventMsg (msgId 30051,
 * 机载自主决策引擎产生) 同样会记进可选的 {@link DecisionMonitorService}——
 * 否则 ai 的 REST 面永远空着，而 WS 面一直在出同一批事件（两个面分裂）。
 */
@Service
public class TelemetryIngestService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestService.class);

    private final ApplicationEventPublisher eventPublisher;

    /** Optional regulator telemetry report service (null when regulator module is not deployed). */
    @Autowired(required = false)
    private TelemetryReportService telemetryReportService;

    /**
     * 决策监控（可选注入，null = ai 模块未部署）。
     * <p>
     * 为什么必须有它：DECISION_EVENT(30051) 由机载自主决策引擎（drone-sim
     * {@code AutonomyAdvisor}）产生，此前只有 WS 转发、从不落库——于是
     * {@code DecisionMonitorService} 的 {@code recordDecision} 零调用方，
     * {@code GET /api/v1/ai/decisions} 恒返回空，对外是个"有接口无数据"的假能力。
     * 接入后 REST 与 WS 两个面看到的是同一批真实决策。
     */
    @Autowired(required = false)
    private DecisionMonitorService decisionMonitor;

    public TelemetryIngestService(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /** Called by the UDP transport for every CRC-valid frame. Never throws. */
    public void handle(MavlinkFrame frame) {
        try {
            MavlinkMessage msg = MavlinkMessage.decode(frame);
            if (msg == null) {
                return; // not one of our message types: ignore at scaffold stage
            }
            int sysid = frame.getSystemId();
            int msgId = frame.getMessageId();
            long timestamp = System.currentTimeMillis();
            eventPublisher.publishEvent(
                    new MavlinkMessageEvent(this, sysid, msgId, msg, timestamp));

            // Forward telemetry to regulator if available and frame carries position data
            if (telemetryReportService != null && msg instanceof GlobalPositionInt gpi) {
                double lat = gpi.lat();
                double lon = gpi.lon();
                double alt = gpi.altMm / 1000.0;
                double speed = Math.sqrt((long) gpi.vx * gpi.vx + (long) gpi.vy * gpi.vy) / 100.0;
                double heading = gpi.hdg != 65535 ? gpi.hdg / 100.0 : 0.0;
                boolean airborne = gpi.relativeAltMm > 0;
                telemetryReportService.onTelemetry(sysid, lat, lon, alt, speed, heading, airborne);
            }

            // 机载自主决策事件落监控（见 decisionMonitor 注释：补上"从不落库"的缺口）
            if (decisionMonitor != null && msg instanceof DecisionEventMsg de) {
                decisionMonitor.recordDecision(sysid, decisionTypeName(de.decisionType),
                        "reason=" + de.reason + " triggerValue=" + de.triggerValue,
                        de.confidence);
            }
        } catch (RuntimeException e) {
            // Decode errors must never kill the UDP receive loop.
            //
            // 这里刻意不解引用 frame：frame 为 null 时（decode 抛的 NPE 会被这里捕获）
            // 再去取 frame.getMessageId() 会二次抛 NPE，把 catch 块自己变成抛出点 ——
            // 而本方法 Javadoc 承诺 "Never throws"。frame 为 null 时这段日志直接省掉：
            // 那本来就不是"某一帧解码失败"，是调用方传了 null，日志里不重复制造噪声。
            if (frame != null) {
                log.debug("Failed to process frame msgId={} from sysid={}: {}",
                        frame.getMessageId(), frame.getSystemId(), e.getMessage());
            } else {
                log.debug("Failed to process null frame: {}", e.getMessage());
            }
        }
    }

    /**
     * 决策类型码 → 名称。越界/未知值不抛：监控面宁可记 "UNKNOWN(n)" 也不能让
     * 一个枚举外的字节把 ingest 打断（调用点在 UDP 收包线程上）。
     */
    private static String decisionTypeName(int code) {
        DecisionType[] values = DecisionType.values();
        if (code < 0 || code >= values.length) {
            return "UNKNOWN(" + code + ")";
        }
        return values[code].name();
    }
}

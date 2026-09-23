package io.aerofleet.cloud.api.service;

import io.aerofleet.cloud.alarm.AlarmEvent;
import io.aerofleet.cloud.alarm.AlarmEventStore;
import io.aerofleet.cloud.alarm.AlarmLinkageEngine;
import io.aerofleet.cloud.api.dto.LoRaAlarmDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LoRa 回传报警接收服务。
 * <p>
 * 接收从无人机 mesh 网络路由过来的 LoRa 回传报警事件，将 LoRa 回传报警转换为
 * 标准 {@link AlarmEvent} 格式，转发到 {@link AlarmEventStore} 和
 * {@link AlarmLinkageEngine} 进行联动处理，并记录 LoRa 回传通道的统计信息。
 *
 * <h3>处理流程</h3>
 * <ol>
 *   <li>接收 LoRaAlarmDto（从无人机 mesh 网络路由而来）</li>
 *   <li>转换为标准 AlarmEvent 格式</li>
 *   <li>若 AlarmEventStore 可用，存储报警事件</li>
 *   <li>若 AlarmLinkageEngine 可用，执行联动处理（规则匹配+动作执行）</li>
 *   <li>记录回传通道统计信息（成功率、延迟等）</li>
 * </ol>
 *
 * <h3>降级模式</h3>
 * AlarmEventStore 和 AlarmLinkageEngine 使用 {@code @Autowired(required=false)} 注入，
 * 当依赖不可用时自动降级为仅记录日志，不影响 LoRa 回传通道的基本功能。
 */
@Service
public class LoRaRelayService {

    private static final Logger log = LoggerFactory.getLogger(LoRaRelayService.class);

    /** 报警事件存储（可为 null，降级模式）。 */
    @Autowired(required = false)
    private AlarmEventStore alarmEventStore;

    /** 报警联动引擎（可为 null，降级模式）。 */
    @Autowired(required = false)
    private AlarmLinkageEngine alarmLinkageEngine;

    /** LoRa 回传总数。 */
    private final AtomicLong totalReceived = new AtomicLong(0);
    /** LoRa 回传成功数（成功转换为 AlarmEvent 并存储/联动）。 */
    private final AtomicLong totalSuccess = new AtomicLong(0);
    /** LoRa 回传失败数。 */
    private final AtomicLong totalFailed = new AtomicLong(0);
    /** LoRa 回传累计延迟（毫秒）。 */
    private final AtomicLong totalLatencyMs = new AtomicLong(0);

    /**
     * 接收 LoRa 回传报警。
     * <p>
     * 将 LoRa 回传报警转换为标准 AlarmEvent 格式，转发到 AlarmEventStore 和
     * AlarmLinkageEngine 进行联动处理。
     *
     * @param dto LoRa 回传报警 DTO
     * @return 处理结果（含 eventId、status、message），失败时 status 为 "FAILED"
     */
    public Map<String, Object> receiveLoRaAlarm(LoRaAlarmDto dto) {
        long startTime = System.currentTimeMillis();
        totalReceived.incrementAndGet();

        if (dto == null) {
            totalFailed.incrementAndGet();
            log.warn("LoRa relay alarm received null dto");
            return failedResult("null dto");
        }

        try {
            // 1. 转换为标准 AlarmEvent
            AlarmEvent event = convertToAlarmEvent(dto);

            // 2. 存储到 AlarmEventStore（降级：仅日志）
            if (alarmEventStore != null) {
                alarmEventStore.store(event);
                log.debug("LoRa relay alarm stored: eventId={} deviceId={}",
                        event.getId(), dto.getDeviceId());
            } else {
                log.debug("AlarmEventStore not available, skip store: deviceId={}",
                        dto.getDeviceId());
            }

            // 3. 联动处理（降级：仅日志）
            if (alarmLinkageEngine != null) {
                AlarmLinkageEngine.ProcessResult result = alarmLinkageEngine.processEvent(event);
                log.info("LoRa relay alarm processed by linkage engine: eventId={} matched={}",
                        event.getId(), result.getMatchedCount());
            } else {
                log.debug("AlarmLinkageEngine not available, skip linkage: deviceId={}",
                        dto.getDeviceId());
            }

            // 4. 记录统计
            long latency = System.currentTimeMillis() - startTime;
            totalLatencyMs.addAndGet(latency);
            totalSuccess.incrementAndGet();

            log.info("LoRa relay alarm received: deviceId={} type={} severity={} relayDrone={} rssi={}dBm",
                    dto.getDeviceId(), dto.getAlarmType(), dto.getSeverity(),
                    dto.getRelayDroneSysid(), dto.getSignalStrengthDbm());

            return successResult(event.getId(), latency);
        } catch (Exception e) {
            totalFailed.incrementAndGet();
            log.error("LoRa relay alarm processing failed: deviceId={} type={}",
                    dto.getDeviceId(), dto.getAlarmType(), e);
            return failedResult(e.getMessage());
        }
    }

    /**
     * 获取 LoRa 回传通道统计信息。
     *
     * @return 统计信息 map（含 totalReceived/totalSuccess/totalFailed/avgLatencyMs）
     */
    public Map<String, Object> getRelayStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        long received = totalReceived.get();
        long success = totalSuccess.get();
        long failed = totalFailed.get();
        long latency = totalLatencyMs.get();

        stats.put("totalReceived", received);
        stats.put("totalSuccess", success);
        stats.put("totalFailed", failed);
        stats.put("successRate", received > 0 ? (double) success / received : 0.0);
        stats.put("avgLatencyMs", success > 0 ? (double) latency / success : 0.0);
        return stats;
    }

    // =====================================================================
    // 内部方法
    // =====================================================================

    /**
     * 将 LoRaAlarmDto 转换为标准 AlarmEvent。
     * <p>
     * 映射规则：
     * <ul>
     *   <li>deviceId → sourceDeviceId（字符串形式）</li>
     *   <li>alarmType → eventType（FIRE/INTRUSION/MOTION 映射，其他映射为 CUSTOM）</li>
     *   <li>severity（1-5）→ AlarmEvent.Severity（1-2=INFO, 3=WARN, 4-5=CRITICAL）</li>
     *   <li>lat/lon/timestamp 直接映射</li>
     * </ul>
     */
    private AlarmEvent convertToAlarmEvent(LoRaAlarmDto dto) {
        String eventId = UUID.randomUUID().toString();
        String sourceDeviceId = "LoRa-" + dto.getDeviceId();
        String sourceDeviceName = "布控球-" + dto.getDeviceId();
        AlarmEvent.EventType eventType = mapAlarmType(dto.getAlarmType());
        AlarmEvent.Severity severity = mapSeverity(dto.getSeverity());
        String description = buildDescription(dto);

        return new AlarmEvent(
                eventId,
                sourceDeviceId,
                sourceDeviceName,
                eventType,
                severity,
                description,
                dto.getLat(),
                dto.getLon(),
                0.0,
                dto.getTimestamp(),
                false);
    }

    /** LoRa 报警类型 → AlarmEvent.EventType 映射。 */
    private static AlarmEvent.EventType mapAlarmType(String alarmType) {
        if (alarmType == null) {
            return AlarmEvent.EventType.CUSTOM;
        }
        switch (alarmType.toUpperCase()) {
            case "FIRE":
                return AlarmEvent.EventType.FIRE;
            case "INTRUSION":
                return AlarmEvent.EventType.INTRUSION;
            case "MOTION":
                return AlarmEvent.EventType.MOTION;
            default:
                return AlarmEvent.EventType.CUSTOM;
        }
    }

    /** LoRa severity（1-5）→ AlarmEvent.Severity 映射。 */
    private static AlarmEvent.Severity mapSeverity(int severity) {
        if (severity >= 4) {
            return AlarmEvent.Severity.CRITICAL;
        } else if (severity >= 3) {
            return AlarmEvent.Severity.WARN;
        } else {
            return AlarmEvent.Severity.INFO;
        }
    }

    /** 构建报警描述文本。 */
    private static String buildDescription(LoRaAlarmDto dto) {
        return "LoRa回传报警: 设备=" + dto.getDeviceId()
                + " 类型=" + dto.getAlarmType()
                + " 严重程度=" + dto.getSeverity()
                + " 中继无人机=" + dto.getRelayDroneSysid()
                + " 信号强度=" + dto.getSignalStrengthDbm() + "dBm";
    }

    /** 构建成功结果。 */
    private static Map<String, Object> successResult(String eventId, long latencyMs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", eventId);
        result.put("status", "SUCCESS");
        result.put("latencyMs", latencyMs);
        result.put("message", "LoRa回传报警处理成功");
        return result;
    }

    /** 构建失败结果。 */
    private static Map<String, Object> failedResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", null);
        result.put("status", "FAILED");
        result.put("latencyMs", 0);
        result.put("message", message == null ? "处理失败" : message);
        return result;
    }
}
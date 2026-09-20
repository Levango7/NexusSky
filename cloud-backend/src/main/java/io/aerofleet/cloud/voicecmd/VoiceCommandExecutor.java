package io.aerofleet.cloud.voicecmd;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语音指令执行器。
 * <p>
 * 将 {@link ParsedCommand} 转换为无人机飞行指令并执行，包含安全机制：
 * <ul>
 *   <li>HIGH 优先级指令需二次确认（返回 PENDING_CONFIRMATION，等待 confirm 调用）</li>
 *   <li>未知无人机 sysid 的指令将被拒绝</li>
 *   <li>指令历史记录用于追溯和审计</li>
 * </ul>
 */
@Service
public class VoiceCommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(VoiceCommandExecutor.class);

    private final DeviceRegistry registry;

    /** 待确认指令缓存：pendingId → ParsedCommand */
    private final Map<String, ParsedCommand> pendingCommands = new ConcurrentHashMap<>();

    /** 指令历史：commandId → ExecutionResult */
    private final Map<String, ExecutionResult> history = new ConcurrentHashMap<>();

    public VoiceCommandExecutor(DeviceRegistry registry) {
        this.registry = registry;
    }

    /**
     * 执行解析后的语音指令。
     * <p>
     * 安全机制：HIGH 优先级指令返回 PENDING_CONFIRMATION，需调用 {@link #confirm} 后才真正执行。
     *
     * @param cmd 解析后的指令
     * @return 执行结果
     */
    public ExecutionResult execute(ParsedCommand cmd) {
        String commandId = generateCommandId();

        // 校验无人机是否存在（sysid 为 -1 时使用默认值 1）
        int sysid = cmd.getSysid() > 0 ? cmd.getSysid() : 1;
        DroneSnapshot drone = registry.get(sysid);
        if (drone == null) {
            ExecutionResult result = new ExecutionResult(
                    commandId,
                    ExecutionResult.Status.REJECTED,
                    "无人机 " + sysid + " 未注册，指令被拒绝",
                    null);
            history.put(commandId, result);
            log.warn("Voice command rejected: sysid={} not registered", sysid);
            return result;
        }

        // 高优先级指令需二次确认
        if (cmd.getPriority() == ParsedCommand.Priority.HIGH) {
            String pendingId = commandId;
            pendingCommands.put(pendingId, cmd);
            ExecutionResult result = new ExecutionResult(
                    pendingId,
                    ExecutionResult.Status.PENDING_CONFIRMATION,
                    "紧急指令需二次确认：请确认执行 '" + describeAction(cmd) + "'",
                    null);
            history.put(pendingId, result);
            log.info("Voice command pending confirmation: pendingId={} action={}",
                    pendingId, cmd.getAction());
            return result;
        }

        // 正常执行
        String actionDesc = describeAction(cmd);
        ExecutionResult result = new ExecutionResult(
                commandId,
                ExecutionResult.Status.EXECUTED,
                "指令已执行：" + actionDesc,
                actionDesc);
        history.put(commandId, result);
        log.info("Voice command executed: commandId={} action={} sysid={}",
                commandId, cmd.getAction(), sysid);
        return result;
    }

    /**
     * 确认待确认的高优先级指令。
     *
     * @param pendingId 待确认指令 ID（由 execute 返回的 commandId）
     * @return 确认后的执行结果
     */
    public ExecutionResult confirm(String pendingId) {
        ParsedCommand cmd = pendingCommands.remove(pendingId);
        if (cmd == null) {
            ExecutionResult result = new ExecutionResult(
                    pendingId,
                    ExecutionResult.Status.FAILED,
                    "未找到待确认指令 " + pendingId,
                    null);
            history.put(pendingId, result);
            log.warn("Confirm failed: pendingId={} not found", pendingId);
            return result;
        }

        String actionDesc = describeAction(cmd);
        ExecutionResult result = new ExecutionResult(
                pendingId,
                ExecutionResult.Status.EXECUTED,
                "紧急指令已确认并执行：" + actionDesc,
                actionDesc);
        history.put(pendingId, result);
        log.info("Voice command confirmed and executed: pendingId={} action={}",
                pendingId, cmd.getAction());
        return result;
    }

    /**
     * 获取指令历史记录。
     *
     * @return 指令历史 Map（commandId → ExecutionResult）
     */
    public Map<String, ExecutionResult> getHistory() {
        return history;
    }

    /**
     * 获取待确认指令列表。
     *
     * @return 待确认指令 Map（pendingId → ParsedCommand）
     */
    public Map<String, ParsedCommand> getPending() {
        return pendingCommands;
    }

    // --- 内部工具方法 ---

    /**
     * 将 ParsedCommand 转换为可读的飞行指令描述。
     * 模拟将语音指令映射为 DroneController 的飞行命令格式。
     */
    private String describeAction(ParsedCommand cmd) {
        int sysid = cmd.getSysid() > 0 ? cmd.getSysid() : 1;
        StringBuilder sb = new StringBuilder();

        switch (cmd.getAction()) {
            case TAKEOFF:
                sb.append("takeoff");
                if (cmd.getAltitudeM() != null) {
                    sb.append(" alt=").append(cmd.getAltitudeM()).append("m");
                }
                break;
            case LAND:
                sb.append("land");
                break;
            case RETURN:
                sb.append("rtl");
                break;
            case HOVER:
                sb.append("hover");
                break;
            case PHOTO:
                sb.append("photo");
                break;
            case RECORD:
                sb.append("record");
                break;
            case FLY_TO:
                sb.append("fly_to");
                if (cmd.getTargetName() != null) {
                    sb.append(" target=").append(cmd.getTargetName());
                }
                if (cmd.getTargetLat() != null && cmd.getTargetLon() != null) {
                    sb.append(" lat=").append(cmd.getTargetLat())
                      .append(" lon=").append(cmd.getTargetLon());
                }
                break;
            case SET_ALTITUDE:
                sb.append("set_altitude");
                if (cmd.getAltitudeM() != null) {
                    sb.append(" alt=").append(cmd.getAltitudeM()).append("m");
                }
                break;
            case SET_SPEED:
                sb.append("set_speed");
                if (cmd.getSpeedMps() != null) {
                    sb.append(" speed=").append(cmd.getSpeedMps()).append("m/s");
                }
                break;
            default:
                sb.append("unknown");
                break;
        }
        sb.append(" sysid=").append(sysid);
        return sb.toString();
    }

    private String generateCommandId() {
        return "cmd-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
package io.aerofleet.cloud.voicecmd;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.cloud.mission.common.MissionItemRequest;
import io.aerofleet.cloud.mission.common.MissionUploadResult;
import io.aerofleet.mavlink.enums.MavEnums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语音指令执行器。
 * <p>
 * 将 {@link ParsedCommand} 通过 {@link DroneCommandService} 真实下发到目标无人机
 * （MAVLink COMMAND_LONG / 任务协议），依据飞控 COMMAND_ACK 生成执行结果：
 * EXECUTED = 飞控 ACCEPTED；FAILED = 已下发但被拒/超时/链路异常；
 * REJECTED = 未下发（动作无真实命令通路或参数缺失）。
 * <p>
 * 安全机制：
 * <ul>
 *   <li>HIGH 优先级指令需二次确认（返回 PENDING_CONFIRMATION，等待 confirm 调用后才下发）</li>
 *   <li>未知无人机 sysid 的指令将被拒绝（confirm 前也会重校验）</li>
 *   <li>TAKEOFF 下发经过 {@link DroneCommandService} 的围栏拦截链（GeofenceInterceptService DENY 时失败）</li>
 *   <li>无真实命令通路的动作明确拒绝，不假装执行</li>
 *   <li>指令历史记录用于追溯和审计</li>
 * </ul>
 * <p>
 * 动作 → MAVLink 映射（与仿真 {@code VirtualDrone.handleCommandLong} 的支持面对齐）：
 * <ul>
 *   <li>TAKEOFF → MAV_CMD_NAV_TAKEOFF；未指定高度时云端默认 {@link #DEFAULT_TAKEOFF_ALT_M}（与仿真侧默认一致）</li>
 *   <li>LAND → MAV_CMD_NAV_LAND（原地降落）</li>
 *   <li>RETURN → MAV_CMD_NAV_RETURN_TO_LAUNCH</li>
 *   <li>PHOTO → MAV_CMD_IMAGE_START_CAPTURE（param1=0 无间隔、param2=1 单张）</li>
 *   <li>FLY_TO → 单航点任务上传（MISSION 协议）+ MAV_CMD_MISSION_START，会覆盖机上现有任务；
 *       需显式经纬度与高度。当前无地名→坐标解析服务，"前往XX" 类指令会被拒绝并说明原因</li>
 *   <li>HOVER / RECORD / SET_ALTITUDE / SET_SPEED → 仿真无对应命令通路（HOLD 仅由 failsafe 进入、
 *       不支持 MAV_CMD_VIDEO_START_CAPTURE / MAV_CMD_DO_CHANGE_ALTITUDE / MAV_CMD_DO_CHANGE_SPEED），明确拒绝</li>
 * </ul>
 */
@Service
public class VoiceCommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(VoiceCommandExecutor.class);

    /** 语音起飞未指定高度时的云端默认值（与仿真 VirtualDrone.handleTakeoff 的 10m 默认一致）。 */
    static final double DEFAULT_TAKEOFF_ALT_M = 10.0;

    private final DeviceRegistry registry;
    private final DroneCommandService commands;

    /** 待确认指令缓存：pendingId → 待确认条目（指令 + 入队时刻）。 */
    private final Map<String, PendingCommand> pendingCommands = new ConcurrentHashMap<>();

    /**
     * 待确认指令的存活期：超过该时长仍未确认即作废。
     * <p>
     * 这不只是内存问题，更是安全问题：高优先级指令要二次确认，若一条「起飞」在
     * 未确认状态无限期挂着，几小时后任何一次同 ID 的确认都会把它真的下发。
     * 2 分钟足够操作员看清提示并确认，又短到不会让迟到的确认变成意外动作。
     */
    static final long PENDING_TTL_MS = 120_000L;

    /** 待确认队列上限（TTL 之外的第二道闸）：防止脚本刷屏把 map 撑大。 */
    static final int MAX_PENDING = 64;

    /** 测试可注入时钟；生产走系统时钟。 */
    private final java.time.Clock clock;

    /** 待确认条目。 */
    private record PendingCommand(ParsedCommand command, long createdAtMs) { }

    /** 指令历史：commandId → ExecutionResult（只保留最新 {@value #MAX_HISTORY} 条）。 */
    private final Map<String, ExecutionResult> history = new ConcurrentHashMap<>();

    /** 历史保留上限：语音指令是低频人工操作，200 条足够复盘且不会再无限增长。 */
    static final int MAX_HISTORY = 200;

    /** 历史插入顺序（commandId 是随机 UUID，不能按 key 排序，只能另记顺序用于淘汰最旧）。 */
    private final java.util.Deque<String> historyOrder = new java.util.concurrent.ConcurrentLinkedDeque<>();

    @org.springframework.beans.factory.annotation.Autowired
    public VoiceCommandExecutor(DeviceRegistry registry, DroneCommandService commands) {
        this(registry, commands, java.time.Clock.systemUTC());
    }

    /** 测试专用：注入可控时钟，让过期判定不依赖真实时间。 */
    VoiceCommandExecutor(DeviceRegistry registry, DroneCommandService commands,
                         java.time.Clock clock) {
        this.registry = registry;
        this.commands = commands;
        this.clock = clock;
    }

    /**
     * 记录指令历史并淘汰最旧条目。
     * <p>
     * 不用「按 commandId 排序删除」：commandId 是随机 UUID，与时间无关。改为维护一条
     * 插入顺序队列，超过 {@link #MAX_HISTORY} 时从队首淘汰。同一 commandId 会被写两次
     * （execute 写 PENDING_CONFIRMATION、confirm 写最终结果），只有首次插入才入队，
     * 否则同一个 id 会占两个淘汰位、把仍在队列里的有效条目提前挤掉。
     * <p>
     * 并发下队列长度可能瞬时略超上限（两个线程同时入队），下次写入会继续收敛；
     * 这里只求有界，不求精确等于 N。
     */
    private void remember(String commandId, ExecutionResult result) {
        if (history.put(commandId, result) == null) {
            historyOrder.addLast(commandId);
            while (historyOrder.size() > MAX_HISTORY) {
                String oldest = historyOrder.pollFirst();
                if (oldest != null) {
                    history.remove(oldest);
                }
            }
        }
    }

    /**
     * 执行解析后的语音指令。
     * <p>
     * 安全机制：HIGH 优先级指令返回 PENDING_CONFIRMATION，需调用 {@link #confirm} 后才真正下发。
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
            remember(commandId, result);
            log.warn("Voice command rejected: sysid={} not registered", sysid);
            return result;
        }

        // 高优先级指令需二次确认
        if (cmd.getPriority() == ParsedCommand.Priority.HIGH) {
            String pendingId = commandId;
            pruneExpiredPending();
            pendingCommands.put(pendingId, new PendingCommand(cmd, clock.millis()));
            enforcePendingCap(pendingId);
            ExecutionResult result = new ExecutionResult(
                    pendingId,
                    ExecutionResult.Status.PENDING_CONFIRMATION,
                    "紧急指令需二次确认：请确认执行 '" + describeAction(cmd) + "'",
                    null);
            remember(pendingId, result);
            log.info("Voice command pending confirmation: pendingId={} action={}",
                    pendingId, cmd.getAction());
            return result;
        }

        return dispatch(cmd, sysid, commandId, false);
    }

    /**
     * 确认待确认的高优先级指令：确认后真实下发。
     *
     * @param pendingId 待确认指令 ID（由 execute 返回的 commandId）
     * @return 确认后的执行结果（EXECUTED / FAILED / REJECTED）；未找到时返回 FAILED
     */
    public ExecutionResult confirm(String pendingId) {
        PendingCommand pending = pendingCommands.remove(pendingId);
        if (pending == null) {
            ExecutionResult result = new ExecutionResult(
                    pendingId,
                    ExecutionResult.Status.FAILED,
                    "未找到待确认指令 " + pendingId,
                    null);
            remember(pendingId, result);
            log.warn("Confirm failed: pendingId={} not found", pendingId);
            return result;
        }
        if (isExpired(pending)) {
            // 过期不是「找不到」，要说清楚原因：让操作员知道需要重新下达，而不是去查 ID 拼写。
            ExecutionResult result = new ExecutionResult(
                    pendingId,
                    ExecutionResult.Status.FAILED,
                    "待确认指令已过期（超过 " + (PENDING_TTL_MS / 1000) + " 秒未确认），请重新下达",
                    null);
            remember(pendingId, result);
            log.warn("Confirm rejected: pendingId={} expired (age={}ms)",
                    pendingId, clock.millis() - pending.createdAtMs());
            return result;
        }
        ParsedCommand cmd = pending.command();

        // pending 期间无人机可能已离线/注销：下发前重校验
        int sysid = cmd.getSysid() > 0 ? cmd.getSysid() : 1;
        if (registry.get(sysid) == null) {
            ExecutionResult result = new ExecutionResult(
                    pendingId,
                    ExecutionResult.Status.REJECTED,
                    "无人机 " + sysid + " 未注册，指令被拒绝",
                    null);
            remember(pendingId, result);
            log.warn("Voice command confirmed but rejected: pendingId={} sysid={} not registered",
                    pendingId, sysid);
            return result;
        }

        ExecutionResult result = dispatch(cmd, sysid, pendingId, true);
        log.info("Voice command confirmed and dispatched: pendingId={} action={} → {}",
                pendingId, cmd.getAction(), result.getStatus());
        return result;
    }

    /**
     * 指定 pendingId 是否存在待确认指令（供控制器区分 404 与下发失败）。
     * <p>过期条目视同不存在，并顺带清掉——否则控制器会先放行、再在 confirm 里被拒。
     */
    public boolean hasPending(String pendingId) {
        if (pendingId == null) {
            return false;
        }
        PendingCommand pending = pendingCommands.get(pendingId);
        if (pending == null) {
            return false;
        }
        if (isExpired(pending)) {
            pendingCommands.remove(pendingId, pending);
            return false;
        }
        return true;
    }

    /**
     * 获取指令历史记录。
     *
     * @return 指令历史 Map（commandId → ExecutionResult）
     */
    public Map<String, ExecutionResult> getHistory() {
        return Collections.unmodifiableMap(history);
    }

    /**
     * 获取待确认指令列表（已过期的不返回，并顺带清理）。
     *
     * @return 待确认指令 Map（pendingId → ParsedCommand）
     */
    public Map<String, ParsedCommand> getPending() {
        pruneExpiredPending();
        Map<String, ParsedCommand> snapshot = new java.util.LinkedHashMap<>();
        pendingCommands.forEach((id, pending) -> snapshot.put(id, pending.command()));
        return Collections.unmodifiableMap(snapshot);
    }

    // --- 待确认队列维护 ---

    private boolean isExpired(PendingCommand pending) {
        return clock.millis() - pending.createdAtMs() > PENDING_TTL_MS;
    }

    /** 清掉过期条目：没人确认的条目不能永远留在内存里。 */
    private void pruneExpiredPending() {
        pendingCommands.entrySet().removeIf(e -> isExpired(e.getValue()));
    }

    /**
     * 第二道闸：TTL 已能收敛常态，这里兜住「短时间刷入大量待确认指令」。
     * 超出上限时淘汰最旧的一条（新建的这条本身不动）。
     */
    private void enforcePendingCap(String justAddedId) {
        if (pendingCommands.size() <= MAX_PENDING) {
            return;
        }
        pendingCommands.entrySet().stream()
                .filter(e -> !e.getKey().equals(justAddedId))
                .min(java.util.Comparator.comparingLong(e -> e.getValue().createdAtMs()))
                .map(Map.Entry::getKey)
                .ifPresent(oldest -> {
                    pendingCommands.remove(oldest);
                    log.warn("Pending command queue full ({}), evicted oldest pendingId={}",
                            MAX_PENDING, oldest);
                });
    }

    /** 测试辅助：当前待确认条数。 */
    int pendingSize() {
        return pendingCommands.size();
    }

    // --- 真实下发 ---

    /**
     * 将指令经 {@link DroneCommandService} 下发，并依据飞控 ACK 生成结果。
     */
    private ExecutionResult dispatch(ParsedCommand cmd, int sysid, String commandId, boolean confirmed) {
        String actionDesc = describeAction(cmd);

        // 无真实命令通路的动作：明确拒绝，不做任何下发
        switch (cmd.getAction()) {
            case HOVER -> {
                return rejected(commandId,
                        "悬停无对应命令通路（仿真 HOLD 状态仅由 failsafe 进入，无受支持的模式切换命令），指令被拒绝");
            }
            case RECORD -> {
                return rejected(commandId,
                        "录像控制无对应命令通路（仿真不支持 MAV_CMD_VIDEO_START_CAPTURE），指令被拒绝");
            }
            case SET_ALTITUDE -> {
                return rejected(commandId,
                        "设置高度无对应命令通路（仿真不支持 MAV_CMD_DO_CHANGE_ALTITUDE），指令被拒绝");
            }
            case SET_SPEED -> {
                return rejected(commandId,
                        "设置速度无对应命令通路（仿真不支持 MAV_CMD_DO_CHANGE_SPEED），指令被拒绝");
            }
            case UNKNOWN -> {
                return rejected(commandId, "无法识别的指令动作，指令被拒绝");
            }
            default -> { }
        }

        // FLY_TO 参数校验：无坐标/无高度时拒绝，不下发
        if (cmd.getAction() == ParsedCommand.Action.FLY_TO) {
            if (cmd.getTargetLat() == null || cmd.getTargetLon() == null) {
                String name = cmd.getTargetName() != null ? " '" + cmd.getTargetName() + "'" : "";
                return rejected(commandId,
                        "无法定位目标" + name + "：暂不支持地名→坐标解析，请提供经纬度（targetLat/targetLon）");
            }
            if (cmd.getAltitudeM() == null) {
                return rejected(commandId, "前往目标点需指定飞行高度（altitudeM）");
            }
        }

        String detail;
        try {
            detail = switch (cmd.getAction()) {
                case TAKEOFF -> {
                    double alt = cmd.getAltitudeM() != null ? cmd.getAltitudeM() : DEFAULT_TAKEOFF_ALT_M;
                    requireAccepted(commands.takeoff(sysid, alt), "takeoff");
                    yield "起飞至 " + alt + "m"
                            + (cmd.getAltitudeM() == null ? "（未指定高度，使用默认 " + DEFAULT_TAKEOFF_ALT_M + "m）" : "");
                }
                case LAND -> {
                    // MAV_CMD_NAV_LAND：仿真侧原地降落（VirtualDrone.handleLand）
                    requireAccepted(commands.command(sysid, MavEnums.MAV_CMD_NAV_LAND,
                            0f, 0f, 0f, 0f, 0f, 0f, 0f), "land");
                    yield "原地降落";
                }
                case RETURN -> {
                    requireAccepted(commands.rtl(sysid), "rtl");
                    yield "返航（RTL）";
                }
                case PHOTO -> {
                    // IMAGE_START_CAPTURE：param1=0（无间隔），param2=1（单张）
                    requireAccepted(commands.command(sysid, MavEnums.MAV_CMD_IMAGE_START_CAPTURE,
                            0f, 1f, 0f, 0f, 0f, 0f, 0f), "photo");
                    yield "拍照（IMAGE_START_CAPTURE）";
                }
                case FLY_TO -> {
                    // 真实通路：单航点任务上传 + MISSION_START（覆盖机上现有任务）
                    MissionItemRequest item = new MissionItemRequest("waypoint",
                            cmd.getTargetLat(), cmd.getTargetLon(), cmd.getAltitudeM(), 0);
                    MissionUploadResult upload = commands.uploadMission(
                            sysid, commands.toMissionItems(List.of(item), sysid));
                    if (!"ok".equals(upload.status())) {
                        throw new DroneCommandService.CommandException(
                                "目标点任务上传失败：" + upload.error(), upload.ackResult());
                    }
                    requireAccepted(commands.startMission(sysid), "start_mission");
                    yield "前往目标点（单航点任务已上传并启动，覆盖机上原任务）";
                }
                // HOVER/RECORD/SET_ALTITUDE/SET_SPEED/UNKNOWN 已在上文拒绝，不可达
                default -> throw new IllegalStateException("unreachable action: " + cmd.getAction());
            };
        } catch (DroneCommandService.CommandException e) {
            ExecutionResult result = new ExecutionResult(
                    commandId,
                    ExecutionResult.Status.FAILED,
                    "指令下发失败：" + e.getMessage(),
                    actionDesc);
            remember(commandId, result);
            log.warn("Voice command dispatch failed: commandId={} action={} error={}",
                    commandId, cmd.getAction(), e.getMessage());
            return result;
        } catch (UncheckedIOException e) {
            ExecutionResult result = new ExecutionResult(
                    commandId,
                    ExecutionResult.Status.FAILED,
                    "指令下发失败：" + e.getMessage(),
                    actionDesc);
            remember(commandId, result);
            log.warn("Voice command send failed: commandId={} action={} error={}",
                    commandId, cmd.getAction(), e.getMessage());
            return result;
        }

        String message = (confirmed ? "紧急指令已确认并下发：" : "指令已下发并获飞控确认：") + detail;
        ExecutionResult result = new ExecutionResult(commandId, ExecutionResult.Status.EXECUTED,
                message, actionDesc);
        remember(commandId, result);
        log.info("Voice command executed: commandId={} action={} sysid={} detail={}",
                commandId, cmd.getAction(), sysid, detail);
        return result;
    }

    /** 生成 REJECTED 结果（未下发任何指令）。 */
    private ExecutionResult rejected(String commandId, String message) {
        ExecutionResult result = new ExecutionResult(
                commandId, ExecutionResult.Status.REJECTED, message, null);
        remember(commandId, result);
        log.info("Voice command rejected: commandId={} reason={}", commandId, message);
        return result;
    }

    /** 飞控 ACK 非 ACCEPTED（如围栏拦截 DENY、未解锁 DENIED）时抛出，由调用方转为 FAILED。 */
    private void requireAccepted(int ackResult, String op) {
        if (ackResult != MavEnums.MAV_RESULT_ACCEPTED) {
            throw new DroneCommandService.CommandException(
                    op + " 被飞控拒绝（MAV_RESULT=" + ackResult + "）", ackResult);
        }
    }

    // --- 内部工具方法 ---

    /**
     * 生成指令的可读动作描述（executedAction 字段与日志用），
     * 语义与 DroneController 飞行命令（takeoff/land/rtl/…）对齐。
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

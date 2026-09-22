package io.aerofleet.cloud.voicecmd;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.aerofleet.cloud.api.exception.ApiExceptionHandler.NotFoundException;

/**
 * 语音/自然语言指挥 REST API。
 * <p>
 * 端点：
 * <ul>
 *   <li>{@code POST /api/voice-cmd/parse} — 解析语音文本为指令</li>
 *   <li>{@code POST /api/voice-cmd/execute} — 执行解析后的指令</li>
 *   <li>{@code POST /api/voice-cmd/confirm/{pendingId}} — 确认待确认指令</li>
 *   <li>{@code POST /api/voice-cmd/broadcast/{sysid}} — 语音播报</li>
 *   <li>{@code GET /api/voice-cmd/broadcast/{sysid}/status} — 获取状态播报文本</li>
 *   <li>{@code GET /api/voice-cmd/broadcast/{sysid}/alert} — 获取告警播报文本</li>
 *   <li>{@code GET /api/voice-cmd/history} — 查询指令历史</li>
 *   <li>{@code GET /api/voice-cmd/pending} — 查询待确认指令</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/voice-cmd")
@Tag(name = "Voice Command", description = "语音/自然语言指挥 REST API：语音解析、指令执行、语音播报")
public class VoiceCommandController {

    private static final Logger log = LoggerFactory.getLogger(VoiceCommandController.class);

    private final VoiceCommandParser parser;
    private final VoiceCommandExecutor executor;
    private final VoiceBroadcaster broadcaster;
    private final DeviceRegistry registry;

    public VoiceCommandController(VoiceCommandParser parser,
                                  VoiceCommandExecutor executor,
                                  VoiceBroadcaster broadcaster,
                                  DeviceRegistry registry) {
        this.parser = parser;
        this.executor = executor;
        this.broadcaster = broadcaster;
        this.registry = registry;
    }

    /**
     * 解析语音文本为结构化指令。
     *
     * @param body 请求体，包含 {"text": "语音文本"}
     * @return 解析后的 {@link ParsedCommand}
     */
    @Operation(summary = "解析语音文本", description = "将自然语言语音文本解析为结构化的无人机指令")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "解析成功"),
        @ApiResponse(responseCode = "400", description = "请求体缺少 text 字段")
    })
    @PostMapping("/parse")
    public ParsedCommand parse(@RequestBody Map<String, String> body) {
        if (body == null) {
            throw new io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException(
                    "request body must not be null");
        }
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            throw new io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException(
                    "body must contain a non-empty 'text' field");
        }
        log.info("Parsing voice text: '{}'", text);
        return parser.parse(text);
    }

    /**
     * 执行解析后的指令。
     *
     * @param cmd 解析后的指令
     * @return 执行结果
     */
    @Operation(summary = "执行语音指令", description = "执行已解析的语音指令，高优先级指令需二次确认")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "执行结果"),
        @ApiResponse(responseCode = "404", description = "无人机未注册")
    })
    @PostMapping("/execute")
    public ExecutionResult execute(@RequestBody ParsedCommand cmd) {
        log.info("Executing voice command: action={} sysid={}", cmd.getAction(), cmd.getSysid());
        return executor.execute(cmd);
    }

    /**
     * 确认待确认的高优先级指令。
     *
     * @param pendingId 待确认指令 ID
     * @return 确认后的执行结果
     */
    @Operation(summary = "确认紧急指令", description = "确认并执行待确认的高优先级指令")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "确认结果"),
        @ApiResponse(responseCode = "404", description = "待确认指令不存在")
    })
    @PostMapping("/confirm/{pendingId}")
    public ExecutionResult confirm(@PathVariable("pendingId") String pendingId) {
        log.info("Confirming pending command: {}", pendingId);
        ExecutionResult result = executor.confirm(pendingId);
        if (result.getStatus() == ExecutionResult.Status.FAILED) {
            throw new NotFoundException("pending command not found: " + pendingId);
        }
        return result;
    }

    /**
     * 发送语音播报到指定无人机操作员。
     *
     * @param sysid 目标无人机 systemId
     * @param body  请求体，包含 {"text": "播报文本"}
     * @return 播报结果
     */
    @Operation(summary = "发送语音播报", description = "将文本播报发送给指定无人机的操作员")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "播报结果"),
        @ApiResponse(responseCode = "404", description = "无人机未注册")
    })
    @PostMapping("/broadcast/{sysid}")
    public BroadcastResult broadcast(@PathVariable("sysid") int sysid,
                                     @RequestBody Map<String, String> body) {
        requireRegistered(sysid);
        if (body == null) {
            throw new io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException(
                    "request body must not be null");
        }
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            throw new io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException(
                    "body must contain a non-empty 'text' field");
        }
        log.info("Broadcasting to sysid={}: '{}'", sysid, text);
        return broadcaster.broadcast(text, sysid);
    }

    /**
     * 获取指定无人机的状态播报文本。
     *
     * @param sysid 无人机 systemId
     * @return 状态播报文本
     */
    @Operation(summary = "获取状态播报", description = "根据无人机当前遥测数据生成状态播报文本")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "状态播报文本"),
        @ApiResponse(responseCode = "404", description = "无人机未注册")
    })
    @GetMapping("/broadcast/{sysid}/status")
    public Map<String, String> statusBroadcast(@PathVariable("sysid") int sysid) {
        requireRegistered(sysid);
        String text = broadcaster.statusBroadcast(sysid);
        return Map.of("text", text);
    }

    /**
     * 获取指定无人机的告警播报文本。
     *
     * @param sysid 无人机 systemId
     * @return 告警播报文本
     */
    @Operation(summary = "获取告警播报", description = "根据告警类型生成告警播报文本")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "告警播报文本"),
        @ApiResponse(responseCode = "404", description = "无人机未注册")
    })
    @GetMapping("/broadcast/{sysid}/alert")
    public Map<String, String> alertBroadcast(@PathVariable("sysid") int sysid) {
        requireRegistered(sysid);
        // 自动检测告警类型：优先低电量告警
        String alertType = broadcaster.needsLowBatteryAlert(sysid) ? "low_battery" : "none";
        String text = broadcaster.alertBroadcast(alertType, sysid);
        return Map.of("text", text, "alertType", alertType);
    }

    /**
     * 查询指令历史记录。
     *
     * @return 指令历史列表
     */
    @Operation(summary = "查询指令历史", description = "获取所有已执行的语音指令历史记录")
    @GetMapping("/history")
    public List<ExecutionResult> history() {
        return executor.getHistory().values().stream()
                .collect(Collectors.toList());
    }

    /**
     * 查询待确认指令列表。
     *
     * @return 待确认指令列表
     */
    @Operation(summary = "查询待确认指令", description = "获取所有等待确认的高优先级指令")
    @GetMapping("/pending")
    public List<Map<String, Object>> pending() {
        return executor.getPending().entrySet().stream()
                .map(e -> Map.<String, Object>of(
                        "pendingId", e.getKey(),
                        "command", e.getValue()))
                .collect(Collectors.toList());
    }

    // --- 内部工具方法 ---

    private void requireRegistered(int sysid) {
        if (registry.get(sysid) == null) {
            throw new NotFoundException("unknown drone sysid " + sysid);
        }
    }
}
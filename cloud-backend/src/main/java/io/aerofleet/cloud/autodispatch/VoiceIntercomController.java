package io.aerofleet.cloud.autodispatch;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语音对讲 REST API（P0-1 安防报警→无人机自动出警闭环）。
 * <p>
 * 端点前缀 {@code /api/voice-intercom}，覆盖：
 * <ul>
 *   <li>启动双向语音对讲（{@code POST /{sysid}/start}）</li>
 *   <li>停止语音对讲（{@code POST /{sysid}/stop}）</li>
 *   <li>语音对讲状态查询（{@code GET /{sysid}/status}）</li>
 *   <li>广播喊话（{@code POST /{sysid}/broadcast}）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/voice-intercom")
@Tag(name = "VoiceIntercom", description = "语音对讲 REST API：双向对讲启停、状态查询、广播喊话")
public class VoiceIntercomController {

    private static final Logger log = LoggerFactory.getLogger(VoiceIntercomController.class);

    private final VoiceIntercomService service;

    public VoiceIntercomController(VoiceIntercomService service) {
        this.service = service;
    }

    /**
     * 启动双向语音对讲。
     *
     * @param sysid 无人机 systemId
     * @return 启动后的对讲状态
     */
    @Operation(summary = "启动双向语音对讲")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "启动成功"),
            @ApiResponse(responseCode = "404", description = "无人机未注册")
    })
    @PostMapping("/{sysid}/start")
    public ResponseEntity<Map<String, Object>> start(@PathVariable("sysid") int sysid) {
        VoiceIntercomService.IntercomState state = service.startIntercom(sysid);
        log.info("intercom start requested: sysid={} status={}", sysid, state.getStatus());
        return ResponseEntity.ok(intercomStateToMap(state));
    }

    /**
     * 停止语音对讲。
     *
     * @param sysid 无人机 systemId
     * @return 停止后的对讲状态
     */
    @Operation(summary = "停止语音对讲")
    @ApiResponse(responseCode = "200", description = "停止成功")
    @PostMapping("/{sysid}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable("sysid") int sysid) {
        VoiceIntercomService.IntercomState state = service.stopIntercom(sysid);
        log.info("intercom stop requested: sysid={} status={}", sysid, state.getStatus());
        return ResponseEntity.ok(intercomStateToMap(state));
    }

    /**
     * 语音对讲状态查询。
     *
     * @param sysid 无人机 systemId
     * @return 对讲状态
     */
    @Operation(summary = "语音对讲状态查询")
    @ApiResponse(responseCode = "200", description = "对讲状态")
    @GetMapping("/{sysid}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable("sysid") int sysid) {
        VoiceIntercomService.IntercomState state = service.getStatus(sysid);
        return ResponseEntity.ok(intercomStateToMap(state));
    }

    /**
     * 广播喊话：将文本转为语音指令发送给无人机。
     * <p>
     * body: {@code {"text": "请立即离开", "volume": 70}}
     *
     * @param sysid 无人机 systemId
     * @param body  广播请求体
     * @return 广播结果
     */
    @Operation(summary = "广播喊话", description = "body: {text, volume}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "广播结果"),
            @ApiResponse(responseCode = "400", description = "文本为空或过长")
    })
    @PostMapping("/{sysid}/broadcast")
    public ResponseEntity<Map<String, Object>> broadcast(@PathVariable("sysid") int sysid,
                                                         @RequestBody Map<String, Object> body) {
        String text = body.get("text") == null ? null : String.valueOf(body.get("text"));
        int volume = 0;
        Object volObj = body.get("volume");
        if (volObj instanceof Number n) {
            volume = n.intValue();
        } else if (volObj instanceof String s) {
            try {
                volume = Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                // 保持 0，使用默认值
            }
        }

        VoiceIntercomService.BroadcastResult result = service.broadcast(sysid, text, volume);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sysid", result.getSysid());
        resp.put("status", result.getStatus());
        resp.put("message", result.getMessage());
        resp.put("volume", result.getVolume());
        resp.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(resp);
    }

    private static Map<String, Object> intercomStateToMap(VoiceIntercomService.IntercomState s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sysid", s.getSysid());
        m.put("status", s.getStatus().name());
        m.put("startTimeMs", s.getStartTimeMs());
        m.put("stopTimeMs", s.getStopTimeMs());
        return m;
    }
}
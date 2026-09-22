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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 视频流回传 REST API（P0-1 安防报警→无人机自动出警闭环）。
 * <p>
 * 端点前缀 {@code /api/video-stream}，覆盖：
 * <ul>
 *   <li>获取无人机视频流 URL（{@code GET /{sysid}/url}）</li>
 *   <li>启动视频流推送（{@code POST /{sysid}/start}）</li>
 *   <li>停止视频流（{@code POST /{sysid}/stop}）</li>
 *   <li>视频流状态查询（{@code GET /{sysid}/status}）</li>
 *   <li>所有活跃视频流（{@code GET /active}）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/video-stream")
@Tag(name = "VideoStream", description = "视频流回传 REST API：无人机 RTSP 流 URL、启停控制、状态查询")
public class VideoStreamController {

    private static final Logger log = LoggerFactory.getLogger(VideoStreamController.class);

    private final VideoStreamService service;

    public VideoStreamController(VideoStreamService service) {
        this.service = service;
    }

    /**
     * 获取无人机视频流 URL（RTSP）。
     *
     * @param sysid 无人机 systemId
     * @return RTSP URL
     */
    @Operation(summary = "获取无人机视频流 URL", description = "返回 RTSP URL")
    @ApiResponse(responseCode = "200", description = "RTSP URL")
    @GetMapping("/{sysid}/url")
    public ResponseEntity<Map<String, Object>> getUrl(@PathVariable("sysid") int sysid) {
        String url = service.getRtspUrl(sysid);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sysid", sysid);
        resp.put("url", url);
        resp.put("protocol", "RTSP");
        return ResponseEntity.ok(resp);
    }

    /**
     * 启动视频流推送。
     *
     * @param sysid 无人机 systemId
     * @return 启动后的视频流状态
     */
    @Operation(summary = "启动视频流推送")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "启动成功"),
            @ApiResponse(responseCode = "404", description = "无人机未注册")
    })
    @PostMapping("/{sysid}/start")
    public ResponseEntity<Map<String, Object>> start(@PathVariable("sysid") int sysid) {
        VideoStreamService.StreamState state = service.startStream(sysid);
        log.info("video stream start requested: sysid={} status={}", sysid, state.getStatus());
        return ResponseEntity.ok(streamStateToMap(state));
    }

    /**
     * 停止视频流。
     *
     * @param sysid 无人机 systemId
     * @return 停止后的视频流状态
     */
    @Operation(summary = "停止视频流")
    @ApiResponse(responseCode = "200", description = "停止成功")
    @PostMapping("/{sysid}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable("sysid") int sysid) {
        VideoStreamService.StreamState state = service.stopStream(sysid);
        log.info("video stream stop requested: sysid={} status={}", sysid, state.getStatus());
        return ResponseEntity.ok(streamStateToMap(state));
    }

    /**
     * 视频流状态查询。
     *
     * @param sysid 无人机 systemId
     * @return 视频流状态
     */
    @Operation(summary = "视频流状态查询")
    @ApiResponse(responseCode = "200", description = "视频流状态")
    @GetMapping("/{sysid}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable("sysid") int sysid) {
        VideoStreamService.StreamState state = service.getStatus(sysid);
        return ResponseEntity.ok(streamStateToMap(state));
    }

    /**
     * 查询所有活跃视频流。
     */
    @Operation(summary = "查询所有活跃视频流")
    @ApiResponse(responseCode = "200", description = "活跃视频流列表")
    @GetMapping("/active")
    public ResponseEntity<Map<String, Object>> active() {
        List<VideoStreamService.StreamState> streams = service.getActiveStreams();
        List<Map<String, Object>> items = new ArrayList<>(streams.size());
        for (VideoStreamService.StreamState s : streams) {
            items.add(streamStateToMap(s));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", items.size());
        return ResponseEntity.ok(resp);
    }

    private static Map<String, Object> streamStateToMap(VideoStreamService.StreamState s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sysid", s.getSysid());
        m.put("url", s.getUrl());
        m.put("status", s.getStatus().name());
        m.put("startTimeMs", s.getStartTimeMs());
        m.put("stopTimeMs", s.getStopTimeMs());
        if (s.getErrorMsg() != null) {
            m.put("errorMsg", s.getErrorMsg());
        }
        return m;
    }
}
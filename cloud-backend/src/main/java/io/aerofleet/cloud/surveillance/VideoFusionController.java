package io.aerofleet.cloud.surveillance;

import io.aerofleet.cloud.autodispatch.VideoStreamService;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * 视频融合 REST API（端点前缀 {@code /api/v1/video-fusion}）。
 *
 * <h2>为什么以前没有这个控制器</h2>
 * GCS 侧的 {@code VideoFusionPanel}（734 行）从建仓起就调用本前缀下的 4 个端点，
 * 但**后端从未实现过**——面板因此在运行期必然 404（README 已在「已知边界」登记此事）。
 * 本控制器把这 4 个端点补齐，使面板与「应急千元级」档位（{@code videofusion}）
 * 名义上已售出的能力真正可用。
 *
 * <h2>数据来源（复用既有服务，不新建状态）</h2>
 * <ul>
 *   <li>安防流 ← {@link SurveillanceDeviceRegistry#listDevices()}（已按
 *       {@code TenantContext} 过滤，与 {@code /api/v1/surveillance/devices} 同源同权限面）；</li>
 *   <li>无人机画面 ← {@link DeviceRegistry#all()} + {@link VideoStreamService}
 *       （与 {@code /api/v1/video-stream} 同源）。</li>
 * </ul>
 *
 * <h2>与 {@code /api/v1/video-stream} 的分工</h2>
 * 两者刻意**不合并**：{@code video-stream} 是单机的推流生命周期控制
 * （start/stop/url/status，面向自动化编排）；本控制器是**多路画面聚合视图**
 * （一次列出全部可用画面供拼屏），额外承载录制登记。合并会让拼屏视图被单机
 * 生命周期语义绑死，也多一处要同步的文档。
 *
 * <h2>录制语义边界</h2>
 * 骨架阶段录制只是**状态登记**，不落盘不转码，响应里的
 * {@code recorded} / {@code storageUrl} 如实反映"没有产物"。
 * 见 {@link VideoFusionRecordingService} 的说明。
 *
 * <h2>授权归属</h2>
 * 商业分档中「视频/视觉」属应急版，故本前缀在 {@code LicenseModuleMap} 中
 * 登记为 {@code emergency}——与 surveillance / vision / alarms 同档。
 */
@RestController
@RequestMapping("/api/v1/video-fusion")
@Tag(name = "VideoFusion", description = "视频融合 REST API：安防流与无人机画面聚合视图、录制登记")
@RequireRole(Role.OBSERVER)
public class VideoFusionController {

    private final SurveillanceDeviceRegistry registry;
    private final VideoStreamService videoStreamService;
    private final DeviceRegistry deviceRegistry;
    private final VideoFusionRecordingService recordingService;

    public VideoFusionController(SurveillanceDeviceRegistry registry,
                                VideoStreamService videoStreamService,
                                DeviceRegistry deviceRegistry,
                                VideoFusionRecordingService recordingService) {
        this.registry = registry;
        this.videoStreamService = videoStreamService;
        this.deviceRegistry = deviceRegistry;
        this.recordingService = recordingService;
    }

    // ------------------------------------------------------------------
    // 画面列表
    // ------------------------------------------------------------------

    @Operation(summary = "列出安防摄像头视频流（供拼屏选择）",
               description = "返回已登记设备的 rtspUrl 与在线状态；凭据按 SurveillanceController 同规则脱敏。"
                       + "租户可见性与 /api/v1/surveillance/devices 完全一致（未归属设备仅全局域可见）")
    @ApiResponse(responseCode = "200", description = "流列表")
    @GetMapping("/surveillance/streams")
    public ResponseEntity<Map<String, Object>> surveillanceStreams() {
        List<SurveillanceDevice> all = registry.listDevices();
        List<Map<String, Object>> streams = new ArrayList<>(all.size());
        for (SurveillanceDevice d : all) {
            Map<String, Object> v = new LinkedHashMap<>();
            // 面板用 deviceId || id 双读（VideoFusionPanel.jsx 的槽位回填逻辑），
            // 两个键都给，省掉前端的兼容分支。
            v.put("deviceId", d.id);
            v.put("name", d.name);
            v.put("vendor", d.vendor.name());
            v.put("rtspUrl", maskRtspCredentials(d.rtspUrl));
            v.put("online", SurveillanceDevice.Status.ONLINE.equals(d.status));
            v.put("recording", recordingService.isRecording(d.id));
            streams.add(v);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", streams.size());
        result.put("streams", streams);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "列出无人机画面（供拼屏选择）",
               description = "按机队注册表列出全部已知无人机；feedUrl 由 VideoStreamService 生成，"
                       + "online 取自注册表在线判定（心跳未超时）")
    @ApiResponse(responseCode = "200", description = "画面列表")
    @GetMapping("/drone/feeds")
    public ResponseEntity<Map<String, Object>> droneFeeds() {
        List<DroneSnapshot> all = deviceRegistry.all();
        List<Map<String, Object>> feeds = new ArrayList<>(all.size());
        for (DroneSnapshot d : all) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("sysid", d.sysid);
            v.put("name", "Drone-" + d.sysid);
            v.put("feedUrl", videoStreamService.getRtspUrl(d.sysid));
            v.put("online", d.online);
            // 无姓名字段可继承（注册表不存别名），故显式给出 mode/battery：
            // 面板在离线灰显之外还要标"在飞/待机"，否则操作员分不清"离线"与"待命"。
            v.put("mode", d.mode);
            v.put("battery", d.battery);
            v.put("recording", recordingService.isRecording(String.valueOf(d.sysid)));
            feeds.add(v);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", feeds.size());
        result.put("feeds", feeds);
        return ResponseEntity.ok(result);
    }

    // ------------------------------------------------------------------
    // 录制登记
    // ------------------------------------------------------------------

    @Operation(summary = "开始录制登记",
               description = "sourceId 为安防设备 id 或无人机 sysid。**骨架阶段只做状态登记，"
                       + "不落盘不转码**，故响应中 recorded=true 而 storageUrl=null")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "已在录（幂等）"),
        @ApiResponse(responseCode = "404", description = "sourceId 既非已登记设备也非已知 sysid")
    })
    @PostMapping("/recording/{sourceId}/start")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> startRecording(@PathVariable("sourceId") String sourceId) {
        String sourceType = resolveSourceType(sourceId);
        if (sourceType == null) {
            return notFound(sourceId);
        }
        VideoFusionRecordingService.Recording rec = recordingService.start(sourceId, sourceType);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("sourceId", rec.sourceId());
        body.put("sourceType", rec.sourceType());
        body.put("recording", true);
        body.put("startedAtMs", rec.startedAtMs());
        // 如实为 null：登记簿不落盘，没有产物就没有地址。
        body.put("storageUrl", null);
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "停止录制登记",
               description = "未在录时返回 404（面板据此把按钮复位）")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "已停止"),
        @ApiResponse(responseCode = "404", description = "该源未在录，或源不存在")
    })
    @PostMapping("/recording/{sourceId}/stop")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> stopRecording(@PathVariable("sourceId") String sourceId) {
        VideoFusionRecordingService.Recording removed = recordingService.stop(sourceId);
        if (removed == null) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "recording not active: " + sourceId));
        }
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "sourceId", removed.sourceId(),
                "sourceType", removed.sourceType(),
                "recording", false));
    }

    @Operation(summary = "列出当前录制中的源")
    @ApiResponse(responseCode = "200", description = "录制登记列表")
    @GetMapping("/recording")
    public ResponseEntity<Map<String, Object>> listRecordings() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", recordingService.activeCount());
        result.put("recordings", recordingService.list());
        return ResponseEntity.ok(result);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 判定 {@code sourceId} 是安防设备还是无人机，并据此登记来源类型。
     *
     * <p>安防设备 id 与 sysid 命名空间可能重叠（外部传入的 id 可以是 "1"），
     * 故**安防优先**——理由是安防设备走 {@code registry} 的租户过滤，
     * 无人机走 {@code deviceRegistry}，混判会让租户可见性跟着漂。
     *
     * @return {@code surveillance} / {@code drone}；两者都不匹配则 {@code null}
     */
    private String resolveSourceType(String sourceId) {
        if (registry.getDevice(sourceId) != null) {
            return "surveillance";
        }
        try {
            int sysid = Integer.parseInt(sourceId.trim());
            if (deviceRegistry.get(sysid) != null) {
                return "drone";
            }
        } catch (NumberFormatException ignored) {
            // 非数字且非设备 id —— 交给调用方回 404
        }
        return null;
    }

    private static ResponseEntity<Map<String, Object>> notFound(String sourceId) {
        return ResponseEntity.status(404)
                .body(Map.of("error", "unknown video source: " + sourceId));
    }

    /**
     * RTSP URL 凭据脱敏（与 {@code SurveillanceController.maskRtspCredentials} 同规则）。
     * <p>此处独立实现而非复用：那个方法是 private static，且它对
     * {@code rtsp://user:pass@host} 的处理是整段替换——把它提到公共工具类属于
     * 与本任务无关的重构，复制三行并注明同源更划算。
     */
    static String maskRtspCredentials(String rtspUrl) {
        if (rtspUrl == null || rtspUrl.isEmpty()) {
            return rtspUrl;
        }
        int schemeEnd = rtspUrl.indexOf("://");
        if (schemeEnd < 0) {
            return rtspUrl;
        }
        int hostStart = schemeEnd + 3;
        int at = rtspUrl.indexOf('@', hostStart);
        int slash = rtspUrl.indexOf('/', hostStart);
        if (at < 0 || (slash >= 0 && at > slash)) {
            return rtspUrl;                       // 无 userinfo
        }
        return rtspUrl.substring(0, hostStart) + "***:***@" + rtspUrl.substring(at + 1);
    }
}
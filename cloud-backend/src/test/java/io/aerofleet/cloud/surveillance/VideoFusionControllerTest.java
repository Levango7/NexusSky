package io.aerofleet.cloud.surveillance;

import io.aerofleet.cloud.autodispatch.VideoStreamService;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VideoFusionController 端点测试（直接实例化，无 Spring 上下文）。
 *
 * <h2>为什么需要这组测试</h2>
 * GCS 的 {@code VideoFusionPanel} 从建仓起就调用 {@code /api/v1/video-fusion/*}
 * 的 4 个端点，而**后端此前从未实现**——面板在运行期必然 404。README 只用文字
 * 登记了这个事实，但文字不会变红。本组测试把「这 4 个端点存在且返回面板真正需要的
 * 字段」钉成断言：端点被删或返回体形状变了（面板依赖 {@code deviceId}/{@code sysid}
 * 的双键回填、{@code online} 布尔、{@code rtspUrl}/{@code feedUrl}），本测试即红。
 */
@DisplayName("VideoFusionController：GCS 视频融合面板的 4 个端点")
class VideoFusionControllerTest {

    private SurveillanceDeviceRegistry registry;
    private VideoStreamService videoStreamService;
    private DeviceRegistry deviceRegistry;
    private VideoFusionRecordingService recordingService;
    private VideoFusionController controller;

    @BeforeEach
    void setUp() {
        registry = new SurveillanceDeviceRegistry();
        videoStreamService = new VideoStreamService();
        deviceRegistry = new DeviceRegistry();
        recordingService = new VideoFusionRecordingService();
        controller = new VideoFusionController(registry, videoStreamService, deviceRegistry, recordingService);
    }

    // ===== 安防流列表 =====

    @Test
    @DisplayName("GET /surveillance/streams：返回面板槽位回填所需的 deviceId 与 online")
    void surveillanceStreamsExposeFieldsPanelNeeds() {
        registry.register(new SurveillanceDevice("cam-1", "大门", SurveillanceDevice.Vendor.HIKVISION,
                "10.0.0.9", 554, "admin", "secret"));

        ResponseEntity<Map<String, Object>> resp = controller.surveillanceStreams();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("count", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> streams = (List<Map<String, Object>>) resp.getBody().get("streams");
        assertThat(streams).hasSize(1);
        Map<String, Object> s = streams.get(0);
        // VideoFusionPanel 用 `s.deviceId || s.id` 回填槽位，deviceId 必须存在
        assertThat(s.get("deviceId")).isEqualTo("cam-1");
        assertThat(s.get("name")).isEqualTo("大门");
        assertThat(s.get("online")).isEqualTo(Boolean.TRUE);
        assertThat(s).containsKey("rtspUrl").containsKey("recording");
    }

    @Test
    @DisplayName("空机队时 streams 返回空数组而非 null（面板对 null 做 .find 会崩）")
    void emptySurveillanceReturnsEmptyArrayNotNull() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> streams =
                (List<Map<String, Object>>) controller.surveillanceStreams().getBody().get("streams");
        assertThat(streams).isEmpty();
    }

    // ===== 无人机画面列表 =====

    @Test
    @DisplayName("GET /drone/feeds：feedUrl 取自 VideoStreamService，offline 也列出（前端自行灰显）")
    void droneFeedsListRegisteredSnapshots() {
        deviceRegistry.registerIfAbsent(7);
        videoStreamService.startStream(7);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> feeds =
                (List<Map<String, Object>>) controller.droneFeeds().getBody().get("feeds");

        assertThat(feeds).hasSize(1);
        Map<String, Object> f = feeds.get(0);
        assertThat(f.get("sysid")).isEqualTo(7);
        assertThat(f.get("name")).isEqualTo("Drone-7");
        assertThat(f.get("feedUrl")).isEqualTo(videoStreamService.getRtspUrl(7));
        // 离线机也应出现在列表里：面板靠 online 字段灰显，而不是靠"列表里没有"来表达离线
        assertThat(f).containsKey("online").containsKey("mode").containsKey("battery");
    }

    // ===== 录制登记 =====

    @Test
    @DisplayName("start/stop 安防设备：登记可开关，且 storageUrl 如实为 null（登记簿无产物）")
    void recordingLifecycleForSurveillanceDevice() {
        registry.register(new SurveillanceDevice("cam-2", "后门", SurveillanceDevice.Vendor.DAHUA,
                "10.0.0.10", 554, "admin", "pw"));

        ResponseEntity<Map<String, Object>> started = controller.startRecording("cam-2");
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(started.getBody()).containsEntry("recording", Boolean.TRUE)
                .containsEntry("sourceType", "surveillance");
        // 关键诚实性断言：骨架阶段不落盘，不得伪造一个产物地址
        assertThat(started.getBody()).containsEntry("storageUrl", null);
        assertThat(recordingService.isRecording("cam-2")).isTrue();

        ResponseEntity<Map<String, Object>> stopped = controller.stopRecording("cam-2");
        assertThat(stopped.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stopped.getBody()).containsEntry("recording", Boolean.FALSE);
        assertThat(recordingService.isRecording("cam-2")).isFalse();
    }

    @Test
    @DisplayName("start 无人机 sysid：sourceType 判为 drone")
    void recordingStartForDroneSysid() {
        deviceRegistry.registerIfAbsent(9);

        ResponseEntity<Map<String, Object>> resp = controller.startRecording("9");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("sourceType", "drone");
    }

    @Test
    @DisplayName("start 幂等：重复 start 不重置起始时刻（否则录时长永远为 0）")
    void recordingStartIsIdempotent() {
        registry.register(new SurveillanceDevice("cam-3", "仓库", SurveillanceDevice.Vendor.ONVIF,
                "10.0.0.11", 554, "admin", "pw"));

        long first = (Long) controller.startRecording("cam-3").getBody().get("startedAtMs");
        long second = (Long) controller.startRecording("cam-3").getBody().get("startedAtMs");

        assertThat(second).isEqualTo(first);
        assertThat(recordingService.activeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("未知 sourceId 一律 404：不存在的东西不能被登记成在录")
    void unknownSourceIdYields404() {
        assertThat(controller.startRecording("no-such-thing").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.startRecording("999").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(recordingService.activeCount()).isZero();
    }

    @Test
    @DisplayName("stop 未在录的源返回 404（面板据此把按钮复位）")
    void stopInactiveSourceYields404() {
        registry.register(new SurveillanceDevice("cam-4", "北门", SurveillanceDevice.Vendor.UNIVIEW,
                "10.0.0.12", 554, "admin", "pw"));
        assertThat(controller.stopRecording("cam-4").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("id 命名空间重叠时安防优先：否则租户可见性会跟着漂")
    void surveillanceWinsWhenIdNamespaceOverlaps() {
        // 安防设备 id 恰好是 "1"，同时 sysid=1 也已注册
        registry.register(new SurveillanceDevice("1", "数字编号设备",
                SurveillanceDevice.Vendor.HIKVISION, "10.0.0.13", 554, "admin", "pw"));
        deviceRegistry.registerIfAbsent(1);

        assertThat(controller.startRecording("1").getBody()).containsEntry("sourceType", "surveillance");
    }

    // ===== 凭据脱敏 =====

    @Test
    @DisplayName("RTSP 凭据脱敏：user:pass 不出现在任何响应里")
    void rtspCredentialsAreMasked() {
        SurveillanceDevice d = new SurveillanceDevice("cam-5", "敏感点位", SurveillanceDevice.Vendor.HIKVISION,
                "10.0.0.14", 554, "admin", "SuperSecret123");
        d.rtspUrl = "rtsp://admin:SuperSecret123@10.0.0.14:554/Streaming/Channels/101";
        registry.register(d);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> streams =
                (List<Map<String, Object>>) controller.surveillanceStreams().getBody().get("streams");

        String url = (String) streams.get(0).get("rtspUrl");
        assertThat(url).doesNotContain("SuperSecret123").doesNotContain("admin:");
        assertThat(url).contains("@10.0.0.14:554");   // 主机与路径保留，前端仍可连
    }

    @Test
    @DisplayName("无 userinfo 的 RTSP URL 原样返回（不误伤）")
    void maskLeavesPlainUrlUntouched() {
        assertThat(VideoFusionController.maskRtspCredentials("rtsp://10.0.0.15:554/live"))
                .isEqualTo("rtsp://10.0.0.15:554/live");
        assertThat(VideoFusionController.maskRtspCredentials(null)).isNull();
        assertThat(VideoFusionController.maskRtspCredentials("")).isEmpty();
    }

    @Test
    @DisplayName("@ 在路径段里不算 userinfo（rtsp://host/path@x 不应被截断）")
    void atSignInsidePathIsNotUserInfo() {
        String url = "rtsp://10.0.0.16:554/live@2x";
        assertThat(VideoFusionController.maskRtspCredentials(url)).isEqualTo(url);
    }
}
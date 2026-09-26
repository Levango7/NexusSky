package io.aerofleet.cloud.autodispatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VideoStreamService} 单元测试（P0-1 安防报警→无人机自动出警闭环）。
 * <p>
 * 覆盖 RTSP URL 生成、启动/停止视频流、状态查询、活跃流查询等。
 */
@DisplayName("VideoStreamService 视频流服务 (P0-1)")
class VideoStreamServiceTest {

    private VideoStreamService service;

    @BeforeEach
    void setUp() {
        service = new VideoStreamService();
    }

    @Test
    @DisplayName("getRtspUrl 生成正确格式的 RTSP URL")
    void getRtspUrlReturnsCorrectFormat() {
        String url = service.getRtspUrl(1);
        assertThat(url).isEqualTo("rtsp://drone-1:8554/live");
    }

    @Test
    @DisplayName("getRtspUrl 不同 sysid 生成不同 URL")
    void getRtspUrlDifferentSysidDifferentUrl() {
        String url1 = service.getRtspUrl(1);
        String url2 = service.getRtspUrl(2);
        assertThat(url1).isNotEqualTo(url2);
        assertThat(url1).contains("drone-1");
        assertThat(url2).contains("drone-2");
    }

    @Test
    @DisplayName("startStream 启动视频流后状态为 STREAMING")
    void startStreamSetsStreamingStatus() {
        VideoStreamService.StreamState state = service.startStream(1);

        assertThat(state.getStatus()).isEqualTo(VideoStreamService.StreamStatus.STREAMING);
        assertThat(state.getUrl()).isEqualTo("rtsp://drone-1:8554/live");
        assertThat(state.getStartTimeMs()).isGreaterThan(0);
    }

    @Test
    @DisplayName("startStream 幂等：已 STREAMING 时再次启动保持状态")
    void startStreamIdempotent() {
        service.startStream(1);
        VideoStreamService.StreamState state = service.startStream(1);

        assertThat(state.getStatus()).isEqualTo(VideoStreamService.StreamStatus.STREAMING);
    }

    @Test
    @DisplayName("stopStream 停止视频流后状态为 IDLE")
    void stopStreamSetsIdleStatus() {
        service.startStream(1);
        VideoStreamService.StreamState state = service.stopStream(1);

        assertThat(state.getStatus()).isEqualTo(VideoStreamService.StreamStatus.IDLE);
        assertThat(state.getStopTimeMs()).isGreaterThan(0);
    }

    @Test
    @DisplayName("stopStream 幂等：已 IDLE 时再次停止保持状态")
    void stopStreamIdempotent() {
        VideoStreamService.StreamState state = service.stopStream(1);

        assertThat(state.getStatus()).isEqualTo(VideoStreamService.StreamStatus.IDLE);
    }

    @Test
    @DisplayName("getStatus 未启动过视频流时返回 IDLE")
    void getStatusReturnsIdleForNewDrone() {
        VideoStreamService.StreamState state = service.getStatus(1);

        assertThat(state.getStatus()).isEqualTo(VideoStreamService.StreamStatus.IDLE);
        assertThat(state.getUrl()).isEqualTo("rtsp://drone-1:8554/live");
    }

    @Test
    @DisplayName("getActiveStreams 返回所有 STREAMING 状态的视频流")
    void getActiveStreamsReturnsOnlyStreaming() {
        service.startStream(1);
        service.startStream(2);
        service.startStream(3);
        service.stopStream(2);

        List<VideoStreamService.StreamState> active = service.getActiveStreams();

        assertThat(active).hasSize(2);
        assertThat(active.stream().map(s -> s.sysid)).containsExactlyInAnyOrder(1, 3);
    }

    @Test
    @DisplayName("getActiveStreams 无活跃流时返回空列表")
    void getActiveStreamsEmptyWhenNoStreaming() {
        List<VideoStreamService.StreamState> active = service.getActiveStreams();
        assertThat(active).isEmpty();
    }

    @Test
    @DisplayName("activeCount 返回活跃视频流数量")
    void activeCountReturnsCorrectNumber() {
        service.startStream(1);
        service.startStream(2);
        service.stopStream(1);

        assertThat(service.activeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("markError 标记视频流为 ERROR 状态")
    void markErrorSetsErrorStatus() {
        service.startStream(1);
        service.markError(1, "connection lost");

        VideoStreamService.StreamState state = service.getStatus(1);
        assertThat(state.getStatus()).isEqualTo(VideoStreamService.StreamStatus.ERROR);
        assertThat(state.getErrorMsg()).isEqualTo("connection lost");
    }

    @Test
    @DisplayName("startStream 后 stopStream 再 startStream 可重新启动")
    void restartAfterStop() {
        service.startStream(1);
        service.stopStream(1);
        VideoStreamService.StreamState state = service.startStream(1);

        assertThat(state.getStatus()).isEqualTo(VideoStreamService.StreamStatus.STREAMING);
    }
}
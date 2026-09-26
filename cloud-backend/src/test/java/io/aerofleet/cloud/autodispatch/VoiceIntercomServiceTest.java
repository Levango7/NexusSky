package io.aerofleet.cloud.autodispatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VoiceIntercomService} 单元测试（P0-1 安防报警→无人机自动出警闭环）。
 * <p>
 * 覆盖语音对讲启动/停止、状态查询、广播喊话、广播历史等。
 */
@DisplayName("VoiceIntercomService 语音对讲服务 (P0-1)")
class VoiceIntercomServiceTest {

    private VoiceIntercomService service;

    @BeforeEach
    void setUp() {
        service = new VoiceIntercomService();
    }

    @Test
    @DisplayName("startIntercom 启动对讲后状态为 ACTIVE")
    void startIntercomSetsActiveStatus() {
        VoiceIntercomService.IntercomState state = service.startIntercom(1);

        assertThat(state.getStatus()).isEqualTo(VoiceIntercomService.IntercomStatus.ACTIVE);
        assertThat(state.getStartTimeMs()).isGreaterThan(0);
    }

    @Test
    @DisplayName("startIntercom 幂等：已 ACTIVE 时再次启动保持状态")
    void startIntercomIdempotent() {
        service.startIntercom(1);
        VoiceIntercomService.IntercomState state = service.startIntercom(1);

        assertThat(state.getStatus()).isEqualTo(VoiceIntercomService.IntercomStatus.ACTIVE);
    }

    @Test
    @DisplayName("stopIntercom 停止对讲后状态为 INACTIVE")
    void stopIntercomSetsInactiveStatus() {
        service.startIntercom(1);
        VoiceIntercomService.IntercomState state = service.stopIntercom(1);

        assertThat(state.getStatus()).isEqualTo(VoiceIntercomService.IntercomStatus.INACTIVE);
        assertThat(state.getStopTimeMs()).isGreaterThan(0);
    }

    @Test
    @DisplayName("stopIntercom 幂等：已 INACTIVE 时再次停止保持状态")
    void stopIntercomIdempotent() {
        VoiceIntercomService.IntercomState state = service.stopIntercom(1);

        assertThat(state.getStatus()).isEqualTo(VoiceIntercomService.IntercomStatus.INACTIVE);
    }

    @Test
    @DisplayName("getStatus 未启动过对讲时返回 INACTIVE")
    void getStatusReturnsInactiveForNewDrone() {
        VoiceIntercomService.IntercomState state = service.getStatus(1);

        assertThat(state.getStatus()).isEqualTo(VoiceIntercomService.IntercomStatus.INACTIVE);
    }

    @Test
    @DisplayName("broadcast 成功发送广播喊话")
    void broadcastSendsSuccessfully() {
        VoiceIntercomService.BroadcastResult result = service.broadcast(1, "请立即离开", 70);

        assertThat(result.getStatus()).isEqualTo("SENT");
        assertThat(result.getMessage()).isEqualTo("broadcast sent");
        assertThat(result.getVolume()).isEqualTo(70);
    }

    @Test
    @DisplayName("broadcast 文本为空时返回 FAILED")
    void broadcastEmptyTextReturnsFailed() {
        VoiceIntercomService.BroadcastResult result = service.broadcast(1, "", 70);

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getMessage()).contains("empty");
    }

    @Test
    @DisplayName("broadcast 文本为 null 时返回 FAILED")
    void broadcastNullTextReturnsFailed() {
        VoiceIntercomService.BroadcastResult result = service.broadcast(1, null, 70);

        assertThat(result.getStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("broadcast 文本超长时返回 FAILED")
    void broadcastTooLongTextReturnsFailed() {
        String longText = "a".repeat(VoiceIntercomService.MAX_TEXT_LENGTH + 1);
        VoiceIntercomService.BroadcastResult result = service.broadcast(1, longText, 70);

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getMessage()).contains("exceeds");
    }

    @Test
    @DisplayName("broadcast volume <= 0 时使用默认音量")
    void broadcastZeroVolumeUsesDefault() {
        VoiceIntercomService.BroadcastResult result = service.broadcast(1, "test", 0);

        assertThat(result.getStatus()).isEqualTo("SENT");
        assertThat(result.getVolume()).isEqualTo(VoiceIntercomService.DEFAULT_VOLUME);
    }

    @Test
    @DisplayName("broadcast volume 超过最大值时截断为 MAX_VOLUME")
    void broadcastExcessiveVolumeCapped() {
        VoiceIntercomService.BroadcastResult result = service.broadcast(1, "test", 200);

        assertThat(result.getStatus()).isEqualTo("SENT");
        assertThat(result.getVolume()).isEqualTo(VoiceIntercomService.MAX_VOLUME);
    }

    @Test
    @DisplayName("broadcast 记录存入广播历史")
    void broadcastRecordedInHistory() {
        service.broadcast(1, "test message", 70);

        List<VoiceIntercomService.BroadcastRecord> history = service.getBroadcastHistory(1, 10);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getText()).isEqualTo("test message");
        assertThat(history.get(0).getVolume()).isEqualTo(70);
    }

    @Test
    @DisplayName("getBroadcastHistory 按 sysid 过滤")
    void broadcastHistoryFilteredBySysid() {
        service.broadcast(1, "msg-1", 70);
        service.broadcast(2, "msg-2", 70);
        service.broadcast(1, "msg-3", 70);

        List<VoiceIntercomService.BroadcastRecord> history = service.getBroadcastHistory(1, 10);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getText()).isEqualTo("msg-1");
        assertThat(history.get(1).getText()).isEqualTo("msg-3");
    }

    @Test
    @DisplayName("getBroadcastHistory sysid <= 0 时返回全部")
    void broadcastHistoryAllWhenSysidNonPositive() {
        service.broadcast(1, "msg-1", 70);
        service.broadcast(2, "msg-2", 70);

        List<VoiceIntercomService.BroadcastRecord> history = service.getBroadcastHistory(0, 10);
        assertThat(history).hasSize(2);
    }

    @Test
    @DisplayName("activeCount 返回活跃对讲数量")
    void activeCountReturnsCorrectNumber() {
        service.startIntercom(1);
        service.startIntercom(2);
        service.stopIntercom(1);

        assertThat(service.activeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("startIntercom 后 stopIntercom 再 startIntercom 可重新启动")
    void restartAfterStop() {
        service.startIntercom(1);
        service.stopIntercom(1);
        VoiceIntercomService.IntercomState state = service.startIntercom(1);

        assertThat(state.getStatus()).isEqualTo(VoiceIntercomService.IntercomStatus.ACTIVE);
    }
}
package io.aerofleet.cloud.mission.common;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.UdpGateway;
import io.aerofleet.cloud.telemetry.PendingAcks;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.CommandAck;
import io.aerofleet.mavlink.messages.MissionCountMsg;
import io.aerofleet.mavlink.messages.MissionItemInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CommandException / MissionUploadResult 的消息不得粘底层异常文本（源头治理回归门）。
 * <p>
 * 这些消息会经控制器错误体（DroneController / RocController 逐机 reason）或
 * IncidentDispatchService 的 suggestionReason（席位视图）回到操作端，因此
 * <b>message 只允许服务端自建短句，底层原因走 cause 链 + throw 点日志</b>。
 * 判据同 #246：用 canary 串模拟底层异常消息，断言对外消息不含该串。
 */
@DisplayName("CommandException/MissionUploadResult 消息不含底层异常细节")
class CommandExceptionMessageTest {

    /** 底层异常消息里的内部细节（host/端口/凭据名）。 */
    private static final String CANARY =
            "connect to 10.9.9.9:16666 failed (redis token redis-secret-xyz)";

    private DroneCommandService newService(PendingAcks pendings) {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(7);
        UdpGateway gateway = mock(UdpGateway.class);
        return new DroneCommandService(gateway, pendings, registry, 7);
    }

    /** 一个「以 IOException(CANARY) 为因」的 ExecutionException 完成态。 */
    private static <T> CompletableFuture<T> failedWithInternalCause() {
        CompletableFuture<T> f = new CompletableFuture<>();
        f.completeExceptionally(new ExecutionException(new IOException(CANARY)));
        return f;
    }

    // ------------------------------------------------------------------
    // command()：等 ACK 阶段 ExecutionException
    // ------------------------------------------------------------------

    @Test
    @DisplayName("command 等 ack 失败 → 消息只含命令与失败，不含底层细节；cause 保留")
    void commandAckFailureKeepsMessageAuthoredAndCause() {
        PendingAcks pendings = mock(PendingAcks.class);
        when(pendings.expectCommandAck(anyInt(), anyInt())).thenReturn(failedWithInternalCause());
        DroneCommandService service = newService(pendings);

        assertThatThrownBy(() -> service.command(7, MavEnums.MAV_CMD_NAV_WAYPOINT,
                0, 0, 0, 0, 0, 0, 0))
                .isInstanceOf(DroneCommandService.CommandException.class)
                .hasMessageContaining("command " + MavEnums.MAV_CMD_NAV_WAYPOINT)
                .hasMessageContaining("failed")
                .hasMessageNotContaining(CANARY)
                .hasMessageNotContaining("10.9.9.9")
                .hasCauseInstanceOf(ExecutionException.class);
    }

    // ------------------------------------------------------------------
    // uploadMission()：等 MISSION_REQUEST 阶段 ExecutionException
    // ------------------------------------------------------------------

    @Test
    @DisplayName("uploadMission 等请求失败 → failure 消息不含底层细节")
    void uploadRequestFailureKeepsMessageAuthored() {
        PendingAcks pendings = mock(PendingAcks.class);
        when(pendings.expectMissionRequest(anyInt())).thenReturn(failedWithInternalCause());
        DroneCommandService service = newService(pendings);

        MissionUploadResult r = service.uploadMission(7, List.of(new MissionItemInt(
                7, 0, 0, MavEnums.MAV_FRAME_MISSION, MavEnums.MAV_CMD_NAV_WAYPOINT,
                0, 1, 0, 0, 0, 0, 0, 0, 0f, MavEnums.MAV_MISSION_TYPE_MISSION)));

        assertThat(r.status()).isEqualTo("error");
        assertThat(r.error()).contains("mission request failed");
        assertThat(r.error()).doesNotContain(CANARY);
        assertThat(r.error()).doesNotContain("10.9.9.9");
    }

    // ------------------------------------------------------------------
    // downloadMission()：等 MISSION_COUNT 阶段 ExecutionException
    // ------------------------------------------------------------------

    @Test
    @DisplayName("downloadMission 等 count 失败 → 消息不含底层细节；cause 保留")
    void downloadCountFailureKeepsMessageAuthoredAndCause() {
        PendingAcks pendings = mock(PendingAcks.class);
        when(pendings.expectMissionCount(anyInt())).thenReturn(failedWithInternalCause());
        DroneCommandService service = newService(pendings);

        assertThatThrownBy(() -> service.downloadMission(7))
                .isInstanceOf(DroneCommandService.CommandException.class)
                .hasMessage("mission count failed")
                .hasMessageNotContaining(CANARY)
                .hasCauseInstanceOf(ExecutionException.class);
    }

    // ------------------------------------------------------------------
    // 契约自证：cause 链可恢复底层细节（服务端定位不留死角）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CommandException cause 链可追到原始 IOException（定位不丢）")
    void causeChainReachesRootIOException() {
        IOException root = new IOException(CANARY);
        DroneCommandService.CommandException e =
                new DroneCommandService.CommandException("command 22 failed", -1, root);

        assertThat(e).hasMessage("command 22 failed");
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        assertThat(t).isSameAs(root);
    }
}

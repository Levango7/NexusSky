package io.aerofleet.cloud.mission.common;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.UdpGateway;
import io.aerofleet.cloud.telemetry.PendingAcks;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.CommandAck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * #149 幂等契约矩阵 · 命令投递（下行指令）重复场景。
 *
 * <p>锁定 {@link DroneCommandService} 的重复投递语义：
 * <ul>
 *   <li><b>重复提交同一命令 = 每次提交各发一条线上命令</b>：投递路径没有
 *       客户端去重键 / 请求 ID（全仓无 Idempotency-Key），也不按 MAV_CMD
 *       内容去重。REST 重试因此会真实地重复下发。</li>
 *   <li><b>单次提交的超时重试 = 恰好 2 次线上发送</b>
 *       （{@code ACK_RETRIES=2}：首次 + 一次重试），随后抛
 *       {@link DroneCommandService.CommandException}。</li>
 * </ul>
 *
 * <p><b>待裁决（OPEN RULING，owner）</b>：命令投递当前是
 * <b>at-least-once 且无幂等键</b>。对 ARM/TAKEOFF 等“效果幂等”的命令这通常
 * 可接受，但对一次性动作（任务启动、返航、相机触发）重复下发可能被执行多次。
 * 是否引入客户端去重键（如请求 ID + 服务端短期窗口）或改为
 * exactly-once 语义，属 owner 裁决。本矩阵钉住当前行为，使任何未来引入
 * 去重键的改动必须是显式的契约变更（改这里 + 改文档），而非静默漂移。
 */
@DisplayName("幂等契约矩阵：命令投递重复场景")
class IdempotencyCommandDeliveryContractTest {

    private DeviceRegistry registry;
    private PendingAcks pendings;
    private UdpGateway gateway;
    private DroneCommandService service;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        pendings = new PendingAcks();
        gateway = mock(UdpGateway.class);
        service = new DroneCommandService(gateway, pendings, registry, 1);
    }

    @Test
    @DisplayName("重复提交同一命令：每次提交各发一条线上命令（无内容去重）")
    void repeatCommandSubmissionSendsOneWireCommandPerSubmission() throws Exception {
        completeAcksWithAccepted();

        int first = service.rtl(1);
        int second = service.rtl(1);

        assertThat(first).isEqualTo(MavEnums.MAV_RESULT_ACCEPTED);
        assertThat(second).isEqualTo(MavEnums.MAV_RESULT_ACCEPTED);
        verify(gateway, times(2)).send(anyInt(), any(MavlinkFrame.class));
    }

    @Test
    @DisplayName("单次提交超时：恰好重试一次（共 2 次发送）后失败")
    void commandTimeoutRetriesExactlyOnceThenFails() throws Exception {
        // 不提供 COMMAND_ACK：两次发送都超时
        assertThatThrownBy(() -> service.rtl(1))
                .isInstanceOf(DroneCommandService.CommandException.class);

        verify(gateway, times(2)).send(anyInt(), any(MavlinkFrame.class));
    }

    /** 网关发送时同步回一条 ACCEPTED 的 COMMAND_ACK，让 future 立即完成。 */
    private void completeAcksWithAccepted() throws Exception {
        doAnswer(inv -> {
            int target = inv.getArgument(0);
            pendings.offer(CommandAck.ID,
                    new CommandAck(io.aerofleet.mavlink.enums.MavEnums.MAV_CMD_NAV_RETURN_TO_LAUNCH,
                            MavEnums.MAV_RESULT_ACCEPTED, 0, 0, target, 1),
                    target);
            return null;
        }).when(gateway).send(anyInt(), any(MavlinkFrame.class));
    }
}

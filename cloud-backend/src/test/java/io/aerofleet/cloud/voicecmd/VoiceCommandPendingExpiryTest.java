package io.aerofleet.cloud.voicecmd;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.mavlink.enums.MavEnums;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VoiceCommandExecutor} 待确认队列的**存活期**与**上限**回归测试。
 *
 * <h2>被钉住的缺陷（安全，不只是内存）</h2>
 *
 * 高优先级语音指令走「先入队、确认后下发」：{@code pendingCommands.put(...)} 只在
 * {@code confirm()} 里被移除，**全仓没有超时**。后果有两条：
 *
 * <ol>
 *   <li>没人确认的条目永远留在内存里（只增不减）；</li>
 *   <li>更严重：一条「起飞」在未确认状态挂几小时后，任何一次同 ID 的确认都会把它
 *       真的下发——迟到的确认变成了意外动作。</li>
 * </ol>
 *
 * <h2>本类证明的不变式</h2>
 *
 * 待确认指令有明确存活期（{@value VoiceCommandExecutor#PENDING_TTL_MS} ms）：过期后
 * 确认必须被拒（且不得下发），队列在无人确认时也会被清理，并有容量上限兜底。
 *
 * <p>时钟通过包内构造器注入，过期判定不依赖 sleep，用例确定性。
 */
@DisplayName("VoiceCommandExecutor 待确认指令：过期即作废 + 队列有界")
class VoiceCommandPendingExpiryTest {

    private static final long T0 = 1_700_000_000_000L;

    private final Clock clock = mock(Clock.class);
    private final DeviceRegistry registry = new DeviceRegistry();
    private final DroneCommandService commands = mock(DroneCommandService.class);

    private VoiceCommandExecutor newExecutor() {
        registry.registerIfAbsent(1);
        when(commands.takeoff(anyInt(), anyDouble())).thenReturn(MavEnums.MAV_RESULT_ACCEPTED);
        return new VoiceCommandExecutor(registry, commands, clock);
    }

    private ParsedCommand highPriorityTakeoff() {
        ParsedCommand cmd = new ParsedCommand("紧急起飞");
        cmd.setAction(ParsedCommand.Action.TAKEOFF);
        cmd.setSysid(1);
        cmd.setPriority(ParsedCommand.Priority.HIGH);
        return cmd;
    }

    @Test
    @DisplayName("过期后确认必须被拒，且绝不能下发到飞控")
    void expiredPendingIsRejectedAndNeverDispatched() {
        when(clock.millis()).thenReturn(T0);
        VoiceCommandExecutor executor = newExecutor();

        ExecutionResult pending = executor.execute(highPriorityTakeoff());
        assertThat(pending.getStatus()).isEqualTo(ExecutionResult.Status.PENDING_CONFIRMATION);

        // 超过存活期后再确认
        when(clock.millis()).thenReturn(T0 + VoiceCommandExecutor.PENDING_TTL_MS + 1);
        ExecutionResult confirmed = executor.confirm(pending.getCommandId());

        assertThat(confirmed.getStatus())
                .as("过期确认只能是 FAILED")
                .isEqualTo(ExecutionResult.Status.FAILED);
        assertThat(confirmed.getMessage())
                .as("要说清是过期，而不是让操作员去查 ID")
                .contains("过期");
        verify(commands, never()).takeoff(anyInt(), anyDouble());
        assertThat(executor.pendingSize()).as("过期条目应已被移除").isZero();
    }

    @Test
    @DisplayName("存活期内确认照常下发（不得误伤正常流程）")
    void freshPendingStillDispatches() {
        when(clock.millis()).thenReturn(T0);
        VoiceCommandExecutor executor = newExecutor();

        ExecutionResult pending = executor.execute(highPriorityTakeoff());
        when(clock.millis()).thenReturn(T0 + VoiceCommandExecutor.PENDING_TTL_MS - 1);
        ExecutionResult confirmed = executor.confirm(pending.getCommandId());

        assertThat(confirmed.getStatus()).isEqualTo(ExecutionResult.Status.EXECUTED);
        verify(commands, times(1)).takeoff(anyInt(), anyDouble());
    }

    @Test
    @DisplayName("无人确认时，后续指令会把过期条目清掉（队列不会被挂死条目占满）")
    void expiredPendingsAreSweptOnNextCommand() {
        when(clock.millis()).thenReturn(T0);
        VoiceCommandExecutor executor = newExecutor();

        List<String> oldIds = List.of(
                executor.execute(highPriorityTakeoff()).getCommandId(),
                executor.execute(highPriorityTakeoff()).getCommandId(),
                executor.execute(highPriorityTakeoff()).getCommandId());
        assertThat(executor.pendingSize()).isEqualTo(3);

        when(clock.millis()).thenReturn(T0 + VoiceCommandExecutor.PENDING_TTL_MS + 1);
        executor.execute(highPriorityTakeoff());

        assertThat(executor.pendingSize())
                .as("只应剩刚入队的那一条，旧的三条已过期被清")
                .isEqualTo(1);
        assertThat(executor.getPending()).hasSize(1);
        for (String id : oldIds) {
            assertThat(executor.hasPending(id))
                    .as("过期条目不得被 hasPending 认作可用")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("短时间刷入超过上限时淘汰最旧，队列大小不越界")
    void pendingQueueIsCapped() {
        VoiceCommandExecutor executor = newExecutor();

        int total = VoiceCommandExecutor.MAX_PENDING + 5;
        for (int i = 0; i < total; i++) {
            when(clock.millis()).thenReturn(T0 + i);   // 全部在 TTL 内，只能靠上限收敛
            executor.execute(highPriorityTakeoff());
            assertThat(executor.pendingSize()).isLessThanOrEqualTo(VoiceCommandExecutor.MAX_PENDING);
        }

        assertThat(executor.pendingSize()).isEqualTo(VoiceCommandExecutor.MAX_PENDING);
    }
}

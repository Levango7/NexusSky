package io.aerofleet.cloud.voicecmd;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link VoiceCommandExecutor#getHistory()} 的**有界性**回归测试。
 *
 * <h2>被钉住的缺陷</h2>
 *
 * {@code history} 是 {@code ConcurrentHashMap}，每条指令 {@code put} 一次，全仓没有
 * 任何淘汰逻辑。语音指令虽是低频人工操作，但历史会在进程生命周期内单调增长；一条指令
 * 携带的 {@code ExecutionResult} 还包含动作描述与拒绝原因，长期运行的后端只增不减。
 *
 * <h2>本类证明的不变式</h2>
 *
 * 历史最多保留最新 {@link VoiceCommandExecutor#MAX_HISTORY} 条；最旧的先淘汰，
 * 最近一次指令必在。{@code commandId} 是随机 UUID，不能按 key 排序，因此淘汰顺序
 * 必须由独立的插入序队列保证——这正是本用例能抓到「用 key 排序淘汰」这种错解的原因。
 */
@DisplayName("VoiceCommandExecutor 指令历史有界")
class VoiceCommandHistoryBoundTest {

    private final DeviceRegistry registry = mock(DeviceRegistry.class);
    private final DroneCommandService commands = mock(DroneCommandService.class);
    private final VoiceCommandExecutor executor = new VoiceCommandExecutor(registry, commands);

    /** registry.get() 默认返回 null ⇒ 走「无人机未注册」的 REJECTED 快路径，不触发真实下发。 */
    private ParsedCommand command() {
        ParsedCommand cmd = new ParsedCommand("测试指令");
        cmd.setAction(ParsedCommand.Action.TAKEOFF);
        cmd.setSysid(1);
        return cmd;
    }

    @Test
    @DisplayName("超过上限后只保留最新 N 条：最旧的被淘汰，最新的仍在")
    void historyKeepsOnlyNewestEntries() {
        int extra = 5;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < VoiceCommandExecutor.MAX_HISTORY + extra; i++) {
            ids.add(executor.execute(command()).getCommandId());
        }

        assertThat(executor.getHistory())
                .as("历史必须有界")
                .hasSize(VoiceCommandExecutor.MAX_HISTORY);

        // 插入序淘汰：最早的 extra 条应被挤出，最后一条必须还在。
        // 若实现按 commandId（随机 UUID）排序淘汰，这两条断言都会红。
        assertThat(executor.getHistory())
                .as("最早的条目应被淘汰")
                .doesNotContainKeys(ids.subList(0, extra).toArray(new String[0]));
        assertThat(executor.getHistory())
                .as("最新一条必须保留")
                .containsKey(ids.get(ids.size() - 1));
    }

    @Test
    @DisplayName("同一 commandId 的二次写入（确认流程）不占两个淘汰位")
    void confirmDoesNotDoubleCountHistorySlot() {
        // 待确认路径要求无人机存在，用一个最小可用的 registry stub。
        io.aerofleet.cloud.gateway.DroneSnapshot drone =
                new io.aerofleet.cloud.gateway.DroneSnapshot(1);
        org.mockito.Mockito.when(registry.get(1)).thenReturn(drone);

        ParsedCommand cmd = command();
        cmd.setPriority(ParsedCommand.Priority.HIGH);

        ExecutionResult pending = executor.execute(cmd);
        assertThat(pending.getStatus()).isEqualTo(ExecutionResult.Status.PENDING_CONFIRMATION);
        assertThat(executor.getHistory()).hasSize(1);

        // 确认会以同一 pendingId 再写一次结果：是覆盖，不是新增。
        executor.confirm(pending.getCommandId());

        assertThat(executor.getHistory())
                .as("同一 commandId 只应占一个条目")
                .hasSize(1);
        assertThat(executor.getHistory().get(pending.getCommandId()))
                .as("二次写入应覆盖为最终结果")
                .isNotSameAs(pending);
    }
}

package io.aerofleet.cloud.commadapt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FailoverManager#getFailoverHistory(int)} 的**有界性**回归测试。
 *
 * <h2>被钉住的缺陷</h2>
 *
 * {@code failoverHistory} 是 {@code sysid → CopyOnWriteArrayList}，每次切换
 * {@code add} 一条，从不裁剪。一条长期在线但链路抖动的无人机，其切换记录会随运行
 * 时长无限增长——#220 的 DB 留存 job 管的是已落库的行，管不到这张进程内列表。
 *
 * <h2>本类证明的不变式</h2>
 *
 * 每架无人机最多保留最新 {@link FailoverManager#MAX_HISTORY_PER_DRONE} 条；
 * 最旧的先淘汰，最近的必在，返回顺序仍是时间升序（旧→新）。
 */
@DisplayName("FailoverManager 故障切换历史有界")
class FailoverHistoryBoundTest {

    private FailoverManager failoverManager;

    @BeforeEach
    void setUp() {
        LinkQualityMonitor monitor = new LinkQualityMonitor();
        CommAdaptConfig config = new CommAdaptConfig();
        AdaptiveRouter router = new AdaptiveRouter(monitor, config);
        failoverManager = new FailoverManager(monitor, router, config);
    }

    @Test
    @DisplayName("超过上限后每 sysid 只保留最新 N 条，最旧的被淘汰")
    void historyKeepsOnlyNewestRecords() {
        int sysid = 7;

        // 先打 5 次，记下最早一批 recordId（之后应被挤出）。
        for (int i = 0; i < 5; i++) {
            failoverManager.executeFailover(sysid, LinkQuality.LinkType.MESH);
        }
        Set<String> earliest = failoverManager.getFailoverHistory(sysid).stream()
                .map(FailoverRecord::getId)
                .collect(Collectors.toSet());
        assertThat(earliest).hasSize(5);

        // 再打满上限 + 余量。
        for (int i = 0; i < FailoverManager.MAX_HISTORY_PER_DRONE; i++) {
            failoverManager.executeFailover(sysid, LinkQuality.LinkType.MESH);
        }
        List<FailoverRecord> history = failoverManager.getFailoverHistory(sysid);

        assertThat(history)
                .as("每 sysid 的历史必须有界")
                .hasSize(FailoverManager.MAX_HISTORY_PER_DRONE);
        assertThat(history)
                .as("最早的记录应被淘汰")
                .noneMatch(r -> earliest.contains(r.getId()));

        // 返回顺序仍是时间升序：最后一条的 completeTime 不小于第一条。
        assertThat(history.get(history.size() - 1).getCompleteTime())
                .as("历史应按时间升序返回")
                .isGreaterThanOrEqualTo(history.get(0).getCompleteTime());
    }

    @Test
    @DisplayName("不同 sysid 的上限互不影响")
    void capIsPerDrone() {
        for (int i = 0; i < FailoverManager.MAX_HISTORY_PER_DRONE + 10; i++) {
            failoverManager.executeFailover(3, LinkQuality.LinkType.MESH);
        }
        failoverManager.executeFailover(4, LinkQuality.LinkType.MESH);

        assertThat(failoverManager.getFailoverHistory(3)).hasSize(FailoverManager.MAX_HISTORY_PER_DRONE);
        assertThat(failoverManager.getFailoverHistory(4)).hasSize(1);
    }
}

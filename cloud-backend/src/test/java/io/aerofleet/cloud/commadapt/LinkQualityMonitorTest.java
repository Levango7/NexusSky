package io.aerofleet.cloud.commadapt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LinkQualityMonitor 单元测试。
 * <p>
 * 验证通信链路质量监控服务的核心功能：
 * 采集质量数据、综合评分计算、等级判定、机队总览等。
 */
class LinkQualityMonitorTest {

    private LinkQualityMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new LinkQualityMonitor();
    }

    @Test
    @DisplayName("collectQuality 应返回三种链路的质量数据")
    void collectQuality_shouldReturnThreeLinkTypes() {
        int sysid = 1;
        List<LinkQuality> qualities = monitor.collectQuality(sysid);

        assertEquals(3, qualities.size());
        // 验证包含三种链路类型
        boolean hasMesh = qualities.stream().anyMatch(q -> q.getLinkType() == LinkQuality.LinkType.MESH);
        boolean hasSatellite = qualities.stream().anyMatch(q -> q.getLinkType() == LinkQuality.LinkType.SATELLITE);
        boolean hasCellular = qualities.stream().anyMatch(q -> q.getLinkType() == LinkQuality.LinkType.CELLULAR);
        assertTrue(hasMesh, "应包含 MESH 链路");
        assertTrue(hasSatellite, "应包含 SATELLITE 链路");
        assertTrue(hasCellular, "应包含 CELLULAR 链路");
    }

    @Test
    @DisplayName("collectQuality 应设置正确的 sysid 和 timestamp")
    void collectQuality_shouldSetSysidAndTimestamp() {
        int sysid = 42;
        long before = System.currentTimeMillis();
        List<LinkQuality> qualities = monitor.collectQuality(sysid);
        long after = System.currentTimeMillis();

        for (LinkQuality lq : qualities) {
            assertEquals(sysid, lq.getSysid());
            assertTrue(lq.getTimestamp() >= before && lq.getTimestamp() <= after,
                    "timestamp 应在采集时间范围内");
        }
    }

    @Test
    @DisplayName("collectQuality 应生成合理的质量指标范围")
    void collectQuality_shouldGenerateReasonableRanges() {
        List<LinkQuality> qualities = monitor.collectQuality(1);

        for (LinkQuality lq : qualities) {
            assertTrue(lq.getLatencyMs() > 0, "延迟应为正数");
            assertTrue(lq.getBandwidthKbps() > 0, "带宽应为正数");
            assertTrue(lq.getPacketLossPct() >= 0, "丢包率应 >= 0");
            assertTrue(lq.getJitterMs() > 0, "抖动应为正数");
        }

        // MESH 延迟应最低
        LinkQuality mesh = qualities.stream()
                .filter(q -> q.getLinkType() == LinkQuality.LinkType.MESH)
                .findFirst().orElseThrow();
        LinkQuality satellite = qualities.stream()
                .filter(q -> q.getLinkType() == LinkQuality.LinkType.SATELLITE)
                .findFirst().orElseThrow();
        assertTrue(mesh.getLatencyMs() < satellite.getLatencyMs(),
                "MESH 延迟应低于 SATELLITE 延迟");
    }

    @Test
    @DisplayName("getOverallQuality 在采集后应返回非 null 评分")
    void getOverallQuality_shouldReturnScoreAfterCollect() {
        int sysid = 1;
        monitor.collectQuality(sysid);
        CommQualityScore score = monitor.getOverallQuality(sysid);

        assertNotNull(score);
        assertTrue(score.getOverallScore() >= 0 && score.getOverallScore() <= 100,
                "评分应在 0-100 范围内");
        assertNotNull(score.getBestLinkType());
        assertNotNull(score.getGrade());
        assertNotNull(score.getDetails());
        assertEquals(3, score.getDetails().size());
    }

    @Test
    @DisplayName("getOverallQuality 在未采集时应返回 null")
    void getOverallQuality_shouldReturnNullWithoutData() {
        CommQualityScore score = monitor.getOverallQuality(999);
        assertNull(score);
    }

    @Test
    @DisplayName("getFleetQuality 应返回所有已采集无人机的评分")
    void getFleetQuality_shouldReturnAllDrones() {
        monitor.collectQuality(1);
        monitor.collectQuality(2);
        monitor.collectQuality(3);

        List<CommQualityScore> fleet = monitor.getFleetQuality();
        assertEquals(3, fleet.size());
    }

    @Test
    @DisplayName("calculateLinkScore 应对低延迟高带宽链路给出高分")
    void calculateLinkScore_shouldGiveHighScoreForGoodLink() {
        LinkQuality goodLink = new LinkQuality(
                LinkQuality.LinkType.MESH, 5, 2000, -30, 0, 1, System.currentTimeMillis(), 1);
        int score = LinkQualityMonitor.calculateLinkScore(goodLink);
        assertTrue(score >= 90, "优质链路评分应 >= 90, 实际=" + score);
    }

    @Test
    @DisplayName("calculateLinkScore 应对高延迟低带宽链路给出低分")
    void calculateLinkScore_shouldGiveLowScoreForBadLink() {
        LinkQuality badLink = new LinkQuality(
                LinkQuality.LinkType.SATELLITE, 500, 10, -100, 10, 30, System.currentTimeMillis(), 1);
        int score = LinkQualityMonitor.calculateLinkScore(badLink);
        assertTrue(score < 30, "劣质链路评分应 < 30, 实际=" + score);
    }

    @Test
    @DisplayName("scoreToGrade 应正确映射评分到等级")
    void scoreToGrade_shouldMapCorrectly() {
        assertEquals(CommQualityScore.Grade.A, LinkQualityMonitor.scoreToGrade(95));
        assertEquals(CommQualityScore.Grade.A, LinkQualityMonitor.scoreToGrade(90));
        assertEquals(CommQualityScore.Grade.B, LinkQualityMonitor.scoreToGrade(85));
        assertEquals(CommQualityScore.Grade.B, LinkQualityMonitor.scoreToGrade(80));
        assertEquals(CommQualityScore.Grade.C, LinkQualityMonitor.scoreToGrade(75));
        assertEquals(CommQualityScore.Grade.C, LinkQualityMonitor.scoreToGrade(70));
        assertEquals(CommQualityScore.Grade.D, LinkQualityMonitor.scoreToGrade(65));
        assertEquals(CommQualityScore.Grade.D, LinkQualityMonitor.scoreToGrade(60));
        assertEquals(CommQualityScore.Grade.F, LinkQualityMonitor.scoreToGrade(59));
        assertEquals(CommQualityScore.Grade.F, LinkQualityMonitor.scoreToGrade(0));
    }

    @Test
    @DisplayName("getLatestData 应返回各链路的最新质量数据")
    void getLatestData_shouldReturnLatestData() {
        int sysid = 1;
        monitor.collectQuality(sysid);
        Map<LinkQuality.LinkType, LinkQuality> data = monitor.getLatestData(sysid);

        assertEquals(3, data.size());
        assertNotNull(data.get(LinkQuality.LinkType.MESH));
        assertNotNull(data.get(LinkQuality.LinkType.SATELLITE));
        assertNotNull(data.get(LinkQuality.LinkType.CELLULAR));
    }

    @Test
    @DisplayName("getLatestData 在未采集时应返回空映射")
    void getLatestData_shouldReturnEmptyMapWithoutData() {
        Map<LinkQuality.LinkType, LinkQuality> data = monitor.getLatestData(999);
        assertTrue(data.isEmpty());
    }

    @Test
    @DisplayName("removeDrone 应移除指定无人机的数据")
    void removeDrone_shouldRemoveData() {
        int sysid = 1;
        monitor.collectQuality(sysid);
        assertNotNull(monitor.getOverallQuality(sysid));

        monitor.removeDrone(sysid);
        assertNull(monitor.getOverallQuality(sysid));
    }
}
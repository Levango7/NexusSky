package io.aerofleet.cloud.api.service;

import io.aerofleet.mavlink.messages.FlightRestrictionMsg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TerrainMapService} 限制区列表的**有界性**回归测试。
 *
 * <h2>被钉住的缺陷</h2>
 *
 * 一条 30001 消息只描述**一个**限制区，同一节点的多个区本来就靠多条消息累积
 * （所以不能像 {@code maps} 那样按 sysid 覆盖，那会把同节点的其它区丢掉）。
 * 会无限增长的是另一种情况：节点**周期性重报同一批区**，旧实现每次都原样再追加一遍，
 * {@code getRestrictions()} 也跟着越返回越多。
 *
 * <h2>本类证明的不变式</h2>
 *
 * 内容相同的区不会重复入列；不同的区照常累加；单节点条数不越界（真正的上限兜底）。
 */
@DisplayName("TerrainMapService 限制区列表有界")
class TerrainRestrictionsBoundTest {

    private TerrainMapService service;

    @BeforeEach
    void setUp() {
        service = new TerrainMapService();
    }

    /** 构造一个限制区消息（type/limit/顶点可指定，用于构造「不同区」）。 */
    private FlightRestrictionMsg restriction(int type, double limit, int latE7Offset) {
        List<FlightRestrictionMsg.GeoPoint> area = List.of(
                new FlightRestrictionMsg.GeoPoint(300000000, 1200000000),
                new FlightRestrictionMsg.GeoPoint(300010000 + latE7Offset, 1200000000),
                new FlightRestrictionMsg.GeoPoint(300005000, 120010000));
        return new FlightRestrictionMsg(type, (float) limit, area);
    }

    private FlightRestrictionMsg defaultRestriction() {
        return restriction(0, 0f, 0);
    }

    @Test
    @DisplayName("节点周期性重报同一批区：内容相同不重复入列（这是无限增长的真正来源）")
    void duplicateReportsDoNotGrowTheList() {
        for (int i = 0; i < 50; i++) {
            service.onFlightRestriction(1, defaultRestriction());
        }
        assertThat(service.getRestrictions())
                .as("同一批区重报 50 次不该变成 50 条")
                .hasSize(1);
    }

    @Test
    @DisplayName("不同的限制区照常累加（一个消息一个区，同节点多区本就靠多条累积）")
    void distinctZonesStillAccumulate() {
        service.onFlightRestriction(1, restriction(0, 0f, 0));
        service.onFlightRestriction(1, restriction(1, 30f, 100));
        service.onFlightRestriction(1, restriction(2, 120f, 200));

        assertThat(service.getRestrictions())
                .as("不同的区必须都留着")
                .hasSize(3);
    }

    @Test
    @DisplayName("同一区重报但内容有变（限值不同）：视为新区，替换入列")
    void sameAreaDifferentLimitIsADifferentZone() {
        service.onFlightRestriction(1, restriction(0, 0f, 0));
        service.onFlightRestriction(1, restriction(0, 50f, 0));

        assertThat(service.getRestrictions())
                .as("限值变了是不同内容，应记为新的一条")
                .hasSize(2);
    }

    @Test
    @DisplayName("单节点上限兜底：即便内容全都不同也不越界，且最旧的先淘汰")
    void perNodeCapHoldsEvenForDistinctZones() {
        int total = TerrainMapService.MAX_RESTRICTIONS_PER_NODE + 5;
        for (int i = 0; i < total; i++) {
            service.onFlightRestriction(1, restriction(0, 100f, i));
        }
        assertThat(service.getRestrictions())
                .as("单节点条数必须有界")
                .hasSize(TerrainMapService.MAX_RESTRICTIONS_PER_NODE);

        // 最新一条在（顶点偏移最大）
        double maxOffset = -1;
        for (TerrainMapService.FlightRestrictionSnapshot r : service.getRestrictions()) {
            maxOffset = Math.max(maxOffset, r.areaPolygon().get(1)[0]);
        }
        assertThat(maxOffset).isGreaterThan(0);
    }

    @Test
    @DisplayName("多节点互不影响：一个节点满了不挤掉另一个节点的区")
    void capIsPerNode() {
        for (int i = 0; i < TerrainMapService.MAX_RESTRICTIONS_PER_NODE + 5; i++) {
            service.onFlightRestriction(1, restriction(0, 100f, i));
        }
        service.onFlightRestriction(2, defaultRestriction());

        List<TerrainMapService.FlightRestrictionSnapshot> all = service.getRestrictions();
        assertThat(all)
                .as("总条数 = 节点1上限 + 节点2一条")
                .hasSize(TerrainMapService.MAX_RESTRICTIONS_PER_NODE + 1);
        assertThat(all)
                .as("节点2 的区必须还在")
                .anyMatch(r -> r.areaPolygon().get(1)[0] == 300010000d / 1e7);
    }
}

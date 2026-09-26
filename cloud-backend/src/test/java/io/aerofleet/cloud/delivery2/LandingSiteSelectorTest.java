package io.aerofleet.cloud.delivery2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LandingSiteSelector} 单测。
 * <p>
 * 测试降落点选择、验证和搜索。
 */
@DisplayName("LandingSiteSelector 降落点选择 (P4-1)")
class LandingSiteSelectorTest {

    private LandingSiteSelector selector;

    @BeforeEach
    void setUp() {
        selector = new LandingSiteSelector();
    }

    // ------------------------------------------------------------------
    // 降落点选择
    // ------------------------------------------------------------------

    @Test
    @DisplayName("selectLandingSite 返回非 null 结果")
    void selectLandingSite_returnsNotNull() {
        LandingSite site = selector.selectLandingSite(39.9050, 116.4070);

        assertThat(site).isNotNull();
        assertThat(site.getAccessibility()).isNotEqualTo(LandingSite.Accessibility.NO_ACCESS);
    }

    @Test
    @DisplayName("selectLandingSite 不选择 NO_ACCESS 的地点")
    void selectLandingSite_excludesNoAccess() {
        LandingSite site = selector.selectLandingSite(39.9200, 116.4200);

        // LS-006 是 WATER + NO_ACCESS，不应被选中
        assertThat(site).isNotNull();
        assertThat(site.getAccessibility()).isNotEqualTo(LandingSite.Accessibility.NO_ACCESS);
    }

    @Test
    @DisplayName("selectLandingSite 优先选择平坦地点")
    void selectLandingSite_prefersFlat() {
        LandingSite site = selector.selectLandingSite(39.9050, 116.4070);

        assertThat(site).isNotNull();
        // FLAT 或 CONCRETE 应优先于 GRASS/ROOF/WATER
        assertThat(site.getSurfaceType()).isIn(
                LandingSite.SurfaceType.FLAT, LandingSite.SurfaceType.CONCRETE);
    }

    @Test
    @DisplayName("selectLandingSite 选择距离目标最近的可用地点")
    void selectLandingSite_nearestAvailable() {
        // 目标在 LS-001 附近
        LandingSite site = selector.selectLandingSite(39.9051, 116.4071);

        assertThat(site).isNotNull();
        // LS-001 是 FLAT + OPEN + 距离最近
        assertThat(site.getId()).isEqualTo("LS-001");
    }

    // ------------------------------------------------------------------
    // 降落点验证
    // ------------------------------------------------------------------

    @Test
    @DisplayName("verifyLandingSite 合格地点验证通过")
    void verifyLandingSite_valid() {
        LandingSite site = new LandingSite("test-1", 39.90, 116.40, 50,
                LandingSite.SurfaceType.FLAT, 10, 0,
                LandingSite.Accessibility.OPEN, false);

        boolean result = selector.verifyLandingSite(site);

        assertThat(result).isTrue();
        assertThat(site.isVerified()).isTrue();
    }

    @Test
    @DisplayName("verifyLandingSite NO_ACCESS 验证失败")
    void verifyLandingSite_noAccess_fails() {
        LandingSite site = new LandingSite("test-2", 39.90, 116.40, 50,
                LandingSite.SurfaceType.FLAT, 10, 0,
                LandingSite.Accessibility.NO_ACCESS, false);

        boolean result = selector.verifyLandingSite(site);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("verifyLandingSite 坡度过大验证失败")
    void verifyLandingSite_steepSlope_fails() {
        LandingSite site = new LandingSite("test-3", 39.90, 116.40, 50,
                LandingSite.SurfaceType.FLAT, 10, 15,
                LandingSite.Accessibility.OPEN, false);

        boolean result = selector.verifyLandingSite(site);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("verifyLandingSite 净空不足验证失败")
    void verifyLandingSite_lowClearance_fails() {
        LandingSite site = new LandingSite("test-4", 39.90, 116.40, 50,
                LandingSite.SurfaceType.FLAT, 1, 0,
                LandingSite.Accessibility.OPEN, false);

        boolean result = selector.verifyLandingSite(site);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("verifyLandingSite 半径过小验证失败")
    void verifyLandingSite_smallRadius_fails() {
        LandingSite site = new LandingSite("test-5", 39.90, 116.40, 5,
                LandingSite.SurfaceType.FLAT, 10, 0,
                LandingSite.Accessibility.OPEN, false);

        boolean result = selector.verifyLandingSite(site);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("verifyLandingSite null 返回 false")
    void verifyLandingSite_null() {
        boolean result = selector.verifyLandingSite(null);

        assertThat(result).isFalse();
    }

    // ------------------------------------------------------------------
    // 降落点搜索
    // ------------------------------------------------------------------

    @Test
    @DisplayName("searchLandingSites 返回范围内的降落点")
    void searchLandingSites_returnsInRange() {
        List<LandingSite> sites = selector.searchLandingSites(39.9050, 116.4070, 5.0);

        assertThat(sites).isNotEmpty();
        // 所有返回的地点都应在 5km 范围内
        for (LandingSite site : sites) {
            double dist = haversineKm(39.9050, 116.4070, site.getLat(), site.getLon());
            assertThat(dist).isLessThanOrEqualTo(5.0);
        }
    }

    @Test
    @DisplayName("searchLandingSites 结果按距离排序")
    void searchLandingSites_sortedByDistance() {
        List<LandingSite> sites = selector.searchLandingSites(39.9050, 116.4070, 10.0);

        assertThat(sites).isNotEmpty();
        for (int i = 1; i < sites.size(); i++) {
            double distPrev = haversineKm(39.9050, 116.4070,
                    sites.get(i - 1).getLat(), sites.get(i - 1).getLon());
            double distCurr = haversineKm(39.9050, 116.4070,
                    sites.get(i).getLat(), sites.get(i).getLon());
            assertThat(distCurr).isGreaterThanOrEqualTo(distPrev);
        }
    }

    @Test
    @DisplayName("searchLandingSites 小半径可能返回空列表")
    void searchLandingSites_smallRadius_empty() {
        // 在远离所有候选点的位置搜索
        List<LandingSite> sites = selector.searchLandingSites(0.0, 0.0, 1.0);

        assertThat(sites).isEmpty();
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return 6371.0 * c;
    }
}
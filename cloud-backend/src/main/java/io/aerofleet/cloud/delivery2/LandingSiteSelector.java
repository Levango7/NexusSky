package io.aerofleet.cloud.delivery2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 降落点选择与验证服务。
 * <p>
 * 选择策略：优先选距离目标最近、平坦、无障碍的地点。
 * 内置候选降落点数据库（模拟），支持按目标坐标搜索最优降落点。
 */
@Service
public class LandingSiteSelector {

    private static final Logger log = LoggerFactory.getLogger(LandingSiteSelector.class);

    /** 地球半径（km）。 */
    private static final double EARTH_RADIUS_KM = 6371.0;

    /** 候选降落点列表（模拟数据库）。 */
    private final List<LandingSite> candidateSites = new ArrayList<>();

    public LandingSiteSelector() {
        initCandidateSites();
    }

    /**
     * 初始化候选降落点（模拟数据）。
     */
    private void initCandidateSites() {
        candidateSites.add(new LandingSite("LS-001", 39.9050, 116.4070, 50,
                LandingSite.SurfaceType.FLAT, 10, 0, LandingSite.Accessibility.OPEN, true));
        candidateSites.add(new LandingSite("LS-002", 39.9080, 116.4100, 30,
                LandingSite.SurfaceType.GRASS, 5, 2, LandingSite.Accessibility.OPEN, true));
        candidateSites.add(new LandingSite("LS-003", 39.9000, 116.4000, 40,
                LandingSite.SurfaceType.CONCRETE, 8, 1, LandingSite.Accessibility.OPEN, true));
        candidateSites.add(new LandingSite("LS-004", 39.9120, 116.4150, 25,
                LandingSite.SurfaceType.ROOF, 3, 5, LandingSite.Accessibility.RESTRICTED, false));
        candidateSites.add(new LandingSite("LS-005", 39.8950, 116.3950, 60,
                LandingSite.SurfaceType.FLAT, 15, 0, LandingSite.Accessibility.OPEN, true));
        candidateSites.add(new LandingSite("LS-006", 39.9200, 116.4200, 20,
                LandingSite.SurfaceType.WATER, 0, 0, LandingSite.Accessibility.NO_ACCESS, false));
    }

    /**
     * 选择最优降落点。
     * <p>
     * 选择策略：
     * <ol>
     *   <li>过滤掉 NO_ACCESS 的地点</li>
     *   <li>按距离目标点的远近排序</li>
     *   <li>优先选择平坦（FLAT/CONCRETE）、坡度小、净空高度大的地点</li>
     * </ol>
     *
     * @param targetLat 目标纬度
     * @param targetLon 目标经度
     * @return 最优降落点
     */
    public LandingSite selectLandingSite(double targetLat, double targetLon) {
        log.debug("选择降落点：目标=({}, {})", targetLat, targetLon);

        LandingSite best = candidateSites.stream()
                .filter(s -> s.getAccessibility() != LandingSite.Accessibility.NO_ACCESS)
                .min(Comparator.comparingDouble((LandingSite s) -> {
                    // 综合评分：距离权重 60% + 地面条件权重 25% + 净空权重 15%
                    double distScore = haversineKm(targetLat, targetLon, s.getLat(), s.getLon());
                    double surfaceScore = surfacePenalty(s.getSurfaceType());
                    double clearanceScore = Math.max(0, 10 - s.getClearanceM()) * 0.5;
                    double slopeScore = s.getSlopeDeg() * 0.3;
                    return distScore * 0.6 + surfaceScore * 0.25 + clearanceScore + slopeScore;
                }))
                .orElse(null);

        if (best != null) {
            log.info("选择降落点：{} 距离={}km 类型={}",
                    best.getId(),
                    String.format("%.3f", haversineKm(targetLat, targetLon, best.getLat(), best.getLon())),
                    best.getSurfaceType());
        }

        return best;
    }

    /**
     * 验证降落点是否可用。
     * <p>
     * 验证条件：
     * <ul>
     *   <li>可访问性不为 NO_ACCESS</li>
     *   <li>坡度 <= 10 度</li>
     *   <li>净空高度 >= 3m</li>
     *   <li>半径 >= 10m</li>
     * </ul>
     *
     * @param site 待验证的降落点
     * @return 是否可用
     */
    public boolean verifyLandingSite(LandingSite site) {
        if (site == null) {
            return false;
        }

        boolean valid = site.getAccessibility() != LandingSite.Accessibility.NO_ACCESS
                && site.getSlopeDeg() <= 10
                && site.getClearanceM() >= 3
                && site.getRadiusM() >= 10;

        if (valid) {
            site.setVerified(true);
            log.info("降落点验证通过：{}", site.getId());
        } else {
            log.warn("降落点验证失败：{} accessibility={} slope={} clearance={} radius={}",
                    site.getId(), site.getAccessibility(), site.getSlopeDeg(),
                    site.getClearanceM(), site.getRadiusM());
        }

        return valid;
    }

    /**
     * 搜索指定范围内的降落点。
     *
     * @param lat    中心纬度
     * @param lon    中心经度
     * @param radius 搜索半径（km）
     * @return 范围内的降落点列表
     */
    public List<LandingSite> searchLandingSites(double lat, double lon, double radius) {
        List<LandingSite> result = new ArrayList<>();
        for (LandingSite site : candidateSites) {
            double dist = haversineKm(lat, lon, site.getLat(), site.getLon());
            if (dist <= radius) {
                result.add(site);
            }
        }
        result.sort(Comparator.comparingDouble(s ->
                haversineKm(lat, lon, s.getLat(), s.getLon())));
        return result;
    }

    /** 地面类型惩罚分（越小越好）。 */
    private double surfacePenalty(LandingSite.SurfaceType type) {
        return switch (type) {
            case FLAT -> 0;
            case CONCRETE -> 0.5;
            case GRASS -> 1.0;
            case ROOF -> 3.0;
            case WATER -> 10.0;
        };
    }

    /** Haversine 距离（km）。 */
    private double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }
}
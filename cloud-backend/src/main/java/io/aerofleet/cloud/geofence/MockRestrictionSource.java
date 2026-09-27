package io.aerofleet.cloud.geofence;

import java.util.ArrayList;
import java.util.List;

/**
 * 模拟限飞区数据源：内置默认模拟数据，用于开发和测试环境。
 * <p>
 * source 字段标记为 "mock"，以区分真实数据源。
 * <p>
 * 内置模拟数据包含：
 * <ul>
 *   <li>北京首都机场圆形禁飞区（半径 5km）</li>
 *   <li>上海虹桥机场圆形禁飞区（半径 3km）</li>
 *   <li>深圳城区多边形禁飞区</li>
 * </ul>
 *
 * @see RestrictionDataSource
 */
public class MockRestrictionSource implements RestrictionDataSource {

    private static final String SOURCE_ID = "mock";

    @Override
    public String getSourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<RestrictionZone> fetch() {
        List<RestrictionZone> zones = new ArrayList<>();

        // 北京首都机场圆形禁飞区（半径 5km）
        zones.add(RestrictionZone.circleRestriction(
                "BJ-PEK-001", "北京首都机场禁飞区",
                40.0801, 116.5846, 5000,
                SOURCE_ID));

        // 上海虹桥机场圆形禁飞区（半径 3km）
        zones.add(RestrictionZone.circleRestriction(
                "SH-SHA-001", "上海虹桥机场禁飞区",
                31.1979, 121.3360, 3000,
                SOURCE_ID));

        // 深圳城区多边形禁飞区
        List<GeofenceZone.GeoPoint> szPoints = List.of(
                new GeofenceZone.GeoPoint(22.5400, 113.9200),
                new GeofenceZone.GeoPoint(22.5400, 114.0800),
                new GeofenceZone.GeoPoint(22.4800, 114.0800),
                new GeofenceZone.GeoPoint(22.4800, 113.9200)
        );
        zones.add(RestrictionZone.polygonRestriction(
                "SZ-CITY-001", "深圳城区禁飞区",
                szPoints, SOURCE_ID));

        return zones;
    }
}
package io.aerofleet.cloud.sensing;

/**
 * 非合作目标航迹（E2/E4 统一模型）——反制雷达侦测与 5G-A 通感共用。
 *
 * @param trackId        来源内唯一的航迹标识
 * @param sourceType     来源类型（COUNTER_DRONE_RADAR / FIVE_G_SENSING）
 * @param sourceId       来源设备标识（雷达站号/基站号）
 * @param lat            纬度（WGS-84）
 * @param lon            经度
 * @param altM           高度（米，可为 NaN 表示来源不提供）
 * @param speedMps       速度（m/s，可为 NaN）
 * @param headingDeg     航向（度，可为 NaN）
 * @param confidence     置信度 0-1
 * @param classification 分类：DRONE / BIRD / UNKNOWN（来源识别能力而定）
 * @param firstSeenMs    首次发现时间
 * @param lastSeenMs     最近更新时间
 */
public record SensingTrack(
        String trackId,
        String sourceType,
        String sourceId,
        double lat,
        double lon,
        double altM,
        double speedMps,
        double headingDeg,
        double confidence,
        String classification,
        long firstSeenMs,
        long lastSeenMs) {

    /** 近域告警标记（由聚合层维护，非来源提供）。 */
    public static final String TYPE_COUNTER_DRONE_RADAR = "COUNTER_DRONE_RADAR";
    public static final String TYPE_FIVE_G_SENSING = "FIVE_G_SENSING";

    public SensingTrack {
        if (trackId == null || trackId.isBlank()) {
            throw new IllegalArgumentException("trackId must not be blank");
        }
        if (sourceType == null || sourceType.isBlank()) {
            throw new IllegalArgumentException("sourceType must not be blank");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1]: " + confidence);
        }
    }

    /** 带更新的 lastSeen（聚合层用）。 */
    public SensingTrack seenAgain(long nowMs, double newLat, double newLon,
                                  double newAlt, double newSpeed, double newHeading) {
        return new SensingTrack(trackId, sourceType, sourceId, newLat, newLon,
                newAlt, newSpeed, newHeading, confidence, classification,
                firstSeenMs, nowMs);
    }
}

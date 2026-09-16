package io.aerofleet.cloud.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * M10 空域冲突避免服务。
 * 检测两机航迹冲突（3D 空间 + 时间窗口），提供冲突解决策略。
 */
@Service
public class ConflictAvoidanceService {
    private static final Logger log = LoggerFactory.getLogger(ConflictAvoidanceService.class);

    private static final double MIN_HORIZONTAL_SEP = 50.0;  // 最小水平间隔 50m
    private static final double MIN_VERTICAL_SEP = 10.0;    // 最小垂直间隔 10m
    private static final double TIME_WINDOW_SEC = 30.0;     // 时间窗口 30s

    /** 检测两机航迹冲突 */
    public ConflictResult checkConflict(double lat1, double lon1, double alt1, double v1, double heading1,
                                        double lat2, double lon2, double alt2, double v2, double heading2) {
        double hDist = haversine(lat1, lon1, lat2, lon2);
        double vDist = Math.abs(alt1 - alt2);

        boolean hConflict = hDist < MIN_HORIZONTAL_SEP;
        boolean vConflict = vDist < MIN_VERTICAL_SEP;

        if (hConflict && vConflict) {
            double timeToConflict = estimateTimeToConflict(hDist, v1, v2);
            log.warn("Conflict detected: hDist={}m vDist={}m timeToConflict={}s", hDist, vDist, timeToConflict);
            return new ConflictResult(true, hDist, vDist, timeToConflict, "COLLISION");
        }
        return new ConflictResult(false, hDist, vDist, -1, "CLEAR");
    }

    /** 冲突解决：高度分层 */
    public String resolveByAltitude(int sysid1, int sysid2) {
        log.info("Resolving conflict by altitude separation: {} vs {}", sysid1, sysid2);
        return String.format("sysid %d climb +10m, sysid %d descend -10m", sysid1, sysid2);
    }

    /** 冲突解决：时间错开 */
    public String resolveByTime(int sysid1, int sysid2) {
        log.info("Resolving conflict by time offset: {} vs {}", sysid1, sysid2);
        return String.format("sysid %d delay 5s", sysid2);
    }

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        double RE = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return RE * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private double estimateTimeToConflict(double dist, double v1, double v2) {
        double closingRate = (v1 + v2) / 2;
        return closingRate > 0 ? dist / closingRate : Double.MAX_VALUE;
    }

    /** 冲突检测结果 DTO */
    public static class ConflictResult {
        public final boolean conflict;
        public final double horizontalDistance;
        public final double verticalDistance;
        public final double timeToConflict;
        public final String type;

        public ConflictResult(boolean conflict, double hDist, double vDist, double time, String type) {
            this.conflict = conflict;
            this.horizontalDistance = hDist;
            this.verticalDistance = vDist;
            this.timeToConflict = time;
            this.type = type;
        }
    }
}
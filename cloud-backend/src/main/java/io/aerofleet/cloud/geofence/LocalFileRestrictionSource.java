package io.aerofleet.cloud.geofence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地文件限飞区数据源：从本地 JSON 文件读取限飞区数据。
 * <p>
 * JSON 文件格式示例：
 * <pre>{@code
 * [
 *   {
 *     "zoneId": "BJ-PEK-001",
 *     "name": "北京首都机场禁飞区",
 *     "type": "CIRCLE",
 *     "centerLat": 40.0801,
 *     "centerLon": 116.5846,
 *     "radiusM": 5000
 *   },
 *   {
 *     "zoneId": "SZ-CITY-001",
 *     "name": "深圳城区禁飞区",
 *     "type": "POLYGON",
 *     "points": [
 *       {"lat": 22.54, "lon": 113.92},
 *       {"lat": 22.54, "lon": 114.08},
 *       {"lat": 22.48, "lon": 114.08},
 *       {"lat": 22.48, "lon": 113.92}
 *     ]
 *   }
 * ]
 * }</pre>
 * <p>
 * source 字段标记为文件路径，以区分其他数据源。
 *
 * @see RestrictionDataSource
 * @see RestrictionZone
 */
@Component
public class LocalFileRestrictionSource implements RestrictionDataSource {

    private static final Logger log = LoggerFactory.getLogger(LocalFileRestrictionSource.class);

    /** 限飞区 JSON 文件路径，默认为 classpath:restrictions.json。 */
    @Value("${aerofleet.geofence.restriction-file:restrictions.json}")
    private String filePath;

    @Override
    public String getSourceId() {
        return filePath;
    }

    @Override
    public List<RestrictionZone> fetch() throws Exception {
        Path path = resolveFilePath();
        if (!Files.exists(path)) {
            log.warn("Restriction file not found: {} — returning empty list", path);
            return new ArrayList<>();
        }

        String json = Files.readString(path);
        log.info("Loading restriction zones from file: {}", path);
        return parseJson(json);
    }

    /**
     * 解析限飞区 JSON 数据。
     * <p>
     * 手动解析 JSON（不引入 Jackson 依赖），支持 CIRCLE 和 POLYGON 两种类型。
     *
     * @param json JSON 字符串
     * @return 限飞区列表
     * @throws IOException 解析异常
     */
    private List<RestrictionZone> parseJson(String json) throws IOException {
        List<RestrictionZone> zones = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return zones;
        }

        // 手动解析 JSON 数组
        int i = 0;
        int len = json.length();
        while (i < len) {
            // 查找下一个对象开始
            int objStart = json.indexOf('{', i);
            if (objStart == -1) {
                break;
            }
            int objEnd = findMatchingBrace(json, objStart);
            if (objEnd == -1) {
                break;
            }
            String objJson = json.substring(objStart, objEnd + 1);

            try {
                RestrictionZone zone = parseZoneObject(objJson);
                if (zone != null) {
                    zones.add(zone);
                }
            } catch (Exception e) {
                log.warn("Failed to parse restriction zone object: err={}", e.getMessage());
            }

            i = objEnd + 1;
        }

        log.info("Parsed {} restriction zones from file: {}", zones.size(), filePath);
        return zones;
    }

    /**
     * 解析单个限飞区 JSON 对象。
     */
    private RestrictionZone parseZoneObject(String json) {
        String zoneId = extractStringValue(json, "zoneId");
        String name = extractStringValue(json, "name");
        String type = extractStringValue(json, "type");

        if (zoneId == null || name == null || type == null) {
            log.warn("Missing required field in restriction zone JSON: zoneId={}, name={}, type={}",
                    zoneId, name, type);
            return null;
        }

        GeofenceZone.Type typeEnum = GeofenceZone.Type.valueOf(type);

        if (typeEnum == GeofenceZone.Type.CIRCLE) {
            double centerLat = extractDoubleValue(json, "centerLat");
            double centerLon = extractDoubleValue(json, "centerLon");
            double radiusM = extractDoubleValue(json, "radiusM");
            return RestrictionZone.circleRestriction(zoneId, name, centerLat, centerLon, radiusM, filePath);
        } else {
            // POLYGON
            List<GeofenceZone.GeoPoint> points = extractPoints(json);
            return RestrictionZone.polygonRestriction(zoneId, name, points, filePath);
        }
    }

    /**
     * 从 JSON 字符串中提取字符串字段值。
     */
    private String extractStringValue(String json, String field) {
        String key = "\"" + field + "\":";
        int start = json.indexOf(key);
        if (start == -1) {
            return null;
        }
        start += key.length();
        // 跳过冒号后的空白字符（支持标准 JSON 格式 "field": "value"）
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        if (start >= json.length() || json.charAt(start) != '"') {
            return null;
        }
        start++; // 跳过开头的引号
        int end = json.indexOf('"', start);
        if (end == -1) {
            return null;
        }
        return json.substring(start, end);
    }

    /**
     * 从 JSON 字符串中提取数值字段值。
     */
    private double extractDoubleValue(String json, String field) {
        String key = "\"" + field + "\":";
        int start = json.indexOf(key);
        if (start == -1) {
            return Double.NaN;
        }
        start += key.length();
        // 跳过冒号后的空白字符（支持标准 JSON 格式 "field": 123.45）
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (Character.isDigit(c) || c == '.' || c == '-' || c == 'e' || c == 'E') {
                end++;
            } else {
                break;
            }
        }
        if (end == start) {
            return Double.NaN;
        }
        return Double.parseDouble(json.substring(start, end));
    }

    /**
     * 从 JSON 字符串中提取 points 数组。
     */
    private List<GeofenceZone.GeoPoint> extractPoints(String json) {
        List<GeofenceZone.GeoPoint> points = new ArrayList<>();
        String pointsKey = "\"points\":";
        int pointsStart = json.indexOf(pointsKey);
        if (pointsStart == -1) {
            return points;
        }
        // 找到 points 数组的开始和结束
        int arrStart = json.indexOf('[', pointsStart);
        int arrEnd = findMatchingBracket(json, arrStart);
        if (arrStart == -1 || arrEnd == -1) {
            return points;
        }
        String arrJson = json.substring(arrStart, arrEnd + 1);

        // 解析每个 {"lat":xxx,"lon":xxx} 对象
        int i = 0;
        while (i < arrJson.length()) {
            int objStart = arrJson.indexOf('{', i);
            if (objStart == -1) {
                break;
            }
            int objEnd = arrJson.indexOf('}', objStart);
            if (objEnd == -1) {
                break;
            }
            String pointJson = arrJson.substring(objStart, objEnd + 1);
            double lat = extractDoubleValue(pointJson, "lat");
            double lon = extractDoubleValue(pointJson, "lon");
            points.add(new GeofenceZone.GeoPoint(lat, lon));
            i = objEnd + 1;
        }
        return points;
    }

    /**
     * 找到与开括号匹配的闭括号位置。
     */
    private static int findMatchingBrace(String s, int openPos) {
        int depth = 0;
        for (int i = openPos; i < s.length(); i++) {
            if (s.charAt(i) == '{') depth++;
            else if (s.charAt(i) == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * 找到与开方括号匹配的闭方括号位置。
     */
    private static int findMatchingBracket(String s, int openPos) {
        int depth = 0;
        for (int i = openPos; i < s.length(); i++) {
            if (s.charAt(i) == '[') depth++;
            else if (s.charAt(i) == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * 解析文件路径：支持 classpath: 前缀和绝对/相对路径。
     */
    private Path resolveFilePath() {
        if (filePath.startsWith("classpath:")) {
            String resourcePath = filePath.substring("classpath:".length());
            // 尝试从工作目录加载
            return Path.of(resourcePath);
        }
        return Path.of(filePath);
    }
}
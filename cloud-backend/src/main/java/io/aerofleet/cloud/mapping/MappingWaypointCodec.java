package io.aerofleet.cloud.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测绘航点 JSON 编解码（V34；审计 §2.1 第 4 条）。
 *
 * <p>{@link MappingWaypoint} 是不可变类（public final 字段 + 全参构造 + 静态工厂），
 * 交给 Jackson 反射做反序列化会踩"无默认构造"的坑——这里用**显式字段映射**，
 * 序列化与反序列化在同一处，加字段时两边一起看见（同 V33 的取舍）。
 *
 * <p>解码对坏数据降级为空列表并 WARN：航点读取接口要的是"能看"，一条坏 JSON
 * 不该炸整个任务详情。
 */
final class MappingWaypointCodec {

    private static final Logger log = LoggerFactory.getLogger(MappingWaypointCodec.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MappingWaypointCodec() {
    }

    static String encode(List<MappingWaypoint> waypoints) {
        if (waypoints == null) {
            return null;
        }
        List<Map<String, Object>> list = new ArrayList<>(waypoints.size());
        for (MappingWaypoint w : waypoints) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", w.seq);
            m.put("lat", w.lat);
            m.put("lon", w.lon);
            m.put("alt", w.alt);
            m.put("headingDeg", w.headingDeg);
            m.put("cameraAngleDeg", w.cameraAngleDeg);
            m.put("action", w.action == null ? null : w.action.name());
            m.put("photoId", w.photoId);
            list.add(m);
        }
        try {
            return MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("航点序列化失败，按 null 落库：{}", e.getMessage());
            return null;
        }
    }

    static List<MappingWaypoint> decode(String json) {
        List<MappingWaypoint> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = MAPPER.readValue(json, List.class);
            for (Map<String, Object> m : list) {
                MappingWaypoint.Action action = null;
                Object raw = m.get("action");
                if (raw != null) {
                    try {
                        action = MappingWaypoint.Action.valueOf(String.valueOf(raw));
                    } catch (IllegalArgumentException e) {
                        action = null;   // 未知动作名：保留航点，动作为空（降级不炸）
                    }
                }
                out.add(new MappingWaypoint(
                        intOf(m.get("seq")), dblOf(m.get("lat")), dblOf(m.get("lon")),
                        dblOf(m.get("alt")), dblOf(m.get("headingDeg")),
                        dblOf(m.get("cameraAngleDeg")), action, intOf(m.get("photoId"))));
            }
        } catch (Exception e) {
            log.warn("航点反序列化失败，按空列表降级：{}", e.getMessage());
            return new ArrayList<>();
        }
        return out;
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static double dblOf(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }
}

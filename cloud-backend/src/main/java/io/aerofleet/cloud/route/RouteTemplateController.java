package io.aerofleet.cloud.route;

import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行业航线模板 REST（F3）：四类模板生成，输出可直接下发到
 * POST /api/v1/drones/{sysid}/mission 的航点列表。
 * <p>
 * 生成是纯计算（无副作用），OPERATOR 可用；参数问题 400 + 字段名。
 */
@RestController
@RequestMapping("/api/v1/route-templates")
public class RouteTemplateController {

    @PostMapping("/generate")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> generate(@RequestBody Map<String, Object> body) {
        String type = body.get("type") instanceof String s && !s.isBlank() ? s : null;
        if (type == null) {
            return bad("missing field 'type' (tower|solar|pipeline|shoreline)");
        }
        try {
            RouteGenerator.Result r = switch (type) {
                case "tower" -> RouteGenerator.tower(
                        towersOf(body.get("towers")),
                        num(body, "altM", 60),
                        num(body, "orbitRadiusM", 25),
                        (int) num(body, "orbitPoints", 4),
                        num(body, "hoverSec", 5));
                case "solar" -> RouteGenerator.solar(
                        polyOf(body.get("polygon"), "polygon"),
                        num(body, "altM", 60),
                        num(body, "lineSpacingM", 30),
                        num(body, "directionDeg", 0));
                case "pipeline" -> RouteGenerator.pipeline(
                        polyOf(body.get("line"), "line"),
                        num(body, "altM", 60),
                        num(body, "stepM", 50));
                case "shoreline" -> RouteGenerator.shoreline(
                        polyOf(body.get("polygon"), "polygon"),
                        num(body, "altM", 60),
                        num(body, "stepM", 50),
                        num(body, "offsetM", 0));
                default -> throw new IllegalArgumentException(
                        "unknown type: " + type + " (tower|solar|pipeline|shoreline)");
            };
            return ResponseEntity.ok(view(r));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    // ------------------------------------------------------------------

    static Map<String, Object> view(RouteGenerator.Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", r.type());
        out.put("waypointCount", r.waypoints().size());
        out.put("estKm", r.estKm());
        out.put("altM", r.altM());
        out.put("waypoints", r.waypoints().stream().map(RouteTemplateController::pointView).toList());
        out.put("legs", r.legs().stream().map(leg -> Map.of(
                "towerNo", leg.towerNo(),
                "waypoints", leg.waypoints().stream()
                        .map(RouteTemplateController::pointView).toList())).toList());
        return out;
    }

    private static Map<String, Object> pointView(RouteGenerator.Point p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cmd", p.cmd());
        m.put("lat", p.lat());
        m.put("lon", p.lon());
        m.put("alt", p.alt());
        m.put("holdTime", p.holdTime());
        return m;
    }

    private static List<RouteGenerator.Tower> towersOf(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("invalid route template parameter: towers (non-empty array)");
        }
        List<RouteGenerator.Tower> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("invalid route template parameter: towers[] must be objects");
            }
            Object no = m.get("towerNo");
            Object lat = m.get("lat");
            Object lon = m.get("lon");
            if (!(lat instanceof Number la) || !(lon instanceof Number lo)) {
                throw new IllegalArgumentException("invalid route template parameter: towers[].lat/lon");
            }
            out.add(new RouteGenerator.Tower(
                    no instanceof String s ? s : null, la.doubleValue(), lo.doubleValue()));
        }
        return out;
    }

    /** 坐标数组：[[lat,lon],...]；polygon/line 共用（点名用于报错）。 */
    private static double[][] polyOf(Object raw, String field) {
        if (!(raw instanceof List<?> list) || list.size() < 2) {
            throw new IllegalArgumentException(
                    "invalid route template parameter: " + field + " (non-empty array)");
        }
        double[][] out = new double[list.size()][];
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof List<?> p) || p.size() < 2
                    || !(p.get(0) instanceof Number) || !(p.get(1) instanceof Number)) {
                throw new IllegalArgumentException(
                        "invalid route template parameter: " + field + "[] must be [lat,lon] pairs");
            }
            out[i] = new double[]{((Number) p.get(0)).doubleValue(), ((Number) p.get(1)).doubleValue()};
        }
        return out;
    }

    private static double num(Map<String, Object> body, String key, double dflt) {
        Object v = body.get(key);
        return v instanceof Number n ? n.doubleValue() : dflt;
    }

    private static ResponseEntity<Map<String, Object>> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("status", "error", "result", message));
    }
}
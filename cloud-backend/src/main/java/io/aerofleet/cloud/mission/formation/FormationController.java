package io.aerofleet.cloud.mission.formation;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编队 REST 端点（FR-04/FR-11/FR-13~FR-17，DFX 4.5 独立路径 /api/v1/formation/*）。
 *
 * 请求校验 + 委托 {@link FormationService}，不含业务逻辑。
 * 既有端点不受影响（独立路径前缀）。
 *
 * 端点清单：
 *   POST   /api/v1/formation              创建编队（FR-14）
 *   GET    /api/v1/formation/{id}         查询编队状态（FR-13）
 *   POST   /api/v1/formation/{id}/command 下发编队命令（FR-15）
 *   POST   /api/v1/formation/{id}/transition 队形变换（FR-04）
 *   POST   /api/v1/formation/{id}/lights  灯光控制（FR-11）
 *   GET    /api/v1/formation/{id}/lights  查询灯光状态（FR-13）
 *   DELETE /api/v1/formation/{id}/members/{sysid} 单机脱离（FR-17）
 *   POST   /api/v1/formation/{id}/dissolve 解散编队（FR-16）
 */
@RestController
@RequestMapping("/api/v1/formation")
public class FormationController {


    private final FormationService formationService;
    private final DeviceRegistry registry;

    public FormationController(FormationService formationService, DeviceRegistry registry) {
        this.formationService = formationService;
        this.registry = registry;
    }

    /** 创建编队（FR-14）。 */
    @PostMapping
    public Map<String, Object> create(@RequestBody FormationCreateRequest req) {
        FormationService.FormationCreateResult r = formationService.create(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("formationId", r.formationId());
        out.put("assignments", r.assignments());
        out.put("state", r.state().name());
        out.put("leader", r.leaderSysid());
        return out;
    }

    /** 查询编队状态（FR-13）。 */
    @GetMapping("/{id}")
    public Map<String, Object> getFormation(@PathVariable("id") int id) {
        Formation f = formationService.formation(id);
        if (f == null) {
            throw new io.aerofleet.cloud.api.exception.ApiExceptionHandler.NotFoundException(
                    "formation " + id + " not found");
        }
        return formationView(f);
    }

    /** 下发编队命令（FR-15）。 */
    @PostMapping("/{id}/command")
    public Map<String, Object> command(@PathVariable("id") int id,
                                        @RequestBody CommandRequest body) {
        FormationService.FormationCommand cmd = body.toCommand();
        Map<Integer, FormationService.AckResult> results = formationService.command(id, cmd);
        return ackView(id, results);
    }

    /** 队形变换（FR-04）。 */
    @PostMapping("/{id}/transition")
    public Map<String, Object> transition(@PathVariable("id") int id,
                                           @RequestBody TransitionRequest body) {
        if (body.steps < 1) {
            throw new io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException(
                    "steps must be >= 1");
        }
        Map<Integer, List<Formation.GeoPos>> waypoints =
                formationService.transition(id, body.newShape, body.steps);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("formationId", id);
        out.put("newShape", body.newShape.name());
        out.put("waypoints", waypoints);
        return out;
    }

    /** 灯光控制（FR-11）。 */
    @PostMapping("/{id}/lights")
    public Map<String, Object> lights(@PathVariable("id") int id,
                                       @RequestBody LedControlCommand cmd) {
        Map<Integer, FormationService.AckResult> results = formationService.lights(id, cmd);
        return ackView(id, results);
    }

    /** 查询编队灯光状态（FR-13）。 */
    @GetMapping("/{id}/lights")
    public Map<String, Object> getLights(@PathVariable("id") int id) {
        Formation f = formationService.formation(id);
        if (f == null) {
            throw new io.aerofleet.cloud.api.exception.ApiExceptionHandler.NotFoundException(
                    "formation " + id + " not found");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("formationId", id);
        LedControlCommand led = f.lastLightCommand;
        if (led == null) {
            out.put("on", false);
        } else {
            out.put("on", led.on);
            out.put("pattern", led.pattern);
            out.put("brightness", led.brightness);
            out.put("freq", led.freq);
            out.put("sync", led.sync);
            out.put("colorR", led.colorR);
            out.put("colorG", led.colorG);
            out.put("colorB", led.colorB);
        }
        return out;
    }

    /** 单机脱离（FR-17）。 */
    @DeleteMapping("/{id}/members/{sysid}")
    public Map<String, Object> removeMember(@PathVariable("id") int id,
                                             @PathVariable("sysid") int sysid) {
        formationService.removeMember(id, sysid);
        Formation f = formationService.formation(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("formationId", id);
        out.put("removedSysid", sysid);
        out.put("state", f != null ? f.state.name() : "DISSOLVED");
        out.put("members", f != null ? f.sortedMembers() : List.of());
        return out;
    }

    /** 解散编队（FR-16）。 */
    @PostMapping("/{id}/dissolve")
    public Map<String, Object> dissolve(@PathVariable("id") int id) {
        Map<Integer, FormationService.AckResult> results =
                formationService.command(id, FormationService.FormationCommand.dissolve());
        return ackView(id, results);
    }

    // =====================================================================
    // 请求体 DTO
    // =====================================================================

    /** 编队命令请求体。 */
    public static final class CommandRequest {
        public String type;           // TAKEOFF/TRANSITION/LIGHTS/RTL/DISSOLVE
        public Double alt;
        public FormationGeometry.Shape newShape;
        public Integer steps;
        public LedControlCommand ledCommand;

        public FormationService.FormationCommand toCommand() {
            FormationService.FormationCommand.Type t =
                    FormationService.FormationCommand.Type.valueOf(type);
            double altVal = alt != null ? alt : 0;
            int stepsVal = steps != null ? steps : 0;
            return new FormationService.FormationCommand(
                    t, altVal, newShape, stepsVal, ledCommand);
        }
    }

    /** 队形变换请求体。 */
    public static final class TransitionRequest {
        public FormationGeometry.Shape newShape;
        public int steps;
    }

    // =====================================================================
    // 响应视图
    // =====================================================================

    private Map<String, Object> formationView(Formation f) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("formationId", f.formationId);
        out.put("state", f.state.name());
        out.put("shape", f.shape.name());
        out.put("spacing", f.spacing);
        out.put("heading", f.heading);
        out.put("leader", f.leaderSysid);
        out.put("version", f.version.get());
        List<Map<String, Object>> members = new ArrayList<>();
        for (int sysid : f.sortedMembers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sysid", sysid);
            DroneSnapshot snap = registry.get(sysid);
            m.put("online", snap != null && snap.online);
            Formation.GeoPos target = f.targetPositions.get(sysid);
            if (target != null) {
                m.put("targetLat", target.lat());
                m.put("targetLon", target.lon());
                m.put("targetAlt", target.alt());
            }
            members.add(m);
        }
        out.put("members", members);
        return out;
    }

    private Map<String, Object> ackView(int id, Map<Integer, FormationService.AckResult> results) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("formationId", id);
        out.put("results", results);
        return out;
    }
}
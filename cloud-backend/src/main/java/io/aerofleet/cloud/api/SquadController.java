package io.aerofleet.cloud.api;

import io.aerofleet.cloud.mission.SquadDispatcher;
import io.aerofleet.cloud.mission.SquadRoleService;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Squad/编队 endpoints (batch F): role state machine + multi-target dispatch.
 *   GET  /api/v1/squad/roles      -> {version, leader, drones:[{sysid,role,battery,online}]}
 *   POST /api/v1/squad/assign     -> compute + dispatch orbit missions
 *   POST /api/v1/squad/leader/{id}-> manual leader override
 *
 * Pure coordination layer: reads the fleet/tracker/battery, writes nothing to
 * the routing or command services. Dispatch is a suggestion (OrbitService still
 * enforces its own safety bounds and per-drone one-in-flight rule).
 */
@RestController
@RequestMapping("/api/v1/squad")
public class SquadController {

    private static final Logger log = LoggerFactory.getLogger(SquadController.class);

    private final SquadRoleService roles;
    private final SquadDispatcher dispatcher;

    public SquadController(SquadRoleService roles, SquadDispatcher dispatcher) {
        this.roles = roles;
        this.dispatcher = dispatcher;
    }

    /** Current roles: {version, leader, drones:[{sysid, role, battery, online}]}. */
    @GetMapping("/roles")
    public Map<String, Object> roles() {
        roles.refresh();
        return roles.rolesView();
    }

    /** Recompute + dispatch orbit missions to the assigned drones. */
    @PostMapping("/assign")
    @RequireRole(Role.OPERATOR)
    public Map<String, Object> assign() {
        roles.refresh();
        Map<String, Object> result = dispatcher.dispatch();
        log.info("squad assign done: {} assigned", result.get("assignments"));
        return result;
    }

    /** Manual leader override. */
    @PostMapping("/leader/{sysid}")
    @RequireRole(Role.OPERATOR)
    public Map<String, Object> setLeader(@PathVariable("sysid") int sysid) {
        roles.refresh();
        roles.assignLeader(sysid);
        return roles.rolesView();
    }
}
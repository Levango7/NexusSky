package io.aerofleet.cloud.ai;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** M11 决策监控 REST API */
@RestController
@RequestMapping("/api/v1/ai")
public class DecisionMonitorController {
    private final DecisionMonitorService service;

    public DecisionMonitorController(DecisionMonitorService service) {
        this.service = service;
    }

    @GetMapping("/decisions")
    public Map<Integer, java.util.List<Map<String, Object>>> allDecisions() {
        return service.getAllDecisions();
    }

    @GetMapping("/decisions/{sysid}")
    public java.util.List<Map<String, Object>> droneDecisions(@PathVariable int sysid) {
        return service.getDecisions(sysid);
    }
}

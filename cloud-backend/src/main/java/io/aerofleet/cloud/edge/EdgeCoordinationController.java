package io.aerofleet.cloud.edge;

import org.springframework.web.bind.annotation.*;
import java.util.*;

/** M12 边缘协同 REST API */
@RestController
@RequestMapping("/api/edge")
public class EdgeCoordinationController {
    private final EdgeCoordinationService service;

    public EdgeCoordinationController(EdgeCoordinationService service) { this.service = service; }

    @PostMapping("/results")
    public Map<String, Object> submitResult(@RequestBody Map<String, Object> body) {
        int sysid = (Integer) body.get("sysid");
        service.submitResult(sysid, (String) body.get("taskId"), (String) body.get("type"), body);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "OK");
        return resp;
    }

    @GetMapping("/tasks")
    public Map<Integer, List<Map<String, Object>>> allTasks() { return service.getAllResults(); }

    @GetMapping("/fusion/{sysid}")
    public List<Map<String, Object>> fusionData(@PathVariable int sysid) { return service.getResults(sysid); }
}

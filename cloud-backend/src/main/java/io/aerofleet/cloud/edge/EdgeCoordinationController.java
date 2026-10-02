package io.aerofleet.cloud.edge;

import org.springframework.web.bind.annotation.*;
import java.util.*;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;

import static io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException;

/** M12 边缘协同 REST API */
@RestController
@RequestMapping("/api/v1/edge")
@RequireRole(Role.OBSERVER)
public class EdgeCoordinationController {
    private final EdgeCoordinationService service;

    public EdgeCoordinationController(EdgeCoordinationService service) { this.service = service; }

    @PostMapping("/results")
    @RequireRole(Role.OPERATOR)
    public Map<String, Object> submitResult(@RequestBody Map<String, Object> body) {
        Object sysidRaw = body.get("sysid");
        if (!(sysidRaw instanceof Number sysidNum)) {
            throw new BadRequestException("field 'sysid' is required and must be numeric");
        }
        service.submitResult(sysidNum.intValue(), asText(body.get("taskId")), asText(body.get("type")), body);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "OK");
        return resp;
    }

    @GetMapping("/tasks")
    public Map<Integer, List<Map<String, Object>>> allTasks() { return service.getAllResults(); }

    @GetMapping("/fusion/{sysid}")
    public List<Map<String, Object>> fusionData(@PathVariable int sysid) { return service.getResults(sysid); }

    /** 宽容字符串转换：缺失返回 null（服务层容忍），非字符串类型取其文本形式而非抛 500。 */
    private static String asText(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}

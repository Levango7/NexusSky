package io.aerofleet.cloud.linksim;

import com.fasterxml.jackson.databind.JsonNode;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * link-sim 运行时损伤控制的云端转发（P2 #20）。
 *
 * <pre>
 * GET   /api/v1/link-sim/control   读当前画像/参数/统计        OBSERVER
 * PATCH /api/v1/link-sim/control   切画像 / 恢复默认 / 调损伤  OPERATOR
 * </pre>
 *
 * <p>为什么云端只做转发而不复制校验规则：损伤参数的唯一权威是 link-sim 的
 * {@code LinkSimControl}（范围/互斥/方向语义都在那里）。云端复制一份必然漂移，
 * 于是本层只负责鉴权、超时和错误映射：
 * <ul>
 *   <li>link-sim 未启动控制通道（连不上）→ 503，明确提示启动参数；</li>
 *   <li>连接/读取超时 → 504；</li>
 *   <li>link-sim 判定参数非法 → 400，原因原样透传；</li>
 *   <li>上游其它失败 → 502。</li>
 * </ul>
 *
 * <p><b>鉴权边界</b>：link-sim 控制端口只绑 {@code 127.0.0.1} 且无应用层鉴权；
 * 公网可达的只有本控制器，写操作要求 OPERATOR 及以上。
 *
 * @see LinkSimControlClient
 */
@RestController
@RequestMapping("/api/v1/link-sim")
public class LinkSimControlController {

    private static final Logger log = LoggerFactory.getLogger(LinkSimControlController.class);

    private final LinkSimControlClient client;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public LinkSimControlController(LinkSimControlClient client,
                                    com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    /** 当前链路损伤状态（画像、双方向十元组、累计统计、参数上限）。 */
    @GetMapping("/control")
    @RequireRole(Role.OBSERVER)
    public ResponseEntity<JsonNode> status() {
        try {
            return ResponseEntity.ok(client.getProfile());
        } catch (LinkSimControlClient.LinkSimControlException e) {
            return failure(e);
        }
    }

    /**
     * 修改链路损伤。
     *
     * <p>请求体三种互斥形态（详见 link-sim 控制面）：
     * <ul>
     *   <li>{@code {"profile":"lte-edge"}}：整画像切换；</li>
     *   <li>{@code {"reset":true}}：恢复当前画像默认值；</li>
     *   <li>{@code {"delayMs":..,"jitterMs":..,"dropPct":..,"bandwidthKbps":..,
     *       "partitionCycleSec":..,"partitionDownSec":..}}：局部更新；</li>
     * </ul>
     * 均可附加 {@code direction=uplink|downlink|both}（默认 both）。
     */
    @PatchMapping("/control")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<JsonNode> patch(@RequestBody(required = false) JsonNode body) {
        if (body == null || !body.isObject()) {
            return ResponseEntity.badRequest().body(error("request body must be a JSON object"));
        }
        try {
            return ResponseEntity.ok(client.patchProfile(body));
        } catch (LinkSimControlClient.LinkSimControlException e) {
            return failure(e);
        }
    }

    private ResponseEntity<JsonNode> failure(LinkSimControlClient.LinkSimControlException e) {
        if (e.getStatus() >= 500) {
            log.warn("link-sim control unavailable: {}", e.getMessage());
        }
        return ResponseEntity.status(e.getStatus()).body(error(e.getMessage()));
    }

    private JsonNode error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("result", message == null ? "error" : message);
        return mapper.valueToTree(body);
    }
}

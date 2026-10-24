package io.aerofleet.cloud.linksim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * 云端 → link-sim 控制面的 HTTP 转发客户端（P2 #20）。
 *
 * <p>只做两件事：{@code GET /profile} 读当前损伤参数，{@code PATCH /profile}
 * 改参数。错误按"能不能到达"分层，便于调用方给出准确的状态码：
 * <ul>
 *   <li>连接被拒绝/DNS 失败等 IO 错误 → {@link LinkSimUnreachableException}（503）</li>
 *   <li>连接或读取超时 → {@link LinkSimTimeoutException}（504）</li>
 *   <li>上游 400（参数非法）→ {@link LinkSimBadRequestException}（400，透传原因）</li>
 *   <li>上游其它非 200 → {@link LinkSimUpstreamException}（502）</li>
 * </ul>
 *
 * <p><b>不重试</b>：PATCH 不是幂等的语义边界（同一份参数重复应用虽等价，
 * 但重试会把"上游慢"变成"操作员多点一次"），失败就让调用方看见。
 */
@Component
public class LinkSimControlClient {

    private static final Logger log = LoggerFactory.getLogger(LinkSimControlClient.class);

    private final LinkSimProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public LinkSimControlClient(LinkSimProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .build();
    }

    /** 读取 link-sim 当前画像/参数/统计。 */
    public JsonNode getProfile() {
        return exchange(HttpRequest.newBuilder(uri()).GET());
    }

    /**
     * 应用一次控制请求。
     *
     * @param body JSON 对象（字段校验由 link-sim 负责，云端不复制一份规则）
     */
    public JsonNode patchProfile(JsonNode body) {
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new LinkSimBadRequestException("serialize control request failed: " + e.getMessage());
        }
        return exchange(HttpRequest.newBuilder(uri())
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(payload)));
    }

    private JsonNode exchange(HttpRequest.Builder builder) {
        HttpRequest request = builder.timeout(Duration.ofMillis(props.getTimeoutMs())).build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new LinkSimTimeoutException("link-sim control timed out after "
                    + props.getTimeoutMs() + "ms: " + props.getControlUrl());
        } catch (IOException e) {
            throw new LinkSimUnreachableException("link-sim control unreachable at "
                    + props.getControlUrl() + " (" + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + "); start link-sim with --control-port");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LinkSimUnreachableException("link-sim control request interrupted");
        }

        int status = response.statusCode();
        if (status == 200) {
            try {
                return mapper.readTree(response.body());
            } catch (IOException e) {
                throw new LinkSimUpstreamException("link-sim returned malformed JSON: " + e.getMessage());
            }
        }
        String detail = upstreamDetail(response.body());
        if (status == 400) {
            throw new LinkSimBadRequestException(detail);
        }
        log.warn("link-sim control {} -> HTTP {}: {}", request.method(), status, detail);
        throw new LinkSimUpstreamException("link-sim control HTTP " + status + ": " + detail);
    }

    /** 上游错误体形如 {"status":"error","result":"..."}；取 result 给前端看。 */
    private String upstreamDetail(String body) {
        if (body == null || body.isBlank()) {
            return "(empty response)";
        }
        try {
            JsonNode node = mapper.readTree(body);
            String result = node.path("result").asText("");
            if (!result.isBlank()) {
                return result;
            }
        } catch (IOException ignored) {
            // 非 JSON 错误体：原样透传（截断，避免把大段 HTML 灌进日志/响应）
        }
        return body.length() > 512 ? body.substring(0, 512) + "…" : body;
    }

    private URI uri() {
        return URI.create(props.getControlUrl());
    }

    /** 转发失败的基类：{@link #status} 是云端应返回给调用方的 HTTP 状态。 */
    public static class LinkSimControlException extends RuntimeException {
        private final int status;

        LinkSimControlException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    /** 503：link-sim 没启动控制通道 / 端口不通。 */
    public static class LinkSimUnreachableException extends LinkSimControlException {
        LinkSimUnreachableException(String message) {
            super(503, message);
        }
    }

    /** 504：连上了但超时。 */
    public static class LinkSimTimeoutException extends LinkSimControlException {
        LinkSimTimeoutException(String message) {
            super(504, message);
        }
    }

    /** 400：link-sim 拒绝了参数（未知字段/越界/语义冲突）。 */
    public static class LinkSimBadRequestException extends LinkSimControlException {
        LinkSimBadRequestException(String message) {
            super(400, message);
        }
    }

    /** 502：上游返回了 200/400 之外的状态（协议不一致或上游内部错误）。 */
    public static class LinkSimUpstreamException extends LinkSimControlException {
        LinkSimUpstreamException(String message) {
            super(502, message);
        }
    }
}

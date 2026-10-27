package io.aerofleet.cloud.dock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * sim 传输：把物模型形状的命令 POST 到机巢模拟器（dock-sim）的 /services。
 * <p>
 * 默认装配（{@code matchIfMissing = true}）——与 C1 NoopReportSink 同型：
 * 缺省即工作，不因配置缺失导致机巢功能整体不可用；通道故障如实抛异常，
 * 由上一层记 WARN/504，不返回假成功。
 */
@Component
@ConditionalOnProperty(name = "aerofleet.dock.transport", havingValue = "sim", matchIfMissing = true)
public class SimLoopbackGateway implements DockGateway {

    private static final Logger log = LoggerFactory.getLogger(SimLoopbackGateway.class);

    private final ObjectMapper mapper;
    private final DockProperties props;
    private final HttpClient http;
    private final AtomicLong tidSeq = new AtomicLong(0);

    /**
     * 单调序列发号器（V35；§5 Q4 裁决：机巢事务号跨重启单调）。缺失时走内存 tidSeq
     * （纯单测语义不变）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.aerofleet.cloud.api.service.PersistentSequence sequence;

    public SimLoopbackGateway(ObjectMapper mapper, DockProperties props) {
        this.mapper = mapper;
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getCommandTimeoutMs()))
                .build();
    }

    @Override
    public String transportName() {
        return "sim";
    }

    @Override
    public DockReply sendCommand(String sn, DockCommand command, Map<String, Object> data) {
        Long issued = sequence != null ? sequence.next("dock_tid", 0) : null;
        long tid = issued != null ? issued : tidSeq.incrementAndGet();
        long bid = System.currentTimeMillis();
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("tid", tid);
        frame.put("bid", bid);
        frame.put("timestamp", System.currentTimeMillis());
        frame.put("method", command.method());
        frame.put("data", data == null ? Map.of() : data);

        String body;
        try {
            body = mapper.writeValueAsString(frame);
        } catch (Exception e) {
            throw new DockGatewayException("serialize dock command failed: " + e.getMessage(), e);
        }

        String url = props.getSimBaseUrl() + "/services";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(props.getCommandTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new DockGatewayException("dock-sim HTTP " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode node = mapper.readTree(resp.body());
            int result = node.path("data").path("result").asInt(0);
            String message = node.path("data").path("message").asText(null);
            long rTid = node.path("tid").asLong(tid);
            long rBid = node.path("bid").asLong(bid);
            return new DockReply(rTid, rBid, System.currentTimeMillis(), result, message, resp.body());
        } catch (DockGatewayException e) {
            throw e;
        } catch (Exception e) {
            log.warn("dock command transport failed sn={} method={}: {}", sn, command.method(), e.getMessage());
            throw new DockGatewayException("dock command transport failed: " + e.getMessage(), e);
        }
    }
}

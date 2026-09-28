package io.aerofleet.cloud.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 外部推理服务视觉源（F1 真 CV 接入，spec §1.1/§2.2）。
 * <p>
 * {@link VisionSource} 的第四实现：把渲染 JPEG 通过 HTTP 推给外部推理服务
 * （兼容 ONNX Runtime Server / TorchServe / Triton / 自建 FastAPI 封装），
 * 收回像素坐标检测框。模型运行在独立服务进程，平台零模型依赖（spec 1.5-2）。
 * <p>
 * 协议（spec §2.2）：
 * <ul>
 *   <li>请求：POST {endpoint}，Content-Type: image/jpeg，body=JPEG 字节</li>
 *   <li>响应：200 + JSON 数组 [{kind, confidence(0..1), u(px), v(px)}]，
 *       坐标相对该 JPEG（640×360）</li>
 * </ul>
 * 失败语义（spec N1）：非 200 / 超时 / 非法 JSON / 单条字段非法 → WARN 并丢弃，
 * 返回已解析部分（全部失败返回空列表），不向调用方抛异常。
 * <p>
 * 装配：仅 {@code aerofleet.vision.source=external} 时注册（与 NoopReportSink
 * 同款条件装配）；endpoint 缺失时构造 WARN，detect 恒返回空列表（spec N2 双保险）。
 */
@Component
@ConditionalOnProperty(name = "aerofleet.vision.source", havingValue = "external")
public class ExternalVisionSource implements VisionSource {

    private static final Logger log = LoggerFactory.getLogger(ExternalVisionSource.class);

    private final String endpoint;
    private final long timeoutMs;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public ExternalVisionSource(
            @Value("${aerofleet.vision.external.endpoint:}") String endpoint,
            @Value("${aerofleet.vision.external.timeout-ms:3000}") long timeoutMs) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.timeoutMs = timeoutMs;
        if (this.endpoint.isEmpty()) {
            log.warn("aerofleet.vision.source=external but endpoint is empty — "
                    + "detect() will return empty results (check N2)");
        }
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.mapper = new ObjectMapper();
    }

    @Override
    public List<VisionDetection> detect(CameraShot shot, CameraPose pose) {
        // 元数据级调用：无 JPEG 像素可用（外部源只走 jpeg 重载）
        return List.of();
    }

    @Override
    public List<VisionDetection> detect(CameraShot shot, CameraPose pose, byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0 || endpoint.isEmpty()) {
            return List.of();
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "image/jpeg")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(jpeg))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("external inference {} -> HTTP {}", endpoint, resp.statusCode());
                return List.of();
            }
            return parseDetections(resp.body());
        } catch (Exception e) {
            log.warn("external inference failed (frame {}): {}",
                    shot == null ? -1 : shot.frameSeq(), e.getMessage());
            return List.of();
        }
    }

    /** 解析推理服务响应；非法整体/非法单条按 spec N1 丢弃不抛。 */
    private List<VisionDetection> parseDetections(String body) {
        List<VisionDetection> out = new ArrayList<>();
        try {
            JsonNode arr = mapper.readTree(body);
            if (!arr.isArray()) {
                log.warn("external inference response is not a JSON array");
                return out;
            }
            for (JsonNode n : arr) {
                double u = n.path("u").asDouble(Double.NaN);
                double v = n.path("v").asDouble(Double.NaN);
                double confidence = n.path("confidence").asDouble(Double.NaN);
                String kind = n.path("kind").asText("");
                if (Double.isNaN(u) || Double.isNaN(v) || Double.isNaN(confidence)
                        || kind.isEmpty()) {
                    log.warn("external inference entry missing fields, skipped");
                    continue;
                }
                if (confidence < 0.0 || confidence > 1.0) {
                    log.warn("external inference confidence {} out of range, skipped", confidence);
                    continue;
                }
                out.add(new VisionDetection(u, v, kind, confidence, -1));
            }
        } catch (Exception e) {
            log.warn("external inference response parse failed: {}", e.getMessage());
            return List.of();
        }
        return out;
    }
}

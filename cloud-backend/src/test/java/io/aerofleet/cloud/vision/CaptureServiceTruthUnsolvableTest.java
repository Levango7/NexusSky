package io.aerofleet.cloud.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.aerofleet.cloud.defect.DefectService;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * N1 钉测：truth 分支对 ray-above-horizon 的目标（GeolocationSolver 在 wz<=1e-9
 * 返回 null）**不再 NPE**——与 locateAndScore 的 `if (loc == null) continue;`
 * 同款「该条跳过」语义，其余目标正常定位。
 */
@DisplayName("CaptureService truth：ray-above-horizon 目标跳过不 NPE（N1）")
class CaptureServiceTruthUnsolvableTest {

    private HttpServer server;
    private String baseUrl;
    private final java.util.concurrent.atomic.AtomicInteger shotsCalls =
            new java.util.concurrent.atomic.AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/camera/shots", ex -> {
            // latestFrameSeq() 取数组最后一个元素的 frameSeq 作为 before，
            // awaitNewShot 只接受 frameSeq > before 的帧。静态数组里 before
            // 就是该数组最大值 ⇒ 永远等不到更大的帧（此前 frameSeq=1/2/3、
            // 空数组全部 "no shot arrived from sim" 的根因）。
            // 故 stub 必须动态：第一次（latestFrameSeq）返回空数组 ⇒ before=0；
            // 之后（awaitNewShot 轮询）返回 frameSeq=1 的新帧 ⇒ 1>0 被接受。
            String body;
            if (shotsCalls.getAndIncrement() == 0) {
                body = "[]";
            } else {
                // gimbalPitch=90 = 水平前视（solver 约定 boresight B=(sin p,0,cos p)、
                // z 轴向下 ⇒ p=0 垂直俯视、p=90 水平）。无机体姿态时 wz=(cv−v)/f：
                //   上半部 v<540 ⇒ wz>0 ⇒ 可解；下半部 v>540 ⇒ wz<0 ⇒ 朝天返回 null。
                body = "[{\"frameSeq\":1,\"lat\":22.59,\"lon\":113.93,\"altM\":100,"
                        + "\"droneRollDeg\":0,\"dronePitchDeg\":0,\"droneYawDeg\":0,"
                        + "\"gimbalPitchDeg\":90,\"gimbalYawDeg\":0,"
                        + "\"targets\":["
                        + "{\"id\":1,\"kind\":\"insulator\",\"u\":120.5,\"v\":80.0},"
                        + "{\"id\":2,\"kind\":\"tower\",\"u\":960.0,\"v\":1000.0}"
                        + "]}]";
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        server.createContext("/targets", ex -> {
            String body = "[{\"id\":1,\"lat\":22.5907,\"lon\":113.9345},"
                    + "{\"id\":2,\"lat\":22.5908,\"lon\":113.9346}]";
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private CaptureService newService() {
        DroneCommandService commands = mock(DroneCommandService.class);
        DeviceRegistry registry = mock(DeviceRegistry.class);
        GeolocationSolver solver = new GeolocationSolver();
        ObjectMapper mapper = new ObjectMapper();
        CvEvalService eval = mock(CvEvalService.class);
        DefectService defectService = mock(DefectService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ExternalVisionSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return new CaptureService(commands, registry, solver, mapper, eval, defectService,
                provider, baseUrl, "truth");
    }

    @Test
    @DisplayName("ray-above-horizon 目标被跳过、不 NPE，正常目标仍定位")
    void unsolvableTarget_skippedWithoutNpe() throws Exception {
        Map<String, Object> out = newService().captureAndLocate(1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> detections =
                (List<Map<String, Object>>) out.get("detections");
        assertThat(detections).as("ray-above-horizon 目标被跳过，只剩正常目标").hasSize(1);
        assertThat(detections.get(0).get("id")).isEqualTo(1);
        assertThat(detections.get(0).get("kind")).isEqualTo("insulator");
        // 正常目标有定位结果与真值误差
        assertThat(detections.get(0)).containsKeys("lat", "lon", "truthErrorM", "confidence");
    }
}

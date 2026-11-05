package io.aerofleet.cloud.api.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.aerofleet.cloud.dock.DockProperties;
import io.aerofleet.cloud.dock.SimLoopbackGateway;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.linksim.LinkSimControlClient;
import io.aerofleet.cloud.linksim.LinkSimProperties;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.cloud.roc.IncidentDispatchService;
import io.aerofleet.cloud.roc.RocSeatService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 异常消息「源头」不得粘底层异常文本（信息泄露第二批回归门）。
 * <p>
 * 与 #246（控制器宽捕边界）互补：那批收口消费侧，本批收口构造侧——
 * {@code new XxxException("...: " + e.getMessage())} 的消息会经错误体/席位视图/日志消费方
 * 回到操作端，或进入持久化字段（suggestionReason）。判据同样：canary 串不进对外消息，
 * 细节只走 cause 链与日志。
 */
@DisplayName("异常构造点消息不含底层异常细节（源头治理）")
class SourceSideMessagesDoNotEmbedInternalDetailTest {

    /** 底层异常消息/上游响应体里的内部细节。 */
    private static final String CANARY =
            "connect to 10.9.9.9:16666 failed (redis token redis-secret-xyz)";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private static HttpServer newServer(String path, int status, String body) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext(path, exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        s.start();
        return s;
    }

    // ------------------------------------------------------------------
    // 警情派飞：suggestionReason 会经 RocController 席位视图回到操作端
    // ------------------------------------------------------------------

    @Test
    @DisplayName("派飞失败：ISE 消息与 suggestionReason 都不含底层细节")
    void incidentDispatchFailureKeepsMessagesAuthored() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(7);              // online=true，参与建议打分
        RocSeatService seats = new RocSeatService(registry);
        long seatId = (long) (Number) seats.create("op-7").get("seatId");
        seats.bindFleet(seatId, List.of(7));

        DroneCommandService commands = mock(DroneCommandService.class);
        doThrow(new DroneCommandService.CommandException(
                "mission ack failed: " + CANARY, 3)).when(commands).arm(7);
        IncidentDispatchService incidents =
                new IncidentDispatchService(seats, registry, commands);

        Map<String, Object> reported = incidents.report(
                seatId, 22.59, 113.93, "P1", "测试警情");
        long incidentId = ((Number) reported.get("incidentId")).longValue();

        assertThatThrownBy(() -> incidents.dispatch(seatId, incidentId, 30))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("dispatch failed")
                .hasMessageNotContaining(CANARY);

        // 席位视图（含 suggestionReason）不泄露
        List<Map<String, Object>> views = incidents.listIncidents(seatId);
        assertThat(views).hasSize(1);
        assertThat(String.valueOf(views.get(0).get("suggestionReason")))
                .doesNotContain(CANARY);
        assertThat(String.valueOf(views.get(0).get("suggestionReason")))
                .isEqualTo("dispatch failed (see server log)");
    }

    // ------------------------------------------------------------------
    // SimLoopbackGateway：序列化失败 / 上游非 2xx / 传输失败
    // ------------------------------------------------------------------

    @Test
    @DisplayName("dock 序列化失败：消息不含底层细节")
    void dockSerializeFailureKeepsMessageAuthored() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any()))
                .thenThrow(new com.fasterxml.jackson.databind.JsonMappingException(null, CANARY));
        DockProperties props = new DockProperties();
        props.setSimBaseUrl("http://127.0.0.1:1");
        SimLoopbackGateway gateway = new SimLoopbackGateway(mapper, props);

        assertThatThrownBy(() -> gateway.sendCommand(
                "sn-1", io.aerofleet.cloud.dock.DockCommand.OPEN_DOOR, Map.of()))
                .isInstanceOf(io.aerofleet.cloud.dock.DockGateway.DockGatewayException.class)
                .hasMessage("serialize dock command failed")
                .hasMessageNotContaining(CANARY);
    }

    @Test
    @DisplayName("dock 上游非 2xx：不透传响应体（消息只带状态码）")
    void dockUpstreamErrorBodyNotForwarded() throws Exception {
        server = newServer("/services", 500, "internal: dock at 10.9.9.9 down, " + CANARY);
        ObjectMapper mapper = new ObjectMapper();
        DockProperties props = new DockProperties();
        props.setSimBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setCommandTimeoutMs(5000);
        SimLoopbackGateway gateway = new SimLoopbackGateway(mapper, props);

        assertThatThrownBy(() -> gateway.sendCommand(
                "sn-1", io.aerofleet.cloud.dock.DockCommand.OPEN_DOOR, Map.of()))
                .isInstanceOf(io.aerofleet.cloud.dock.DockGateway.DockGatewayException.class)
                .hasMessage("dock-sim returned HTTP 500")
                .hasMessageNotContaining(CANARY)
                .hasMessageNotContaining("10.9.9.9");
    }

    @Test
    @DisplayName("dock 传输失败：消息不含底层细节（连不上的端口）")
    void dockTransportFailureKeepsMessageAuthored() {
        ObjectMapper mapper = new ObjectMapper();
        DockProperties props = new DockProperties();
        props.setSimBaseUrl("http://127.0.0.1:1");   // 拒连端口
        props.setCommandTimeoutMs(300);
        SimLoopbackGateway gateway = new SimLoopbackGateway(mapper, props);

        assertThatThrownBy(() -> gateway.sendCommand(
                "sn-1", io.aerofleet.cloud.dock.DockCommand.OPEN_DOOR, Map.of()))
                .isInstanceOf(io.aerofleet.cloud.dock.DockGateway.DockGatewayException.class)
                .hasMessage("dock command transport failed")
                .hasMessageNotContaining(CANARY);
    }

    // ------------------------------------------------------------------
    // LinkSimControlClient：序列化失败 / 上游 200 但非 JSON
    // ------------------------------------------------------------------

    @Test
    @DisplayName("link-sim 序列化失败：消息不含底层细节")
    void linkSimSerializeFailureKeepsMessageAuthored() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsBytes(any()))
                .thenThrow(new com.fasterxml.jackson.databind.JsonMappingException(null, CANARY));
        LinkSimProperties props = new LinkSimProperties();
        LinkSimControlClient client = new LinkSimControlClient(props, mapper);

        assertThatThrownBy(() -> client.patchProfile(
                new ObjectMapper().createObjectNode()))
                .isInstanceOf(io.aerofleet.cloud.linksim.LinkSimControlClient
                        .LinkSimBadRequestException.class)
                .hasMessage("serialize control request failed")
                .hasMessageNotContaining(CANARY);
    }

    @Test
    @DisplayName("link-sim 返回 200 非 JSON：消息不含响应正文细节")
    void linkSimMalformedJsonDoesNotEchoBody() throws Exception {
        server = newServer("/control/profile", 200, "not-json: " + CANARY);
        LinkSimProperties props = new LinkSimProperties();
        props.setControlUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/control/profile");
        props.setTimeoutMs(5000);
        LinkSimControlClient client =
                new LinkSimControlClient(props, new ObjectMapper());

        assertThatThrownBy(() -> client.patchProfile(
                new ObjectMapper().createObjectNode()))
                .isInstanceOf(io.aerofleet.cloud.linksim.LinkSimControlClient
                        .LinkSimUpstreamException.class)
                .hasMessage("link-sim returned malformed JSON")
                .hasMessageNotContaining(CANARY);
    }
}

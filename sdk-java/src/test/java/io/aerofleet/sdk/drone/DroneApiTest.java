package io.aerofleet.sdk.drone;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.aerofleet.sdk.ApiResponse;
import io.aerofleet.sdk.NexusSkyClient;
import io.aerofleet.sdk.SdkException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DroneApi 单元测试，使用 JDK 内置 HttpServer 作为 stub。
 */
class DroneApiTest {

    private HttpServer server;
    private NexusSkyClient client;
    private DroneApi droneApi;
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/api/v1", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                lastMethod.set(exchange.getRequestMethod());
                lastPath.set(exchange.getRequestURI().getPath());

                byte[] reqBody = exchange.getRequestBody().readAllBytes();
                lastBody.set(reqBody.length > 0 ? new String(reqBody, StandardCharsets.UTF_8) : null);

                String response;
                String path = exchange.getRequestURI().getPath();

                if (path.equals("/api/v1/drones")) {
                    response = "{\"status\":\"ok\",\"data\":[{\"sysid\":1,\"online\":true},{\"sysid\":2,\"online\":false}]}";
                } else if (path.equals("/api/v1/drones/1")) {
                    response = "{\"status\":\"ok\",\"data\":{\"sysid\":1,\"online\":true,\"battery\":85}}";
                } else if (path.equals("/api/v1/drones/1/telemetry")) {
                    response = "{\"status\":\"ok\",\"data\":{\"sysid\":1,\"lat\":39.9,\"lon\":116.4,\"alt\":100}}";
                } else if (path.equals("/api/v1/drones/1/commands")) {
                    response = "{\"status\":\"ok\",\"data\":{\"result\":\"command accepted\"}}";
                } else {
                    response = "{\"status\":\"ok\",\"data\":{}}";
                }

                byte[] respBytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, respBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(respBytes);
                }
            }
        });

        server.start();
        client = NexusSkyClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .apiKey("test-key")
                .allowInsecureHttp(true)
                .build();
        droneApi = client.drones();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void testList() throws SdkException {
        ApiResponse resp = droneApi.list();
        assertNotNull(resp);
        assertTrue(resp.isOk());
        assertEquals("GET", lastMethod.get());
        assertEquals("/api/v1/drones", lastPath.get());
    }

    @Test
    void testGet() throws SdkException {
        ApiResponse resp = droneApi.get(1);
        assertNotNull(resp);
        assertTrue(resp.isOk());
        assertEquals("GET", lastMethod.get());
        assertEquals("/api/v1/drones/1", lastPath.get());
    }

    @Test
    void testTelemetry() throws SdkException {
        ApiResponse resp = droneApi.telemetry(1);
        assertNotNull(resp);
        assertTrue(resp.isOk());
        assertEquals("GET", lastMethod.get());
        assertEquals("/api/v1/drones/1/telemetry", lastPath.get());
    }

    @Test
    void testArm() throws SdkException {
        ApiResponse resp = droneApi.arm(1);
        assertNotNull(resp);
        assertTrue(resp.isOk());
        assertEquals("POST", lastMethod.get());
        assertEquals("/api/v1/drones/1/commands", lastPath.get());
        assertNotNull(lastBody.get());
        assertTrue(lastBody.get().contains("\"type\":\"arm\""));
    }

    @Test
    void testTakeoff() throws SdkException {
        ApiResponse resp = droneApi.takeoff(1, 50);
        assertNotNull(resp);
        assertTrue(resp.isOk());
        assertEquals("POST", lastMethod.get());
        assertEquals("/api/v1/drones/1/commands", lastPath.get());
        assertNotNull(lastBody.get());
        assertTrue(lastBody.get().contains("\"type\":\"takeoff\""));
        assertTrue(lastBody.get().contains("\"alt\":50"));
    }

    @Test
    void testRtl() throws SdkException {
        ApiResponse resp = droneApi.rtl(1);
        assertNotNull(resp);
        assertTrue(resp.isOk());
        assertEquals("POST", lastMethod.get());
        assertEquals("/api/v1/drones/1/commands", lastPath.get());
        assertNotNull(lastBody.get());
        assertTrue(lastBody.get().contains("\"type\":\"rtl\""));
    }

    @Test
    void testStartMission() throws SdkException {
        ApiResponse resp = droneApi.startMission(1);
        assertNotNull(resp);
        assertTrue(resp.isOk());
        assertEquals("POST", lastMethod.get());
        assertEquals("/api/v1/drones/1/commands", lastPath.get());
        assertNotNull(lastBody.get());
        assertTrue(lastBody.get().contains("\"type\":\"start_mission\""));
    }
}

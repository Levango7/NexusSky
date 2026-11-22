package io.aerofleet.sdk;

import com.sun.net.httpserver.HttpServer;
import io.aerofleet.sdk.mission.MissionApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-2 契约钉测：SDK ↔ 后端 REST 契约。
 *
 * 后端返回**裸形状**（`listDrones()` 返回裸 `List<Map>`、`getDrone()` 返回裸 `Map`，
 * 无 `{status,data}` envelope 包装、无全局 ResponseBodyAdvice）。此前 SDK 统一按
 * `{status,data}` envelope 解析 ⇒ 对真实后端基本不可用。
 *
 * 本钉测用**与后端实际一致的裸形状**驱动 SDK（不是 SDK 自己期待的 envelope）——
 * 这正是此前 SDK 单测 stub 人为返回 envelope 造成的假绿根源。
 * 先红（修复前裸形状解析失败）后绿（修复后成功）。
 *
 * 断言用 JUnit 原生（sdk-java 无 AssertJ 依赖）。
 */
@DisplayName("SDK ↔ 后端裸形状契约（P0-2）")
class SdkBackendContractTest {

    private HttpServer server;
    private NexusSkyClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        client = NexusSkyClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("test-key")
                .allowInsecureHttp(true)
                .build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void respond(String path, String json) {
        server.createContext(path, ex -> {
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
    }

    @Test
    @DisplayName("GET /drones：裸数组被正确解析（非 envelope）")
    void getDrones_parsesBareArray() throws Exception {
        respond("/api/v1/drones", "[{\"sysid\":1,\"online\":true,\"battery\":90},"
                + "{\"sysid\":2,\"online\":false,\"battery\":30}]");

        List<Map<String, Object>> drones = client.getDrones();

        assertEquals(2, drones.size());
        assertEquals(1, drones.get(0).get("sysid"));
        assertEquals(90, drones.get(0).get("battery"));
        assertEquals(2, drones.get(1).get("sysid"));
    }

    @Test
    @DisplayName("GET /drones/{id}：裸 Map 被正确解析（无 status 字段不算错误）")
    void getDrone_parsesBareMap() throws Exception {
        respond("/api/v1/drones/1", "{\"sysid\":1,\"online\":true,\"battery\":88}");

        Map<String, Object> drone = client.getDrone(1);

        assertEquals(1, drone.get("sysid"));
        assertEquals(88, drone.get("battery"));
    }

    @Test
    @DisplayName("POST /drones/{id}/mission：请求体为 items 且每项带 cmd（非 waypoints）")
    void uploadMission_sendsItemsWithCmd() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>("");
        server.createContext("/api/v1/drones/1/mission", ex -> {
            captured.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = "{\"status\":\"accepted\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });

        ApiResponse resp = client.missions().upload(1,
                List.of(new MissionApi.Waypoint(22.59, 113.93, 50)));

        assertTrue(resp.isOk());
        String body = captured.get();
        assertTrue(body.contains("\"items\""), "body 应含 items: " + body);
        assertTrue(body.contains("\"cmd\":\"waypoint\""), "每项应带 cmd: " + body);
        assertFalse(body.contains("\"waypoints\""), "不应再发 waypoints: " + body);
    }

    @Test
    @DisplayName("GET /flightlog?sysid=：按 sysid 查询（不再调不存在的 /flightlog/{id}）")
    void flightLogByDrone_usesSysidQuery() throws Exception {
        respond("/api/v1/flightlog", "[{\"lat\":22.59,\"lon\":113.93,\"alt\":50}]");

        ApiResponse resp = client.flightLogs().getByDrone(1);

        assertTrue(resp.isOk());
        assertEquals(1, resp.getDataAsList().size());
    }

    @Test
    @DisplayName("mission clear：诚实降级（后端无 MISSION_CLEAR_ALL 能力，不假成功）")
    void missionClear_degradesHonestly() {
        // 后端无 mission clear 能力，SDK 应明确抛错而非调不存在的端点 / 假成功
        assertThrows(UnsupportedOperationException.class, () -> client.missions().clear(1));
    }
}

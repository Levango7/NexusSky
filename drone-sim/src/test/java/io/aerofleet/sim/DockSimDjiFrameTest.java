package io.aerofleet.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DockSim 设备侧的 DJI 帧形状契约测试。
 *
 * <p>DockSim 是「设备侧替身」——它替真机巢回答云端的 services 命令，
 * 因此它的应答必须与官方 {@code dji-sdk/Cloud-API-Doc}（MIT）v1.18 的
 * {@code 00.topic-definition.md} 逐字段同形：
 * {@code {tid,bid,timestamp,gateway,method,data:{result(int),output(struct)}}}。
 *
 * <p>字段集断言用<b>恰好相等</b>（多一个少一个都红），
 * 避免"顺手多塞一个字段"蒙混过关。
 */
@DisplayName("DockSim 设备侧：services_reply 形状对齐 DJI 官方字段表")
class DockSimDjiFrameTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SN = "DOCK-SIM-DJI";
    /** 官方 services_reply 帧字段集。 */
    private static final Set<String> OFFICIAL_REPLY_FIELDS =
            Set.of("tid", "bid", "timestamp", "gateway", "method", "data");

    private DockSim sim;
    private HttpClient http;
    private String base;

    @BeforeEach
    void boot() throws Exception {
        sim = new DockSim(SN, "契约测试机巢", 0, null, 60_000, 9, null);
        sim.start();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        base = "http://127.0.0.1:" + sim.boundPort();
    }

    @AfterEach
    void shutdown() {
        if (sim != null) {
            sim.close();
        }
    }

    private JsonNode services(String tid, String bid, String method) throws Exception {
        String body = "{\"tid\":\"" + tid + "\",\"bid\":\"" + bid + "\","
                + "\"timestamp\":" + System.currentTimeMillis() + ","
                + "\"gateway\":\"" + SN + "\","
                + "\"method\":\"" + method + "\",\"data\":{}}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/services"))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return MAPPER.readTree(resp.body());
    }

    @Test
    @DisplayName("应答字段集恰好等于官方字段表（tid/bid/timestamp/gateway/method/data）")
    void replyFieldSetMatchesOfficialTable() throws Exception {
        JsonNode reply = services("11111111-2222-3333-4444-555555555555",
                "66666666-7777-8888-9999-aaaaaaaaaaaa", "door_open");

        List<String> actual = new ArrayList<>();
        reply.fieldNames().forEachRemaining(actual::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(OFFICIAL_REPLY_FIELDS);
    }

    @Test
    @DisplayName("tid/bid 文本回显（官方 uuid text），gateway 回显机巢 SN")
    void replyEchoesTransactionIdsAndGateway() throws Exception {
        String tid = "11111111-2222-3333-4444-555555555555";
        String bid = "66666666-7777-8888-9999-aaaaaaaaaaaa";

        JsonNode reply = services(tid, bid, "door_open");

        assertThat(reply.get("tid").isTextual()).isTrue();
        assertThat(reply.get("tid").asText()).isEqualTo(tid);
        assertThat(reply.get("bid").isTextual()).isTrue();
        assertThat(reply.get("bid").asText()).isEqualTo(bid);
        assertThat(reply.get("gateway").asText()).isEqualTo(SN);
        assertThat(reply.get("timestamp").isIntegralNumber()).isTrue();
    }

    @Test
    @DisplayName("data 必填位齐备：result=int、output=struct（官方必填字段表）")
    void dataCarriesResultAndOutputStruct() throws Exception {
        JsonNode reply = services("11111111-2222-3333-4444-555555555555",
                "66666666-7777-8888-9999-aaaaaaaaaaaa", "door_open");

        JsonNode data = reply.get("data");
        assertThat(data.isObject()).isTrue();
        assertThat(data.get("result").isIntegralNumber()).isTrue();
        assertThat(data.get("result").asInt()).isZero();
        assertThat(data.get("output").isObject()).isTrue();
    }

    @Test
    @DisplayName("非法态拒绝：result!=0，设备消息落在 data.output.message")
    void rejectionUsesOutputStruct() throws Exception {
        sim.forceState("OFFLINE");

        JsonNode reply = services("11111111-2222-3333-4444-555555555555",
                "66666666-7777-8888-9999-aaaaaaaaaaaa", "door_open");

        JsonNode data = reply.get("data");
        assertThat(data.get("result").asInt()).isEqualTo(1);
        assertThat(data.get("output").isObject()).isTrue();
        assertThat(data.get("output").get("message").asText()).contains("state=OFFLINE");
    }

    @Test
    @DisplayName("未知 method：result=2 + output.message 点名（不静默成功）")
    void unknownMethodUsesOfficialErrorShape() throws Exception {
        JsonNode reply = services("11111111-2222-3333-4444-555555555555",
                "66666666-7777-8888-9999-aaaaaaaaaaaa", "no_such_method");

        JsonNode data = reply.get("data");
        assertThat(data.get("result").asInt()).isEqualTo(2);
        assertThat(data.get("output").get("message").asText()).contains("unknown method");
    }
}

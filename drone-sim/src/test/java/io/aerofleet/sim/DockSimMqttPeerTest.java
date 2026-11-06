package io.aerofleet.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 机巢 MQTT 对端（设备侧替身）的帧形状契约测试。
 *
 * <p>对端是「设备侧替身」——它替真机巢回答云端的 services 命令、按官方 topic 上报属性，
 * 因此它的帧必须与官方 {@code dji-sdk/Cloud-API-Doc}（MIT）v1.18 的
 * {@code 60.api-reference/20.dock-to-cloud/00.mqtt/00.topic-definition.md} 逐字段同形。
 *
 * <p>本类**不起 broker**（CI 无 MQTT 服务）：直接调用对端的纯函数入口
 * （{@code replyPayload}/{@code osdPayload}）验证帧形状。真 broker 联调由
 * {@code scripts/e2e-dock-mqtt.ps1} 承担。
 */
@DisplayName("DockSimMqttPeer：设备侧帧形状对齐 DJI 官方字段表")
class DockSimMqttPeerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SN = "DOCK-MQTT-001";

    /** 官方 services_reply 帧字段集。 */
    private static final Set<String> OFFICIAL_REPLY_FIELDS =
            Set.of("tid", "bid", "timestamp", "gateway", "method", "data");
    /** 官方 osd 帧字段集（公共字段 + data，无 method）。 */
    private static final Set<String> OFFICIAL_OSD_FIELDS =
            Set.of("tid", "bid", "timestamp", "gateway", "data");

    private DockSimMqttPeer newPeer(DockSimCore core) {
        return new DockSimMqttPeer(SN, core, "tcp://127.0.0.1:1883", null, null, 2000);
    }

    private JsonNode reply(DockSimMqttPeer peer, String method) throws Exception {
        String req = "{\"tid\":\"11111111-2222-3333-4444-555555555555\","
                + "\"bid\":\"66666666-7777-8888-9999-aaaaaaaaaaaa\","
                + "\"timestamp\":" + System.currentTimeMillis() + ","
                + "\"gateway\":\"" + SN + "\",\"method\":\"" + method + "\",\"data\":{}}";
        String payload = peer.replyPayload(req);
        assertThat(payload).isNotNull();
        return MAPPER.readTree(payload);
    }

    @Test
    @DisplayName("services_reply：字段集恰好等于官方字段表，tid/bid 文本回显")
    void replyFieldSetMatchesOfficialTable() throws Exception {
        DockSimCore core = new DockSimCore(9, null);
        try (DockSimMqttPeer peer = newPeer(core)) {
            JsonNode r = reply(peer, "door_open");

            List<String> actual = new ArrayList<>();
            r.fieldNames().forEachRemaining(actual::add);
            assertThat(actual).containsExactlyInAnyOrderElementsOf(OFFICIAL_REPLY_FIELDS);

            assertThat(r.get("tid").isTextual()).isTrue();
            assertThat(r.get("tid").asText()).isEqualTo("11111111-2222-3333-4444-555555555555");
            assertThat(r.get("bid").isTextual()).isTrue();
            assertThat(r.get("bid").asText()).isEqualTo("66666666-7777-8888-9999-aaaaaaaaaaaa");
            assertThat(r.get("gateway").asText()).isEqualTo(SN);
            assertThat(r.get("method").asText()).isEqualTo("door_open");
            assertThat(r.get("timestamp").isIntegralNumber()).isTrue();
            assertThat(r.get("data").get("result").asInt()).isZero();
            assertThat(r.get("data").get("output").isObject()).isTrue();
        } finally {
            core.close();
        }
    }

    @Test
    @DisplayName("非法态拒绝：result=1 + output.message 点名当前状态")
    void illegalStateCarriesResultAndMessage() throws Exception {
        DockSimCore core = new DockSimCore(9, null);
        core.forceState("OPEN");
        try (DockSimMqttPeer peer = newPeer(core)) {
            JsonNode r = reply(peer, "door_open");

            assertThat(r.get("data").get("result").asInt()).isEqualTo(1);
            assertThat(r.get("data").get("output").get("message").asText()).contains("state=OPEN");
        } finally {
            core.close();
        }
    }

    @Test
    @DisplayName("未知 method：result=2 + output.message 点名（不静默成功）")
    void unknownMethodCarriesResult2() throws Exception {
        DockSimCore core = new DockSimCore(9, null);
        try (DockSimMqttPeer peer = newPeer(core)) {
            JsonNode r = reply(peer, "no_such_method");

            assertThat(r.get("data").get("result").asInt()).isEqualTo(2);
            assertThat(r.get("data").get("output").get("message").asText()).contains("unknown method");
        } finally {
            core.close();
        }
    }

    @Test
    @DisplayName("缺 method 的帧：result=1（设备自建消息），不产出假成功")
    void missingMethodCarriesResult1() throws Exception {
        DockSimCore core = new DockSimCore(9, null);
        try (DockSimMqttPeer peer = newPeer(core)) {
            String req = "{\"tid\":\"t\",\"bid\":\"b\",\"timestamp\":1,\"gateway\":\"" + SN + "\","
                    + "\"data\":{}}";
            JsonNode r = MAPPER.readTree(peer.replyPayload(req));

            assertThat(r.get("data").get("result").asInt()).isEqualTo(1);
            assertThat(r.get("data").get("output").get("message").asText()).contains("missing method");
        } finally {
            core.close();
        }
    }

    @Test
    @DisplayName("osd 帧：官方公共字段 + 官方属性名子集（mode_code/putter_state 等）")
    void osdUsesOfficialPropertyNames() throws Exception {
        DockSimCore core = new DockSimCore(9, null);
        try (DockSimMqttPeer peer = newPeer(core)) {
            JsonNode r = MAPPER.readTree(peer.osdPayload());

            List<String> actual = new ArrayList<>();
            r.fieldNames().forEachRemaining(actual::add);
            assertThat(actual).containsExactlyInAnyOrderElementsOf(OFFICIAL_OSD_FIELDS);

            JsonNode data = r.get("data");
            assertThat(data.has("mode_code")).isTrue();
            assertThat(data.has("environment_temperature")).isTrue();
            assertThat(data.has("drone_charge_state")).isTrue();
            assertThat(data.get("drone_charge_state").has("capacity_percent")).isTrue();
            assertThat(data.get("drone_charge_state").has("state")).isTrue();
            assertThat(data.has("drone_in_dock")).isTrue();
            assertThat(data.has("putter_state")).isTrue();
            assertThat(data.has("alarm_state")).isTrue();
        } finally {
            core.close();
        }
    }

    @Test
    @DisplayName("osd 反映舱盖状态：打开=1 / 过渡=2(半开) / 关闭=0（官方 putter_state 枚举）")
    void osdReflectsPutterState() throws Exception {
        DockSimCore core = new DockSimCore(9, null);
        try (DockSimMqttPeer peer = newPeer(core)) {
            core.forceState("IDLE");
            assertThat(MAPPER.readTree(peer.osdPayload()).get("data").get("putter_state").asInt())
                    .isZero();

            core.forceState("OPENING");
            assertThat(MAPPER.readTree(peer.osdPayload()).get("data").get("putter_state").asInt())
                    .isEqualTo(2);

            core.forceState("OPEN");
            assertThat(MAPPER.readTree(peer.osdPayload()).get("data").get("putter_state").asInt())
                    .isEqualTo(1);
        } finally {
            core.close();
        }
    }

    @Test
    @DisplayName("osd 里的整数属性必须是**整型节点**（0 不是 0.0——对端按 isIntegralNumber 判定）")
    void osdIntegerPropertiesAreIntegralNodes() throws Exception {
        DockSimCore core = new DockSimCore(9, null);
        try (DockSimMqttPeer peer = newPeer(core)) {
            JsonNode data = MAPPER.readTree(peer.osdPayload()).get("data");

            assertThat(data.get("mode_code").isIntegralNumber()).isTrue();
            assertThat(data.get("putter_state").isIntegralNumber()).isTrue();
            assertThat(data.get("drone_in_dock").isIntegralNumber()).isTrue();
            assertThat(data.get("alarm_state").isIntegralNumber()).isTrue();
            assertThat(data.get("drone_charge_state").get("state").isIntegralNumber()).isTrue();
            assertThat(data.get("drone_charge_state").get("capacity_percent").isIntegralNumber())
                    .isTrue();
            // 温度是 float，保留小数（不是整型节点）
            assertThat(data.get("environment_temperature").isNumber()).isTrue();
        } finally {
            core.close();
        }
    }

    @Test
    @DisplayName("osd 反映充电状态：CHARGING → drone_charge_state.state=1")
    void osdReflectsChargeState() throws Exception {
        DockSimCore core = new DockSimCore(9, null);
        try (DockSimMqttPeer peer = newPeer(core)) {
            core.forceState("CHARGING");
            JsonNode data = MAPPER.readTree(peer.osdPayload()).get("data");

            assertThat(data.get("drone_charge_state").get("state").asInt()).isEqualTo(1);
            assertThat(data.get("drone_in_dock").asInt()).isEqualTo(1);
        } finally {
            core.close();
        }
    }

    @Test
    @DisplayName("两条传输共用同一份状态机语义（HTTP 对端与 MQTT 对端不打架）")
    void httpAndMqttPeersShareCoreSemantics() throws Exception {
        DockSimCore core = new DockSimCore(9, null);
        try (DockSimMqttPeer peer = newPeer(core);
             DockSim http = new DockSim(SN, "共享语义", 0, null, 60_000, 9, null)) {
            // HTTP 对端走自己的 core（独立实例），MQTT 对端走共享 core：
            // 两者对同一 method 的**判定结果**必须一致（否则两条 e2e 断言会互相打架）
            http.forceState("OPEN");
            core.forceState("OPEN");

            JsonNode r = reply(peer, "door_open");
            assertThat(r.get("data").get("result").asInt()).isEqualTo(1);
            assertThat(http.applyMethod("door_open")).contains("\"result\":1");
            assertThat(http.applyMethod("door_open")).contains("state=OPEN");
        } finally {
            core.close();
        }
    }
}

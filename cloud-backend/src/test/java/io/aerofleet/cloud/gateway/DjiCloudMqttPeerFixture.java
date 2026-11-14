package io.aerofleet.cloud.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * DJI Cloud API MQTT 协议级对端模拟夹具（无真实 Broker/EMQX）。
 * <p>
 * 用途：在单测中扮演“设备/机巢侧”对端——捕获云端下发的 services 帧，
 * 并按场景回放/注入官方形状的 {@code services_reply} 消息到网关回调。
 * 适用于协议适配与软件逻辑回归（非真机/非 EMQX 联调）。
 */
final class DjiCloudMqttPeerFixture {

    static final ObjectMapper MAPPER = new ObjectMapper();

    record Published(String topic, int qos, String payload) {
        JsonNode json() throws Exception {
            return MAPPER.readTree(payload);
        }
    }

    private final IMqttAsyncClient client;
    private final MqttCallbackExtended[] callbackOut = new MqttCallbackExtended[1];
    private final List<Published> published = new CopyOnWriteArrayList<>();

    private DjiCloudMqttPeerFixture(IMqttAsyncClient client) {
        this.client = client;
    }

    static DjiCloudMqttPeerFixture create() {
        IMqttAsyncClient client = mock(IMqttAsyncClient.class);
        DjiCloudMqttPeerFixture fx = new DjiCloudMqttPeerFixture(client);
        doAnswer(inv -> {
            fx.callbackOut[0] = inv.getArgument(0);
            return null;
        }).when(client).setCallback(any());
        stubPublish(client, inv -> {
            String topic = inv.getArgument(0);
            MqttMessage msg = inv.getArgument(1);
            fx.published.add(new Published(topic, msg.getQos(),
                    new String(msg.getPayload(), StandardCharsets.UTF_8)));
            return null;
        });
        return fx;
    }

    @SuppressWarnings("unchecked")
    private static void stubPublish(IMqttAsyncClient client, org.mockito.stubbing.Answer<?> answer) {
        try {
            doAnswer(answer).when(client).publish(anyString(), any(MqttMessage.class));
        } catch (MqttException e) {
            throw new AssertionError(e);
        }
    }

    IMqttAsyncClient client() { return client; }

    MqttCallbackExtended callback() { return callbackOut[0]; }

    /** 模拟 broker 连接完成并触发订阅。 */
    void connectComplete(String serverUri) {
        callback().connectComplete(false, serverUri);
    }

    List<Published> published() { return Collections.unmodifiableList(new ArrayList<>(published)); }

    Published lastPublished() {
        if (published.isEmpty()) throw new IllegalStateException("no published message");
        return published.get(published.size() - 1);
    }

    /** 从最近一帧 services 取 tid。 */
    String lastTid() throws Exception {
        return lastPublished().json().get("tid").asText();
    }

    /** 按发布序号取 tid（0-based）。 */
    String tidAt(int index) throws Exception {
        return published.get(index).json().get("tid").asText();
    }

    /** 投递一条官方形状 services_reply（topic: thing/product/{sn}/services_reply）。 */
    void deliverServicesReply(String sn, String tid, String bid, String method, int result,
                              Map<String, Object> output) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("tid", tid);
        root.put("bid", bid == null ? "b-" + tid : bid);
        root.put("timestamp", System.currentTimeMillis());
        root.put("method", method);
        ObjectNode data = root.putObject("data");
        data.put("result", result);
        data.set("output", output == null ? MAPPER.createObjectNode() : MAPPER.valueToTree(output));
        String payload = MAPPER.writeValueAsString(root);
        try {
            callback().messageArrived("thing/product/" + sn + "/services_reply",
                    new MqttMessage(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    void deliverServicesReply(String sn, String tid, int result) throws Exception {
        deliverServicesReply(sn, tid, "b-" + tid, DjiCloudApiDeviceGateway.FLIGHT_TASK_METHOD,
                result, Map.of());
    }

    /** 投递原始 payload（用于 malformed/非 JSON 等回放场景）。 */
    void deliverRaw(String topic, String payload) {
        try {
            callback().messageArrived(topic,
                    new MqttMessage(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** publish 时自动回固定 result（按每次发布帧 tid 关联），模拟“设备即时应答”。 */
    void autoReply(int result) {
        stubPublish(client, inv -> {
            try {
                String topic = inv.getArgument(0);
                MqttMessage msg = inv.getArgument(1);
                String payload = new String(msg.getPayload(), StandardCharsets.UTF_8);
                published.add(new Published(topic, msg.getQos(), payload));
                String tid = MAPPER.readTree(payload).get("tid").asText();
                String[] parts = topic.split("/");
                String sn = parts.length >= 3 ? parts[2] : "UNKNOWN";
                deliverServicesReply(sn, tid, result);
                return null;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** publish 时按序列回放 result（超出序列长度则不回，模拟超时）。 */
    void autoReplySequence(int... results) {
        final int[] idx = {0};
        stubPublish(client, inv -> {
            try {
                String topic = inv.getArgument(0);
                MqttMessage msg = inv.getArgument(1);
                String payload = new String(msg.getPayload(), StandardCharsets.UTF_8);
                published.add(new Published(topic, msg.getQos(), payload));
                int i = idx[0]++;
                if (i < results.length) {
                    String tid = MAPPER.readTree(payload).get("tid").asText();
                    String[] parts = topic.split("/");
                    String sn = parts.length >= 3 ? parts[2] : "UNKNOWN";
                    deliverServicesReply(sn, tid, results[i]);
                }
                return null;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** 构造一条官方形状 OSD 帧 payload（用于回放到监听器/解析场景）。 */
    static String osdPayload(String sn, Map<String, Object> dataProps) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("tid", UUID.randomUUID().toString());
        root.put("bid", UUID.randomUUID().toString());
        root.put("timestamp", System.currentTimeMillis());
        root.put("gateway", sn);
        root.set("data", MAPPER.valueToTree(dataProps == null ? Map.of() : dataProps));
        return MAPPER.writeValueAsString(root);
    }

    /** 让 publish 抛异常（模拟 broker 断链/不可达）。 */
    void failPublish(MqttException ex) {
        stubPublish(client, inv -> { throw ex; });
    }
}
package io.aerofleet.cloud.dock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DJI Cloud API 帧形状契约测试（官方字段表驱动）。
 *
 * <p>字段表逐行来自官方文档仓库 {@code dji-sdk/Cloud-API-Doc}（MIT）v1.18：
 * {@code docs/cn/60.api-reference/20.dock-to-cloud/00.mqtt/00.topic-definition.md}
 * <ul>
 *   <li>公共字段（L31-36）：{@code tid}=事务uuid(text)、{@code bid}=业务uuid(text)、
 *       {@code timestamp}=毫秒时间戳(int)、{@code gateway}=网关设备序列号(text)、
 *       {@code data}=消息内容(object)</li>
 *   <li>services 帧（L182-189）：{@code {tid,bid,timestamp,gateway,method,data}}</li>
 *   <li>services_reply 帧（L199-210）：
 *       {@code {tid,bid,timestamp,gateway,method,data:{result(int),output(struct)}}}</li>
 * </ul>
 *
 * <p>本类把上表编码成断言：字段集<b>恰好相等</b>（多一个少一个都红）、
 * 类型逐项校验（tid/bid 必须是文本 UUID、timestamp 必须是整数）、
 * 应答只认官方 {@code data.output} 字段位（旧实现的 {@code data.message} 位不算数）。
 */
@DisplayName("DJI Cloud API 帧形状契约：官方字段表逐项对齐")
class DockDjiFrameContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SN = "DOCK-DJI-001";
    private static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    /** 官方 services 帧字段集（公共字段 + method + data）。 */
    private static final Set<String> OFFICIAL_SERVICES_FIELDS =
            Set.of("tid", "bid", "timestamp", "gateway", "method", "data");
    /** 官方 services_reply 帧字段集。 */
    private static final Set<String> OFFICIAL_REPLY_FIELDS =
            Set.of("tid", "bid", "timestamp", "gateway", "method", "data");

    private IMqttAsyncClient client;
    private MqttCallbackExtended callback;
    private final AtomicReference<JsonNode> published = new AtomicReference<>();
    private MqttDockGateway gw;

    private MqttDockGateway newGateway(int timeoutMs) throws Exception {
        client = mock(IMqttAsyncClient.class);
        when(client.isConnected()).thenReturn(true);
        AtomicReference<MqttCallbackExtended> cb = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(inv -> {
            cb.set(inv.getArgument(0));
            return null;
        }).when(client).setCallback(any());
        org.mockito.Mockito.doAnswer(inv -> {
            MqttMessage msg = inv.getArgument(1, MqttMessage.class);
            published.set(MAPPER.readTree(msg.getPayload()));
            return null;
        }).when(client).publish(anyString(), any(MqttMessage.class));

        DockProperties props = new DockProperties();
        props.setTransport("mqtt");
        props.setMqttBrokerUrl("tcp://127.0.0.1:1883");
        props.setCommandTimeoutMs(timeoutMs);
        gw = new MqttDockGateway(MAPPER, props, client);
        gw.start();
        callback = cb.get();
        return gw;
    }

    /** 投递官方形状的 services_reply（data.output 为设备消息 struct）。 */
    private void deliverOfficialReply(String tid, int result, String outputJson) throws Exception {
        String payload = "{\"tid\":\"" + tid + "\","
                + "\"bid\":\"11111111-2222-3333-4444-555555555555\","
                + "\"timestamp\":" + System.currentTimeMillis() + ","
                + "\"gateway\":\"" + SN + "\","
                + "\"method\":\"door_open\","
                + "\"data\":{\"result\":" + result + ",\"output\":" + outputJson + "}}";
        callback.messageArrived("thing/product/" + SN + "/services_reply",
                new MqttMessage(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("services 帧：字段集恰好等于官方字段表（多/少字段都红）")
    void servicesFrameFieldSetMatchesOfficialTable() throws Exception {
        newGateway(2000);
        // publish 时就地回一份官方应答，让 sendCommand 正常收尾
        org.mockito.Mockito.doAnswer(inv -> {
            MqttMessage msg = inv.getArgument(1, MqttMessage.class);
            JsonNode frame = MAPPER.readTree(msg.getPayload());
            published.set(frame);
            deliverOfficialReply(frame.get("tid").asText(), 0, "{}");
            return null;
        }).when(client).publish(anyString(), any(MqttMessage.class));

        gw.sendCommand(SN, DockCommand.OPEN_DOOR, Map.of("holdSec", 3));

        List<String> actual = new ArrayList<>();
        published.get().fieldNames().forEachRemaining(actual::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(OFFICIAL_SERVICES_FIELDS);
    }

    @Test
    @DisplayName("services 帧：字段类型逐项对齐官方表（tid/bid=文本 UUID，timestamp=整数）")
    void servicesFrameFieldTypesMatchOfficialTable() throws Exception {
        newGateway(2000);
        org.mockito.Mockito.doAnswer(inv -> {
            MqttMessage msg = inv.getArgument(1, MqttMessage.class);
            JsonNode frame = MAPPER.readTree(msg.getPayload());
            published.set(frame);
            deliverOfficialReply(frame.get("tid").asText(), 0, "{}");
            return null;
        }).when(client).publish(anyString(), any(MqttMessage.class));

        gw.sendCommand(SN, DockCommand.OPEN_DOOR, Map.of("holdSec", 3));

        JsonNode frame = published.get();
        assertThat(frame.get("tid").isTextual()).isTrue();
        assertThat(frame.get("tid").asText()).matches(UUID_RE);
        assertThat(frame.get("bid").isTextual()).isTrue();
        assertThat(frame.get("bid").asText()).matches(UUID_RE);
        assertThat(frame.get("timestamp").isIntegralNumber()).isTrue();
        assertThat(frame.get("timestamp").asLong()).isPositive();
        // gateway = 网关设备（机巢）序列号
        assertThat(frame.get("gateway").asText()).isEqualTo(SN);
        assertThat(frame.get("method").isTextual()).isTrue();
        assertThat(frame.get("data").isObject()).isTrue();
    }

    @Test
    @DisplayName("services_reply：官方形状解析（result=0 成功，设备消息取自 data.output）")
    void officialReplyIsParsed() throws Exception {
        newGateway(2000);
        final String[] tidOut = new String[1];
        org.mockito.Mockito.doAnswer(inv -> {
            MqttMessage msg = inv.getArgument(1, MqttMessage.class);
            tidOut[0] = MAPPER.readTree(msg.getPayload()).get("tid").asText();
            deliverOfficialReply(tidOut[0], 0, "{\"message\":\"door opened\"}");
            return null;
        }).when(client).publish(anyString(), any(MqttMessage.class));

        DockGateway.DockReply reply = gw.sendCommand(SN, DockCommand.OPEN_DOOR, Map.of());

        assertThat(reply.ok()).isTrue();
        assertThat(reply.result()).isZero();
        assertThat(reply.tid()).isEqualTo(tidOut[0]);
        assertThat(reply.bid()).isEqualTo("11111111-2222-3333-4444-555555555555");
        assertThat(reply.message()).contains("door opened");
    }

    @Test
    @DisplayName("services_reply：data 无 output 时不编造设备消息（message=null）")
    void replyWithoutOutputDoesNotFabricateMessage() throws Exception {
        newGateway(2000);
        org.mockito.Mockito.doAnswer(inv -> {
            MqttMessage msg = inv.getArgument(1, MqttMessage.class);
            String tid = MAPPER.readTree(msg.getPayload()).get("tid").asText();
            String payload = "{\"tid\":\"" + tid + "\",\"bid\":\"b\",\"timestamp\":1,"
                    + "\"gateway\":\"" + SN + "\",\"method\":\"door_open\","
                    + "\"data\":{\"result\":0}}";
            callback.messageArrived("thing/product/" + SN + "/services_reply",
                    new MqttMessage(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return null;
        }).when(client).publish(anyString(), any(MqttMessage.class));

        DockGateway.DockReply reply = gw.sendCommand(SN, DockCommand.OPEN_DOOR, Map.of());

        assertThat(reply.ok()).isTrue();
        assertThat(reply.message()).isNull();
    }

    @Test
    @DisplayName("services_reply：旧位 data.message 不是官方字段，不作为设备消息读取")
    void legacyDataMessageFieldIsNotOfficialAndNotParsed() throws Exception {
        newGateway(2000);
        org.mockito.Mockito.doAnswer(inv -> {
            MqttMessage msg = inv.getArgument(1, MqttMessage.class);
            String tid = MAPPER.readTree(msg.getPayload()).get("tid").asText();
            String payload = "{\"tid\":\"" + tid + "\",\"bid\":\"b\",\"timestamp\":1,"
                    + "\"gateway\":\"" + SN + "\",\"method\":\"door_open\","
                    + "\"data\":{\"result\":3,\"message\":\"legacy field\"}}";
            callback.messageArrived("thing/product/" + SN + "/services_reply",
                    new MqttMessage(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return null;
        }).when(client).publish(anyString(), any(MqttMessage.class));

        DockGateway.DockReply reply = gw.sendCommand(SN, DockCommand.OPEN_DOOR, Map.of());

        assertThat(reply.ok()).isFalse();
        assertThat(reply.result()).isEqualTo(3);
        // 只认官方 data.output 位：旧位不采信、不回显
        assertThat(reply.message()).isNull();
    }

    @Test
    @DisplayName("services_reply：tid 不匹配的命令不被关联（超时按自建异常收尾）")
    void replyWithMismatchedTidDoesNotCompleteCommand() throws Exception {
        newGateway(200);
        org.mockito.Mockito.doAnswer(inv -> {
            MqttMessage msg = inv.getArgument(1, MqttMessage.class);
            String tid = MAPPER.readTree(msg.getPayload()).get("tid").asText();
            deliverOfficialReply(tid + "-other", 0, "{}");
            return null;
        }).when(client).publish(anyString(), any(MqttMessage.class));

        assertThatThrownBy(() -> gw.sendCommand(SN, DockCommand.OPEN_DOOR, Map.of()))
                .isInstanceOf(DockGateway.DockGatewayException.class)
                .hasMessageContaining("timed out after 200ms");
    }

    @Test
    @DisplayName("services_reply：数字 tid 不被当成文本键关联（官方 tid 是 UUID text）")
    void numericTidReplyDoesNotCorrelate() throws Exception {
        newGateway(200);
        org.mockito.Mockito.doAnswer(inv -> {
            MqttMessage msg = inv.getArgument(1, MqttMessage.class);
            String payload = "{\"tid\":123,\"bid\":456,\"timestamp\":1,"
                    + "\"gateway\":\"" + SN + "\",\"method\":\"door_open\","
                    + "\"data\":{\"result\":0,\"output\":{}}}";
            callback.messageArrived("thing/product/" + SN + "/services_reply",
                    new MqttMessage(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return null;
        }).when(client).publish(anyString(), any(MqttMessage.class));

        assertThatThrownBy(() -> gw.sendCommand(SN, DockCommand.OPEN_DOOR, Map.of()))
                .isInstanceOf(DockGateway.DockGatewayException.class)
                .hasMessageContaining("timed out after 200ms");
    }
}

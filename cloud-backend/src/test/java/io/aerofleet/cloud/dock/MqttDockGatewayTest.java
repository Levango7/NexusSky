package io.aerofleet.cloud.dock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MqttDockGateway 单测（fake IMqttAsyncClient，CI 无 Broker）。
 * <p>
 * 验证：发布 topic 与物模型帧形状（tid/bid/timestamp/method/data）、命令-应答按 tid 关联、
 * 超时/发布失败/断连的自建异常消息（不粘底层文本，#248/#250 纪律）、
 * transport=mqtt 且 broker-url 空缺的启动 fail-fast。
 */
@DisplayName("MqttDockGateway：物模型帧形状 + tid 关联 + 自建异常消息")
class MqttDockGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** fake client：捕获 callback 与 publish，测试手动投递 services_reply。 */
    private record FakeClient(IMqttAsyncClient client, MqttCallbackExtended[] callbackOut) {
    }

    private FakeClient fakeClient(boolean connected) throws Exception {
        IMqttAsyncClient client = mock(IMqttAsyncClient.class);
        when(client.isConnected()).thenReturn(connected);
        MqttCallbackExtended[] cbOut = new MqttCallbackExtended[1];
        org.mockito.Mockito.doAnswer(inv -> {
            cbOut[0] = inv.getArgument(0);
            return null;
        }).when(client).setCallback(any());
        return new FakeClient(client, cbOut);
    }

    private MqttDockGateway newGateway(FakeClient fake, int timeoutMs) {
        DockProperties props = new DockProperties();
        props.setTransport("mqtt");
        props.setMqttBrokerUrl("tcp://127.0.0.1:1883");
        props.setCommandTimeoutMs(timeoutMs);
        return new MqttDockGateway(MAPPER, props, fake.client());
    }

    /** 模拟机巢回 services_reply（DJI 形状：tid/bid 为 UUID 文本，设备消息在 data.output）。 */
    private static void deliverReply(MqttCallbackExtended cb, String tid, int result, String message)
            throws Exception {
        String payload = MAPPER.writeValueAsString(Map.of(
                "tid", tid, "bid", "b-" + tid, "timestamp", System.currentTimeMillis(),
                "method", "door_open",
                "data", Map.of("result", result, "output", Map.of("message", message))));
        cb.messageArrived("thing/product/SN001/services_reply", new MqttMessage(payload.getBytes()));
    }

    @Test
    @DisplayName("sendCommand：发布到 thing/product/{sn}/services，帧形状与物模型一致")
    void publishesThingModelFrameShape() throws Exception {
        FakeClient fake = fakeClient(true);
        MqttDockGateway gw = newGateway(fake, 2000);
        gw.start();

        // 发布后由 mock 投一份 ok reply，让 sendCommand 正常返回（本用例只验发布形状）
        org.mockito.Mockito.doAnswer(inv -> {
            MqttMessage msg = inv.getArgument(1);
            String tid = MAPPER.readTree(msg.getPayload()).get("tid").asText();
            deliverReply(fake.callbackOut()[0], tid, 0, "ok");
            return null;
        }).when(fake.client()).publish(anyString(), any(MqttMessage.class));

        gw.sendCommand("SN001", DockCommand.OPEN_DOOR, Map.of("holdSec", 5));

        org.mockito.ArgumentCaptor<MqttMessage> msgCap =
                org.mockito.ArgumentCaptor.forClass(MqttMessage.class);
        verify(fake.client()).publish(contains("SN001"), msgCap.capture());
        assertThat(msgCap.getValue().getQos()).isEqualTo(1);
        JsonNode frame = MAPPER.readTree(msgCap.getValue().getPayload());
        // DJI 公共字段表：tid/bid 是 UUID 文本
        assertThat(frame.get("tid").asText()).matches("[0-9a-f-]{36}");
        assertThat(frame.get("bid").asText()).matches("[0-9a-f-]{36}");
        assertThat(frame.get("timestamp").asLong()).isGreaterThan(0);
        assertThat(frame.get("method").asText()).isEqualTo(DockCommand.OPEN_DOOR.method());
        assertThat(frame.get("data").get("holdSec").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("services_reply 按 tid 关联：result=0 → ok 应答")
    void replyCorrelatesByTid() throws Exception {
        FakeClient fake = fakeClient(true);
        MqttDockGateway gw = newGateway(fake, 2000);
        gw.start();
        MqttCallbackExtended cb = fake.callbackOut()[0];
        cb.connectComplete(false, "tcp://127.0.0.1:1883");

        // 先发命令拿 tid（mock publish 直接成功），再从 payload 里取 tid 回 reply
        final String[] tidOut = new String[1];
        org.mockito.Mockito.doAnswer(inv -> {
            MqttMessage msg = inv.getArgument(1);
            tidOut[0] = MAPPER.readTree(msg.getPayload()).get("tid").asText();
            deliverReply(cb, tidOut[0], 0, "ok");
            return null;
        }).when(fake.client()).publish(anyString(), any(MqttMessage.class));

        DockGateway.DockReply reply = gw.sendCommand("SN001", DockCommand.OPEN_DOOR, Map.of());

        assertThat(reply.ok()).isTrue();
        assertThat(reply.tid()).isEqualTo(tidOut[0]);
        assertThat(reply.result()).isZero();
        verify(fake.client()).subscribe(MqttDockGateway.SERVICES_REPLY_FILTER, 1);
    }

    @Test
    @DisplayName("机巢拒绝（result!=0）→ 返回应答而非异常，result 如实带回")
    void dockRejectionCarriesResult() throws Exception {
        FakeClient fake = fakeClient(true);
        MqttDockGateway gw = newGateway(fake, 2000);
        gw.start();
        MqttCallbackExtended cb = fake.callbackOut()[0];
        cb.connectComplete(false, "tcp://127.0.0.1:1883");
        org.mockito.Mockito.doAnswer(inv -> {
            MqttMessage msg = inv.getArgument(1);
            String tid = MAPPER.readTree(msg.getPayload()).get("tid").asText();
            deliverReply(cb, tid, 3, "dock busy");
            return null;
        }).when(fake.client()).publish(anyString(), any(MqttMessage.class));

        DockGateway.DockReply reply = gw.sendCommand("SN001", DockCommand.OPEN_DOOR, Map.of());

        assertThat(reply.ok()).isFalse();
        assertThat(reply.result()).isEqualTo(3);
        // DJI 应答的设备消息在 data.output（本 fake 把 message 放进 output.message）
        assertThat(reply.message()).contains("dock busy");
    }

    @Test
    @DisplayName("超时 → 自建短句异常（不含底层文本）")
    void timeoutThrowsAuthoredMessage() throws Exception {
        FakeClient fake = fakeClient(true);
        MqttDockGateway gw = newGateway(fake, 80);
        gw.start();
        fake.callbackOut()[0].connectComplete(false, "tcp://127.0.0.1:1883");

        assertThatThrownBy(() -> gw.sendCommand("SN001", DockCommand.OPEN_DOOR, Map.of()))
                .isInstanceOf(DockGateway.DockGatewayException.class)
                .hasMessageContaining("timed out after 80ms")
                .hasMessageNotContaining("org.eclipse");
    }

    @Test
    @DisplayName("publish 失败 → 自建短句 + cause 保留（不外泄 Broker 细节）")
    void publishFailureKeepsCauseAndAuthoredMessage() throws Exception {
        FakeClient fake = fakeClient(true);
        MqttDockGateway gw = newGateway(fake, 2000);
        gw.start();
        org.mockito.Mockito.doThrow(new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED))
                .when(fake.client()).publish(anyString(), any(MqttMessage.class));

        assertThatThrownBy(() -> gw.sendCommand("SN001", DockCommand.OPEN_DOOR, Map.of()))
                .isInstanceOf(DockGateway.DockGatewayException.class)
                .hasMessage("dock command publish failed")
                .hasMessageNotContaining("32110")
                .hasCauseInstanceOf(MqttException.class);
    }

    @Test
    @DisplayName("断连 → 在等命令立即以自建异常失败，不挂死")
    void connectionLostFailsPendingCommands() throws Exception {
        FakeClient fake = fakeClient(true);
        MqttDockGateway gw = newGateway(fake, 5000);
        gw.start();
        MqttCallbackExtended cb = fake.callbackOut()[0];
        cb.connectComplete(false, "tcp://127.0.0.1:1883");

        // 不投递 reply，直接断连
        Thread sender = new Thread(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(50);
                cb.connectionLost(new MqttException(MqttException.REASON_CODE_CLIENT_DISCONNECTING));
            } catch (Exception ignored) {
            }
        });
        sender.start();

        assertThatThrownBy(() -> gw.sendCommand("SN001", DockCommand.OPEN_DOOR, Map.of()))
                .isInstanceOf(DockGateway.DockGatewayException.class)
                .hasMessage("dock mqtt connection lost");
        sender.join();
    }

    @Test
    @DisplayName("transport=mqtt 且 broker-url 空缺 → 启动 fail-fast")
    void blankBrokerUrlFailsFast() {
        DockProperties props = new DockProperties();
        props.setTransport("mqtt");
        props.setMqttBrokerUrl("");
        MqttDockGateway gw = new MqttDockGateway(MAPPER, props, null);

        assertThatThrownBy(() -> gw.start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mqtt-broker-url is required");
    }

    @Test
    @DisplayName("transportName=mqtt（状态展示与测试断言用）")
    void transportNameIsMqtt() throws Exception {
        FakeClient fake = fakeClient(true);
        assertThat(newGateway(fake, 1000).transportName()).isEqualTo("mqtt");
    }
}

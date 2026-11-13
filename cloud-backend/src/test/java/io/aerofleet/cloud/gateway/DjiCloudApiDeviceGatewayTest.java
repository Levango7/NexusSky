package io.aerofleet.cloud.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.dock.DockEntity;
import io.aerofleet.cloud.dock.DockRepository;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DJI Cloud API 设备网关的单元测试（fake MQTT client + mock DockRepository，CI 无 Broker）。
 * <p>
 * 钉住 P1-10 的诚实边界（任何一条缺了都在生产上静默假成功）：
 * <ul>
 *   <li>命令映射只覆盖 takeoff/land/rtl/hover 子集；未映射（含 NAV_WAYPOINT）→ false 且不发布；</li>
 *   <li>sysid 无机巢绑定 → false 且不发布；</li>
 *   <li>帧形状 {tid, bid, timestamp, gateway=机巢SN, method=flight_task, data{taskId, action[, targetAltitude]}}；</li>
 *   <li>真值回复（data.result）按 tid 关联：result=0 → true；非 0 → false；超时 → false；</li>
 *   <li>broker 地址缺失 → 启动 fail-fast。</li>
 * </ul>
 */
@DisplayName("DjiCloudApiDeviceGateway：flight_task 子集映射 + tid 关联 + fail-closed")
class DjiCloudApiDeviceGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record FakeClient(IMqttAsyncClient client, MqttCallbackExtended[] callbackOut) {
    }

    private static FakeClient fakeClient() throws Exception {
        IMqttAsyncClient client = mock(IMqttAsyncClient.class);
        MqttCallbackExtended[] cbOut = new MqttCallbackExtended[1];
        org.mockito.Mockito.doAnswer(inv -> {
            cbOut[0] = inv.getArgument(0);
            return null;
        }).when(client).setCallback(any());
        return new FakeClient(client, cbOut);
    }

    private static DockRepository docksReturning(String sn) {
        DockRepository docks = mock(DockRepository.class);
        DockEntity d = new DockEntity();
        d.sn = sn;
        d.droneSysid = 7;
        // 单一 answer 按值路由——多个同型 stub 时后声明的会覆盖先声明的，别踩坑
        lenient().when(docks.findByDroneSysid(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> (int) inv.getArgument(0) == 7 ? Optional.of(d) : Optional.empty());
        return docks;
    }

    private static DockRepository docksNone() {
        DockRepository docks = mock(DockRepository.class);
        lenient().when(docks.findByDroneSysid(org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.empty());
        return docks;
    }

    private static DjiCloudApiProperties props(boolean enabled, String broker, int timeoutMs) {
        DjiCloudApiProperties p = new DjiCloudApiProperties();
        p.setEnabled(enabled);
        p.setMqttBrokerUrl(broker);
        p.setCommandTimeoutMs(timeoutMs);
        return p;
    }

    private static DjiCloudApiDeviceGateway newGateway(DockRepository docks,
                                                       DjiCloudApiProperties props,
                                                       FakeClient fake) throws Exception {
        DjiCloudApiDeviceGateway gw = new DjiCloudApiDeviceGateway(props, MAPPER, docks, fake.client());
        gw.start();
        return gw;
    }

    /** 把一条 services_reply 投到 fake 的 callback（DJI 形状）。 */
    private static void deliverReply(MqttCallbackExtended cb, String tid, int result) throws Exception {
        String payload = MAPPER.writeValueAsString(Map.of(
                "tid", tid, "bid", "b-" + tid, "timestamp", System.currentTimeMillis(),
                "method", DjiCloudApiDeviceGateway.FLIGHT_TASK_METHOD,
                "data", Map.of("result", result, "output", Map.of("taskId", "ignored"))));
        cb.messageArrived("thing/product/SN001/services_reply",
                new MqttMessage(payload.getBytes()));
    }

    /**
     * 让 fake publish 立即拿 payload 里的 tid 投一条 result=0 的 reply，
     * 使 send() 走通 tid 关联路径（只验证映射时也可以用它）。
     */
    private static void publishAutoReply(FakeClient fake, int result) throws Exception {
        org.mockito.Mockito.doAnswer(inv -> {
            MqttMessage msg = inv.getArgument(1);
            String tid = MAPPER.readTree(msg.getPayload()).get("tid").asText();
            deliverReply(fake.callbackOut()[0], tid, result);
            return null;
        }).when(fake.client()).publish(anyString(), any(MqttMessage.class));
    }

    @Test
    @DisplayName("起飞命令：method=flight_task、action=takeoff、targetAltitude=p7=120、gateway=机巢 SN")
    void takeoffMappedAndFrameShape() throws Exception {
        FakeClient fake = fakeClient();
        DockRepository docks = docksReturning("SN001");
        DjiCloudApiDeviceGateway gw = newGateway(docks, props(true, "tcp://127.0.0.1:1883", 2000), fake);
        fake.callbackOut()[0].connectComplete(false, "tcp://127.0.0.1:1883");
        publishAutoReply(fake, 0);

        boolean ok = gw.send(7, 22, 0f, 0f, 0f, 0f, 0f, 0f, 120f);

        assertThat(ok).isTrue();
        org.mockito.Mockito.verify(fake.client()).publish(contains("SN001"), any());
        ArgumentCaptor<MqttMessage> cap = ArgumentCaptor.forClass(MqttMessage.class);
        org.mockito.Mockito.verify(fake.client()).publish(contains("SN001"), cap.capture());
        assertThat(cap.getValue().getQos()).isEqualTo(1);
        JsonNode frame = MAPPER.readTree(cap.getValue().getPayload());
        assertThat(frame.get("tid").asText()).matches("[0-9a-f-]{36}");
        assertThat(frame.get("bid").asText()).matches("[0-9a-f-]{36}");
        assertThat(frame.get("timestamp").asLong()).isGreaterThan(0);
        assertThat(frame.get("gateway").asText()).isEqualTo("SN001");
        assertThat(frame.get("method").asText()).isEqualTo(DjiCloudApiDeviceGateway.FLIGHT_TASK_METHOD);
        JsonNode data = frame.get("data");
        assertThat(data.get("action").asText()).isEqualTo("takeoff");
        assertThat(data.get("taskId").asText()).matches("[0-9a-f-]{36}");
        assertThat(data.get("targetAltitude").asDouble()).isEqualTo(120.0);
    }

    @Test
    @DisplayName("降落/返航/悬停：action 映射正确，且不带 targetAltitude")
    void landRtlHoverMapping() throws Exception {
        FakeClient fake = fakeClient();
        DockRepository docks = docksReturning("SN001");
        DjiCloudApiDeviceGateway gw = newGateway(docks, props(true, "tcp://127.0.0.1:1883", 2000), fake);
        fake.callbackOut()[0].connectComplete(false, "tcp://127.0.0.1:1883");
        publishAutoReply(fake, 0);

        for (int cmd : new int[]{21, 20, 17, 19}) {
            boolean ok = gw.send(7, cmd, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
            assertThat(ok).as("mavCommand=%d", cmd).isTrue();
        }

        ArgumentCaptor<MqttMessage> cap = ArgumentCaptor.forClass(MqttMessage.class);
        org.mockito.Mockito.verify(fake.client(), org.mockito.Mockito.times(4))
                .publish(contains("SN001"), cap.capture());
        String[] actions = new String[4];
        JsonNode[] all = new JsonNode[4];
        int i = 0;
        for (MqttMessage msg : cap.getAllValues()) {
            actions[i] = MAPPER.readTree(msg.getPayload()).get("data").get("action").asText();
            all[i] = MAPPER.readTree(msg.getPayload()).get("data");
            i++;
        }
        assertThat(actions).containsExactly("land", "rtl", "hover", "hover");
        for (JsonNode d : all) {
            assertThat(d.has("targetAltitude")).isFalse();
        }
    }

    @Test
    @DisplayName("未映射的命令（含 NAV_WAYPOINT=16）：返回 false 且不发 MQTT")
    void unmappedCommandNeverPublishes() throws Exception {
        FakeClient fake = fakeClient();
        DockRepository docks = docksReturning("SN001");
        DjiCloudApiDeviceGateway gw = newGateway(docks, props(true, "tcp://127.0.0.1:1883", 2000), fake);
        fake.callbackOut()[0].connectComplete(false, "tcp://127.0.0.1:1883");

        for (int cmd : new int[]{16, 4000, 0, 84, 410}) {
            boolean ok = gw.send(7, cmd, 1f, 2f, 3f, 4f, 5f, 6f, 7f);
            assertThat(ok).as("mavCommand=%d", cmd).isFalse();
        }
        org.mockito.Mockito.verify(fake.client(), never()).publish(anyString(), any(MqttMessage.class));
    }

    @Test
    @DisplayName("sysid 无机巢绑定：返回 false 且不发 MQTT")
    void unknownSysidNeverPublishes() throws Exception {
        FakeClient fake = fakeClient();
        DockRepository docks = docksNone();
        DjiCloudApiDeviceGateway gw = newGateway(docks, props(true, "tcp://127.0.0.1:1883", 2000), fake);
        fake.callbackOut()[0].connectComplete(false, "tcp://127.0.0.1:1883");

        boolean ok = gw.send(42, 22, 0f, 0f, 0f, 0f, 0f, 0f, 100f);

        assertThat(ok).isFalse();
        org.mockito.Mockito.verify(fake.client(), never()).publish(anyString(), any(MqttMessage.class));
    }

    @Test
    @DisplayName("设备应答 data.result≠0（拒绝）→ false；result=0 → true")
    void resultCorrelation() throws Exception {
        for (int result : new int[]{0, 7}) {
            FakeClient fake = fakeClient();
            DockRepository docks = docksReturning("SN001");
            DjiCloudApiDeviceGateway gw = newGateway(docks,
                    props(true, "tcp://127.0.0.1:1883", 2000), fake);
            fake.callbackOut()[0].connectComplete(false, "tcp://127.0.0.1:1883");
            publishAutoReply(fake, result);

            boolean ok = gw.send(7, 21, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
            assertThat(ok).as("result=%d", result).isEqualTo(result == 0);
        }
    }

    @Test
    @DisplayName("机巢/broker 不应答：超时返回 false，不挂死调用方")
    void timeoutReturnsFalse() throws Exception {
        FakeClient fake = fakeClient();
        DockRepository docks = docksReturning("SN001");
        DjiCloudApiDeviceGateway gw = newGateway(docks, props(true, "tcp://127.0.0.1:1883", 250), fake);
        fake.callbackOut()[0].connectComplete(false, "tcp://127.0.0.1:1883");
        // 注意：不 reply —— 走真实超时路径
        boolean ok = gw.send(7, 21, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
        assertThat(ok).isFalse();
    }

    @Test
    @DisplayName("publish 抛异常（Broker 断链）：返回 false，不抛穿 SPI 边界")
    void publishFailureReturnsFalse() throws Exception {
        FakeClient fake = fakeClient();
        DockRepository docks = docksReturning("SN001");
        DjiCloudApiDeviceGateway gw = newGateway(docks, props(true, "tcp://127.0.0.1:1883", 2000), fake);
        fake.callbackOut()[0].connectComplete(false, "tcp://127.0.0.1:1883");
        org.mockito.Mockito.doThrow(new MqttException(0x7))
                .when(fake.client()).publish(anyString(), any(MqttMessage.class));

        boolean ok = gw.send(7, 22, 0f, 0f, 0f, 0f, 0f, 0f, 50f);
        assertThat(ok).isFalse();
    }

    @Test
    @DisplayName("enabled=true 但 broker-url 空缺：start() fail-fast（与 dock transport=mqtt 同口径）")
    void missingBrokerFailsFast() throws Exception {
        FakeClient fake = fakeClient();
        DjiCloudApiDeviceGateway gw = new DjiCloudApiDeviceGateway(
                props(true, "", 1000), MAPPER, docksReturning("SN001"), fake.client());
        assertThatThrownBy(gw::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("actionFor 枚举：17/19→hover，20→rtl，21→land，22→takeoff，其余 null")
    void actionForTable() {
        assertThat(DjiCloudApiDeviceGateway.actionFor(17)).isEqualTo("hover");
        assertThat(DjiCloudApiDeviceGateway.actionFor(19)).isEqualTo("hover");
        assertThat(DjiCloudApiDeviceGateway.actionFor(20)).isEqualTo("rtl");
        assertThat(DjiCloudApiDeviceGateway.actionFor(21)).isEqualTo("land");
        assertThat(DjiCloudApiDeviceGateway.actionFor(22)).isEqualTo("takeoff");
        assertThat(DjiCloudApiDeviceGateway.actionFor(16)).isNull();
        assertThat(DjiCloudApiDeviceGateway.actionFor(85)).isNull();
    }
}

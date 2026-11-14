package io.aerofleet.cloud.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import io.aerofleet.cloud.dock.DockEntity;
import io.aerofleet.cloud.dock.DockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * DJI Cloud API flight_task 协议级场景模拟（无真实 Broker/EMQX，非真机）。
 * <p>
 * 通过 {@link DjiCloudMqttPeerFixture} 扮演“机巢/设备侧”对端，回放 services_reply
 * 等官方形状消息，验证云端网关在正常/拒绝/异常序列下的协议适配与软件逻辑。
 * 证明范围：协议处理与命令关联语义；不证明真实 DJI 设备/EMQX 全链路与硬件行为。
 */
@DisplayName("DJI Cloud API flight_task 协议级场景模拟（无 Broker/非真机）")
class DjiCloudMqttScenarioSimulationTest {

    private static DockRepository docksReturning(String sn) {
        DockRepository docks = mock(DockRepository.class);
        DockEntity d = new DockEntity();
        d.sn = sn;
        d.droneSysid = 7;
        lenient().when(docks.findByDroneSysid(anyInt()))
                .thenAnswer(inv -> (int) inv.getArgument(0) == 7 ? Optional.of(d) : Optional.empty());
        return docks;
    }

    private static DjiCloudApiProperties props(int timeoutMs) {
        DjiCloudApiProperties p = new DjiCloudApiProperties();
        p.setEnabled(true);
        p.setMqttBrokerUrl("tcp://127.0.0.1:1883");
        p.setCommandTimeoutMs(timeoutMs);
        return p;
    }

    @Test
    @DisplayName("场景：takeoff 下发 → 设备按 tid 回 result=0 → 命令被接受且帧/主题符合物模型")
    void takeoffAcceptedBySimulatedDevice() throws Exception {
        DjiCloudMqttPeerFixture peer = DjiCloudMqttPeerFixture.create();
        DjiCloudApiDeviceGateway gw = new DjiCloudApiDeviceGateway(props(2000),
                DjiCloudMqttPeerFixture.MAPPER, docksReturning("SN001"), peer.client());
        gw.start();
        peer.connectComplete("tcp://127.0.0.1:1883");
        peer.autoReply(0);

        boolean ok = gw.send(7, 22, 0f, 0f, 0f, 0f, 0f, 0f, 120f);

        assertThat(ok).isTrue();
        assertThat(peer.published()).hasSize(1);
        DjiCloudMqttPeerFixture.Published pub = peer.lastPublished();
        assertThat(pub.topic()).isEqualTo("thing/product/SN001/services");
        assertThat(pub.qos()).isEqualTo(1);
        JsonNode frame = pub.json();
        assertThat(frame.get("gateway").asText()).isEqualTo("SN001");
        assertThat(frame.get("method").asText()).isEqualTo(DjiCloudApiDeviceGateway.FLIGHT_TASK_METHOD);
        assertThat(frame.get("data").get("action").asText()).isEqualTo("takeoff");
        assertThat(frame.get("data").get("targetAltitude").asDouble()).isEqualTo(120.0);
        assertThat(frame.get("tid").asText()).isNotBlank();
        assertThat(frame.get("bid").asText()).isNotBlank();
    }

    @Test
    @DisplayName("场景：设备按 tid 回 result!=0（拒绝）→ 网关如实返回 false，不假成功")
    void deviceRejectsFlightTask() throws Exception {
        DjiCloudMqttPeerFixture peer = DjiCloudMqttPeerFixture.create();
        DjiCloudApiDeviceGateway gw = new DjiCloudApiDeviceGateway(props(2000),
                DjiCloudMqttPeerFixture.MAPPER, docksReturning("SN001"), peer.client());
        gw.start();
        peer.connectComplete("tcp://127.0.0.1:1883");
        peer.autoReply(314001);

        boolean ok = gw.send(7, 21, 0f, 0f, 0f, 0f, 0f, 0f, 0f);

        assertThat(ok).isFalse();
        assertThat(peer.published()).hasSize(1);
        assertThat(peer.lastPublished().json().get("data").get("action").asText()).isEqualTo("land");
    }

    @Test
    @DisplayName("场景：连续指令序列（hover→rtl）分别按各自 tid 关联应答")
    void sequentialCommandsCorrelatedByTid() throws Exception {
        DjiCloudMqttPeerFixture peer = DjiCloudMqttPeerFixture.create();
        DjiCloudApiDeviceGateway gw = new DjiCloudApiDeviceGateway(props(2000),
                DjiCloudMqttPeerFixture.MAPPER, docksReturning("SN001"), peer.client());
        gw.start();
        peer.connectComplete("tcp://127.0.0.1:1883");
        peer.autoReplySequence(0, 0);

        assertThat(gw.send(7, 17, 0f, 0f, 0f, 0f, 0f, 0f, 0f)).isTrue();
        assertThat(gw.send(7, 20, 0f, 0f, 0f, 0f, 0f, 0f, 0f)).isTrue();

        assertThat(peer.published()).hasSize(2);
        assertThat(peer.published().get(0).json().get("data").get("action").asText()).isEqualTo("hover");
        assertThat(peer.published().get(1).json().get("data").get("action").asText()).isEqualTo("rtl");
        assertThat(peer.tidAt(0)).isNotEqualTo(peer.tidAt(1));
    }

    @Test
    @DisplayName("场景：先回放未知 tid/畸形回复不干扰，随后正确 tid 的 result=0 被接受")
    void unknownOrMalformedReplyIgnoredThenValidAccepted() throws Exception {
        DjiCloudMqttPeerFixture peer = DjiCloudMqttPeerFixture.create();
        DjiCloudApiDeviceGateway gw = new DjiCloudApiDeviceGateway(props(1500),
                DjiCloudMqttPeerFixture.MAPPER, docksReturning("SN001"), peer.client());
        gw.start();
        peer.connectComplete("tcp://127.0.0.1:1883");

        // 发布时注入干扰消息（未知 tid + 畸形 payload），再回正确 tid
        java.util.List<DjiCloudMqttPeerFixture.Published> pubs = new java.util.concurrent.CopyOnWriteArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            String topic = inv.getArgument(0);
            org.eclipse.paho.client.mqttv3.MqttMessage msg = inv.getArgument(1);
            String payload = new String(msg.getPayload(), java.nio.charset.StandardCharsets.UTF_8);
            pubs.add(new DjiCloudMqttPeerFixture.Published(topic, msg.getQos(), payload));
            String tid = DjiCloudMqttPeerFixture.MAPPER.readTree(payload).get("tid").asText();
            peer.deliverServicesReply("SN001", "00000000-0000-0000-0000-000000000000", 0);
            peer.deliverRaw("thing/product/SN001/services_reply", "{not-json");
            peer.deliverServicesReply("SN001", tid, 0);
            return null;
        }).when(peer.client()).publish(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());

        boolean ok = gw.send(7, 22, 0f, 0f, 0f, 0f, 0f, 0f, 50f);

        assertThat(ok).isTrue();
        assertThat(pubs).hasSize(1);
        assertThat(DjiCloudMqttPeerFixture.MAPPER.readTree(pubs.get(0).payload())
                .get("data").get("action").asText()).isEqualTo("takeoff");
    }
}
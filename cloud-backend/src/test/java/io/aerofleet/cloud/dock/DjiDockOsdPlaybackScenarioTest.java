package io.aerofleet.cloud.dock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 机巢 OSD 协议级回放场景（无真实 Broker/EMQX，非真机）。
 * <p>
 * 按 DJI Cloud API 物模型构造官方形状 data 子集，回放到
 * {@link MqttDockOsdListener#ingest}，验证状态/温度/电量映射与同一摄入路径行为。
 * 证明范围：协议消息解析与软件映射逻辑；不证明真实机场硬件行为。
 */
@DisplayName("Dock OSD 协议级回放场景（无 Broker/非真机）")
class DjiDockOsdPlaybackScenarioTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode data(Map<String, Object> props) throws Exception {
        return MAPPER.valueToTree(props);
    }

    @Test
    @DisplayName("场景：设备上报开舱(putter_state=1)+温度/电量 → 映射为 OPEN 且遥测如实传递")
    void openDockTelemetryPlayback() throws Exception {
        DockService docks = mock(DockService.class);
        MqttDockOsdListener listener = new MqttDockOsdListener(MAPPER, new DockProperties(), docks);

        listener.ingest("DOCK-001", data(Map.of(
                "putter_state", 1,
                "environment_temperature", 28.5,
                "drone_charge_state", Map.of("state", 0, "capacity_percent", 86))));

        verify(docks).ingestOsd(eq("DOCK-001"), eq(DockState.OPEN), eq(28.5), eq(86), isNull());
    }

    @Test
    @DisplayName("场景：充电中(drone_charge_state.state=1)优先级高于舱盖状态")
    void chargingPlaybackTakesPrecedence() throws Exception {
        DockService docks = mock(DockService.class);
        MqttDockOsdListener listener = new MqttDockOsdListener(MAPPER, new DockProperties(), docks);

        listener.ingest("DOCK-001", data(Map.of(
                "putter_state", 0,
                "drone_charge_state", Map.of("state", 1, "capacity_percent", 42))));

        verify(docks).ingestOsd(eq("DOCK-001"), eq(DockState.CHARGING), isNull(), eq(42), isNull());
    }

    @Test
    @DisplayName("场景：半开过渡态(putter_state=2)不强推状态（交由云端状态机）")
    void halfOpenPlaybackDoesNotForceState() throws Exception {
        DockService docks = mock(DockService.class);
        MqttDockOsdListener listener = new MqttDockOsdListener(MAPPER, new DockProperties(), docks);

        listener.ingest("DOCK-001", data(Map.of("putter_state", 2)));

        verify(docks).ingestOsd(eq("DOCK-001"), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("场景：topic 解析与未注册 SN 容错（回调不被打断）")
    void topicAndUnknownSnPlayback() throws Exception {
        assertThat(MqttDockOsdListener.snFromTopic("thing/product/DOCK-X/osd")).isEqualTo("DOCK-X");
        DockService docks = mock(DockService.class);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("unknown dock sn DOCK-X"))
                .when(docks).ingestOsd(eq("DOCK-X"), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        MqttDockOsdListener listener = new MqttDockOsdListener(MAPPER, new DockProperties(), docks);

        assertThatCode(() -> listener.ingest("DOCK-X", data(Map.of("putter_state", 1))))
                .doesNotThrowAnyException();
    }
}
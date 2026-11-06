package io.aerofleet.cloud.dock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MQTT OSD 摄入监听器测试（CI 无 Broker：直接喂官方形状的 osd 帧）。
 *
 * <p>映射依据：dji-sdk/Cloud-API-Doc v1.18 dock2 properties
 * （{@code drone_charge_state.state} 0 空闲/1 充电中、{@code putter_state}
 * 0 关闭/1 打开/2 半开、{@code environment_temperature}、{@code capacity_percent}）。
 */
@DisplayName("MqttDockOsdListener：官方 osd 属性 → 本系统 OSD 语义")
class MqttDockOsdListenerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode data(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    @DisplayName("topic 形如 thing/product/{sn}/osd → 取出机巢 SN")
    void snExtractedFromTopic() {
        assertThat(MqttDockOsdListener.snFromTopic("thing/product/DOCK-001/osd"))
                .isEqualTo("DOCK-001");
        assertThat(MqttDockOsdListener.snFromTopic("thing/product/DOCK-001/state")).isNotNull();
        assertThat(MqttDockOsdListener.snFromTopic("malformed")).isNull();
    }

    @Test
    @DisplayName("充电中优先于舱盖状态：drone_charge_state.state=1 → CHARGING")
    void chargingTakesPrecedence() throws Exception {
        JsonNode d = data("{\"drone_charge_state\":{\"state\":1,\"capacity_percent\":40},"
                + "\"putter_state\":0}");
        assertThat(MqttDockOsdListener.mapState(d)).isEqualTo(DockState.CHARGING);
    }

    @Test
    @DisplayName("舱盖打开 → OPEN；舱盖关闭 → IDLE（官方 putter_state 枚举）")
    void putterStateMapsToOpenAndIdle() throws Exception {
        assertThat(MqttDockOsdListener.mapState(data("{\"putter_state\":1}")))
                .isEqualTo(DockState.OPEN);
        assertThat(MqttDockOsdListener.mapState(data("{\"putter_state\":0}")))
                .isEqualTo(DockState.IDLE);
    }

    @Test
    @DisplayName("半开（过渡态）不强制状态：null（过渡态由云端状态机自己管）")
    void halfOpenDoesNotForceState() throws Exception {
        assertThat(MqttDockOsdListener.mapState(data("{\"putter_state\":2}"))).isNull();
        assertThat(MqttDockOsdListener.mapState(data("{}"))).isNull();
    }

    @Test
    @DisplayName("设备把枚举发成浮点也认（putter_state=1.0 → OPEN，不因节点类型漏判）")
    void floatingPointEnumStillMaps() throws Exception {
        assertThat(MqttDockOsdListener.mapState(data("{\"putter_state\":1.0}")))
                .isEqualTo(DockState.OPEN);
        assertThat(MqttDockOsdListener.mapState(data("{\"putter_state\":0.0}")))
                .isEqualTo(DockState.IDLE);
        assertThat(MqttDockOsdListener.mapState(
                data("{\"drone_charge_state\":{\"state\":1.0},\"putter_state\":0}")))
                .isEqualTo(DockState.CHARGING);
    }

    @Test
    @DisplayName("官方属性 → 同一条摄入路径（温度/电量/状态如实传给 DockService）")
    void officialPropertiesReachDockService() throws Exception {
        DockService docks = mock(DockService.class);
        MqttDockOsdListener listener = new MqttDockOsdListener(MAPPER, new DockProperties(), docks);

        listener.ingest("DOCK-001", data("{\"environment_temperature\":31.5,"
                + "\"drone_charge_state\":{\"state\":0,\"capacity_percent\":77},"
                + "\"putter_state\":1,\"mode_code\":0}"));

        verify(docks).ingestOsd(eq("DOCK-001"), eq(DockState.OPEN), eq(31.5), eq(77), isNull());
    }

    @Test
    @DisplayName("未注册的机巢 SN：只吞掉不抛（MQTT 回调线程不能被一条脏数据打断）")
    void unregisteredSnDoesNotThrow() throws Exception {
        DockService docks = mock(DockService.class);
        when(docks.ingestOsd(anyString(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("unknown dock sn NOPE"));
        MqttDockOsdListener listener = new MqttDockOsdListener(MAPPER, new DockProperties(), docks);

        assertThatCode(() -> listener.ingest("NOPE", data("{\"putter_state\":1}")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("电量缺失时不编造（capacity_percent 缺 ⇒ null）")
    void missingBatteryNotFabricated() throws Exception {
        DockService docks = mock(DockService.class);
        MqttDockOsdListener listener = new MqttDockOsdListener(MAPPER, new DockProperties(), docks);

        listener.ingest("DOCK-001", data("{\"drone_charge_state\":{\"state\":0},\"putter_state\":0}"));

        verify(docks).ingestOsd(eq("DOCK-001"), eq(DockState.IDLE), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("温度阈值裁决沿用同一条路径（临界温度 → FAULT 由 DockService 决定）")
    void temperatureHandledByDockService() throws Exception {
        DockService docks = mock(DockService.class);
        MqttDockOsdListener listener = new MqttDockOsdListener(MAPPER, new DockProperties(), docks);

        listener.ingest("DOCK-001", data("{\"environment_temperature\":75.0,\"putter_state\":0}"));

        verify(docks).ingestOsd(eq("DOCK-001"), eq(DockState.IDLE), eq(75.0), isNull(), isNull());
    }
}

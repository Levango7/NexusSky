package io.aerofleet.cloud.dock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 机巢 OSD 的 MQTT 摄入通道（DJI 设备侧上报面）。
 *
 * <p>DJI 机巢把属性推到 {@code thing/product/{device_sn}/osd}（0.5Hz 定频），
 * 帧形状按官方公共字段表 {@code {tid,bid,timestamp,gateway,data}}，{@code data} 内是
 * 官方属性名。本监听器订阅该 topic，把**已核对的官方属性子集**映射成本系统的 OSD
 * 语义并交给 {@link DockService#ingestOsd}——与 HTTP {@code POST /api/v1/docks/osd}
 * 走同一条摄入路径（同一套状态机裁决、同一套温度阈值）。
 *
 * <p>映射依据（dji-sdk/Cloud-API-Doc v1.18 dock2 properties，全为 accessMode=r 只读）：
 * <ul>
 *   <li>{@code drone_charge_state.state}：0 空闲 / 1 充电中 → {@link DockState#CHARGING}</li>
 *   <li>{@code putter_state}：0 关闭 / 1 打开 / 2 半开 → {@link DockState#OPEN} /
 *       {@link DockState#IDLE} / 半开不强制（过渡态由云端自己管）</li>
 *   <li>{@code environment_temperature} → 温度 ℃</li>
 *   <li>{@code drone_charge_state.capacity_percent} → 电量 %</li>
 * </ul>
 *
 * <p>未映射的官方属性（{@code mode_code} 等）**不臆造语义**——只保留原帧，不参与裁决。
 * 未注册的机巢 SN 只记首现告警（哨兵上界），不抛异常打断 MQTT 回调线程。
 */
@Component
@ConditionalOnProperty(name = "aerofleet.dock.transport", havingValue = "mqtt")
public class MqttDockOsdListener implements MqttCallbackExtended {

    private static final Logger log = LoggerFactory.getLogger(MqttDockOsdListener.class);

    /** OSD 上报 topic 过滤（全部机巢）。 */
    static final String OSD_FILTER = "thing/product/+/osd";
    /** 未注册 SN 的首现告警哨兵上界（防只增表）。 */
    private static final int MAX_WARNED_UNKNOWN_SNS = 256;

    private final ObjectMapper mapper;
    private final DockProperties props;
    private final DockService docks;
    private final Map<String, Boolean> warnedUnknownSns = new ConcurrentHashMap<>();

    private IMqttAsyncClient client;

    public MqttDockOsdListener(ObjectMapper mapper, DockProperties props, DockService docks) {
        this.mapper = mapper;
        this.props = props;
        this.docks = docks;
    }

    /** 建连 + 订阅 OSD topic（连接异步建立，失败不影响命令通道）。 */
    @PostConstruct
    void start() throws MqttException {
        if (props.getMqttBrokerUrl() == null || props.getMqttBrokerUrl().isBlank()) {
            // transport=mqtt 且 broker-url 空缺：命令通道已 fail-fast，这里同样不静默
            throw new IllegalStateException(
                    "aerofleet.dock.mqtt-broker-url is required when aerofleet.dock.transport=mqtt");
        }
        String clientId = "aerofleet-dock-osd-" + Long.toHexString(System.nanoTime());
        MqttAsyncClient async = new MqttAsyncClient(props.getMqttBrokerUrl(),
                clientId, new MemoryPersistence());
        async.setCallback(this);
        async.connect(connectOptions());
        this.client = async;
        log.info("dock mqtt osd listener: broker={}, clientId={}", props.getMqttBrokerUrl(), clientId);
    }

    private MqttConnectOptions connectOptions() {
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setAutomaticReconnect(true);
        opts.setCleanSession(true);
        opts.setConnectionTimeout(Math.max(1, props.getCommandTimeoutMs() / 1000));
        opts.setKeepAliveInterval(30);
        if (props.getMqttUsername() != null && !props.getMqttUsername().isBlank()) {
            opts.setUserName(props.getMqttUsername());
            if (props.getMqttPassword() != null) {
                opts.setPassword(props.getMqttPassword().toCharArray());
            }
        }
        return opts;
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        try {
            client.subscribe(OSD_FILTER, 1);
            log.info("dock mqtt osd subscribed {} (reconnect={})", OSD_FILTER, reconnect);
        } catch (MqttException e) {
            log.warn("dock mqtt osd subscribe failed", e);
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            if (topic == null || !topic.endsWith("/osd")) {
                return;
            }
            JsonNode frame = mapper.readTree(message.getPayload());
            JsonNode data = frame.path("data");
            if (!data.isObject()) {
                return;
            }
            String sn = snFromTopic(topic);
            if (sn == null || sn.isBlank()) {
                return;
            }
            ingest(sn, data);
        } catch (Exception e) {
            // 单条 OSD 解析失败只丢该条：日志 + 不崩回调线程
            log.warn("dock mqtt osd parse failed on topic={}", topic, e);
        }
    }

    /** topic 形如 {@code thing/product/{sn}/osd}。 */
    static String snFromTopic(String topic) {
        String[] parts = topic.split("/");
        if (parts.length < 4) {
            return null;
        }
        return parts[2];
    }

    /** 官方属性子集 → 本系统 OSD 语义 → 同一条摄入路径。 */
    void ingest(String sn, JsonNode data) {
        DockState reported = mapState(data);
        Double temperature = data.has("environment_temperature")
                ? data.get("environment_temperature").asDouble()
                : null;
        Integer battery = null;
        JsonNode charge = data.path("drone_charge_state");
        if (charge.isObject() && charge.has("capacity_percent")) {
            battery = charge.get("capacity_percent").asInt();
        }
        try {
            docks.ingestOsd(sn, reported, temperature, battery, null);
        } catch (IllegalArgumentException e) {
            // 未注册的机巢 SN：只记首现告警（哨兵上界），不打断回调线程
            if (warnedUnknownSns.size() < MAX_WARNED_UNKNOWN_SNS
                    && warnedUnknownSns.putIfAbsent(sn, Boolean.TRUE) == null) {
                log.warn("dock mqtt osd for unregistered sn={}（首现告警）", sn);
            }
        }
    }

    /**
     * 官方属性 → 本系统状态。
     *
     * <p>优先级：充电中 > 舱盖打开 > 舱盖关闭；半开（过渡态）不强制状态。
     */
    static DockState mapState(JsonNode data) {
        JsonNode charge = data.path("drone_charge_state");
        if (charge.isObject() && charge.path("state").asInt(0) == 1) {
            return DockState.CHARGING;
        }
        JsonNode putter = data.path("putter_state");
        if (putter.isNumber()) {
            // 真实设备可能把枚举发成浮点（0.0/1.0）——按数值取值，不强求整型节点
            int p = putter.asInt();
            if (p == 1) {
                return DockState.OPEN;
            }
            if (p == 0) {
                return DockState.IDLE;
            }
            // p==2 半开 / p==3 异常：过渡态由云端状态机自己管，不从 OSD 强推
            return null;
        }
        return null;
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("dock mqtt osd listener connection lost", cause);
    }

    @Override
    public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
        // 上报是单向的，交付事件不用于业务判定
    }

    @PreDestroy
    void stop() {
        if (client != null) {
            try {
                client.disconnect().waitForCompletion(2000);
                client.close();
            } catch (MqttException e) {
                log.warn("dock mqtt osd listener close failed", e);
            }
        }
    }
}

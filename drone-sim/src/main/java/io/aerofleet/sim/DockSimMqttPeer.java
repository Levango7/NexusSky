package io.aerofleet.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 机巢 MQTT 对端（设备侧替身）：按 DJI Cloud API 物模型接 broker。
 *
 * <p>对端方向（相对云端）：
 * <ul>
 *   <li>订阅 {@code thing/product/{sn}/services}（云 → 设备：服务调用）</li>
 *   <li>回 {@code thing/product/{sn}/services_reply}（设备 → 云：应答，
 *       形状 {@code {tid,bid,timestamp,gateway,method,data:{result,output}}}）</li>
 *   <li>定频推 {@code thing/product/{sn}/osd}（设备 → 云：属性上报，
 *       0.5Hz 官方口径；帧形状同公共字段表，data 内为 DJI 官方属性名子集）</li>
 * </ul>
 *
 * <p>命令语义与 {@link DockSimCore} 共用（HTTP 回环对端同一份状态机），
 * 差异只在传输层。帧字段按官方文档逐字段对齐：
 * {@code dji-sdk/Cloud-API-Doc} v1.18
 * {@code 60.api-reference/20.dock-to-cloud/00.mqtt/00.topic-definition.md}。
 */
public final class DockSimMqttPeer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 云 → 设备：服务调用 topic 模板。 */
    static final String SERVICES_TOPIC = "thing/product/%s/services";
    /** 设备 → 云：服务应答 topic 模板。 */
    static final String SERVICES_REPLY_TOPIC = "thing/product/%s/services_reply";
    /** 设备 → 云：属性上报 topic 模板。 */
    static final String OSD_TOPIC = "thing/product/%s/osd";

    private final String sn;
    private final DockSimCore core;
    private final String brokerUrl;
    private final String username;
    private final String password;
    private final long osdPeriodMs;

    private MqttClient client;
    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "dock-sim-mqtt-timer");
        t.setDaemon(true);
        return t;
    });

    public DockSimMqttPeer(String sn, DockSimCore core, String brokerUrl,
                           String username, String password, long osdPeriodMs) {
        this.sn = sn;
        this.core = core;
        this.brokerUrl = brokerUrl;
        this.username = username;
        this.password = password;
        this.osdPeriodMs = Math.max(200, osdPeriodMs);
        core.bindSn(sn);
    }

    /** 连 broker + 订阅 services + 起 OSD 定时推送。 */
    public void start() throws MqttException {
        client = new MqttClient(brokerUrl, "dock-sim-" + sn + "-" + Long.toHexString(System.nanoTime()),
                new MemoryPersistence());
        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                try {
                    client.subscribe(String.format(SERVICES_TOPIC, sn), 1);
                    SimLog.info("dock-sim-mqtt subscribed " + String.format(SERVICES_TOPIC, sn));
                } catch (MqttException e) {
                    SimLog.warn("dock-sim-mqtt subscribe failed: " + e.getMessage());
                }
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                String reply = replyPayload(payload);
                if (reply == null) {
                    return;
                }
                try {
                    client.publish(String.format(SERVICES_REPLY_TOPIC, sn),
                            new MqttMessage(reply.getBytes(StandardCharsets.UTF_8)));
                } catch (MqttException e) {
                    SimLog.warn("dock-sim-mqtt reply publish failed: " + e.getMessage());
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // 交付事件不用于业务判定
            }

            @Override
            public void connectionLost(Throwable cause) {
                SimLog.warn("dock-sim-mqtt connection lost: " + cause);
            }
        });

        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setAutomaticReconnect(true);
        opts.setCleanSession(true);
        opts.setConnectionTimeout(10);
        if (username != null && !username.isBlank()) {
            opts.setUserName(username);
            if (password != null) {
                opts.setPassword(password.toCharArray());
            }
        }
        client.connect(opts);
        SimLog.info("dock-sim-mqtt connected broker=" + brokerUrl + " sn=" + sn);
        timer.scheduleAtFixedRate(this::publishOsd, osdPeriodMs, osdPeriodMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 由云端 services 帧生成官方形状的 services_reply 载荷。
     *
     * @return 应答 JSON；请求帧不可解析时返回 null（不造假应答）
     */
    String replyPayload(String requestPayload) {
        JsonNode req;
        try {
            req = MAPPER.readTree(requestPayload);
        } catch (Exception e) {
            SimLog.warn("dock-sim-mqtt bad services payload: " + e.getMessage());
            return null;
        }
        String tid = req.path("tid").asText("");
        String bid = req.path("bid").asText("");
        String method = req.path("method").asText("");
        if (method.isBlank()) {
            // 无 method：按官方错误语义回 result=1（设备消息自建，不粘底层文本）
            return buildReply(tid, bid, "", new DockSimCore.Result(1, "missing method"));
        }
        return buildReply(tid, bid, method, core.apply(method));
    }

    private String buildReply(String tid, String bid, String method, DockSimCore.Result res) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("tid", tid);
        root.put("bid", bid);
        root.put("timestamp", System.currentTimeMillis());
        root.put("gateway", sn);
        root.put("method", method);
        ObjectNode data = root.putObject("data");
        data.put("result", res.result());
        ObjectNode output = data.putObject("output");
        if (res.message() != null) {
            output.put("message", res.message());
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            SimLog.warn("dock-sim-mqtt reply serialize failed: " + e.getMessage());
            return null;
        }
    }

    /** OSD 帧（官方公共字段 + DJI 官方属性名子集）。 */
    String osdPayload() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("tid", UUID.randomUUID().toString());
        root.put("bid", UUID.randomUUID().toString());
        root.put("timestamp", System.currentTimeMillis());
        root.put("gateway", sn);
        ObjectNode data = root.putObject("data");
        for (Map.Entry<String, Object> e : core.osdProperties().entrySet()) {
            putValue(data, e.getKey(), e.getValue());
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            SimLog.warn("dock-sim-mqtt osd serialize failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 写属性值：官方物模型里 struct 属性是**嵌套对象**（如 drone_charge_state），
     * 直接 String.valueOf 会把 struct 拍平成字符串——这里递归写。
     */
    private static void putValue(ObjectNode target, String key, Object v) {
        if (v instanceof Number num) {
            double d = num.doubleValue();
            // 注意：不能写成 `d == rint ? num.intValue() : d`——三元表达式会把 int/double
            // 两个分支统一提升成 double，结果所有整数属性都变成 0.0 这类浮点节点，
            // 对端按 isIntegralNumber() 判定时会全部落空（真 broker 联调抓出）。
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) <= Integer.MAX_VALUE) {
                target.put(key, num.intValue());
            } else {
                target.put(key, d);
            }
        } else if (v instanceof Map<?, ?> map) {
            ObjectNode nested = target.putObject(key);
            for (Map.Entry<?, ?> me : map.entrySet()) {
                putValue(nested, String.valueOf(me.getKey()), me.getValue());
            }
        } else if (v instanceof Boolean b) {
            target.put(key, b);
        } else {
            target.put(key, String.valueOf(v));
        }
    }

    private void publishOsd() {
        if (client == null || !client.isConnected()) {
            return;
        }
        String payload = osdPayload();
        if (payload == null) {
            return;
        }
        try {
            client.publish(String.format(OSD_TOPIC, sn),
                    new MqttMessage(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (MqttException e) {
            SimLog.warn("dock-sim-mqtt osd publish failed: " + e.getMessage());
        }
    }

    /** 设备侧当前状态（e2e 断言用）。 */
    public String state() {
        return core.state();
    }

    @Override
    public void close() {
        timer.shutdownNow();
        if (client != null) {
            try {
                if (client.isConnected()) {
                    client.disconnectForcibly(1000, 1000);
                }
                client.close(true);
            } catch (MqttException e) {
                SimLog.warn("dock-sim-mqtt close failed: " + e.getMessage());
            }
        }
        core.close();
    }
}

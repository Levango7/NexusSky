package io.aerofleet.cloud.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.dock.DockEntity;
import io.aerofleet.cloud.dock.DockRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * DJI Cloud API 栈的 {@link DeviceGateway} 实现（P1-10 双栈网关第 2 栈）。
 * <p>
 * 架构口径（与 DJI 官方"机巢作网关下发 flight 命令到无人机"一致）：
 * 本网关不把 MAVLink 帧直接伪装成物模型——它把 SPI 命令 {@code (mavCommand, p1..p7)}
 * 映射为 <b>flight_task 服务</b>的 {@code action}，payload 经
 * {@code thing/product/{dock-sn}/services}（机巢作网关）下发。
 * <ul>
 *   <li>SYSID → 机巢绑定：{@code docks.drone_sysid}（机巢托管的无人机 sysid），
 *       查不到或机巢无绑 SN 一律如实拒绝（false），不猜测、不编 "SN"。</li>
 *   <li>映射只覆盖 <b>DjThingModelFieldTable flight_task 已 VERIFIED 的 action 子集</b>：
 *       takeoff(22) / land(21) / rtl(20) / hover(17=19=loiter)。</li>
 *   <li>其他一切 mavCommand（含 NAV_WAYPOINT=16：MAVLink 段 p4..p6 是本地 m 坐标，
 *       与 DJI go_to 的 WGS-84 语义不一致，直接投会静默错误，故不映射）→
 *       记 WARN 并返回 false（不假成功）。</li>
 *   <li>{@code takeoff} 透传 {@code targetAltitude=p7}（MAVLink NAV_TAKEOFF p7=高度 m）；
 *       land/rtl/hover 无 target 目标字段，不带垃圾参数。</li>
 * </ul>
 * 帧形状按 DJI Cloud API 物模型逐字段对齐（同 F2 {@code MqttDockGateway}）：
 * 下行 {@code {tid, bid, timestamp, gateway, method:"flight_task", data:{taskId, action, …}}}，
 * 应答 {@code {tid, bid, timestamp, gateway, method, data:{result, output}}}；
 * tid/bid 为 UUID 文本；按 tid 关联；QoS 1。
 * <p>
 * 装配：{@code aerofleet.dji-cloud.enabled=true} 时启用；{@code mqtt-broker-url} 空缺
 * <b>启动即 fail-fast</b>（与 dock transport=mqtt 同一口径）。未装配时本栈缺席——
 * {@code CommandRouter} 按协议拒绝命令（WARN + false），不假装受理。
 * <p>
 * 真实设备联调仍缺（PoC 口径，须用真 EMQX + 真机巢 + 真无人机验证 result=0；
 * 本实现做到的是「真协议栈驱动 + 命令映射 fail-closed + 物模型帧形状」）。
 */
@Component
@ConditionalOnProperty(name = "aerofleet.dji-cloud.enabled", havingValue = "true")
public class DjiCloudApiDeviceGateway implements DeviceGateway, MqttCallbackExtended {

    private static final Logger log = LoggerFactory.getLogger(DjiCloudApiDeviceGateway.class);

    /** 下行 services topic 模板（{sn} 为机巢序列号）。 */
    static final String SERVICES_TOPIC = "thing/product/%s/services";
    /** 应答订阅过滤（机巢服务应答；按 tid 分发）。 */
    static final String SERVICES_REPLY_FILTER = "thing/product/+/services_reply";
    /** objective：flight_task 服务名（DjThingModelService.FLIGHT_TASK）。 */
    static final String FLIGHT_TASK_METHOD = "flight_task";
    /** 未知 tid 哨兵告警上界。 */
    private static final int MAX_WARNED_UNKNOWN_TIDS = 256;

    private static final int MAV_CMD_NAV_LOITER_UNLIMITED = 17;
    private static final int MAV_CMD_NAV_LOITER_TIME = 19;
    private static final int MAV_CMD_NAV_RETURN_TO_LAUNCH = 20;
    private static final int MAV_CMD_NAV_LAND = 21;
    private static final int MAV_CMD_NAV_TAKEOFF = 22;

    private final DockRepository docks;
    private final ObjectMapper mapper;
    private final DjiCloudApiProperties props;
    private final Map<String, CompletableFuture<Integer>> pending = new ConcurrentHashMap<>();
    private final Map<String, Boolean> warnedUnknownTids = new ConcurrentHashMap<>();

    /** 生产路径由 {@link #start()} 创建；测试可注入 fake（包私有构造）。 */
    private IMqttAsyncClient client;

    /**
     * 生产构造器（Spring 注入）。显式 {@code @Autowired}：
     * 本类有第二个构造器（包私有 fake 注入），无标注则 Spring 退找无参构造器→起不来
     * （与 {@code MqttDockGateway} 同一坑，见其 javadoc）。
     */
    @Autowired
    public DjiCloudApiDeviceGateway(DjiCloudApiProperties props, ObjectMapper mapper,
                                    DockRepository docks) {
        this(props, mapper, docks, null);
    }

    DjiCloudApiDeviceGateway(DjiCloudApiProperties props, ObjectMapper mapper,
                             DockRepository docks, IMqttAsyncClient injectedClient) {
        this.props = props;
        this.mapper = mapper;
        this.docks = docks;
        this.client = injectedClient;
    }

    /** 配置校验 + 建连（异步）。enabled=true 且 broker 地址空缺 ⇒ 启动 fail-fast。 */
    @PostConstruct
    void start() throws Exception {
        if (props.getMqttBrokerUrl() == null || props.getMqttBrokerUrl().isBlank()) {
            throw new IllegalStateException(
                    "aerofleet.dji-cloud.mqtt-broker-url is required when aerofleet.dji-cloud.enabled=true");
        }
        if (client != null) {
            // 测试注入：连接由测试自行管理，callback 照常注册
            client.setCallback(this);
            return;
        }
        String clientId = "aerofleet-dji-cloud-" + Long.toHexString(System.nanoTime());
        MqttAsyncClient async = new MqttAsyncClient(props.getMqttBrokerUrl(), clientId,
                new MemoryPersistence());
        async.setCallback(this);
        async.connect(connectOptions());
        this.client = async;
        log.info("dji-cloud mqtt gateway: clientId={}（连接异步建立，自动重连已开）", clientId);
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
    public String protocol() {
        return "dji-cloud";
    }

    /**
     * 发送一条飞行命令（映射到 flight_task.action）。
     * <p>{@code false} = 无映射 / 无机巢绑定 / 超时 / 设备拒绝——一律如实上报，
     * 调用方不得据 false 当作成功。</p>
     */
    @Override
    public boolean send(int sysid, int mavCommand, float p1, float p2, float p3,
                        float p4, float p5, float p6, float p7) {
        String action = actionFor(mavCommand);
        if (action == null) {
            log.warn("dji-cloud send: mavCommand={} has no flight_task mapping (sysid={}) — refusing",
                    mavCommand, sysid);
            return false;
        }
        Optional<DockEntity> dock;
        try {
            dock = docks.findByDroneSysid(sysid);
        } catch (Exception e) {
            // 绑定查询失败（DB 故障等）：拒绝，不假成功
            log.warn("dji-cloud send: dock lookup failed sysid={} — refusing", sysid, e);
            return false;
        }
        if (dock.isEmpty()) {
            log.warn("dji-cloud send: no managed dock bound to sysid={} — refusing", sysid);
            return false;
        }
        String sn = dock.get().sn;

        String taskId = UUID.randomUUID().toString();
        String tid = UUID.randomUUID().toString();
        String bid = UUID.randomUUID().toString();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("action", action);
        if (MAV_CMD_NAV_TAKEOFF == mavCommand) {
            data.put("targetAltitude", (double) p7);  // MAV_CMD_NAV_TAKEOFF p7 = altitude (m)
        }

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("tid", tid);
        frame.put("bid", bid);
        frame.put("timestamp", System.currentTimeMillis());
        frame.put("gateway", sn);
        frame.put("method", FLIGHT_TASK_METHOD);
        frame.put("data", data);

        String body;
        try {
            body = mapper.writeValueAsString(frame);
        } catch (Exception e) {
            log.warn("dji-cloud send: serialize failed sn={} method={} — refusing", sn, FLIGHT_TASK_METHOD, e);
            return false;
        }

        CompletableFuture<Integer> future = new CompletableFuture<>();
        pending.put(tid, future);
        try {
            MqttMessage msg = new MqttMessage(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            msg.setQos(1);
            client.publish(String.format(SERVICES_TOPIC, sn), msg);
        } catch (Exception e) {
            pending.remove(tid);
            log.warn("dji-cloud send: publish failed sn={} tid={} — refusing", sn, tid, e);
            return false;
        }

        try {
            int result = future.get(props.getCommandTimeoutMs(), TimeUnit.MILLISECONDS);
            if (result != 0) {
                log.warn("dji-cloud send: flight_task rejected by device sn={} tid={} result={}",
                        sn, tid, result);
                return false;
            }
            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            pending.remove(tid);
            log.warn("dji-cloud send: flight_task timed out sn={} tid={} timeoutMs={}",
                    sn, tid, props.getCommandTimeoutMs());
            return false;
        } catch (InterruptedException e) {
            pending.remove(tid);
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            pending.remove(tid);
            Throwable c = e.getCause() != null ? e.getCause() : e;
            log.warn("dji-cloud send: flight_task failed sn={} tid={} ({})",
                    sn, tid, c.getMessage());
            return false;
        }
    }

    /** MAVLink command → DJI flight_task action。无映射返回 null（调用方按 false 处理）。 */
    static String actionFor(int mavCommand) {
        return switch (mavCommand) {
            case MAV_CMD_NAV_TAKEOFF -> "takeoff";
            case MAV_CMD_NAV_LAND -> "land";
            case MAV_CMD_NAV_RETURN_TO_LAUNCH -> "rtl";
            case MAV_CMD_NAV_LOITER_UNLIMITED, MAV_CMD_NAV_LOITER_TIME -> "hover";
            default -> null;
        };
    }

    /** Paho 回调：按 tid 分发 services_reply 到在等命令。 */
    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            if (topic == null || !topic.endsWith("/services_reply")) {
                return;
            }
            JsonNode node = mapper.readTree(message.getPayload());
            String tid = node.path("tid").asText(null);
            if (tid == null || tid.isBlank()) {
                return;
            }
            CompletableFuture<Integer> future = pending.remove(tid);
            if (future == null) {
                if (warnedUnknownTids.size() < MAX_WARNED_UNKNOWN_TIDS
                        && warnedUnknownTids.putIfAbsent(tid, Boolean.TRUE) == null) {
                    log.warn("dji-cloud services_reply with unknown tid={} on topic={}（首现告警）",
                            tid, topic);
                }
                return;
            }
            int result = node.path("data").path("result").asInt(-1);
            future.complete(result);
        } catch (Exception e) {
            log.warn("dji-cloud services_reply parse failed on topic={}", topic, e);
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        try {
            client.subscribe(SERVICES_REPLY_FILTER, 1);
            log.info("dji-cloud mqtt subscribed {} (reconnect={})", SERVICES_REPLY_FILTER, reconnect);
        } catch (Exception e) {
            log.warn("dji-cloud mqtt subscribe failed", e);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("dji-cloud mqtt connection lost（{} 个在等命令将失败）", pending.size(), cause);
        for (CompletableFuture<Integer> f : pending.values()) {
            f.completeExceptionally(new RuntimeException("dji-cloud mqtt connection lost"));
        }
        pending.clear();
    }

    @Override
    public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
        // 应答关联走 messageArrived，publish 完成事件不使用
    }

    @PreDestroy
    void stop() {
        for (CompletableFuture<Integer> f : pending.values()) {
            f.completeExceptionally(new RuntimeException("dji-cloud gateway shutting down"));
        }
        pending.clear();
        if (client != null) {
            try {
                client.disconnect().waitForCompletion(2000);
                client.close();
            } catch (Exception e) {
                log.warn("dji-cloud mqtt client close failed", e);
            }
        }
    }
}

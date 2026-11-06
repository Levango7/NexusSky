package io.aerofleet.cloud.dock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * mqtt 传输：按 DJI Cloud API 物模型走 Broker——
 * 下行 {@code thing/product/{sn}/services}，应答 {@code thing/product/{sn}/services_reply}。
 * 帧形状与 {@link SimLoopbackGateway} 逐字段一致（{@code tid,bid,timestamp,method,data}，
 * 应答 {@code data:{result,message}}），命令-应答按 {@code tid} 关联，QoS 1（at-least-once）。
 * <p>
 * 装配：{@code aerofleet.dock.transport=mqtt} 时启用（与 sim 互斥，见各自
 * {@code @ConditionalOnProperty}）。Broker 地址来自
 * {@code aerofleet.dock.mqtt-broker-url}——<b>transport=mqtt 且地址空缺属配置错误，
 * 启动即 fail-fast</b>（与 jwt-secret 同一口径）；Broker 暂时不可达<b>不</b>拖垮后端：
 * 连接异步建立 + 自动重连，通道故障如实抛 {@link DockGatewayException}（504），不返回假成功。
 * <p>
 * 异常消息纪律（#248/#250 同款）：对外只留自建短句，底层异常文本（Broker URL、
 * 原因链）只进日志与 cause。
 */
@Component
@ConditionalOnProperty(name = "aerofleet.dock.transport", havingValue = "mqtt")
public class MqttDockGateway implements DockGateway, MqttCallbackExtended {

    private static final Logger log = LoggerFactory.getLogger(MqttDockGateway.class);

    /** 下行 services topic 模板（{sn} 为机巢序列号）。 */
    static final String SERVICES_TOPIC = "thing/product/%s/services";
    /** 应答订阅过滤（全部机巢；按 tid 分发）。 */
    static final String SERVICES_REPLY_FILTER = "thing/product/+/services_reply";
    /** 告警哨兵集合上界：未知 tid 的 reply 不该成群出现。 */
    private static final int MAX_WARNED_UNKNOWN_TIDS = 256;

    private final ObjectMapper mapper;
    private final DockProperties props;
    private final AtomicLong tidSeq = new AtomicLong(0);
    private final Map<Long, java.util.concurrent.CompletableFuture<DockReply>> pending =
            new ConcurrentHashMap<>();
    private final Map<Long, Boolean> warnedUnknownTids = new ConcurrentHashMap<>();

    /** 生产路径由 {@link #start()} 创建；测试可注入 fake（包私有构造）。 */
    private IMqttAsyncClient client;

    /** 单调序列发号器（V35；机巢事务号跨重启单调，与 Sim 同款）。缺失时走内存 tidSeq。 */
    @Autowired(required = false)
    private io.aerofleet.cloud.api.service.PersistentSequence sequence;

    public MqttDockGateway(ObjectMapper mapper, DockProperties props) {
        this(mapper, props, null);
    }

    MqttDockGateway(ObjectMapper mapper, DockProperties props, IMqttAsyncClient injectedClient) {
        this.mapper = mapper;
        this.props = props;
        this.client = injectedClient;
    }

    /** 配置校验 + 建连（异步）。transport=mqtt 且 broker-url 空缺 ⇒ 启动 fail-fast。 */
    @PostConstruct
    void start() throws Exception {
        if (props.getMqttBrokerUrl() == null || props.getMqttBrokerUrl().isBlank()) {
            throw new IllegalStateException(
                    "aerofleet.dock.mqtt-broker-url is required when aerofleet.dock.transport=mqtt");
        }
        if (client != null) {
            // 测试注入：连接由测试自行管理，但 callback 照常注册（gateway 需要收 reply）
            client.setCallback(this);
            return;
        }
        String clientId = "aerofleet-dock-" + Long.toHexString(System.nanoTime());
        MqttAsyncClient async = new MqttAsyncClient(props.getMqttBrokerUrl(), clientId,
                new MemoryPersistence());
        async.setCallback(this);
        async.connect(connectOptions());
        this.client = async;
        log.info("dock mqtt gateway: broker={}, clientId={}（连接异步建立，自动重连已开）",
                props.getMqttBrokerUrl(), clientId);
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
    public String transportName() {
        return "mqtt";
    }

    @Override
    public DockReply sendCommand(String sn, DockCommand command, Map<String, Object> data) {
        Long issued = sequence != null ? sequence.next("dock_tid", 0) : null;
        long tid = issued != null ? issued : tidSeq.incrementAndGet();
        long bid = System.currentTimeMillis();
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("tid", tid);
        frame.put("bid", bid);
        frame.put("timestamp", System.currentTimeMillis());
        frame.put("method", command.method());
        frame.put("data", data == null ? Map.of() : data);

        String body;
        try {
            body = mapper.writeValueAsString(frame);
        } catch (Exception e) {
            // 序列化失败属内部缺陷：消息只留自建短句，细节走原因链/日志
            log.warn("serialize dock command failed sn={} method={}", sn, command.method(), e);
            throw new DockGatewayException("serialize dock command failed", e);
        }

        java.util.concurrent.CompletableFuture<DockReply> future = new CompletableFuture<>();
        pending.put(tid, future);
        try {
            MqttMessage msg = new MqttMessage(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            msg.setQos(1);
            client.publish(String.format(SERVICES_TOPIC, sn), msg);
        } catch (Exception e) {
            pending.remove(tid);
            // 底层 MqttException 文本（含 Broker URL/原因）不外泄：自建短句 + 完整日志
            log.warn("dock command publish failed sn={} method={} tid={}", sn, command.method(), tid, e);
            throw new DockGatewayException("dock command publish failed", e);
        }

        try {
            return future.get(props.getCommandTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            pending.remove(tid);
            log.warn("dock reply timed out sn={} method={} tid={} timeoutMs={}",
                    sn, command.method(), tid, props.getCommandTimeoutMs());
            throw new DockGatewayException("dock reply timed out after "
                    + props.getCommandTimeoutMs() + "ms");
        } catch (InterruptedException e) {
            pending.remove(tid);
            Thread.currentThread().interrupt();
            throw new DockGatewayException("dock command interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            pending.remove(tid);
            // connectionLost 会以自建异常完成 future；这里取 cause 重新抛出
            Throwable c = e.getCause() != null ? e.getCause() : e;
            if (c instanceof DockGatewayException dge) {
                throw dge;
            }
            log.warn("dock command failed sn={} method={} tid={}", sn, command.method(), tid, c);
            throw new DockGatewayException("dock command failed", c);
        }
    }

    /** Paho 回调入口：按 tid 分发 services_reply 到在等命令。 */
    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            if (topic == null || !topic.endsWith("/services_reply")) {
                return;
            }
            JsonNode node = mapper.readTree(message.getPayload());
            long tid = node.path("tid").asLong(-1);
            if (tid < 0) {
                return;
            }
            java.util.concurrent.CompletableFuture<DockReply> future = pending.remove(tid);
            if (future == null) {
                // 未知 tid：哨兵限流告警（dev 下杂散设备/重放），不进 pending 不崩
                if (warnedUnknownTids.size() < MAX_WARNED_UNKNOWN_TIDS
                        && warnedUnknownTids.putIfAbsent(tid, Boolean.TRUE) == null) {
                    log.warn("dock services_reply with unknown tid={} on topic={}（首现告警）", tid, topic);
                }
                return;
            }
            int result = node.path("data").path("result").asInt(-1);
            String messageText = node.path("data").path("message").asText(null);
            long rBid = node.path("bid").asLong(0);
            future.complete(new DockReply(tid, rBid, System.currentTimeMillis(),
                    result, messageText, new String(message.getPayload(),
                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // 单条解析失败只丢该条：日志 + 不崩回调线程
            log.warn("dock services_reply parse failed on topic={}", topic, e);
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        try {
            client.subscribe(SERVICES_REPLY_FILTER, 1);
            log.info("dock mqtt subscribed {} (reconnect={})", SERVICES_REPLY_FILTER, reconnect);
        } catch (Exception e) {
            log.warn("dock mqtt subscribe failed", e);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        // 在等命令的 future 立即失败（自建异常），不留挂死命令
        log.warn("dock mqtt connection lost（{} 个在等命令将失败）", pending.size(), cause);
        for (Map.Entry<Long, java.util.concurrent.CompletableFuture<DockReply>> en : pending.entrySet()) {
            en.getValue().completeExceptionally(
                    new DockGatewayException("dock mqtt connection lost"));
        }
        pending.clear();
    }

    @Override
    public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
        // 应答关联走 messageArrived，publish 完成事件不使用
    }

    @PreDestroy
    void stop() {
        for (Map.Entry<Long, java.util.concurrent.CompletableFuture<DockReply>> en : pending.entrySet()) {
            en.getValue().completeExceptionally(new DockGatewayException("dock gateway shutting down"));
        }
        pending.clear();
        if (client != null) {
            try {
                client.disconnect().waitForCompletion(2000);
                client.close();
            } catch (Exception e) {
                log.warn("dock mqtt client close failed", e);
            }
        }
    }
}

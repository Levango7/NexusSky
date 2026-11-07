package io.aerofleet.cloud.metrics;

import io.aerofleet.cloud.api.ws.TelemetryWebSocketHandler;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 遥测 WebSocket 扇出的背压与连接面指标（Micrometer）。
 *
 * <p>指标清单：
 * <ul>
 *   <li>{@code aerofleet.ws.sessions} — 当前活跃遥测会话数（Gauge）</li>
 *   <li>{@code aerofleet.ws.fanout.queue.depth} — 待发队列积压深度（Gauge）</li>
 *   <li>{@code aerofleet.ws.fanout.dropped} — 因队列饱和被丢弃的帧数（FunctionCounter，单调递增）</li>
 * </ul>
 *
 * <p>为什么需要这个类（与 {@link DroneMetrics} 里 {@code aerofleet.signing.*} 同一条教训）：
 * <ul>
 *   <li>扇出改造（有界队列 + 单消费者）把「慢客户端阻塞整条机队」降级成了
 *       「队列满则丢最旧」——但降级本身是<b>静默</b>的：{@code droppedFrames} 只是一个
 *       测试可见的原子计数，首次丢弃打一条 WARN 之后就再也不出声。</li>
 *   <li>于是运维侧看到的现象是「遥测变少了」，却没有任何曲线能回答
 *       「是丢帧、是客户端没连上、还是无人机没上报」。持续过载时，唯一的证据是一小时前
 *       那条 WARN。</li>
 *   <li>这三个指标把「丢了多少 / 积压多深 / 有多少会话」变成 Prometheus 上可告警的量：
 *       {@code rate(aerofleet.ws.fanout.dropped[5m]) > 0} 就是「该加容量或该查慢客户端」。</li>
 * </ul>
 *
 * <p>实现取向：
 * <ul>
 *   <li>只<b>读</b> handler 已有的公开原子计数（{@code droppedFrames()} /
 *       {@code outboundQueueDepth()} / {@code connectionCount()}），
 *       不往每帧热路径里加任何一次指标调用——扇出路径上多一次 map 查找都是浪费。</li>
 *   <li>用 {@link FunctionCounter} 而非在 {@code enqueue} 里 {@code counter.increment()}：
 *       丢弃发生在 UDP 接收线程上，指标注册缺失时（本类未装配）也不能影响丢弃语义。</li>
 *   <li>handler 是 {@code @Bean} 单例（{@code WebSocketConfig}），故这里拿到的一定是
 *       全进程唯一那个队列；用 {@link ObjectProvider} 是为了在只加载切片上下文的测试里
 *       优雅缺席（返回 null 就不注册，不抛异常）。</li>
 * </ul>
 */
@Component
public class WsFanoutMetrics {

    private static final Logger log = LoggerFactory.getLogger(WsFanoutMetrics.class);

    static final String SESSIONS = "aerofleet.ws.sessions";
    static final String QUEUE_DEPTH = "aerofleet.ws.fanout.queue.depth";
    static final String DROPPED = "aerofleet.ws.fanout.dropped";

    public WsFanoutMetrics(MeterRegistry meterRegistry,
                           ObjectProvider<TelemetryWebSocketHandler> handlerProvider) {
        TelemetryWebSocketHandler handler = handlerProvider.getIfAvailable();
        if (handler == null) {
            // 切片测试 / 未启用 WebSocket 的部署：没有可观测对象，静默跳过。
            log.info("WsFanoutMetrics skipped: no TelemetryWebSocketHandler bean in this context");
            return;
        }

        Gauge.builder(SESSIONS, handler, h -> (double) h.connectionCount())
                .description("当前活跃的遥测 WebSocket 会话数")
                .register(meterRegistry);

        Gauge.builder(QUEUE_DEPTH, handler, h -> (double) h.outboundQueueDepth())
                .description("遥测扇出待发队列的积压深度（持续贴近容量即意味着有客户端消费不过来）")
                .register(meterRegistry);

        FunctionCounter.builder(DROPPED, handler, h -> (double) h.droppedFrames())
                .description("因扇出队列饱和而被丢弃的遥测帧数（单调递增；"
                        + "非零即表示有客户端跟不上，遥测已开始丢最旧帧）")
                .register(meterRegistry);

        log.info("WsFanoutMetrics registered: {} / {} / {}", SESSIONS, QUEUE_DEPTH, DROPPED);
    }
}

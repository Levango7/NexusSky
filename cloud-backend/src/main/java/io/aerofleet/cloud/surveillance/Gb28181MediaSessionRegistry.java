package io.aerofleet.cloud.surveillance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * GB28181 点播媒体会话注册表（按「设备 + 通道」懒开、可整体关闭）。
 *
 * <p><b>为什么需要它</b>：GB/T 28181 的 INVITE 必须在 SDP 里写**真实的**媒体接收端
 * 地址（{@code c=IN IP4 <host>} + {@code m=video <port> RTP/AVP 96} + {@code y=<ssrc>}）。
 * 对接代码此前把这两个字段写死成 {@code 0.0.0.0} / {@code 0}——设备收到这样的 SDP
 * 不知道该往哪推流，**信令造得出来但发出去也没有意义**。真正可用的前提是本地先开一个
 * 收流会话、拿到端口，再把这个端口写进 SDP。
 *
 * <p>本类负责这件事的生命周期：
 * <ul>
 *   <li>按 {@code deviceId + "/" + channel} 懒开 {@link Gb28181MediaSession}；</li>
 *   <li>同一个键重复获取返回**同一个**会话（点播重入不该换端口，否则设备会往旧端口推）；</li>
 *   <li>不同键互不干扰（多通道并行点播）；</li>
 *   <li>{@link #closeAll()} 一次全关（Spring 容器销毁 / 适配器关闭时调用）。</li>
 * </ul>
 *
 * <p><b>端口纪律</b>：会话绑 {@code 127.0.0.1} 回环（PoC 口径，见
 * {@link Gb28181MediaSession#address()}）。生产部署若要跨机收流需改绑实际网卡地址——
 * 这是明确的已知边界，不假装已支持。
 */
public final class Gb28181MediaSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(Gb28181MediaSessionRegistry.class);

    private final Map<String, Gb28181MediaSession> sessions = new LinkedHashMap<>();

    /**
     * 取（必要时开）该设备通道的收流会话。
     *
     * @param key {@code deviceId + "/" + channel}
     * @return 已启动的会话；重复调用同一 key 返回同一实例
     */
    public synchronized Gb28181MediaSession acquire(String key) {
        Objects.requireNonNull(key, "key");
        Gb28181MediaSession existing = sessions.get(key);
        if (existing != null) {
            return existing;
        }
        try {
            Gb28181MediaSession s = Gb28181MediaSession.open(0);
            s.start();
            sessions.put(key, s);
            log.info("gb28181 media session opened for {} on {} (sessions={})",
                    key, s.address(), sessions.size());
            return s;
        } catch (IOException e) {
            // 端口开不出来要响亮失败：拿不到端口就没法写 SDP，点播无从谈起
            throw new UncheckedIOException("gb28181 media session open failed for " + key, e);
        }
    }

    /** 当前会话数（测试断言用）。 */
    public synchronized int size() {
        return sessions.size();
    }

    /** 当前全部会话的快照（调用方可在关闭后仍持有引用并断言其状态）。 */
    public synchronized List<Gb28181MediaSession> snapshot() {
        return List.copyOf(sessions.values());
    }

    /** 取已开的会话本体（未开返回 null；诊断/E2E 断言用）。 */
    public synchronized Gb28181MediaSession sessionOf(String key) {
        return sessions.get(key);
    }

    /** 取本地回环地址（SDP 的 {@code c=} 行用）；未开返回 null。 */
    public synchronized InetSocketAddress addressOf(String key) {
        Gb28181MediaSession s = sessions.get(key);
        return s == null ? null : s.address();
    }

    /** 关闭全部会话（适配器/容器销毁时调用）。 */
    public synchronized void closeAll() {
        if (sessions.isEmpty()) {
            return;
        }
        int n = sessions.size();
        sessions.values().forEach(Gb28181MediaSession::close);
        sessions.clear();
        log.info("gb28181 media sessions all closed (released {})", n);
    }
}

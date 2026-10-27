package io.aerofleet.cloud.surveillance;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.aerofleet.cloud.surveillance.onvif.OnvifSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OnvifClient} 的 live 事件面（门面 → 后端 → 设备）。
 *
 * <p>单独一个类而不是塞进 {@code OnvifClientModeTest}：这里需要一台会回
 * GetCapabilities / CreatePullPointSubscription / PullMessages 的假设备，
 * 用真实 HTTP 验证门面确实把订阅委托给了 live 后端，且取消后计数归零。
 */
@DisplayName("OnvifClient live 事件订阅（门面委派）")
class OnvifClientLiveEventsTest {

    private static final String USER = "admin";
    private static final String PASS = "pass123";

    private HttpServer server;
    private int port;
    private String base;
    private final AtomicInteger pullCount = new AtomicInteger();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    private OnvifSettings settings;

    @BeforeEach
    void startFakeDevice() throws Exception {
        settings = new OnvifSettings();
        settings.setMode(OnvifSettings.Mode.LIVE);
        settings.setConnectTimeoutMs(2000);
        settings.setReadTimeoutMs(3000);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        base = "http://127.0.0.1:" + port;

        server.createContext("/onvif/device_service", ex -> {
            requests.add("capabilities " + body(ex));
            send(ex, envelope("<tds:GetCapabilitiesResponse><tds:Capabilities>"
                    + "<tt:Media><tt:XAddr>" + base + "/media</tt:XAddr></tt:Media>"
                    + "<tt:PTZ><tt:XAddr>" + base + "/ptz</tt:XAddr></tt:PTZ>"
                    + "<tt:Events><tt:XAddr>" + base + "/onvif/events</tt:XAddr></tt:Events>"
                    + "</tds:Capabilities></tds:GetCapabilitiesResponse>"));
        });
        server.createContext("/onvif/events", ex -> {
            requests.add("subscribe " + body(ex));
            send(ex, envelope("<tev:CreatePullPointSubscriptionResponse>"
                    + "<tev:SubscriptionReference>"
                    + "<wsa:Address>" + base + "/onvif/subscription</wsa:Address>"
                    + "</tev:SubscriptionReference>"
                    + "<tev:TerminationTime>2026-10-27T09:00:00Z</tev:TerminationTime>"
                    + "</tev:CreatePullPointSubscriptionResponse>"));
        });
        server.createContext("/onvif/subscription", ex -> {
            requests.add("pull " + body(ex));
            boolean first = pullCount.incrementAndGet() == 1;
            send(ex, envelope("<tev:PullMessagesResponse>"
                    + (first ? "<tev:NotificationMessage>"
                    + "<tev:Topic>tns1:RuleEngine/CellMotionDetector/Motion</tev:Topic>"
                    + "<tev:Message><tt:Data>"
                    + "<tt:SimpleItem Name=\"IsMotion\" Value=\"true\"/>"
                    + "</tt:Data></tev:Message>"
                    + "</tev:NotificationMessage>" : "")
                    + "</tev:PullMessagesResponse>"));
        });
        server.start();
    }

    @AfterEach
    void stopFakeDevice() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("订阅：门面返回真句柄、计数为 1、通知进回调；取消后计数归零且不再拉取")
    void subscribeEvents_delegatesToLiveBackendAndUnsubscribes() throws Exception {
        OnvifClient client = new OnvifClient(settings);
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch firstEvent = new CountDownLatch(1);

        String handle = client.subscribeEvents("127.0.0.1", port, USER, PASS, payload -> {
            received.add(payload);
            firstEvent.countDown();
        });

        assertThat(handle).isNotBlank().startsWith("onvif-");
        assertThat(client.activeSubscriberCount()).isEqualTo(1);
        assertThat(firstEvent.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get(0))
                .contains("CellMotionDetector/Motion")
                .contains("IsMotion");
        assertThat(requests).anyMatch(r -> r.startsWith("subscribe ")
                && r.contains("CreatePullPointSubscription"));

        client.unsubscribeEvents(handle);
        assertThat(client.activeSubscriberCount()).isZero();
        // 给在途请求一个收尾窗口，之后不应再出现新的 PullMessages
        Thread.sleep(300);
        int after = pullCount.get();
        Thread.sleep(400);
        assertThat(pullCount.get()).as("取消订阅后门面不应再拉取").isEqualTo(after);
    }

    @Test
    @DisplayName("live 下参数非法：门面在联网之前就拒绝（不把空 IP 拼成设备地址）")
    void subscribeEvents_invalidArgs_rejectedBeforeNetwork() {
        OnvifClient client = new OnvifClient(settings);

        assertThatThrownBy(() -> client.subscribeEvents("", port, USER, PASS, payload -> {
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ip");
        assertThatThrownBy(() -> client.subscribeEvents("127.0.0.1", port, USER, PASS, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("callback");
        assertThat(client.activeSubscriberCount()).isZero();
    }

    private static String body(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String envelope(String body) {
        return "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\""
                + " xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\""
                + " xmlns:tev=\"http://www.onvif.org/ver10/events/wsdl\""
                + " xmlns:wsa=\"http://www.w3.org/2005/08/addressing\""
                + " xmlns:tt=\"http://www.onvif.org/ver10/schema\">"
                + "<s:Body>" + body + "</s:Body></s:Envelope>";
    }

    private static void send(HttpExchange exchange, String xml) throws IOException {
        byte[] payload = xml.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/soap+xml; charset=utf-8");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
        exchange.close();
    }
}

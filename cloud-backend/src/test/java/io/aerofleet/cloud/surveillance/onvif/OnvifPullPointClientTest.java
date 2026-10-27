package io.aerofleet.cloud.surveillance.onvif;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OnvifPullPointClient}：在真实 HTTP 上验「订阅 → 拉取」两段协议。
 *
 * <p>不 mock SOAP：这里要证的是发出去的 XML 长什么样、设备回的东西能不能解。
 * 设备端（{@link HttpServer}）按 SOAP 操作名回标准响应，并把请求体留作断言材料。
 */
@DisplayName("OnvifPullPointClient：WS-BaseNotification PullPoint")
class OnvifPullPointClientTest {

    private static final String USER = "admin";
    private static final String PASS = "pass123";

    private HttpServer server;
    private String base;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> subscribeResponseBody = new AtomicReference<>();
    private final AtomicReference<String> pullResponseBody = new AtomicReference<>();

    private OnvifSettings settings;

    @BeforeEach
    void startFakeDevice() throws Exception {
        settings = new OnvifSettings();
        settings.setConnectTimeoutMs(2000);
        settings.setReadTimeoutMs(3000);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/onvif/events", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add("subscribe " + body);
            send(exchange, subscribeResponseBody.get() != null
                    ? subscribeResponseBody.get()
                    : "<tev:CreatePullPointSubscriptionResponse>"
                    + "<tev:SubscriptionReference>"
                    + "<wsa:Address>" + base + "/onvif/subscription</wsa:Address>"
                    + "</tev:SubscriptionReference>"
                    + "<tev:CurrentTime>2026-10-27T08:00:00Z</tev:CurrentTime>"
                    + "<tev:TerminationTime>2026-10-27T09:00:00Z</tev:TerminationTime>"
                    + "</tev:CreatePullPointSubscriptionResponse>");
        });
        server.createContext("/onvif/subscription", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add("pull " + body);
            send(exchange, pullResponseBody.get() != null
                    ? pullResponseBody.get()
                    : "<tev:PullMessagesResponse>"
                    + "<tev:CurrentTime>2026-10-27T08:00:01Z</tev:CurrentTime>"
                    + "<tev:TerminationTime>2026-10-27T09:00:00Z</tev:TerminationTime>"
                    + "<tev:NotificationMessage>"
                    + "<tev:Topic>tns1:RuleEngine/CellMotionDetector/Motion</tev:Topic>"
                    + "<tev:Message><tt:Data><tt:SimpleItem Name=\"IsMotion\" Value=\"true\"/>"
                    + "</tt:Data></tev:Message>"
                    + "</tev:NotificationMessage>"
                    + "<tev:NotificationMessage>"
                    + "<tev:Topic>tns1:Device/Trigger/DigitalInput</tev:Topic>"
                    + "<tev:Message><tt:Data><tt:SimpleItem Name=\"LogicalState\" Value=\"true\"/>"
                    + "</tt:Data></tev:Message>"
                    + "</tev:NotificationMessage>"
                    + "</tev:PullMessagesResponse>");
        });
        server.start();
    }

    @AfterEach
    void stopFakeDevice() {
        if (server != null) {
            server.stop(0);
        }
    }

    private OnvifPullPointClient client() {
        return new OnvifPullPointClient(new OnvifSoapClient(settings));
    }

    @Test
    @DisplayName("订阅：发 CreatePullPointSubscription，取回设备给的订阅地址与到期时间")
    void subscribe_parsesSubscriptionReference() {
        OnvifPullPointClient.Subscription subscription =
                client().subscribe(base + "/onvif/events", USER, PASS);

        assertThat(subscription.address()).isEqualTo(base + "/onvif/subscription");
        assertThat(subscription.terminationTime()).isEqualTo("2026-10-27T09:00:00Z");
        assertThat(requests.get(0))
                .contains("CreatePullPointSubscription")
                .contains("<tev:InitialTerminationTime>PT1H</tev:InitialTerminationTime>");
    }

    @Test
    @DisplayName("订阅响应没有 SubscriptionReference/Address：明确报错，不造一个假地址")
    void subscribe_withoutReference_throws() {
        subscribeResponseBody.set("<tev:CreatePullPointSubscriptionResponse/>");

        assertThatThrownBy(() -> client().subscribe(base + "/onvif/events", USER, PASS))
                .isInstanceOf(OnvifException.class)
                .hasMessageContaining("SubscriptionReference");
    }

    @Test
    @DisplayName("拉取：解析全部 NotificationMessage 并原样序列化（主题与数据都在）")
    void pull_returnsSerializedNotifications() {
        List<String> events = client().pull(base + "/onvif/subscription", USER, PASS);

        assertThat(events).hasSize(2);
        assertThat(events.get(0))
                .contains("CellMotionDetector/Motion")
                .contains("IsMotion")
                .contains("true");
        assertThat(events.get(1)).contains("DigitalInput").contains("LogicalState");
        // 序列化后仍是合法 XML 片段（带命名空间声明）
        assertThat(events.get(0)).contains("NotificationMessage");
    }

    @Test
    @DisplayName("拉取的请求参数：Timeout=PT1S、MessageLimit=16（不一次拉干设备积压）")
    void pull_sendsBoundedRequest() {
        client().pull(base + "/onvif/subscription", USER, PASS);

        assertThat(requests.get(0))
                .contains("PullMessages")
                .contains("<tev:Timeout>PT1S</tev:Timeout>")
                .contains("<tev:MessageLimit>16</tev:MessageLimit>");
    }

    @Test
    @DisplayName("无通知：返回空列表（不是 null，也不是错误）")
    void pull_emptyResponse_isEmptyList() {
        pullResponseBody.set("<tev:PullMessagesResponse>"
                + "<tev:CurrentTime>2026-10-27T08:00:01Z</tev:CurrentTime>"
                + "<tev:TerminationTime>2026-10-27T09:00:00Z</tev:TerminationTime>"
                + "</tev:PullMessagesResponse>");

        assertThat(client().pull(base + "/onvif/subscription", USER, PASS)).isEmpty();
    }

    @Test
    @DisplayName("设备报 Fault（订阅已过期）：抛 OnvifException 并把原因带出来")
    void pull_fault_throws() {
        pullResponseBody.set("<s:Fault><s:Code><s:Value>s:Sender</s:Value></s:Code>"
                + "<s:Reason><s:Text xml:lang=\"en\">Subscription expired</s:Text></s:Reason>"
                + "</s:Fault>");

        assertThatThrownBy(() -> client().pull(base + "/onvif/subscription", USER, PASS))
                .isInstanceOf(OnvifException.class)
                .hasMessageContaining("Subscription expired");
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        String xml = "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\""
                + " xmlns:tev=\"http://www.onvif.org/ver10/events/wsdl\""
                + " xmlns:tt=\"http://www.onvif.org/ver10/schema\""
                + " xmlns:wsa=\"http://www.w3.org/2005/08/addressing\">"
                + "<s:Body>" + body + "</s:Body></s:Envelope>";
        byte[] payload = xml.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/soap+xml; charset=utf-8");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
        exchange.close();
    }
}

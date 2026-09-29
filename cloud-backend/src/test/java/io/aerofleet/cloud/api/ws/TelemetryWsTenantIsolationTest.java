package io.aerofleet.cloud.api.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.cloud.security.JwtTokenProvider;
import io.aerofleet.cloud.security.Role;
import io.aerofleet.mavlink.messages.TaskStatusMsg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 遥测 WebSocket 的租户隔离与握手鉴权测试。
 * <p>
 * 走生产入口 {@link TelemetryWebSocketHandler#onMavlinkMessage}：一条归属租户 A 的设备帧，
 * 只能投递给 A 的连接；他租户连接必须收不到。修复前 handler 无条件下发全部连接，
 * 本类既是该泄露的实测证据，也是回归护栏。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "aerofleet.security.dev-mode=false",
        "aerofleet.security.rbac-enabled=true",
        "aerofleet.license.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ws-chain-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.cache.type=none",
        "aerofleet.udp-port=0",
        "aerofleet.device-registry.persist=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
})
@DisplayName("WS 遥测：租户隔离与匿名握手")
class TelemetryWsTenantIsolationTest {

    private static final int TENANT_A = 7301;
    private static final int TENANT_B = 7302;
    private static final int DEVICE_OF_A = 7301;

    @Autowired
    private JwtTokenProvider tokens;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DeviceRegistry registry;

    private TelemetryWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = newHandler(false);
        registry.registerIfAbsent(DEVICE_OF_A);
        registry.assignTenant(DEVICE_OF_A, TENANT_A);
    }

    /** handler 由 WebSocketConfig 以相同参数手工构造，便于按用例设定 devMode。 */
    private TelemetryWebSocketHandler newHandler(boolean devMode) {
        return new TelemetryWebSocketHandler(tokens, registry, devMode, 10, 20, objectMapper);
    }

    private WebSocketSession connectedSession(String id, String ip, Integer tenantId) throws Exception {
        return connectedSession(id, ip, tenantId, Role.OPERATOR);
    }

    private WebSocketSession connectedSession(String id, String ip, Integer tenantId, Role role)
            throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.getHandshakeHeaders()).thenReturn(new HttpHeaders());
        when(session.getRemoteAddress()).thenReturn(new InetSocketAddress(ip, 40000));
        String jwt = tokens.generateToken("ws-user-" + id, role, tenantId,
                Duration.ofMinutes(10));
        when(session.getUri()).thenReturn(new URI("ws://gcs.local/ws/telemetry?token=" + jwt));
        handler.afterConnectionEstablished(session);
        return session;
    }

    private MavlinkMessageEvent frameFor(int sysid) {
        return new MavlinkMessageEvent(this, sysid, TaskStatusMsg.ID, null,
                System.currentTimeMillis());
    }

    @Test
    @DisplayName("他租户连接不得收到本租户设备的遥测帧")
    void crossTenantFramesAreNotBroadcast() throws Exception {
        WebSocketSession sessionA = connectedSession("A", "10.9.0.1", TENANT_A);
        WebSocketSession sessionB = connectedSession("B", "10.9.0.2", TENANT_B);

        handler.onMavlinkMessage(frameFor(DEVICE_OF_A));

        verify(sessionA, atLeastOnce()).sendMessage(any(TextMessage.class));
        verify(sessionB, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("未归属设备不对任何租户连接投递")
    void unassignedDeviceReachesNoTenantSession() throws Exception {
        registry.registerIfAbsent(DEVICE_OF_A);
        registry.assignTenant(DEVICE_OF_A, null);

        WebSocketSession sessionB = connectedSession("B", "10.9.0.3", TENANT_B);

        handler.onMavlinkMessage(frameFor(DEVICE_OF_A));

        verify(sessionB, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("dev-mode=false 时缺少有效 JWT 的握手被拒")
    void anonymousHandshakeIsRejected() throws Exception {
        WebSocketSession anonymous = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        when(anonymous.getId()).thenReturn("anon");
        when(anonymous.isOpen()).thenReturn(true);
        when(anonymous.getAttributes()).thenReturn(attrs);
        when(anonymous.getHandshakeHeaders()).thenReturn(new HttpHeaders());
        when(anonymous.getRemoteAddress()).thenReturn(new InetSocketAddress("10.9.0.9", 40000));
        when(anonymous.getUri()).thenReturn(new URI("ws://gcs.local/ws/telemetry"));

        handler.afterConnectionEstablished(anonymous);

        verify(anonymous).close(CloseStatus.POLICY_VIOLATION);
        assertThat(attrs).doesNotContainKey("tenantId");
    }

    @Test
    @DisplayName("同租户多连接均可见本租户帧")
    void sameTenantSessionsBothReceive() throws Exception {
        WebSocketSession first = connectedSession("A1", "10.9.1.1", TENANT_A);
        WebSocketSession second = connectedSession("A2", "10.9.1.2", TENANT_A);

        handler.onMavlinkMessage(frameFor(DEVICE_OF_A));

        verify(first, times(1)).sendMessage(any(TextMessage.class));
        verify(second, times(1)).sendMessage(any(TextMessage.class));
    }

    // ==================== 定向投递机制（按条目归属分区） ====================

    /** 注册一台归属租户 B 的设备，供分区测试用。 */
    private static final int DEVICE_OF_B = 7302;
    /** 机队里已登记但尚未指派租户的设备。 */
    private static final int DEVICE_UNASSIGNED = 7303;
    /** 不是机队设备的基础设施 id（基站/卫星一类）。 */
    private static final int INFRA_SYSID = 9101;

    private Map<String, Object> node(int sysid) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("sysid", sysid);
        v.put("online", true);
        return v;
    }

    private Map<String, Object> frameWith(String listKey, List<Map<String, Object>> items) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "mesh-topology");
        frame.put(listKey, items);
        return frame;
    }

    private List<String> payloadsOf(WebSocketSession session) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues().stream().map(TextMessage::getPayload).toList();
    }

    @Test
    @DisplayName("混合帧按设备归属拆分：每租户只收自己的条目")
    void mixedFrameIsPartitionedByDeviceOwner() throws Exception {
        registry.registerIfAbsent(DEVICE_OF_B);
        registry.assignTenant(DEVICE_OF_B, TENANT_B);

        WebSocketSession sessionA = connectedSession("A", "10.9.3.1", TENANT_A);
        WebSocketSession sessionB = connectedSession("B", "10.9.3.2", TENANT_B);

        boolean scoped = handler.tryBroadcastByDeviceOwner(
                frameWith("nodes", List.of(node(DEVICE_OF_A), node(DEVICE_OF_B), node(INFRA_SYSID))),
                objectMapper);
        assertThat(scoped).isTrue();

        List<String> aFrames = payloadsOf(sessionA);
        List<String> bFrames = payloadsOf(sessionB);

        // A 收到含自己设备的帧，且没有任何一帧带 B 的设备
        assertThat(aFrames).anyMatch(p -> p.contains("\"sysid\":" + DEVICE_OF_A)
                && !p.contains("\"sysid\":" + DEVICE_OF_B));
        assertThat(aFrames).noneMatch(p -> p.contains("\"sysid\":" + DEVICE_OF_B));
        // 反过来对 B 同样成立
        assertThat(bFrames).anyMatch(p -> p.contains("\"sysid\":" + DEVICE_OF_B)
                && !p.contains("\"sysid\":" + DEVICE_OF_A));
        assertThat(bFrames).noneMatch(p -> p.contains("\"sysid\":" + DEVICE_OF_A));
        // 非机队条目按公共基础设施投递，两个租户都收得到（与改动前一致）
        assertThat(aFrames).anyMatch(p -> p.contains("\"sysid\":" + INFRA_SYSID));
        assertThat(bFrames).anyMatch(p -> p.contains("\"sysid\":" + INFRA_SYSID));
    }

    @Test
    @DisplayName("未归属设备条目不进任何租户界面，只给全局会话")
    void unassignedEntriesGoOnlyToGlobalSessions() throws Exception {
        registry.registerIfAbsent(DEVICE_UNASSIGNED);
        registry.assignTenant(DEVICE_UNASSIGNED, null);

        WebSocketSession sessionB = connectedSession("B", "10.9.4.1", TENANT_B);
        WebSocketSession globalAdmin = connectedSession("G", "10.9.4.2", null, Role.ADMIN);

        handler.tryBroadcastByDeviceOwner(
                frameWith("nodes", List.of(node(DEVICE_UNASSIGNED))), objectMapper);

        verify(sessionB, never()).sendMessage(any(TextMessage.class));
        verify(globalAdmin, times(1)).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("帧内没有归属信息时返回 false，由调用方显式决定通道")
    void frameWithoutOwnerInfoIsNotSilentlyBroadcast() throws Exception {
        WebSocketSession sessionB = connectedSession("B", "10.9.5.1", TENANT_B);
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "disaster-status");
        frame.put("timestamp", 1L);

        boolean scoped = handler.tryBroadcastByDeviceOwner(frame, objectMapper);

        assertThat(scoped).isFalse();
        verify(sessionB, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("归属键可指定（编队按长机 leader 分区）")
    void ownerKeyIsConfigurable() throws Exception {
        registry.registerIfAbsent(DEVICE_OF_B);
        registry.assignTenant(DEVICE_OF_B, TENANT_B);

        WebSocketSession sessionA = connectedSession("A", "10.9.6.1", TENANT_A);
        WebSocketSession sessionB = connectedSession("B", "10.9.6.2", TENANT_B);

        Map<String, Object> formationOfA = new LinkedHashMap<>();
        formationOfA.put("formationId", 1);
        formationOfA.put("leader", DEVICE_OF_A);
        Map<String, Object> formationOfB = new LinkedHashMap<>();
        formationOfB.put("formationId", 2);
        formationOfB.put("leader", DEVICE_OF_B);

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "formation");
        frame.put("formations", List.of(formationOfA, formationOfB));

        assertThat(handler.tryBroadcastByOwnerKey(frame, "leader", objectMapper)).isTrue();
        assertThat(payloadsOf(sessionA)).allMatch(p -> p.contains("\"formationId\":1")
                && !p.contains("\"formationId\":2"));
        assertThat(payloadsOf(sessionB)).allMatch(p -> p.contains("\"formationId\":2")
                && !p.contains("\"formationId\":1"));
    }

    @Test
    @DisplayName("投递内容仅含本租户设备（帧级复核）")
    void deliveredFrameCarriesOnlyOwnDevice() throws Exception {
        WebSocketSession sessionA = connectedSession("A", "10.9.2.1", TENANT_A);
        connectedSession("B", "10.9.2.2", TENANT_B);

        handler.onMavlinkMessage(frameFor(DEVICE_OF_A));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(sessionA, times(1)).sendMessage(captor.capture());
        String payload = captor.getValue().getPayload();
        assertThat(payload).contains("\"sysid\":" + DEVICE_OF_A);
        assertThat(payload).contains("task-status");
    }
}

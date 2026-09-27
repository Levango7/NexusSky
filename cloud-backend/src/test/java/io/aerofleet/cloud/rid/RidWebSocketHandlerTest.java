package io.aerofleet.cloud.rid;

import io.aerofleet.cloud.rid.model.OperatorIdData;
import io.aerofleet.cloud.rid.model.RidComplianceState;
import io.aerofleet.cloud.rid.model.RidSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RidWebSocketHandler} 单元测试（直接实例化，无 Spring 上下文）。
 * <p>
 * Mock {@link WebSocketSession}，验证 session 管理、推送逻辑、推送失败容错与脱敏功能。
 */
@DisplayName("RidWebSocketHandler WebSocket 推送处理器")
class RidWebSocketHandlerTest {

    private RidWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RidWebSocketHandler();
    }

    // ─── Session 管理 ───

    @Nested
    @DisplayName("Session 管理")
    class SessionManagement {

        @Test
        @DisplayName("addSession 后 session 列表包含该 session")
        void addSession_sessionListContainsSession() throws IOException {
            WebSocketSession session = mock(WebSocketSession.class);
            when(session.getId()).thenReturn("session-1");
            when(session.isOpen()).thenReturn(true);

            handler.addSession(session);

            // 通过推送验证 session 在列表中
            RidSnapshot snapshot = createSnapshot(1);
            handler.pushRidUpdate(1, snapshot);

            verify(session).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("removeSession 后 session 列表不包含该 session")
        void removeSession_sessionListDoesNotContainSession() throws IOException {
            WebSocketSession session = mock(WebSocketSession.class);
            when(session.getId()).thenReturn("session-1");
            when(session.isOpen()).thenReturn(true);

            handler.addSession(session);
            handler.removeSession(session);

            RidSnapshot snapshot = createSnapshot(1);
            handler.pushRidUpdate(1, snapshot);

            verify(session, never()).sendMessage(any(TextMessage.class));
        }
    }

    // ─── 推送逻辑 ───

    @Nested
    @DisplayName("推送逻辑")
    class PushLogic {

        @Test
        @DisplayName("pushRidUpdate 推送 JSON 到所有连接的 session")
        void pushRidUpdate_pushesToAllSessions() throws IOException {
            WebSocketSession session1 = mock(WebSocketSession.class);
            WebSocketSession session2 = mock(WebSocketSession.class);
            when(session1.getId()).thenReturn("s1");
            when(session2.getId()).thenReturn("s2");
            when(session1.isOpen()).thenReturn(true);
            when(session2.isOpen()).thenReturn(true);

            handler.addSession(session1);
            handler.addSession(session2);

            RidSnapshot snapshot = createSnapshot(1);
            handler.pushRidUpdate(1, snapshot);

            verify(session1).sendMessage(any(TextMessage.class));
            verify(session2).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("无连接 session 时 pushRidUpdate 不做任何操作")
        void pushRidUpdate_noSessions_doesNothing() {
            RidSnapshot snapshot = createSnapshot(1);

            // 不应抛出异常
            handler.pushRidUpdate(1, snapshot);
        }

        @Test
        @DisplayName("未打开的 session 不接收推送")
        void pushRidUpdate_closedSession_doesNotReceive() throws IOException {
            WebSocketSession session = mock(WebSocketSession.class);
            when(session.getId()).thenReturn("s1");
            when(session.isOpen()).thenReturn(false);

            handler.addSession(session);

            RidSnapshot snapshot = createSnapshot(1);
            handler.pushRidUpdate(1, snapshot);

            verify(session, never()).sendMessage(any(TextMessage.class));
        }
    }

    // ─── 推送失败容错 ───

    @Nested
    @DisplayName("推送失败容错")
    class PushFailureTolerance {

        @Test
        @DisplayName("推送失败时 WARN 日志但不影响其他 session")
        void pushRidUpdate_failureDoesNotAffectOthers() throws IOException {
            WebSocketSession failingSession = mock(WebSocketSession.class);
            WebSocketSession normalSession = mock(WebSocketSession.class);
            when(failingSession.getId()).thenReturn("failing");
            when(normalSession.getId()).thenReturn("normal");
            when(failingSession.isOpen()).thenReturn(true);
            when(normalSession.isOpen()).thenReturn(true);

            // failingSession 抛出 IOException
            doThrow(new IOException("connection lost"))
                    .when(failingSession).sendMessage(any(TextMessage.class));

            handler.addSession(failingSession);
            handler.addSession(normalSession);

            RidSnapshot snapshot = createSnapshot(1);
            handler.pushRidUpdate(1, snapshot);

            // failingSession 尝试了发送但失败
            verify(failingSession).sendMessage(any(TextMessage.class));
            // normalSession 仍然成功接收到推送
            verify(normalSession).sendMessage(any(TextMessage.class));
        }
    }

    // ─── operatorId 脱敏 ───

    @Nested
    @DisplayName("operatorId 脱敏")
    class OperatorIdDesensitization {

        @Test
        @DisplayName("operatorId 在序列化前脱敏")
        void operatorId_desensitizedBeforeSerialization() throws IOException {
            WebSocketSession session = mock(WebSocketSession.class);
            when(session.getId()).thenReturn("s1");
            when(session.isOpen()).thenReturn(true);

            // 使用 ArgumentCaptor 捕获发送的消息内容
            handler.addSession(session);

            RidSnapshot snapshot = createSnapshotWithOperatorId(1, "ABCD123456789");
            handler.pushRidUpdate(1, snapshot);

            // 捕获 TextMessage 并验证内容中 operatorId 已脱敏
            org.mockito.ArgumentCaptor<TextMessage> captor =
                    org.mockito.ArgumentCaptor.forClass(TextMessage.class);
            verify(session).sendMessage(captor.capture());

            String json = captor.getValue().getPayload();
            // 脱敏后的 operatorId 应为 "ABCD*********"
            assertThat(json).contains("ABCD*********");
            // 不应包含原始的完整 operatorId
            assertThat(json).doesNotContain("ABCD123456789");
        }

        @Test
        @DisplayName("无 operatorId 时推送正常")
        void pushRidUpdate_noOperatorId_pushesNormally() throws IOException {
            WebSocketSession session = mock(WebSocketSession.class);
            when(session.getId()).thenReturn("s1");
            when(session.isOpen()).thenReturn(true);

            handler.addSession(session);

            RidSnapshot snapshot = createSnapshot(1);
            handler.pushRidUpdate(1, snapshot);

            verify(session).sendMessage(any(TextMessage.class));
        }
    }

    // ─── desensitizeOperatorId 静态方法 ───

    @Nested
    @DisplayName("desensitizeOperatorId 脱敏方法")
    class DesensitizeMethod {

        @Test
        @DisplayName("长度 >4 时保留前4位 + *")
        void desensitize_longId_keepsFirst4Chars() {
            assertThat(RidWebSocketHandler.desensitizeOperatorId("ABCD123456789"))
                    .isEqualTo("ABCD*********");
        }

        @Test
        @DisplayName("长度 <=4 时不脱敏")
        void desensitize_shortId_noChange() {
            assertThat(RidWebSocketHandler.desensitizeOperatorId("ABCD"))
                    .isEqualTo("ABCD");
        }

        @Test
        @DisplayName("null 时不脱敏")
        void desensitize_null_returnsNull() {
            assertThat(RidWebSocketHandler.desensitizeOperatorId(null))
                    .isNull();
        }
    }

    // ─── 辅助方法 ───

    /**
     * 创建一个简单的 RidSnapshot（不含 operatorId）。
     */
    private static RidSnapshot createSnapshot(int sysid) {
        return new RidSnapshot(sysid, RidComplianceState.BROADCASTING,
                null, null, null, null, null, System.currentTimeMillis());
    }

    /**
     * 创建一个带 operatorId 的 RidSnapshot。
     */
    private static RidSnapshot createSnapshotWithOperatorId(int sysid, String operatorId) {
        return new RidSnapshot(sysid, RidComplianceState.BROADCASTING,
                null, null, null, null,
                new OperatorIdData(0, operatorId),
                System.currentTimeMillis());
    }
}
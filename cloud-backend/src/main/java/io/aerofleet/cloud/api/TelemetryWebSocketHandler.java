package io.aerofleet.cloud.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raw WebSocket endpoint /ws/telemetry (no SockJS). Sessions are kept in an
 * in-memory set; the 1Hz pusher broadcasts to all of them.
 * TODO auth: accept only GCS sessions with a valid token at handshake.
 */
public class TelemetryWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TelemetryWebSocketHandler.class);

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public TelemetryWebSocketHandler() {
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        log.info("WS client connected: {} (total {})", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("WS client disconnected: {} status={} (total {})",
                session.getId(), status, sessions.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Read-only channel at scaffold stage; ignore client frames.
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable error) {
        sessions.remove(session.getId());
    }

    /** Push one JSON text frame to every live connection. */
    public void broadcast(String json, ObjectMapper mapper) {
        if (sessions.isEmpty()) {
            return;
        }
        TextMessage msg = new TextMessage(json);
        for (WebSocketSession s : sessions.values()) {
            if (!s.isOpen()) {
                sessions.remove(s.getId());
                continue;
            }
            try {
                synchronized (s) {
                    s.sendMessage(msg);
                }
            } catch (IOException e) {
                log.debug("WS send failed to {}: {}", s.getId(), e.getMessage());
                sessions.remove(s.getId());
            }
        }
    }

    public int connectionCount() {
        return sessions.size();
    }
}

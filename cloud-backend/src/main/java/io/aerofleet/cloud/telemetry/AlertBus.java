package io.aerofleet.cloud.telemetry;

import io.aerofleet.cloud.gateway.AlertEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Simple fan-out bus so the WebSocket pusher can forward STATUSTEXT alerts
 * the moment they arrive (telemetry itself is throttled to 1 Hz).
 */
@Component
public class AlertBus {

    private static final Logger log = LoggerFactory.getLogger(AlertBus.class);

    private final List<Consumer<AlertEvent>> listeners = new CopyOnWriteArrayList<>();

    public record AlertEvent(int sysid, AlertEntry entry) {
    }

    public void subscribe(Consumer<AlertEvent> listener) {
        listeners.add(listener);
    }

    public void unsubscribe(Consumer<AlertEvent> listener) {
        listeners.remove(listener);
    }

    public void publish(int sysid, AlertEntry entry) {
        for (Consumer<AlertEvent> l : listeners) {
            try {
                l.accept(new AlertEvent(sysid, entry));
            } catch (RuntimeException e) {
                log.debug("Alert listener failed: {}", e.getMessage());
            }
        }
    }
}

package io.aerofleet.cloud.gateway;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Central MAVLink frame decoder: decodes each CRC-valid frame and publishes a
 * {@link MavlinkMessageEvent} via {@link ApplicationEventPublisher}. All business
 * modules listen for the event types they care about via {@code @EventListener},
 * eliminating the 14 @Lazy cross-package dependencies of the previous design.
 * <p>
 * This service is intentionally thin — it only decodes and publishes. Every
 * onXxx() handler that previously lived here has moved to the owning module.
 */
@Service
public class TelemetryIngestService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestService.class);

    private final ApplicationEventPublisher eventPublisher;

    public TelemetryIngestService(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /** Called by the UDP transport for every CRC-valid frame. Never throws. */
    public void handle(MavlinkFrame frame) {
        try {
            MavlinkMessage msg = MavlinkMessage.decode(frame);
            if (msg == null) {
                return; // not one of our message types: ignore at scaffold stage
            }
            int sysid = frame.getSystemId();
            int msgId = frame.getMessageId();
            long timestamp = System.currentTimeMillis();
            eventPublisher.publishEvent(
                    new MavlinkMessageEvent(this, sysid, msgId, msg, timestamp));
        } catch (RuntimeException e) {
            // Decode errors must never kill the UDP receive loop
            log.debug("Failed to process frame msgId={} from sysid={}: {}",
                    frame.getMessageId(), frame.getSystemId(), e.getMessage());
        }
    }
}

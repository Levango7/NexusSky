package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.regulator.TelemetryReportService;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.messages.GlobalPositionInt;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p>
 * After decoding, if the frame is a {@link GlobalPositionInt} and the optional
 * {@link TelemetryReportService} is available, the extracted position / speed /
 * heading / airborne state is forwarded to the regulator via
 * {@code telemetryReportService.onTelemetry()}.
 */
@Service
public class TelemetryIngestService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestService.class);

    private final ApplicationEventPublisher eventPublisher;

    /** Optional regulator telemetry report service (null when regulator module is not deployed). */
    @Autowired(required = false)
    private TelemetryReportService telemetryReportService;

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

            // Forward telemetry to regulator if available and frame carries position data
            if (telemetryReportService != null && msg instanceof GlobalPositionInt gpi) {
                double lat = gpi.lat();
                double lon = gpi.lon();
                double alt = gpi.altMm / 1000.0;
                double speed = Math.sqrt((long) gpi.vx * gpi.vx + (long) gpi.vy * gpi.vy) / 100.0;
                double heading = gpi.hdg != 65535 ? gpi.hdg / 100.0 : 0.0;
                boolean airborne = gpi.relativeAltMm > 0;
                telemetryReportService.onTelemetry(sysid, lat, lon, alt, speed, heading, airborne);
            }
        } catch (RuntimeException e) {
            // Decode errors must never kill the UDP receive loop
            log.debug("Failed to process frame msgId={} from sysid={}: {}",
                    frame.getMessageId(), frame.getSystemId(), e.getMessage());
        }
    }
}

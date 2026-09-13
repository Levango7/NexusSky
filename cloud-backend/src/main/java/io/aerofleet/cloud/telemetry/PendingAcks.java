package io.aerofleet.cloud.telemetry;

import io.aerofleet.mavlink.messages.CommandAck;
import io.aerofleet.mavlink.messages.MissionAckMsg;
import io.aerofleet.mavlink.messages.MissionRequest;
import io.aerofleet.mavlink.messages.MissionRequestInt;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * Short-lived correlation of outgoing MAVLink requests with their responses
 * (COMMAND_ACK / MISSION_REQUEST_INT / MISSION_ACK). Entries are keyed by
 * message id + discriminator + <b>target sysid</b>, so concurrent command and
 * mission exchanges with several drones never overwrite or cross-complete
 * each other's futures. Entries are completed by the receive thread and
 * expired by the callers (timeout futures bound any leak).
 */
@Component
public class PendingAcks {

    /** Matcher value for "any system id" (legacy / single-drone callers). */
    public static final int ANY_SYSID = 0;

    /**
     * One pending item: a future plus a filter over (response, fromSysid).
     * The source system id comes from the MAVLink frame header - responses
     * from drone A must never complete a waiter armed for drone B.
     */
    public static final class Pending<T> {
        public final CompletableFuture<T> future = new CompletableFuture<>();
        public final BiPredicate<Object, Integer> matcher;

        public Pending(BiPredicate<Object, Integer> matcher) {
            this.matcher = matcher;
        }
    }

    /** Correlation key: message id + caller discriminator + target sysid. */
    public record Key(int messageId, int discriminator, int sysid) {
    }

    private final Map<Key, Pending<?>> pendings = new ConcurrentHashMap<>();

    /** Register an expectation; remove it via {@link #remove}. */
    public <T> CompletableFuture<T> put(int messageId, int discriminator, int sysid,
                                        BiPredicate<Object, Integer> matcher) {
        Pending<T> p = new Pending<>(matcher);
        pendings.put(new Key(messageId, discriminator, sysid), p);
        return p.future;
    }

    /**
     * Feed a decoded response (with the responding system id taken from the
     * frame header) to any waiter whose matcher accepts it. Returns true when
     * at least one waiter consumed the response.
     */
    public boolean offer(int messageId, Object response, int fromSysid) {
        boolean matched = false;
        for (Map.Entry<Key, Pending<?>> e : pendings.entrySet()) {
            if (e.getKey().messageId() != messageId) {
                continue;
            }
            Pending<?> p = e.getValue();
            if (p.matcher.test(response, fromSysid)) {
                matched = true;
                if (pendings.remove(e.getKey(), p)) {
                    @SuppressWarnings("unchecked")
                    CompletableFuture<Object> f = (CompletableFuture<Object>) p.future;
                    f.complete(response);
                }
            }
        }
        return matched;
    }

    /** Drop an expired expectation and fail its future. */
    public void remove(int messageId, int discriminator, int sysid, Throwable cause) {
        Pending<?> p = pendings.remove(new Key(messageId, discriminator, sysid));
        if (p != null) {
            p.future.completeExceptionally(cause);
        }
    }

    public int size() {
        return pendings.size();
    }

    // ---- typed helpers for the response kinds we correlate ----

    /**
     * Command-ack waiter for one drone: completes only on an ACK of the same
     * MAV_CMD from that system id.
     */
    public CompletableFuture<CommandAck> expectCommandAck(int command, int sysid) {
        return put(CommandAck.ID, command, sysid,
                (o, src) -> o instanceof CommandAck a && a.command == command
                        && (sysid == ANY_SYSID || src == sysid));
    }

    /**
     * Mission pull request waiter for one drone. PX4 may answer with either
     * MISSION_REQUEST (43) or MISSION_REQUEST_INT (51); both are registered
     * under BOTH message ids so a waiter never misses whichever variant the
     * autopilot uses. The future resolves to the requested sequence (Integer).
     */
    public CompletableFuture<Integer> expectMissionRequest(int sysid) {
        CompletableFuture<Integer> f = new CompletableFuture<>();
        Pending<Integer> p = new Pending<>((o, src) -> {
            if (sysid != ANY_SYSID && src != sysid) {
                return false;
            }
            if (o instanceof MissionRequestInt ri) {
                f.complete(ri.seq);
                return true;
            }
            if (o instanceof MissionRequest r) {
                f.complete(r.seq);
                return true;
            }
            return false;
        });
        pendings.put(new Key(MissionRequestInt.ID, 0, sysid), p);
        pendings.put(new Key(MissionRequest.ID, 0, sysid), p);
        return f;
    }

    /** Remove any registered mission-request waiters (both variants). */
    public void removeMissionRequest(int sysid, Throwable cause) {
        remove(MissionRequestInt.ID, 0, sysid, cause);
        remove(MissionRequest.ID, 0, sysid, cause);
    }

    /** Mission final-ack waiter for one drone. */
    public CompletableFuture<MissionAckMsg> expectMissionAck(int sysid) {
        return put(MissionAckMsg.ID, 0, sysid,
                (o, src) -> o instanceof MissionAckMsg
                        && (sysid == ANY_SYSID || src == sysid));
    }

    /** Mission-download: wait for the drone's MISSION_COUNT (one sysid). */
    public CompletableFuture<io.aerofleet.mavlink.messages.MissionCountMsg> expectMissionCount(int sysid) {
        return put(io.aerofleet.mavlink.messages.MissionCountMsg.ID, 0, sysid,
                (o, src) -> o instanceof io.aerofleet.mavlink.messages.MissionCountMsg
                        && (sysid == ANY_SYSID || src == sysid));
    }

    /** Mission-download: wait for stored item #seq (one sysid). */
    public CompletableFuture<io.aerofleet.mavlink.messages.MissionItemInt> expectMissionItem(
            int sysid, int seq) {
        return put(io.aerofleet.mavlink.messages.MissionItemInt.ID, seq, sysid,
                (o, src) -> o instanceof io.aerofleet.mavlink.messages.MissionItemInt it
                        && it.seq == seq
                        && (sysid == ANY_SYSID || src == sysid));
    }
}

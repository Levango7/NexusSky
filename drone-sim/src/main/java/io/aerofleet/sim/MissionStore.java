package io.aerofleet.sim;

import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.MissionItemInt;

import java.util.ArrayList;
import java.util.List;

/**
 * Uploaded mission: waypoint list plus the Mission Protocol upload state machine.
 * The simulator is the mission "server": GCS announces MISSION_COUNT, the sim
 * requests each MISSION_ITEM_INT by seq, and acknowledges at the end.
 */
public final class MissionStore {

    /** Max waypoints we accept (PX4 default-ish bound). */
    private static final int MAX_ITEMS = 500;

    private final int sysid;

    // Upload session state
    private int expectedCount = -1;
    private int nextExpectedSeq = 0;
    private boolean uploading = false;

    // Stored mission
    private List<MissionItemInt> items = new ArrayList<>();
    private boolean hasMission = false;

    public MissionStore(int sysid) {
        this.sysid = sysid;
    }

    /**
     * GCS announced a MISSION_COUNT: start a fresh upload session.
     * Returns the first seq to request (always 0).
     */
    public int beginUpload(int count) {
        if (count < 0 || count > MAX_ITEMS) {
            return -1;
        }
        uploading = count > 0;
        expectedCount = count;
        nextExpectedSeq = 0;
        items = new ArrayList<>(count);
        return 0;
    }

    public boolean isUploading() {
        return uploading;
    }

    /** Seq the upload session currently waits for (loss-recovery re-request). */
    public int nextExpectedSeq() {
        return nextExpectedSeq;
    }

    /**
     * Handle an incoming MISSION_ITEM_INT.
     * Returns the next seq to request, or -1 when the mission is complete
     * (caller should then send MISSION_ACK and keep the mission).
     */
    public int onItem(MissionItemInt item) {
        if (!uploading) {
            // Item without MISSION_COUNT: ignore, ask to restart from 0.
            return 0;
        }
        int seq = item.seq;
        if (seq == nextExpectedSeq) {
            // Fill gaps defensively in case of out-of-order arrivals.
            while (items.size() <= seq) {
                items.add(null);
            }
            items.set(seq, item);
            nextExpectedSeq++;
            if (nextExpectedSeq >= expectedCount) {
                uploading = false;
                hasMission = true;
                return -1;
            }
            return nextExpectedSeq;
        }
        if (seq < nextExpectedSeq) {
            // Duplicate/retransmitted item: re-request the slot we actually need.
            return nextExpectedSeq;
        }
        // Future seq we have not asked for yet: re-request the expected one.
        return nextExpectedSeq;
    }

    /** Mission accepted: caller sent MISSION_ACK(ACCEPTED). */
    public void commit() {
        hasMission = true;
        uploading = false;
    }

    /** Discard a failed upload session (error ack path). */
    public void abortUpload() {
        uploading = false;
        expectedCount = -1;
        nextExpectedSeq = 0;
        items = new ArrayList<>();
    }

    public boolean hasMission() {
        return hasMission && !items.isEmpty();
    }

    public int size() {
        return items.size();
    }

    public MissionItemInt get(int seq) {
        if (seq < 0 || seq >= items.size()) {
            return null;
        }
        return items.get(seq);
    }

    public List<MissionItemInt> all() {
        return items;
    }

    public int sysid() {
        return sysid;
    }

    /** Quick debug summary of the stored items. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("mission items=").append(items.size());
        for (MissionItemInt it : items) {
            if (it == null) {
                continue;
            }
            sb.append(" [").append(it.seq).append(" cmd=").append(it.command)
                    .append(" lat=").append(it.lat()).append(" lon=").append(it.lon())
                    .append(" alt=").append(it.z).append(']');
        }
        return sb.toString();
    }
}

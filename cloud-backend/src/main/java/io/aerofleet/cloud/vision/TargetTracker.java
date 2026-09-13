package io.aerofleet.cloud.vision;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Target tracker (P3 batch A): turns per-shot detections into tracks.
 *
 * Association: nearest-neighbor with a distance gate (30 m default - the
 * sim's vehicles move <1.5 m between 10 Hz shots even at 15 m/s, so the gate
 * is generous but not blind). Prediction: constant-velocity extrapolation
 * from the last two observations. Track lifecycle: DORMANT -> ACTIVE (2+
 * hits) -> LOST (no update for {@link #LOST_AFTER_MS}) -> dropped.
 *
 * Pure in-memory, thread-safe: one map per drone, one list of tracks inside.
 */
@Component
public class TargetTracker {

    public enum TrackState { ACTIVE, LOST }

    public static final class Observation {
        public final long frameSeq;
        public final long timeMs;
        public final double lat;
        public final double lon;
        public final String kind;

        public Observation(long frameSeq, long timeMs, double lat, double lon, String kind) {
            this.frameSeq = frameSeq;
            this.timeMs = timeMs;
            this.lat = lat;
            this.lon = lon;
            this.kind = kind;
        }
    }

    public static final class Track {
        public final int trackId;
        public volatile TrackState state = TrackState.ACTIVE;
        public volatile Observation last;
        public volatile Observation prev;
        public volatile int hits = 1;

        Track(int trackId, Observation first) {
            this.trackId = trackId;
            this.last = first;
        }

        /** Constant-velocity prediction {@code dtMs} beyond the last hit. */
        public double[] predictLatLon(long atMs) {
            if (prev == null) {
                return new double[]{last.lat, last.lon};
            }
            double dt = (atMs - last.timeMs) / 1000.0;
            if (dt <= 0) {
                return new double[]{last.lat, last.lon};
            }
            double vLat = (last.lat - prev.lat) / Math.max(0.001, (last.timeMs - prev.timeMs) / 1000.0);
            double vLon = (last.lon - prev.lon) / Math.max(0.001, (last.timeMs - prev.timeMs) / 1000.0);
            return new double[]{last.lat + vLat * dt, last.lon + vLon * dt};
        }
    }

    /** meters: association gate. */
    static final double GATE_M = 30.0;
    /** no update for this long -> LOST; LOST tracks are query-able but not
     *  resurrected (a new detection opens a NEW track - honest and simple). */
    static final long LOST_AFTER_MS = 30_000;

    /** sysid -> live tracks (one tracker per drone). */
    private final Map<Integer, List<Track>> byDrone = new ConcurrentHashMap<>();
    private int nextTrackId = 1;

    /**
     * Feed one shot's detections; returns the tracks after the update.
     * Detections are (frameSeq, timeMs, lat, lon, kind) tuples.
     */
    public synchronized List<Track> ingest(int sysid, List<Observation> detections) {
        List<Track> tracks = byDrone.computeIfAbsent(sysid, k -> new CopyOnWriteArrayList<>());

        // age out lost tracks entirely after they stop being interesting
        long now = System.currentTimeMillis();
        tracks.removeIf(t -> t.state == TrackState.LOST
                && t.last != null && now - t.last.timeMs > LOST_AFTER_MS * 2);
        // mark stale tracks lost
        for (Track t : tracks) {
            if (t.state == TrackState.ACTIVE && t.last != null
                    && now - t.last.timeMs > LOST_AFTER_MS) {
                t.state = TrackState.LOST;
            }
        }

        // greedy nearest-neighbor association (detections are few; O(n*m) is fine)
        List<Observation> unmatched = new ArrayList<>(detections);
        for (Observation o : detections) {
            Track best = null;
            double bestDist = Double.MAX_VALUE;
            for (Track t : tracks) {
                if (t.state != TrackState.ACTIVE) {
                    continue;
                }
                double d = distanceM(t.last.lat, t.last.lon, o.lat, o.lon);
                if (d < bestDist) {
                    bestDist = d;
                    best = t;
                }
            }
            if (best != null && bestDist <= GATE_M) {
                best.prev = best.last;
                best.last = o;
                best.hits++;
                unmatched.remove(o);
            }
        }
        // new tracks for the rest
        for (Observation o : unmatched) {
            tracks.add(new Track(nextTrackId++, o));
        }
        return List.copyOf(tracks);
    }

    /** Current tracks of one drone (snapshot). */
    public List<Track> tracksOf(int sysid) {
        return List.copyOf(byDrone.getOrDefault(sysid, List.of()));
    }

    /** Haversine-ish meter distance on the small-area flat-earth approx. */
    static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double dn = (lat2 - lat1) * 111_320.0;
        double de = (lon2 - lon1) * 111_320.0 * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        return Math.hypot(dn, de);
    }
}

package io.aerofleet.cloud.vision;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Async orbit jobs (D1): the 2-minute orbit run must not pin a Tomcat worker
 * thread (or the browser's fetch). Managers own a small fixed pool; the REST
 * layer submits and gets a jobId instantly, then polls {@code GET
 * /vision/jobs/{id}} for per-station progress and the final result.
 *
 * One in-flight job per drone (409-style rejection on repeats): simpler than
 * queuing, and two concurrent missions to one autopilot is a bug, not a wish.
 */
@Component
public class OrbitJobManager {

    private static final Logger log = LoggerFactory.getLogger(OrbitJobManager.class);

    public enum JobState { STARTED, RUNNING, DONE, FAILED, TIMEOUT }

    public static final class OrbitJob {
        public final String id;
        public final int sysid;
        public final double lat;
        public final double lon;
        public final double radiusM;
        public final double altM;
        public final int photosRequested;
        public volatile JobState state = JobState.STARTED;
        public final long startedAt = System.currentTimeMillis();
        public volatile long finishedAt;
        public final List<Map<String, Object>> progress = new CopyOnWriteArrayList<>();
        public volatile Map<String, Object> result;
        public volatile String error;

        OrbitJob(int sysid, double lat, double lon, double radiusM, double altM, int photos) {
            this.id = UUID.randomUUID().toString();
            this.sysid = sysid;
            this.lat = lat;
            this.lon = lon;
            this.radiusM = radiusM;
            this.altM = altM;
            this.photosRequested = photos;
        }
    }

    /** jobs by id. Completed jobs are kept (bounded below) for result pickup. */
    private final Map<String, OrbitJob> jobs = new ConcurrentHashMap<>();
    /** sysid -> in-flight job (for the one-per-drone rule). */
    private final Map<Integer, OrbitJob> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService pool;
    private final OrbitService orbit;

    public OrbitJobManager(OrbitService orbit,
                           @Value("${aerofleet.orbit-pool-size:2}") int poolSize) {
        this.orbit = orbit;
        this.pool = Executors.newFixedThreadPool(Math.max(1, poolSize), r -> {
            Thread t = new Thread(r, "orbit-job");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Submit an orbit for {@code sysid}. Throws IllegalStateException when this
     * drone already has an in-flight job. Returns the job handle immediately;
     * the flight itself runs on the pool.
     */
    public OrbitJob submit(int sysid, double lat, double lon,
                           double radiusM, double altM, int photos) {
        // Validate BEFORE registering anything: bad params must not create jobs.
        if (radiusM <= 0 || radiusM > 500) {
            throw new IllegalArgumentException("radiusM must be in (0, 500]");
        }
        if (altM < 20 || altM > 120) {
            throw new IllegalArgumentException("altM must be in [20, 120] (legal/safe band)");
        }
        if (photos < 2 || photos > 12) {
            throw new IllegalArgumentException("photos must be in [2, 12]");
        }
        OrbitJob job = new OrbitJob(sysid, lat, lon, radiusM, altM, photos);
        OrbitJob prev = inFlight.putIfAbsent(sysid, job);
        if (prev != null && !terminal(prev.state)) {
            throw new IllegalStateException(
                    "drone " + sysid + " already has an orbit job in progress (" + prev.id + ")");
        }
        if (prev != null) {
            inFlight.replace(sysid, prev, job);   // terminal leftovers: take over
        }
        jobs.put(job.id, job);
        pool.submit(() -> run(job));
        log.info("orbit job {} submitted for sysid={} (r={}m alt={}m photos={})",
                job.id, sysid, radiusM, altM, photos);
        return job;
    }

    private void run(OrbitJob job) {
        job.state = JobState.RUNNING;
        try {
            Map<String, Object> full = orbit.orbitAndTrack(
                    job.sysid, job.lat, job.lon, job.radiusM, job.altM,
                    job.photosRequested, job.progress);
            job.result = full;
            job.state = JobState.DONE;
        } catch (IllegalArgumentException e) {
            job.error = e.getMessage();
            job.state = JobState.FAILED;
        } catch (Exception e) {
            job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            job.state = JobState.FAILED;
        } finally {
            job.finishedAt = System.currentTimeMillis();
            inFlight.remove(job.sysid, job);
            pruneOldJobs();
        }
    }

    /** Job snapshot for the REST layer (null when unknown). */
    public OrbitJob get(String jobId) {
        return jobs.get(jobId);
    }

    /** REST view of a job (progress so far / final result). */
    public Map<String, Object> viewOf(OrbitJob j) {
        Map<String, Object> m = new HashMap<>();
        m.put("jobId", j.id);
        m.put("sysid", j.sysid);
        m.put("state", j.state.name());
        m.put("center", Map.of("lat", j.lat, "lon", j.lon,
                "radiusM", j.radiusM, "altM", j.altM));
        m.put("photosRequested", j.photosRequested);
        m.put("photosTaken", j.progress.size());
        m.put("progress", new ArrayList<>(j.progress));
        m.put("startedAt", j.startedAt);
        if (j.finishedAt > 0) {
            m.put("finishedAt", j.finishedAt);
        }
        if (j.error != null) {
            m.put("error", j.error);
        }
        if (j.state == JobState.DONE && j.result != null) {
            m.put("result", j.result);
        }
        return m;
    }

    private static boolean terminal(JobState s) {
        return s == JobState.DONE || s == JobState.FAILED || s == JobState.TIMEOUT;
    }

    /** Drop terminal jobs older than 30 min so the map cannot grow forever. */
    private void pruneOldJobs() {
        long cutoff = System.currentTimeMillis() - 30 * 60_000L;
        jobs.entrySet().removeIf(e -> terminal(e.getValue().state)
                && e.getValue().finishedAt > 0
                && e.getValue().finishedAt < cutoff);
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }
}

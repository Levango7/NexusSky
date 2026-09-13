package io.aerofleet.cloud.vision;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * OrbitJobManager lifecycle tests (D1). A fake truth-HTTP sidecar (JDK
 * HttpServer) stands in for drone-sim, so the full job state machine runs
 * without a simulator. The OrbitService itself is a stub that only reads
 * the truth channel - job scheduling semantics are what we assert here.
 */
class OrbitJobManagerTest {

    private HttpServer truth;
    private OrbitJobManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.shutdown();
        }
        if (truth != null) {
            truth.stop(0);
        }
    }

    /** Boot a truth sidecar that always reports the given shots JSON. */
    private void startTruth(String shotsJson) throws Exception {
        truth = HttpServer.create(new InetSocketAddress(18099), 0);
        truth.createContext("/camera/shots", ex -> {
            byte[] body = shotsJson.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        truth.createContext("/targets", ex -> {
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        truth.start();
    }

    private OrbitJobManager managerWithRealOrbit() {
        // Real OrbitService against the fake truth base; commands is not
        // exercised by these tests (submit() validation + job map semantics
        // do not need a flight).
        OrbitService svc = new OrbitService(null, null, null, "http://127.0.0.1:18099");
        manager = new OrbitJobManager(svc, 2);
        return manager;
    }

    @Test
    void invalidParamsRejectedWithoutCreatingJob() {
        OrbitJobManager m = managerWithRealOrbit();
        assertThrows(IllegalArgumentException.class,
                () -> m.submit(1, 22.59, 113.93, 600, 60, 4), "radius 600");
        assertThrows(IllegalArgumentException.class,
                () -> m.submit(1, 22.59, 113.93, 25, 200, 4), "alt 200");
        assertThrows(IllegalArgumentException.class,
                () -> m.submit(1, 22.59, 113.93, 25, 60, 13), "photos 13");
        // No job was created for the rejected submissions
        try {
            java.lang.reflect.Field f = OrbitJobManager.class.getDeclaredField("jobs");
            f.setAccessible(true);
            Map<?, ?> jobs = (Map<?, ?>) f.get(m);
            assertTrue(jobs.isEmpty(), "no jobs registered from invalid submits");
        } catch (Exception refl) {
            fail("reflection probe failed: " + refl);
        }
    }

    @Test
    void submitReturnsHandleAndRejectsDuplicateInFlight() {
        OrbitJobManager m = managerWithRealOrbit();
        OrbitJobManager.OrbitJob j = m.submit(9, 22.5916, 113.9345, 25, 60, 2);
        assertNotNull(j.id);
        // STARTED->RUNNING may happen within microseconds on the pool thread;
        // what must hold is: not terminal at submit time.
        assertFalse(j.state == OrbitJobManager.JobState.DONE
                || j.state == OrbitJobManager.JobState.FAILED
                || j.state == OrbitJobManager.JobState.TIMEOUT,
                "job cannot be terminal immediately after submit");
        assertEquals(2, j.photosRequested);
        // same drone while in flight -> IllegalStateException (409 semantics)
        assertThrows(IllegalStateException.class,
                () -> m.submit(9, 22.5916, 113.9345, 25, 60, 2));
        // another drone is fine
        assertNotNull(m.submit(10, 22.5916, 113.9345, 25, 60, 2));
    }

    @Test
    void jobViewExposesProgressShape() {
        OrbitJobManager m = managerWithRealOrbit();
        OrbitJobManager.OrbitJob j = m.submit(11, 22.59, 113.93, 25, 60, 4);
        Map<String, Object> view = m.viewOf(j);
        assertEquals(j.id, view.get("jobId"));
        assertEquals(0, view.get("photosTaken"), "nothing located yet");
        assertEquals(4, view.get("photosRequested"));
        assertTrue(view.get("progress") instanceof List);
        assertTrue(((List<?>) view.get("progress")).isEmpty());
        // unknown id -> null
        assertNull(m.get("no-such-job"));
    }

    @Test
    void terminalJobFreesDroneForResubmit() throws Exception {
        // Truth with a stale frameSeq only: the orbit loop will time out fast
        // because no NEW shot ever appears... that takes 240s. Instead, drive
        // the state machine directly: submit, then mark terminal via run()
        // failure - uploadMission with null commands throws instantly.
        startTruth("[]");
        OrbitService svc = new OrbitService(null, null, null, "http://127.0.0.1:18099");
        manager = new OrbitJobManager(svc, 1);
        OrbitJobManager.OrbitJob j = manager.submit(12, 22.59, 113.93, 25, 60, 2);
        // null DroneCommandService -> uploadMission NPEs -> job FAILED fast
        await().atMost(10, TimeUnit.SECONDS).until(() ->
                j.state == OrbitJobManager.JobState.FAILED);
        assertEquals("FAILED", manager.viewOf(j).get("state"));
        assertNotNull(j.error);
        // drone is free again: a new submit is accepted (not 409)
        OrbitJobManager.OrbitJob j2 = manager.submit(12, 22.59, 113.93, 25, 60, 2);
        assertNotEquals(j.id, j2.id);
    }
}

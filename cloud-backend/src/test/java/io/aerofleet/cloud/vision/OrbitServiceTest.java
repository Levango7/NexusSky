package io.aerofleet.cloud.vision;

import io.aerofleet.cloud.mission.MissionItemRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OrbitService geometry tests: the mission plan must be a real circle at
 * constant altitude with captures interleaved. No network, no drone.
 */
class OrbitServiceTest {

    private final OrbitService svc = new OrbitService(null, null, null, "http://127.0.0.1:18080");

    private static final double LAT = 22.5907;
    private static final double LON = 113.9345;
    private static final double M_PER_DEG_LAT = 111_320.0;
    private static final double M_PER_DEG_LON = M_PER_DEG_LAT * Math.cos(Math.toRadians(LAT));

    @Test
    void planShapeTakeoffArcRtl() {
        List<MissionItemRequest> plan = svc.buildCircle(LAT, LON, 50, 60, 4);
        assertEquals(4 * 2 + 2, plan.size(), "takeoff + 4x(wp,capture) + rtl");
        assertEquals("takeoff", plan.get(0).cmd());
        assertEquals("rtl", plan.get(plan.size() - 1).cmd());
        for (int i = 0; i < 4; i++) {
            assertEquals("waypoint", plan.get(1 + 2 * i).cmd());
            assertEquals("capture", plan.get(2 + 2 * i).cmd());
        }
        // Arc waypoints hold 2.5 s so the braking pitch settles before capture.
        for (int i = 0; i < 4; i++) {
            assertEquals(2.5, plan.get(1 + 2 * i).holdTime(), 1e-9, "wp hold settles attitude");
            assertEquals(0, plan.get(2 + 2 * i).holdTime(), 1e-9, "capture fires at once");
        }
    }

    @Test
    void arcPointsLieOnTheCircle() {
        List<MissionItemRequest> plan = svc.buildCircle(LAT, LON, 50, 60, 8);
        for (int i = 0; i < 8; i++) {
            MissionItemRequest wp = plan.get(1 + 2 * i);
            double dn = (wp.lat() - LAT) * M_PER_DEG_LAT;
            double de = (wp.lon() - LON) * M_PER_DEG_LON;
            assertEquals(50, Math.hypot(dn, de), 0.5, "wp " + i + " on the 50 m circle");
            assertEquals(60, wp.alt(), 1e-9, "constant altitude");
        }
    }

    @Test
    void arcIsEvenlySpaced() {
        List<MissionItemRequest> plan = svc.buildCircle(LAT, LON, 50, 60, 4);
        // first point due north, then clockwise: 0°, 90°, 180°, 270°
        double[][] expect = {
                {50, 0}, {0, 50}, {-50, 0}, {0, -50}};
        for (int i = 0; i < 4; i++) {
            MissionItemRequest wp = plan.get(1 + 2 * i);
            double dn = (wp.lat() - LAT) * M_PER_DEG_LAT;
            double de = (wp.lon() - LON) * M_PER_DEG_LON;
            assertEquals(expect[i][0], dn, 0.5, "wp " + i + " north");
            assertEquals(expect[i][1], de, 0.5, "wp " + i + " east");
        }
    }

    @Test
    void captureFollowsItsWaypoint() {
        List<MissionItemRequest> plan = svc.buildCircle(LAT, LON, 50, 60, 4);
        for (int i = 0; i < 4; i++) {
            MissionItemRequest wp = plan.get(1 + 2 * i);
            MissionItemRequest cap = plan.get(2 + 2 * i);
            assertEquals(wp.lat(), cap.lat(), 1e-12, "capture at the same spot");
            assertEquals(wp.lon(), cap.lon(), 1e-12);
        }
    }
}

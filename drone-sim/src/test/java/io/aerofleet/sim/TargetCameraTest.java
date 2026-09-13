package io.aerofleet.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Synthetic-target world + camera projection unit tests (P3-1/P3-2/P3-3
 * foundation): motion models, geo conversion, and the pinhole projection.
 */
class TargetCameraTest {

    private static final double HOME_LAT = 22.5907;
    private static final double HOME_LON = 113.9345;

    @Test
    void vehicleMovesAlongHeading() {
        TargetSimulator w = TargetSimulator.parse(HOME_LAT, HOME_LON,
                "vehicle:22.5907,113.9345:10:0:0");   // 10 m/s due north
        w.tick(5.0);
        var t = w.listTargets().get(0);
        assertEquals(50.0, t.north, 0.5, "5s at 10 m/s = 50 m north");
        assertEquals(0.0, t.east, 0.5);
    }

    @Test
    void staticTargetDoesNotMove() {
        TargetSimulator w = TargetSimulator.parse(HOME_LAT, HOME_LON,
                "static:22.5917,113.9355");
        w.tick(30.0);
        var t = w.listTargets().get(0);
        double[] ne = TargetSimulator.latLonToNe(HOME_LAT, HOME_LON, HOME_LAT + 0.001, HOME_LON + 0.001);
        assertEquals(ne[0], t.north, 0.6, "static stays at spawn (north)");
        assertEquals(ne[1], t.east, 0.6, "static stays at spawn (east)");
    }

    @Test
    void geoRoundTrip() {
        double[] ne = TargetSimulator.latLonToNe(HOME_LAT, HOME_LON, HOME_LAT + 0.001, HOME_LON + 0.002);
        double[] ll = TargetSimulator.neToLatLonStatic(HOME_LAT, HOME_LON, ne[0], ne[1]);
        assertEquals(HOME_LAT + 0.001, ll[0], 1e-9);
        assertEquals(HOME_LON + 0.002, ll[1], 1e-9);
    }

    @Test
    void malformedTargetsIgnored() {
        TargetSimulator w = TargetSimulator.parse(HOME_LAT, HOME_LON,
                "vehicle:22.5907,113.9345:10;bogus;x:y");
        assertEquals(1, w.size());
    }

    // ---- camera projection ----

    @Test
    void nadirShotCentersTargetBelowDrone() {
        TargetSimulator w = TargetSimulator.parse(HOME_LAT, HOME_LON,
                "vehicle:22.5907,113.9345");   // exactly at home
        CameraModel cam = CameraModel.defaultMappingCamera();   // 1920x1080
        // Drone directly above the target at 100 m, attitude neutral.
        CameraModel.Shot s = cam.capture(0, 0, 100, 0, 0, 0, w, HOME_LAT, HOME_LON);
        assertEquals(1, s.targets.size(), "target must be in frame");
        CameraModel.CapturedTarget t = s.targets.get(0);
        assertEquals(960.0, t.u, 2.0, "dead-center pixel u");
        assertEquals(540.0, t.v, 2.0, "dead-center pixel v");
    }

    @Test
    void offsetTargetProjectsProportionally() {
        // 90 deg FOV at 100 m: ground half-width = 100 m -> 960 px per 100 m.
        // Put the target exactly 50 m east via the meter->deg conversion.
        double lonEast = HOME_LON + 50.0 / (111_320.0 * Math.cos(Math.toRadians(HOME_LAT)));
        TargetSimulator w = TargetSimulator.parse(HOME_LAT, HOME_LON,
                "vehicle:22.5907," + String.format("%.7f", lonEast));
        CameraModel cam = CameraModel.defaultMappingCamera();
        double[] ne = TargetSimulator.latLonToNe(HOME_LAT, HOME_LON, HOME_LAT, lonEast);
        assertEquals(50.0, ne[1], 0.01, "sanity: target 50 m east");

        CameraModel.Shot s = cam.capture(0, 0, 100, 0, 0, 0, w, HOME_LAT, HOME_LON);
        assertEquals(1, s.targets.size());
        assertEquals(960 + 480, s.targets.get(0).u, 6.0, "50 m east -> ~half-frame right");
    }

    @Test
    void outOfFrameTargetNotCaptured() {
        // 90 deg FOV at 100 m covers +-100 m; a target 500 m away is out.
        TargetSimulator w = TargetSimulator.parse(HOME_LAT, HOME_LON,
                "vehicle:22.5947,113.9345");   // ~+445 m north: outside
        CameraModel cam = CameraModel.defaultMappingCamera();
        CameraModel.Shot s = cam.capture(0, 0, 100, 0, 0, 0, w, HOME_LAT, HOME_LON);
        assertEquals(0, s.targets.size(), "far target must not be captured");
    }

    @Test
    void frameSeqIncrements() {
        TargetSimulator w = TargetSimulator.parse(HOME_LAT, HOME_LON, "static:22.5907,113.9345");
        CameraModel cam = CameraModel.defaultMappingCamera();
        cam.capture(0, 0, 100, 0, 0, 0, w, HOME_LAT, HOME_LON);
        cam.capture(0, 0, 100, 0, 0, 0, w, HOME_LAT, HOME_LON);
        assertEquals(2, cam.frameCount());
    }

    @Test
    void smallAttitudeWobbleKeepsFrameTargets() {
        // Regression for the live e2e: hover wobble (~0.5 deg) must NOT eject
        // a target that is well inside the frame (53 m east at 60 m height).
        double lonEast = HOME_LON + 53.4 / (111_320.0 * Math.cos(Math.toRadians(HOME_LAT)));
        TargetSimulator w = TargetSimulator.parse(HOME_LAT, HOME_LON,
                "static:22.5907," + String.format("%.7f", lonEast));
        CameraModel cam = CameraModel.defaultMappingCamera();
        // Same attitude the real hover produced (roll=pitch=+0.557 deg).
        CameraModel.Shot s = cam.capture(0, 0, 60,
                Math.toRadians(0.557), Math.toRadians(0.557), 0, w, HOME_LAT, HOME_LON);
        assertEquals(1, s.targets.size(),
                "in-frame target must survive small attitude wobble");
        if (!s.targets.isEmpty()) {
            assertEquals(1823.0, s.targets.get(0).u, 12.0, "u ~1823 px");
        }
    }
}

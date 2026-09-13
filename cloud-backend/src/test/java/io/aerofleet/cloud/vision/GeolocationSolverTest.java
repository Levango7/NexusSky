package io.aerofleet.cloud.vision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Geolocation solver tests: the solver must invert the same pinhole chain
 * drone-sim's CameraModel uses - verified by ROUND-TRIP math (project a
 * known ground point forward with the sim's exact equations, then solve the
 * pixel back and recover the point).
 */
class GeolocationSolverTest {

    private static final double HOME_LAT = 22.5907;
    private static final double HOME_LON = 113.9345;
    private static final double M_PER_DEG_LAT = 111_320.0;
    private static final double M_PER_DEG_LON = M_PER_DEG_LAT * Math.cos(Math.toRadians(HOME_LAT));

    private static final int W = 1920, H = 1080;
    private static final double FOV = 90;

    private final GeolocationSolver solver = new GeolocationSolver();

    /** Forward projection, mirroring drone-sim CameraModel exactly. */
    private double[] forward(double targetN, double targetE,
                             double droneN, double droneE, double alt,
                             double rollDeg, double pitchDeg, double yawDeg,
                             double gPitchDeg, double gYawDeg) {
        double dx = targetN - droneN;
        double dy = targetE - droneE;
        double dz = alt;   // down direction to ground
        double yaw = Math.toRadians(yawDeg), pit = Math.toRadians(pitchDeg), rol = Math.toRadians(rollDeg);
        double sy = Math.sin(yaw), cyw = Math.cos(yaw);
        double sp = Math.sin(pit), cp = Math.cos(pit);
        double sr = Math.sin(rol), cr = Math.cos(rol);
        double bxx = cyw * cp, bxy = sy * cp, bxz = -sp;
        double byx = cyw * sr * sp - sy * cr, byy = sy * sr * sp + cyw * cr, byz = sr * cp;
        double bzx = cyw * cr * sp + sy * sr, bzy = sy * cr * sp - cyw * sr, bzz = cr * cp;
        double pbx = bxx * dx + bxy * dy + bxz * dz;
        double pby = byx * dx + byy * dy + byz * dz;
        double pbz = bzx * dx + bzy * dy + bzz * dz;
        double yg = Math.toRadians(gYawDeg), cyg = Math.cos(yg), syg = Math.sin(yg);
        double qx = cyg * pbx + syg * pby;
        double qy = -syg * pbx + cyg * pby;
        double qz = pbz;
        double pg = Math.toRadians(gPitchDeg), spg = Math.sin(pg), cpg = Math.cos(pg);
        double depth = spg * qx + cpg * qz;
        double right = qy;
        double down = cpg * qx - spg * qz;
        double f = (W / 2.0) / Math.tan(Math.toRadians(FOV) / 2);
        return new double[]{W / 2.0 + f * right / depth, H / 2.0 + f * down / depth};
    }

    private double[] neToLatLon(double n, double e) {
        return new double[]{HOME_LAT + n / M_PER_DEG_LAT, HOME_LON + e / M_PER_DEG_LON};
    }

    @Test
    void roundTripNadirNeutral() {
        // Drone at (120, -80, 100m), target 40 m north / 25 m east of it.
        double[] uv = forward(160, -55, 120, -80, 100, 0, 0, 0, 0, 0);
        GeolocationSolver.Result r = solver.solve(uv[0], uv[1], W, H, FOV,
                HOME_LAT, HOME_LON, 120, -80, 100, 0, 0, 0, 0, 0);
        assertNotNull(r);
        double[] expect = neToLatLon(160, -55);
        assertEquals(expect[0], r.lat, 1e-9, "lat recovered");
        assertEquals(expect[1], r.lon, 1e-9, "lon recovered");
        assertEquals(Math.hypot(40, 25), r.groundRangeM, 1e-6);
    }

    @Test
    void roundTripWithYawAndAttitude() {
        // Drone yawed 90 deg with some roll/pitch; target far to the side.
        double[] uv = forward(40, 90, 0, 0, 60, 8, -5, 90, 0, 0);
        GeolocationSolver.Result r = solver.solve(uv[0], uv[1], W, H, FOV,
                HOME_LAT, HOME_LON, 0, 0, 60, 8, -5, 90, 0, 0);
        assertNotNull(r);
        double[] expect = neToLatLon(40, 90);
        assertEquals(expect[0], r.lat, 1e-9, "lat with attitude");
        assertEquals(expect[1], r.lon, 1e-9, "lon with attitude");
    }

    @Test
    void roundTripObliqueGimbal() {
        // Gimbal pitched 40 deg forward: sees far ahead. Target 200 m north.
        double[] uv = forward(200, 30, 0, 0, 80, 0, 0, 0, 40, 10);
        GeolocationSolver.Result r = solver.solve(uv[0], uv[1], W, H, FOV,
                HOME_LAT, HOME_LON, 0, 0, 80, 0, 0, 0, 40, 10);
        assertNotNull(r);
        double[] expect = neToLatLon(200, 30);
        assertEquals(expect[0], r.lat, 1e-9, "lat oblique");
        assertEquals(expect[1], r.lon, 1e-9, "lon oblique");
    }

    @Test
    void centerPixelIsBoresightGroundHit() {
        // The image center looks along the boresight. With a tilted airframe
        // (roll 3, pitch -2) the boresight is NOT vertical - its ground hit
        // drifts ~alt*tan(tilt) from the nadir point. Expect the boresight
        // hit, computed by the independent forward projection.
        double droneN = 250, droneE = 150, alt = 90;
        // Forward-project a point far along the boresight: the ground hit IS
        // where the center ray lands, so project a probe and iterate once via
        // the solver itself would be circular - instead place the probe at
        // the analytic boresight direction:
        // body +z in world (roll 3, pitch -2, yaw 137):
        double[] probe = boresightGroundPoint(droneN, droneE, alt, 3, -2, 137, 0, 0);
        GeolocationSolver.Result r = solver.solve(W / 2.0, H / 2.0, W, H, FOV,
                HOME_LAT, HOME_LON, droneN, droneE, alt, 3, -2, 137, 0, 0);
        assertNotNull(r);
        double[] expect = neToLatLon(probe[0], probe[1]);
        assertEquals(expect[0], r.lat, 1e-9, "center = boresight hit (lat)");
        assertEquals(expect[1], r.lon, 1e-9, "center = boresight hit (lon)");
    }

    /** Analytic ground intersection of the camera boresight (no pinhole). */
    private double[] boresightGroundPoint(double n, double e, double alt,
                                          double rollDeg, double pitchDeg, double yawDeg,
                                          double gPitchDeg, double gYawDeg) {
        // Boresight in the yaw-rotated frame: (sin gp, 0, cos gp)
        double pg = Math.toRadians(gPitchDeg);
        double qx = Math.sin(pg), qy = 0, qz = Math.cos(pg);
        double yg = Math.toRadians(gYawDeg), cyg = Math.cos(yg), syg = Math.sin(yg);
        double pbx = cyg * qx - syg * qy;
        double pby = syg * qx + cyg * qy;
        double pbz = qz;
        double sy = Math.sin(Math.toRadians(yawDeg)), cyw = Math.cos(Math.toRadians(yawDeg));
        double sp = Math.sin(Math.toRadians(pitchDeg)), cp = Math.cos(Math.toRadians(pitchDeg));
        double sr = Math.sin(Math.toRadians(rollDeg)), cr = Math.cos(Math.toRadians(rollDeg));
        double bxx = cyw * cp, bxy = sy * cp, bxz = -sp;
        double byx = cyw * sr * sp - sy * cr, byy = sy * sr * sp + cyw * cr, byz = sr * cp;
        double bzx = cyw * cr * sp + sy * sr, bzy = sy * cr * sp - cyw * sr, bzz = cr * cp;
        double wx = pbx * bxx + pby * byx + pbz * bzx;
        double wy = pbx * bxy + pby * byy + pbz * bzy;
        double wz = pbx * bxz + pby * byz + pbz * bzz;
        double t = alt / wz;
        return new double[]{n + t * wx, e + t * wy};
    }
}

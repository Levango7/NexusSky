package io.aerofleet.cloud.vision;

import org.springframework.stereotype.Component;

/**
 * Geolocation solver: invert the pinhole camera projection to recover the
 * ground coordinates of a pixel, given the camera pose and intrinsics.
 *
 * Forward chain (what the simulator does): world point -> body -> camera ->
 * pixel. This class runs it backwards: pixel -> camera ray -> body ray ->
 * world ray -> intersect the ground plane (z=0 in local meters) -> lat/lon.
 *
 * Conventions must mirror drone-sim's CameraModel exactly:
 *   - World: NED meters (x north, y east, z down), camera at (n, e, alt).
 *   - Body: x forward, y right, z down (aerospace ZYX from yaw/pitch/roll).
 *   - Camera neutral: boresight +z_body (nadir); gimbal yaw about z_body;
 *     gimbal pitch tips boresight from +z toward +x (0 = nadir, 90 = horizon).
 *   - Pinhole: u = cx + f*right/depth, v = cy + f*down/depth.
 */
@Component
public class GeolocationSolver {

    public static final class Result {
        public final double lat;
        public final double lon;
        public final double groundRangeM;   // horizontal distance camera->point

        Result(double lat, double lon, double groundRangeM) {
            this.lat = lat;
            this.lon = lon;
            this.groundRangeM = groundRangeM;
        }
    }

    private static final double M_PER_DEG_LAT = 111_320.0;

    /**
     * Solve the ground position of one pixel.
     *
     * @param u,v            pixel coordinates (origin top-left)
     * @param imageW,imageH  sensor size in pixels
     * @param hFovDeg        horizontal field of view
     * @param homeLat,homeLon  local-frame reference (the sim's home)
     * @param droneN,droneE,alt  camera position in local meters (alt up-positive)
     * @param rollDeg,pitchDeg,yawDeg  drone attitude (true values, degrees)
     * @param gimbalPitchDeg,gimbalYawDeg  gimbal (0/0 = straight down)
     */
    public Result solve(double u, double v, int imageW, int imageH, double hFovDeg,
                        double homeLat, double homeLon,
                        double droneN, double droneE, double alt,
                        double rollDeg, double pitchDeg, double yawDeg,
                        double gimbalPitchDeg, double gimbalYawDeg) {
        double f = (imageW / 2.0) / Math.tan(Math.toRadians(hFovDeg) / 2);
        double cu = imageW / 2.0;
        double cv = imageH / 2.0;

        // Camera-frame ray direction for this pixel (inverse perspective).
        // Boresight axis z_cam; image right +x_cam; image down +y_cam.
        double rx = (u - cu) / f;   // right component per unit depth
        double ry = (v - cv) / f;   // down component per unit depth
        double rz = 1.0;            // along boresight

        // Invert the gimbal: camera -> yaw-rotated body frame.
        // Camera axes in the yaw-rotated frame: boresight B=(sin p,0,cos p),
        // right=(0,1,0), down D=(cos p,0,-sin p). So the ray in the yaw frame:
        double pg = Math.toRadians(gimbalPitchDeg);
        double spg = Math.sin(pg), cpg = Math.cos(pg);
        double qx = rz * spg + ry * cpg;    // forward component
        double qy = rx;                     // right component
        double qz = rz * cpg - ry * spg;    // down component

        // Invert gimbal yaw (rotation about z).
        double yg = Math.toRadians(gimbalYawDeg);
        double cyg = Math.cos(yg), syg = Math.sin(yg);
        double pbx = cyg * qx - syg * qy;
        double pby = syg * qx + cyg * qy;
        double pbz = qz;

        // Body -> world: transpose of the world->body ZYX rotation.
        double sy = Math.sin(Math.toRadians(yawDeg)), cyw = Math.cos(Math.toRadians(yawDeg));
        double sp = Math.sin(Math.toRadians(pitchDeg)), cp = Math.cos(Math.toRadians(pitchDeg));
        double sr = Math.sin(Math.toRadians(rollDeg)), cr = Math.cos(Math.toRadians(rollDeg));
        // Body axes in world coords (rows of R_w2b = columns of R_b2w):
        double bxx = cyw * cp,      bxy = sy * cp,       bxz = -sp;
        double byx = cyw * sr * sp - sy * cr, byy = sy * sr * sp + cyw * cr, byz = sr * cp;
        double bzx = cyw * cr * sp + sy * sr, bzy = sy * cr * sp - cyw * sr, bzz = cr * cp;
        // world ray = pbx*body_x_axis + pby*body_y_axis + pbz*body_z_axis
        double wx = pbx * bxx + pby * byx + pbz * bzx;
        double wy = pbx * bxy + pby * byy + pbz * bzy;
        double wz = pbx * bxz + pby * byz + pbz * bzz;

        // Intersect with the ground plane z=0. World z is DOWN-positive, the
        // camera sits at z = -alt (above ground). Ray: P = C + t*w.
        // Ground: 0 = -alt + t*wz  ->  t = alt / wz, needs wz > 0 (pointing down).
        if (wz <= 1e-9) {
            return null; // ray points up or horizontal: no ground hit
        }
        double t = alt / wz;
        double gn = droneN + t * wx;
        double ge = droneE + t * wy;

        double lat = homeLat + gn / M_PER_DEG_LAT;
        double lon = homeLon + ge / (M_PER_DEG_LAT * Math.cos(Math.toRadians(homeLat)));
        return new Result(lat, lon, Math.hypot(gn - droneN, ge - droneE));
    }

    /** Null-safe variant used by the pipeline (drops unsolvable pixels). */
    public Result solveOrNull(double u, double v, int imageW, int imageH, double hFovDeg,
                              double homeLat, double homeLon,
                              double droneN, double droneE, double alt,
                              double rollDeg, double pitchDeg, double yawDeg,
                              double gimbalPitchDeg, double gimbalYawDeg) {
        return solve(u, v, imageW, imageH, hFovDeg, homeLat, homeLon,
                droneN, droneE, alt, rollDeg, pitchDeg, yawDeg,
                gimbalPitchDeg, gimbalYawDeg);
    }
}

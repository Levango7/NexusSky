package io.aerofleet.sim;

/**
 * Virtual camera + gimbal model for the imaging pipeline (P3-2).
 *
 * The camera looks STRAIGHT DOWN by default (a mapping camera); the gimbal
 * can pitch/yaw it for oblique shots. "Capturing" projects every ground
 * target into the image plane and reports which ones landed inside the
 * frame with their pixel coordinates - that is the synthetic photo the
 * downstream detector/geolocation consumes. No pixels are rendered: the
 * metadata IS the image in a simulation world.
 *
 * Intrinsics are the classic pinhole model with a square pixel grid:
 *   fx = fy = (imageWidth / 2) / tan(hFov / 2)
 */
public final class CameraModel {

    public static final class CapturedTarget {
        public final int targetId;
        public final String kind;
        public final double u;    // pixels, origin top-left
        public final double v;
        public final double lat;
        public final double lon;

        CapturedTarget(int targetId, String kind, double u, double v, double lat, double lon) {
            this.targetId = targetId;
            this.kind = kind;
            this.u = u;
            this.v = v;
            this.lat = lat;
            this.lon = lon;
        }
    }

    public static final class Shot {
        public final long frameSeq;
        public final long timeMs;
        public final double lat;        // camera position
        public final double lon;
        public final double altM;      // above ground
        public final double gimbalPitchDeg;
        public final double gimbalYawDeg;
        public final double droneRollDeg;
        public final double dronePitchDeg;
        public final double droneYawDeg;
        public final java.util.List<CapturedTarget> targets;

        Shot(long frameSeq, long timeMs, double lat, double lon, double altM,
             double gimbalPitchDeg, double gimbalYawDeg,
             double droneRollDeg, double dronePitchDeg, double droneYawDeg,
             java.util.List<CapturedTarget> targets) {
            this.frameSeq = frameSeq;
            this.timeMs = timeMs;
            this.lat = lat;
            this.lon = lon;
            this.altM = altM;
            this.gimbalPitchDeg = gimbalPitchDeg;
            this.gimbalYawDeg = gimbalYawDeg;
            this.droneRollDeg = droneRollDeg;
            this.dronePitchDeg = dronePitchDeg;
            this.droneYawDeg = droneYawDeg;
            this.targets = targets;
        }
    }

    public final int imageWidth;
    public final int imageHeight;
    public final double hFovRad;     // horizontal field of view

    /** Gimbal state (degrees). pitch 0 = down, 90 = horizontal. */
    private double gimbalPitchDeg = 0;
    private double gimbalYawDeg = 0;

    private long frameSeq = 0;

    public CameraModel(int imageWidth, int imageHeight, double hFovDeg) {
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.hFovRad = Math.toRadians(hFovDeg);
    }

    /** Default mapping camera: 1920x1080, 90 deg FOV. */
    public static CameraModel defaultMappingCamera() {
        return new CameraModel(1920, 1080, 90);
    }

    public void pointGimbal(double pitchDeg, double yawDeg) {
        this.gimbalPitchDeg = pitchDeg;
        this.gimbalYawDeg = yawDeg;
    }

    public double gimbalPitchDeg() {
        return gimbalPitchDeg;
    }

    public double gimbalYawDeg() {
        return gimbalYawDeg;
    }

    /**
     * Capture a shot: project ground targets into the image plane.
     * The camera model handles the pure-nadir case (gimbal pitch 0) and the
     * general oblique case via ray-plane intersection in the camera frame.
     *
     * @param droneN/E/alt   drone position in the local meter frame
     * @param droneRollRad/pitchRad/yawRad   drone attitude (true values)
     * @param world          the target world to "photograph"
     */
    public Shot capture(double droneN, double droneE, double alt,
                       double droneRollRad, double dronePitchRad, double droneYawRad,
                       TargetSimulator world, double homeLat, double homeLon) {
        java.util.List<CapturedTarget> seen = new java.util.ArrayList<>();
        for (TargetSimulator.Target t : world.listTargets()) {
            double[] uv = project(t, droneN, droneE, alt,
                    droneRollRad, dronePitchRad, droneYawRad);
            if (uv != null) {
                double[] ll = TargetSimulator.neToLatLonStatic(homeLat, homeLon, t.north, t.east);
                seen.add(new CapturedTarget(t.id, t.kind.name().toLowerCase(),
                        uv[0], uv[1], ll[0], ll[1]));
            }
        }
        return new Shot(++frameSeq, System.currentTimeMillis(),
                TargetSimulator.neToLatLonStatic(homeLat, homeLon, droneN, droneE)[0],
                TargetSimulator.neToLatLonStatic(homeLat, homeLon, droneN, droneE)[1],
                alt, gimbalPitchDeg, gimbalYawDeg,
                Math.toDegrees(droneRollRad), Math.toDegrees(dronePitchRad),
                Math.toDegrees(droneYawRad), seen);
    }

    /**
     * Project one ground target (z=0 plane) into pixel coordinates, or null
     * when it falls outside the frame / behind the camera.
     *
     * Frames and conventions (kept deliberately explicit - this math is the
     * heart of the geolocation chain and must survive refactors):
     *   - World: NED meters (x north, y east, z DOWN); camera at
     *     (droneN, droneE, alt) with z = +alt meaning DOWN.
     *   - Body: x forward, y right, z down (aerospace ZYX from yaw/pitch/roll).
     *   - Camera neutral (gimbal 0/0): boresight = +z_body (straight down),
     *     image-right = +y_body, image-down = +x_body (the tail direction:
     *     flying north, south appears at the bottom of the nadir image).
     *   - Gimbal yaw rotates the camera about z_body (heading of the boresight
     *     in the image plane); gimbal pitch tips the boresight from +z toward
     *     +x (0 = nadir, 90 = horizon-forward).
     *   - Pinhole: u = cx + f*X/Z, v = cy + f*Y/Z, Z = depth along boresight.
     */
    private double[] project(TargetSimulator.Target t, double droneN, double droneE,
                             double alt, double rollRad, double pitchRad, double yawRad) {
        // Ray from camera to target, world NED (z down-positive).
        double dx = t.north - droneN;
        double dy = t.east - droneE;
        double dz = alt;                     // ground is at z=0: full depth below

        // World -> body (ZYX: yaw about z, pitch about y, roll about x).
        double sy = Math.sin(yawRad), cyw = Math.cos(yawRad);
        double sp = Math.sin(pitchRad), cp = Math.cos(pitchRad);
        double sr = Math.sin(rollRad), cr = Math.cos(rollRad);
        // Body axes expressed in world coordinates (rows of R_world->body):
        // body_x(forward) = (cy*cp, sy*cp, -sp)
        // body_y(right)   = (cy*sr*sp - sy*cr, sy*sr*sp + cy*cr, sr*cp)
        // body_z(down)    = (cy*cr*sp + sy*sr, sy*cr*sp - cy*sr, cr*cp)
        double bxx = cyw * cp,      bxy = sy * cp,       bxz = -sp;
        double byx = cyw * sr * sp - sy * cr, byy = sy * sr * sp + cyw * cr, byz = sr * cp;
        double bzx = cyw * cr * sp + sy * sr, bzy = sy * cr * sp - cyw * sr, bzz = cr * cp;
        // Direction in body coords: dot products.
        double pbx = bxx * dx + bxy * dy + bxz * dz;
        double pby = byx * dx + byy * dy + byz * dz;
        double pbz = bzx * dx + bzy * dy + bzz * dz;

        // Gimbal yaw: rotate about z_body. The IMAGE-right axis rotates with
        // the camera, so apply the inverse rotation to the direction.
        double yg = Math.toRadians(gimbalYawDeg);
        double cyg = Math.cos(yg), syg = Math.sin(yg);
        // after yaw: components in (yaw-rotated) frame
        double qx = cyg * pbx + syg * pby;   // forward-in-image-plane
        double qy = -syg * pbx + cyg * pby;  // right-in-image-plane
        double qz = pbz;                     // down (boresight at pitch 0)

        // Gimbal pitch: tips boresight from +z toward +x. Camera axes in the
        // yaw-rotated frame: boresight B = (sin p, 0, cos p);
        // image-right = (0, 1, 0); image-down D = (cos p, 0, -sin p).
        double pg = Math.toRadians(gimbalPitchDeg);
        double cpg = Math.cos(pg), spg = Math.sin(pg);
        double depth = spg * qx + cpg * qz;             // dot with boresight
        double right = qy;                              // dot with image-right
        double down = cpg * qx - spg * qz;              // dot with image-down

        if (depth <= 0) {
            return null; // behind the camera / above horizon
        }

        double f = (imageWidth / 2.0) / Math.tan(hFovRad / 2);
        double u = imageWidth / 2.0 + f * right / depth;
        double v = imageHeight / 2.0 + f * down / depth;

        if (u < 0 || u >= imageWidth || v < 0 || v >= imageHeight) {
            return null;
        }
        return new double[]{u, v};
    }

    public long frameCount() {
        return frameSeq;
    }
}

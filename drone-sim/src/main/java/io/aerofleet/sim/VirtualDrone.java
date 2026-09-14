package io.aerofleet.sim;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.Attitude;
import io.aerofleet.mavlink.messages.CommandAck;
import io.aerofleet.mavlink.messages.CommandLong;
import io.aerofleet.mavlink.messages.GlobalPositionInt;
import io.aerofleet.mavlink.messages.GpsRawInt;
import io.aerofleet.mavlink.messages.Heartbeat;
import io.aerofleet.mavlink.messages.HomePosition;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.messages.MissionAckMsg;
import io.aerofleet.mavlink.messages.MissionCountMsg;
import io.aerofleet.mavlink.messages.MissionCurrent;
import io.aerofleet.mavlink.messages.MissionItemInt;
import io.aerofleet.mavlink.messages.MissionRequestInt;
import io.aerofleet.mavlink.messages.Statustext;
import io.aerofleet.mavlink.messages.RadioStatus;
import io.aerofleet.mavlink.messages.SysStatus;
import io.aerofleet.mavlink.messages.SystemTimeMsg;
import io.aerofleet.mavlink.messages.VfrHud;
import io.aerofleet.mavlink.transport.UdpMavlinkTransport;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The virtual drone: wires UDP MAVLink transport, Mission Protocol server logic,
 * COMMAND_LONG handling, and the 20 Hz physics/telemetry tick loop together.
 *
 * Frame listeners run on the transport receive thread; the tick loop runs on a
 * scheduler thread. Shared state is guarded by the intrinsic lock of this object.
 */
public final class VirtualDrone implements AutoCloseable {

    public static final int COMPONENT_ID = 1;      // MAV_COMP_ID_AUTOPILOT
    public static final long TICK_MS = 50;          // 20 Hz
    public static final int GCS_SYSID = 255;        // MAV_SYSTEM_GCS

    private final SimConfig config;
    private final UdpMavlinkTransport transport;
    private final MissionStore missions;
    private final DronePhysics physics;
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger frameSeq = new AtomicInteger(0);
    private final long bootUnixMs = System.currentTimeMillis();
    /** Fault-injection controller (inert when --scenario none). */
    private final ScenarioController scenario;
    /** Autopilot failsafe layer (link loss / battery crit / GPS loss). */
    private final FailsafeController failsafe;
    /** Analytic terrain (flat world when --terrain absent). */
    private final TerrainModel terrain;
    /** RF link geometry (E1): RSSI vs distance + terrain occlusion. */
    private final RadioEnvironment radio;
    /** Geofence polygon + ceiling (disabled when --fence absent). */
    private final GeoFence fence;
    /** Synthetic ground-target world (empty when --targets absent). */
    private final TargetSimulator groundTargets;
    /** Ground-truth HTTP sidecar (null when --http-port absent). */
    private final TargetStateServer truthServer;
    /** Mapping camera + gimbal (P3 imaging chain). */
    private final CameraModel camera = CameraModel.defaultMappingCamera();
    /** Recent shot metadata ring (served over the truth HTTP). */
    private final java.util.ArrayDeque<CameraModel.Shot> shots = new java.util.ArrayDeque<>();
    private static final int MAX_SHOTS = 50;
    /** Set once when the GPS-loss event fires, cleared on recovery. */
    private boolean gpsLossAnnounced = false;
    /** Epoch ms of the last received GCS packet (drives the datalink failsafe). */
    private long lastGcsRxMs = 0;

    // guarded-by-this flight state
    private FlightState state = FlightState.INIT;
    private String lastStatus = null;
    private double holdSecondsLeft = 0;
    private int currentSeq = 0;                    // mission item being flown
    private int lastAnnouncedSeq = -1;
    private boolean missionNotifiedComplete = false;
    private boolean batteryWarned = false;

    public VirtualDrone(SimConfig config) throws IOException {
        this.config = config;
        this.transport = new UdpMavlinkTransport(config.bindIp, config.port);
        this.missions = new MissionStore(config.sysid);
        this.physics = new DronePhysics(config.lat, config.lon, 0.0, config.speed);
        this.scenario = new ScenarioController(config.scenario);
        this.failsafe = config.failsafe
                ? FailsafeController.defaults() : new FailsafeController(false, 0, 0);
        this.terrain = TerrainModel.parse(config.terrain);
        this.fence = GeoFence.parse(config.fence);
        // RF link geometry (E1): GCS mast at home, 1.5 m antenna. RSSI is
        // range-correct and terrain-shadowed; reported via RADIO_STATUS.
        this.radio = new RadioEnvironment(this.terrain, 0, 0, 1.5);
        this.groundTargets = TargetSimulator.parse(config.lat, config.lon, config.targets);
        this.truthPort = config.httpPort;
        if (config.httpPort > 0) {
            this.truthServer = new TargetStateServer(groundTargets, config.httpPort,
                    () -> {
                        // Snapshot under the same lock the tick loop uses.
                        synchronized (this) {
                            return new java.util.ArrayList<>(shots);
                        }
                    },
                    this::currentRssiDbm);
            try {
                this.truthServer.start();
            } catch (java.io.IOException e) {
                SimLog.warn("ground-truth HTTP failed to start on port "
                        + config.httpPort + ": " + e.getMessage());
                throw new RuntimeException(e);
            }
        } else {
            this.truthServer = null;
        }
        SimLog.info("terrain: " + terrain.summary() + " | fence: " + fence.summary()
                + " | targets: " + groundTargets.size());
        this.lastGoodLat = config.lat;
        this.lastGoodLon = config.lon;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "drone-sim-tick");
            t.setDaemon(true);
            return t;
        });
        transport.addFrameListener(this::onFrame);
    }

    /** Start the tick loop; first tick immediately marks STANDBY. */
    public synchronized void start() {
        this.state = FlightState.STANDBY;
        scheduler.scheduleAtFixedRate(this::tickSafe, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        transport.close();
    }

    // ------------------------------------------------------------------
    // inbound MAVLink frames
    // ------------------------------------------------------------------

    private void onFrame(MavlinkFrame frame) {
        // Link-loss scenario black-holes BOTH directions, like a real radio
        // link going down: inbound GCS packets are dropped too, so the
        // datalink failsafe (silence timer) sees exactly what a real vehicle
        // would see. Without this the 1Hz GCS heartbeat would keep the
        // failsafe quiet while only telemetry was black-holed.
        double bootSec = (System.currentTimeMillis() - bootUnixMs) / 1000.0;
        if (scenario.linkLost(bootSec)) {
            return;
        }
        // Any GCS packet (heartbeat, mission traffic, commands) counts as
        // "the datalink is alive" for the failsafe layer.
        lastGcsRxMs = System.currentTimeMillis();
        // Accept frames from any GCS sysid; decode only messages we act on.
        MavlinkMessage msg;
        try {
            msg = MavlinkMessage.decode(frame);
        } catch (RuntimeException e) {
            return; // malformed payload for its type: drop quietly
        }
        if (msg == null) {
            return;
        }
        try {
            synchronized (this) {
                if (msg instanceof MissionCountMsg mc) {
                    handleMissionCount(mc);
                } else if (msg instanceof MissionItemInt item) {
                    handleMissionItem(item);
                } else if (msg instanceof io.aerofleet.mavlink.messages.MissionRequestList rl) {
                    handleMissionRequestList(rl, frame.getSystemId());
                } else if (msg instanceof io.aerofleet.mavlink.messages.MissionRequestInt mr) {
                    // Download direction: GCS asks for stored item #seq.
                    handleMissionItemRequest(mr, frame.getSystemId());
                } else if (msg instanceof CommandLong cmd) {
                    handleCommandLong(cmd, frame.getSystemId());
                } else if (msg instanceof io.aerofleet.mavlink.messages.ManualControl mc) {
                    handleManualControl(mc);
                } else if (msg instanceof Heartbeat) {
                    // A GCS heartbeat means somebody is listening: push home once.
                    onFirstPeerSeen();
                }
            }
        } catch (IOException e) {
            SimLog.error("send failed", e);
        }
    }

    // ------------------------------------------------------------------
    // Mission Protocol (server role)
    // ------------------------------------------------------------------

    /** GCS requested stored item #seq during a download: serve it. */
    private void handleMissionItemRequest(
            io.aerofleet.mavlink.messages.MissionRequestInt mr, int gcsSysid)
            throws IOException {
        if (missions.isUploading()) {
            // An upload session is active on this link: a REQUEST_INT arriving
            // now belongs to it (loss recovery re-request), not a download.
            return;
        }
        MissionItemInt item = missions.get(mr.seq);
        if (item != null) {
            // Re-target the stored item at the requesting GCS.
            send(new MissionItemInt(gcsSysid, COMPONENT_ID, item.seq, item.frame,
                    item.command, item.current, item.autocontinue,
                    item.param1, item.param2, item.param3, item.param4,
                    item.x, item.y, item.z, item.missionType));
        } else {
            // Unknown seq: end the transfer with an error ack.
            send(new MissionAckMsg(gcsSysid, COMPONENT_ID,
                    MavEnums.MAV_MISSION_INVALID, mr.missionType, 0));
        }
    }

    /**
     * GCS wants to READ the stored mission (mission download): reply with a
     * MISSION_COUNT of what we have; the GCS then requests items by seq.
     */
    private void handleMissionRequestList(io.aerofleet.mavlink.messages.MissionRequestList rl,
                                          int gcsSysid) throws IOException {
        if (state == FlightState.CRASHED) {
            send(new MissionAckMsg(gcsSysid, COMPONENT_ID,
                    MavEnums.MAV_MISSION_DENIED, rl.missionType, 0));
            return;
        }
        int count = missions.hasMission() ? missions.size() : 0;
        SimLog.info("MISSION_REQUEST_LIST: replying count=" + count);
        send(new MissionCountMsg(count, gcsSysid, COMPONENT_ID, rl.missionType, 0));
    }

    private void handleMissionCount(MissionCountMsg mc) throws IOException {
        // Mission upload is allowed both before and after arming (PX4 behavior).
        if (mc.count <= 0) {
            SimLog.info("MISSION_COUNT 0 received: clearing mission");
            missions.abortUpload();
            send(new MissionAckMsg(config.sysid, COMPONENT_ID,
                    MavEnums.MAV_MISSION_ACCEPTED, mc.missionType, 0));
            return;
        }
        int first = missions.beginUpload(mc.count);
        SimLog.info("MISSION_COUNT received: " + mc.count + " items, requesting seq 0");
        if (first < 0) {
            send(new MissionAckMsg(config.sysid, COMPONENT_ID,
                    MavEnums.MAV_MISSION_NO_SPACE, mc.missionType, 0));
            missions.abortUpload();
            return;
        }
        lastUploadProgressMs = System.currentTimeMillis();
        sendMissionRequest(first, mc.missionType);
    }

    /** Loss recovery: while an upload session is open, re-request the current
     *  seq every 3s without progress (a GCS behind a lossy link resends items). */
    private void reRequestIfStalled() throws IOException {
        if (!missions.isUploading()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastUploadProgressMs > 3_000) {
            int want = missions.nextExpectedSeq();
            SimLog.info("upload stalled, re-requesting seq " + want);
            sendMissionRequest(want, 0);
            lastUploadProgressMs = now;
        }
    }

    private long lastUploadProgressMs = 0;

    private void handleMissionItem(MissionItemInt item) throws IOException {
        int next = missions.onItem(item);
        lastUploadProgressMs = System.currentTimeMillis();
        if (next < 0) {
            // All items received: accept and store.
            SimLog.info("mission upload complete: " + missions.size() + " items stored");
            SimLog.info(missions.summary());
            send(new MissionAckMsg(config.sysid, COMPONENT_ID,
                    MavEnums.MAV_MISSION_ACCEPTED, 0, 0));
            missions.commit();
            missionNotifiedComplete = false;
            pushStatus(MavEnums.MAV_SEVERITY_INFO,
                    "Mission uploaded: " + missions.size() + " waypoints");
        } else {
            if (item.seq != next) {
                SimLog.info("duplicate/out-of-order item seq=" + item.seq
                        + ", re-requesting seq=" + next);
            }
            sendMissionRequest(next, 0);
        }
    }

    private void sendMissionRequest(int seq, int missionType) throws IOException {
        send(new MissionRequestInt(seq, config.sysid, COMPONENT_ID, missionType));
    }

    // ------------------------------------------------------------------
    // COMMAND_LONG handling
    // ------------------------------------------------------------------

    private void handleCommandLong(CommandLong cmd, int senderSysid) throws IOException {
        int result;
        switch (cmd.command) {
            case MavEnums.MAV_CMD_COMPONENT_ARM_DISARM -> result = handleArmDisarm(cmd);
            case MavEnums.MAV_CMD_NAV_TAKEOFF -> result = handleTakeoff(cmd);
            case MavEnums.MAV_CMD_MISSION_START -> result = handleMissionStart();
            case MavEnums.MAV_CMD_NAV_RETURN_TO_LAUNCH -> result = handleRtl();
            case MavEnums.MAV_CMD_NAV_LAND -> {
                // Not required, but easy: descend in place from wherever we are.
                result = handleLand();
            }
            case io.aerofleet.mavlink.enums.MavEnums.MAV_CMD_IMAGE_START_CAPTURE -> {
                // Camera trigger: capture a shot of the synthetic world.
                result = handleImageCapture(senderSysid);
            }
            case io.aerofleet.mavlink.enums.MavEnums.MAV_CMD_REQUEST_CAMERA_INFORMATION -> {
                sendCameraInformation(senderSysid);
                result = MavEnums.MAV_RESULT_ACCEPTED;
            }
            case io.aerofleet.mavlink.enums.MavEnums.MAV_CMD_REQUEST_CAMERA_SETTINGS -> {
                sendCameraSettings(senderSysid);
                result = MavEnums.MAV_RESULT_ACCEPTED;
            }
            case io.aerofleet.mavlink.enums.MavEnums.MAV_CMD_REQUEST_CAMERA_CAPTURE_STATUS -> {
                sendCameraCaptureStatus(senderSysid);
                result = MavEnums.MAV_RESULT_ACCEPTED;
            }
            case io.aerofleet.mavlink.enums.MavEnums.MAV_CMD_REQUEST_MESSAGE -> {
                // Generic once-shot request: param1 names the message id.
                int wanted = Math.round(cmd.param1);
                if (wanted == io.aerofleet.mavlink.messages.CameraInformation.ID) {
                    sendCameraInformation(senderSysid);
                    result = MavEnums.MAV_RESULT_ACCEPTED;
                } else if (wanted == io.aerofleet.mavlink.messages.CameraSettings.ID) {
                    sendCameraSettings(senderSysid);
                    result = MavEnums.MAV_RESULT_ACCEPTED;
                } else if (wanted == io.aerofleet.mavlink.messages.CameraCaptureStatus.ID) {
                    sendCameraCaptureStatus(senderSysid);
                    result = MavEnums.MAV_RESULT_ACCEPTED;
                } else {
                    SimLog.info("REQUEST_MESSAGE for unsupported id " + wanted);
                    result = MavEnums.MAV_RESULT_UNSUPPORTED;
                }
            }
            default -> {
                SimLog.info("unsupported command " + cmd.command);
                result = MavEnums.MAV_RESULT_UNSUPPORTED;
            }
        }
        send(new CommandAck(cmd.command, result, 255, 0, senderSysid, 0));
    }

    /**
     * MAV_CMD_IMAGE_START_CAPTURE: "take a photo" - project the synthetic
     * world through the current drone pose into the image plane and store
     * the shot metadata. Accepted only in flight (like a real camera that
     * cannot shoot from the ground). Every successful shot BROADCASTS a
     * CAMERA_IMAGE_CAPTURED (the protocol's authoritative photo event).
     */
    private int handleImageCapture(int gcsSysid) throws java.io.IOException {
        if (!state.armed()) {
            return MavEnums.MAV_RESULT_DENIED;
        }
        CameraModel.Shot shot = camera.capture(physics.north(), physics.east(),
                physics.alt(), physics.rollRad(), physics.pitchRad(), physics.yawRad(),
                groundTargets, config.lat, config.lon);
        // E4: a photo costs energy (camera + gimbal + storage load).
        physics.drainForPhoto();
        // Debug line kept: one line per shot is cheap and this chain (sim pose
        // -> projection -> detected targets) is the one thing e2e cannot
        // inspect any other way.
        SimLog.info(String.format(
                "IMAGE inputs: drone n=%.2f e=%.2f alt=%.2f r=%.3f p=%.3f y=%.3f; %d targets in world (first: %s)",
                physics.north(), physics.east(), physics.alt(),
                Math.toDegrees(physics.rollRad()), Math.toDegrees(physics.pitchRad()),
                Math.toDegrees(physics.yawRad()),
                groundTargets.size(),
                groundTargets.isEmpty() ? "none"
                        : String.format("n=%.2f e=%.2f",
                                groundTargets.listTargets().get(0).north,
                                groundTargets.listTargets().get(0).east)));
        shots.add(shot);
        while (shots.size() > MAX_SHOTS) {
            shots.removeFirst();
        }
        broadcastImageCaptured(shot);
        SimLog.info("IMAGE captured: frame " + shot.frameSeq + ", "
                + shot.targets.size() + " target(s) in frame");
        return MavEnums.MAV_RESULT_ACCEPTED;
    }

    // ------------------------------------------------------------------
    // Camera Protocol v2 server role
    // ------------------------------------------------------------------

    /** Camera identity: AeroFleet synthetic mapping camera. */
    private void sendCameraInformation(int gcsSysid) throws java.io.IOException {
        send(new io.aerofleet.mavlink.messages.CameraInformation(
                physics.bootMillis(),
                0x01000001L,                     // v1.0.0
                3.5f, 5.6f, 3.15f,               // focal/sensor from the 90deg FOV model
                0x03FFL,                          // captures images + video-ish flags
                camera.imageWidth, camera.imageHeight,
                0, "AeroFleet", "SIM-CAM-90", 0,
                "", 0, 0));
    }

    private void sendCameraSettings(int gcsSysid) throws java.io.IOException {
        send(new io.aerofleet.mavlink.messages.CameraSettings(
                physics.bootMillis(), 0, 0f, 0f, 0));
    }

    private void sendCameraCaptureStatus(int gcsSysid) throws java.io.IOException {
        send(new io.aerofleet.mavlink.messages.CameraCaptureStatus(
                physics.bootMillis(), 0f, 0L, 1024.0f,
                0, 0, (int) camera.frameCount(), 0));
    }

    /**
     * Broadcast the photo event after each capture: position, attitude
     * quaternion, image index, and the truth-HTTP URL as the "file".
     */
    private void broadcastImageCaptured(CameraModel.Shot shot) throws java.io.IOException {
        double[] ll = TargetSimulator.neToLatLonStatic(config.lat, config.lon,
                physics.north(), physics.east());
        send(new io.aerofleet.mavlink.messages.CameraImageCaptured(
                shot.timeMs * 1000L,
                physics.bootMillis(),
                (int) Math.round(ll[0] * 1e7),
                (int) Math.round(ll[1] * 1e7),
                0,                                 // MSL alt: ground is 0 in the sim world
                (int) Math.round(shot.altM * 1000),
                attitudeQuaternion(),
                (int) shot.frameSeq,
                0, 1,                              // camera 0, capture ok
                "http://127.0.0.1:" + truthPort + "/camera/shots"));
    }

    /** RPY (aerospace ZYX, radians) -> quaternion (w, x, y, z). */
    private float[] attitudeQuaternion() {
        double cr = Math.cos(physics.rollRad() / 2), sr = Math.sin(physics.rollRad() / 2);
        double cp = Math.cos(physics.pitchRad() / 2), sp = Math.sin(physics.pitchRad() / 2);
        double cy = Math.cos(physics.yawRad() / 2), sy = Math.sin(physics.yawRad() / 2);
        return new float[]{
                (float) (cr * cp * cy + sr * sp * sy),
                (float) (sr * cp * cy - cr * sp * sy),
                (float) (cr * sp * cy + sr * cp * sy),
                (float) (cr * cp * sy - sr * sp * cy)};
    }

    /** Truth-HTTP port for file URLs (0 when the sidecar is off). */
    private int truthPort;

    private int handleArmDisarm(CommandLong cmd) {
        // A crashed vehicle rejects everything until a process restart.
        if (state == FlightState.CRASHED) {
            pushStatus(MavEnums.MAV_SEVERITY_ERROR, "Vehicle crashed: reboot required");
            return MavEnums.MAV_RESULT_DENIED;
        }
        boolean wantArm = cmd.param1 > 0.5f;
        if (wantArm) {
            if (state.armed()) {
                return MavEnums.MAV_RESULT_ACCEPTED; // idempotent
            }
            state = FlightState.ARMED;
            SimLog.info("vehicle ARMED");
            pushStatus(MavEnums.MAV_SEVERITY_INFO, "Armed");
            return MavEnums.MAV_RESULT_ACCEPTED;
        }
        // disarm
        if (!state.armed()) {
            return MavEnums.MAV_RESULT_ACCEPTED; // already disarmed
        }
        if (isFlying()) {
            SimLog.warn("disarm denied while flying (alt=" + String.format("%.1f", physics.alt()) + "m)");
            pushStatus(MavEnums.MAV_SEVERITY_WARNING, "Disarm denied: vehicle is flying");
            return MavEnums.MAV_RESULT_DENIED;
        }
        state = FlightState.STANDBY;
        SimLog.info("vehicle DISARMED");
        pushStatus(MavEnums.MAV_SEVERITY_INFO, "Disarmed");
        return MavEnums.MAV_RESULT_ACCEPTED;
    }

    private int handleTakeoff(CommandLong cmd) {
        if (!state.armed()) {
            SimLog.warn("takeoff rejected: not armed");
            return MavEnums.MAV_RESULT_DENIED;
        }
        float targetAlt = cmd.param7;
        if (targetAlt <= 0) {
            targetAlt = 10.0f; // sensible default if GCS sends 0
        }
        physics.holdAt(targetAlt);
        // Stay in ARMED (hover climb) unless already in mission.
        if (state == FlightState.ARMED) {
            pushStatus(MavEnums.MAV_SEVERITY_INFO,
                    "Takeoff to " + String.format("%.1f", (double) targetAlt) + "m");
            SimLog.info("takeoff: climbing to " + targetAlt + " m");
        }
        return MavEnums.MAV_RESULT_ACCEPTED;
    }

    private int handleMissionStart() {
        if (!state.armed()) {
            SimLog.warn("mission start rejected: not armed");
            pushStatus(MavEnums.MAV_SEVERITY_WARNING, "Mission start denied: not armed");
            return MavEnums.MAV_RESULT_DENIED;
        }
        if (!missions.hasMission()) {
            SimLog.warn("mission start rejected: no mission");
            pushStatus(MavEnums.MAV_SEVERITY_WARNING, "Mission start denied: no mission");
            return MavEnums.MAV_RESULT_DENIED;
        }
        state = FlightState.MISSION;
        currentSeq = 0;
        lastAnnouncedSeq = -1;
        holdSecondsLeft = 0;
        missionNotifiedComplete = false;
        beginCurrentLeg();
        SimLog.info("mission START: flying " + missions.size() + " items");
        pushStatus(MavEnums.MAV_SEVERITY_INFO, "Mission started");
        return MavEnums.MAV_RESULT_ACCEPTED;
    }

    private int handleRtl() {
        if (!state.armed()) {
            SimLog.warn("RTL rejected: not armed");
            return MavEnums.MAV_RESULT_DENIED;
        }
        startRtl();
        return MavEnums.MAV_RESULT_ACCEPTED;
    }

    private int handleLand() {
        if (!state.armed()) {
            return MavEnums.MAV_RESULT_DENIED;
        }
        physics.holdAt(0.0);
        return MavEnums.MAV_RESULT_ACCEPTED;
    }

    // ------------------------------------------------------------------
    // flight control helpers
    // ------------------------------------------------------------------

    /** Enter RTL: climb to a safe altitude if needed, then fly home and land. */
    private void startRtl() {
        state = FlightState.RTL;
        double rtlAlt = Math.max(physics.alt(), 15.0);
        physics.setTarget(0, 0, rtlAlt);
        SimLog.info("RTL: returning home (target alt " + String.format("%.1f", rtlAlt) + "m)");
        pushStatus(MavEnums.MAV_SEVERITY_INFO, "Return to launch");
    }

    /** Mission leg initialization: set physics target from the current item. */
    private void beginCurrentLeg() {
        MissionItemInt item = missions.get(currentSeq);
        if (item == null) {
            SimLog.warn("mission item " + currentSeq + " missing, finishing mission");
            finishMission();
            return;
        }
        switch (item.command) {
            case MavEnums.MAV_CMD_NAV_TAKEOFF -> {
                // Hold position climb to item z.
                physics.holdAt(Math.max(item.z, 0.1));
                SimLog.info("wp " + item.seq + ": TAKEOFF to " + item.z + "m");
            }
            case MavEnums.MAV_CMD_NAV_WAYPOINT -> {
                double tn = GeoUtil.north(config.lat, config.lon, item.lat(), item.lon());
                double te = GeoUtil.east(config.lat, config.lon, item.lat(), item.lon());
                physics.setTarget(tn, te, Math.max(item.z, 0.0));
                SimLog.info("wp " + item.seq + ": fly to lat=" + item.lat()
                        + " lon=" + item.lon() + " alt=" + item.z + "m");
            }
            case MavEnums.MAV_CMD_NAV_RETURN_TO_LAUNCH -> {
                startRtl();
            }
            case MavEnums.MAV_CMD_NAV_LAND -> {
                double tn = GeoUtil.north(config.lat, config.lon, item.lat(), item.lon());
                double te = GeoUtil.east(config.lat, config.lon, item.lat(), item.lon());
                physics.setTarget(tn, te, 0.0);
            }
            case MavEnums.MAV_CMD_IMAGE_START_CAPTURE -> {
                // In-mission camera trigger (an orbit waypoint's photo item):
                // shoot immediately on reaching this item.
                try {
                    handleImageCapture(0);
                } catch (java.io.IOException e) {
                    SimLog.warn("in-mission capture failed: " + e.getMessage());
                }
                SimLog.info("wp " + item.seq + ": camera triggered in mission");
            }
            default -> {
                // Non-nav item (jump, delay...): no motion target; the tick
                // loop will announce it once and advance immediately.
                SimLog.info("wp " + item.seq + ": non-nav command " + item.command + ", skipping");
            }
        }
    }


    private void finishMission() {
        missionNotifiedComplete = true;
        SimLog.info("mission complete");
        pushStatus(MavEnums.MAV_SEVERITY_INFO, "Mission complete");
        startRtl();
    }

    // ------------------------------------------------------------------
    // 20 Hz tick loop
    // ------------------------------------------------------------------

    private void tickSafe() {
        try {
            synchronized (this) {
                tickOnce();
            }
        } catch (Throwable t) {
            SimLog.error("tick failed", t);
        }
    }

    private void tickOnce() throws IOException {
        double dt = TICK_MS / 1000.0;
        double bootSec = (System.currentTimeMillis() - bootUnixMs) / 1000.0;

        // Fault injection: wind pushes the vehicle; link loss silences ALL
        // outbound MAVLink (telemetry black-hole, exactly like a lost radio).
        double[] wind = scenario.windVector(bootSec);
        if (wind[0] != 0 || wind[1] != 0) {
            physics.applyWind(dt, wind[0], wind[1]);
        }

        physics.tick(dt);
        advanceStateMachine(dt);
        reRequestIfStalled();

        // The synthetic-target world moves with the same clock as the drone.
        if (!groundTargets.isEmpty()) {
            groundTargets.tick(dt);
        }

        // Ground collision: terminal state. Checked before anything else -
        // a wreck must stop all further flight logic immediately.
        if (state != FlightState.CRASHED
                && terrain.collided(physics.north(), physics.east(), physics.alt())) {
            state = FlightState.CRASHED;
            physics.clearTarget();
            SimLog.error("CRASHED: terrain collision at n=" + physics.north()
                    + " e=" + physics.east() + " alt=" + physics.alt());
            pushStatus(MavEnums.MAV_SEVERITY_EMERGENCY, "Terrain collision - vehicle crashed");
        }

        // Geofence: leaving the polygon or busting the ceiling triggers the
        // fence failsafe (PX4 GF_ACTION=RTL default).
        if (state.armed() && state != FlightState.CRASHED) {
            GeoFence.Violation v = fence.violationAt(
                    physics.north(), physics.east(), physics.alt());
            if (v != null && !fenceViolationActive) {
                fenceViolationActive = true;
                SimLog.warn("FAILSAFE: geofence violation (" + v + ") -> RTL");
                pushStatus(MavEnums.MAV_SEVERITY_CRITICAL,
                        "Geofence violation (" + v + "), returning home");
                startRtl();
            } else if (v == null) {
                fenceViolationActive = false;
            }
        }

        // Autopilot failsafe evaluation (before the link black-hole so the
        // vehicle reacts even while the GCS sees nothing).
        runFailsafe();

        // Manual-stick timeout: revert to position hold when sticks go quiet.
        runManualTimeout();

        if (scenario.linkLost(bootSec)) {
            // Black-hole: skip every outbound message while the link is down.
            // Heartbeat watchdog on the cloud side must flag the drone offline.
            return;
        }
        telemetryRates(dt);

        // GPS loss: degrade the reported fix and warn once per outage.
        boolean gpsLost = scenario.gpsLost(bootSec);
        if (gpsLost && !gpsLossAnnounced) {
            gpsLossAnnounced = true;
            SimLog.warn("GPS fix lost (scenario)");
            pushStatus(MavEnums.MAV_SEVERITY_CRITICAL, "GPS fix lost");
        } else if (!gpsLost && gpsLossAnnounced) {
            gpsLossAnnounced = false;
            SimLog.info("GPS fix restored (scenario)");
            pushStatus(MavEnums.MAV_SEVERITY_NOTICE, "GPS fix restored");
        }
        checkBattery();
    }

    /** True while the scenario black-holes the link (telemetry senders consult this). */
    private boolean linkDown() {
        return scenario.linkLost((System.currentTimeMillis() - bootUnixMs) / 1000.0);
    }

    // ------------------------------------------------------------------
    // autopilot failsafe layer
    // ------------------------------------------------------------------

    /**
     * Evaluate the failsafe triggers once per tick and apply the reaction.
     * Pilot commands received over the (still working) link override the
     * failsafe state until the next trigger edge.
     */
    private void runFailsafe() {
        double bootSec = (System.currentTimeMillis() - bootUnixMs) / 1000.0;
        int batteryPct = physics.batteryRemainingPct();
        if (scenario.batteryFault(bootSec)) {
            batteryPct = (int) scenario.batteryFaultPct(bootSec);
        }
        boolean gpsLost = scenario.gpsLost(bootSec);
        FailsafeController.Action action = failsafe.evaluate(
                state, lastGcsRxMs, System.currentTimeMillis(), batteryPct, gpsLost);
        switch (action) {
            case ENTER_RTL -> {
                // Remember where the mission stood so MISSION_CURRENT keeps
                // reporting a sensible seq after the failsafe RTL.
                if (state == FlightState.MISSION) {
                    missionNotifiedComplete = false;
                }
                SimLog.warn("FAILSAFE: datalink/battery critical -> RTL");
                pushStatus(MavEnums.MAV_SEVERITY_CRITICAL, "Failsafe RTL engaged");
                startRtl();
            }
            case ENTER_HOLD -> {
                // Hover at the current spot; the pre-emergency state is
                // remembered so the mission can resume when GPS returns.
                resumeAfterHold = state;
                SimLog.warn("FAILSAFE: GPS lost -> HOLD (hover)");
                pushStatus(MavEnums.MAV_SEVERITY_CRITICAL, "Failsafe: GPS lost, holding");
                physics.holdAt(physics.alt());
                state = FlightState.HOLD;
            }
            case RESUME_MISSION -> {
                FlightState back = resumeAfterHold;
                resumeAfterHold = null;
                if (back == FlightState.MISSION && missions.hasMission()) {
                    SimLog.info("FAILSAFE: GPS restored -> resume mission at seq " + currentSeq);
                    pushStatus(MavEnums.MAV_SEVERITY_NOTICE, "GPS restored, resuming mission");
                    state = FlightState.MISSION;
                    beginCurrentLeg();
                } else {
                    // Was armed-hover before the loss: return to plain ARMED.
                    SimLog.info("FAILSAFE: GPS restored -> back to ARMED");
                    state = FlightState.ARMED;
                }
            }
            case NONE -> { /* nothing to do */ }
        }
    }

    /** State to return to when a HOLD (GPS-loss failsafe) ends. */
    private FlightState resumeAfterHold;

    /** Last MANUAL_CONTROL arrival; 2s of silence reverts to position hold. */
    private long lastManualRxMs;

    /** Geofence violation edge flag (announce/RTL once per violation). */
    private boolean fenceViolationActive;

    /**
     * Virtual joystick: axes -1000..1000 (z: throttle 0..1000). Valid while
     * armed and not in an autopilot mode (MISSION/RTL/HOLD take precedence -
     * exactly like a PX4 mode switch).
     */
    private void handleManualControl(io.aerofleet.mavlink.messages.ManualControl mc) {
        lastManualRxMs = System.currentTimeMillis();
        if (state == FlightState.CRASHED) {
            return;
        }
        if (!state.armed()) {
            return; // sticks on the ground do nothing (like a real FC)
        }
        if (state == FlightState.MISSION || state == FlightState.RTL
                || state == FlightState.HOLD) {
            return; // autopilot modes own the vehicle
        }
        if (state == FlightState.ARMED) {
            state = FlightState.MANUAL;
            pushStatus(MavEnums.MAV_SEVERITY_NOTICE, "Manual control active");
            SimLog.info("MANUAL control active");
        }
        // Axis mapping (m/s and rad/s), ~8 m/s full deflection like cruise.
        double fwd = (mc.y / 1000.0) * 8.0;
        double right = (mc.x / 1000.0) * 8.0;
        double climb = ((mc.z - 500) / 500.0) * 3.0;
        double yawRate = -(mc.r / 1000.0) * 1.8;
        physics.setManualVelocity(fwd, right, climb, yawRate);
    }

    /** Revert to hover when the sticks go quiet (radio-like RC timeout). */
    private void runManualTimeout() {
        if (state == FlightState.MANUAL
                && System.currentTimeMillis() - lastManualRxMs > 2000) {
            state = FlightState.ARMED;
            physics.clearManual();
            physics.clearTarget();
            pushStatus(MavEnums.MAV_SEVERITY_WARNING, "Manual input timeout - position hold");
            SimLog.info("MANUAL timeout: position hold");
        }
    }

    private void advanceStateMachine(double dt) {
        switch (state) {
            case STANDBY, INIT, ARMED -> {
                // Hover targets are managed by commands; nothing periodic to do.
            }
            case MISSION -> advanceMission(dt);
            case RTL -> advanceRtl();
            case HOLD -> {
                // Hovering: position hold is handled by physics.holdAt; the
                // failsafe layer moves us out of HOLD when the fix returns.
            }
            default -> {
            }
        }
    }

    private void advanceMission(double dt) {
        MissionItemInt item = missions.get(currentSeq);
        if (item == null) {
            finishMission();
            return;
        }
        // Phase 1: hold at the waypoint for param1 seconds after arrival.
        if (holdSecondsLeft > 0) {
            holdSecondsLeft -= dt;
            return;
        }
        // Phase 2: wait until physics reaches this item's target.
        if (physics.hasTarget() && !physics.targetReached()) {
            return;
        }
        // Phase 3: waypoint reached (or instant item): announce once, then advance.
        if (item.seq != lastAnnouncedSeq) {
            lastAnnouncedSeq = item.seq;
            boolean needsHold = item.command == MavEnums.MAV_CMD_NAV_WAYPOINT && item.param1 > 0;
            if (needsHold) {
                holdSecondsLeft = item.param1;
                SimLog.info("wp " + item.seq + " reached, holding " + item.param1 + "s");
            } else {
                SimLog.info("wp " + item.seq + " reached");
            }
            pushStatus(MavEnums.MAV_SEVERITY_INFO, "Waypoint " + item.seq + " reached");
            if (needsHold) {
                return; // hold at the spot, next pass will consume holdSecondsLeft
            }
        }
        if (currentSeq + 1 < missions.size()) {
            currentSeq++;
            beginCurrentLeg();
        } else {
            finishMission();
        }
    }

    private void advanceRtl() {
        if (physics.alt() > ACCEPT_NEAR_GROUND) {
            // Still airborne: target is home at safe altitude, then descend.
            if (physics.targetReached()) {
                if (physics.alt() > RTL_DESCEND_SWITCH) {
                    // Arrived above home: start descending.
                    physics.holdAt(0.0);
                    SimLog.info("RTL: arrived home, descending");
                } else {
                    physics.setTarget(0, 0, 0.0);
                }
            }
        } else {
            // On the ground: mission over, disarm.
            state = FlightState.STANDBY;
            physics.clearTarget();
            SimLog.info("RTL: landed at home, disarm");
            pushStatus(MavEnums.MAV_SEVERITY_INFO, "Landed at home, disarmed");
        }
    }

    /**
     * True when the vehicle is committed to flight: executing a mission/RTL,
     * already airborne, or holding/climbing to a target well above the ground
     * (e.g. takeoff accepted and climbing). Disarm is denied in this state.
     */
    private boolean isFlying() {
        if (state.flying() || physics.alt() > AIRBORNE_ALT) {
            return true;
        }
        return physics.hasTarget() && physics.targetAlt() > AIRBORNE_ALT;
    }

    /** Above this altitude the vehicle counts as airborne (disarm denied). */
    private static final double AIRBORNE_ALT = 0.3;
    /** Above this altitude RTL first flies home, below it the target is the ground. */
    private static final double RTL_DESCEND_SWITCH = 0.5;
    private static final double ACCEPT_NEAR_GROUND = 0.15;

    // ------------------------------------------------------------------
    // telemetry scheduling (rate control on the 20 Hz tick)
    // ------------------------------------------------------------------

    private long tickCount;

    private void telemetryRates(double dt) throws IOException {
        tickCount++;
        // 1 Hz: every 20 ticks
        if (tickCount % 20 == 0) {
            sendHeartbeat();
            sendSysStatus();
            sendGpsRawInt();
            sendSystemTime();
            sendRadioStatus();
        }
        // 5 Hz: every 4 ticks
        if (tickCount % 4 == 0) {
            sendGlobalPosition();
            sendAttitude();
        }
        // 2 Hz: every 10 ticks
        if (tickCount % 10 == 0) {
            sendVfrHud();
        }
        // 2 Hz during mission
        if (state == FlightState.MISSION && tickCount % 10 == 0) {
            sendMissionCurrent();
        }
    }

    // ------------------------------------------------------------------
    // individual telemetry messages
    // ------------------------------------------------------------------

    private void sendHeartbeat() throws IOException {
        send(new Heartbeat(state.px4NavState(), MavEnums.MAV_TYPE_QUADROTOR, MavEnums.MAV_AUTOPILOT_PX4,
                state.baseMode(), state.mavState()));
    }

    private void sendSysStatus() throws IOException {
        double bootSec = (System.currentTimeMillis() - bootUnixMs) / 1000.0;
        double voltage = physics.batteryVoltage();
        int pct = physics.batteryRemainingPct();
        if (scenario.batteryFault(bootSec)) {
            // Fault: cell failure - voltage sags and remaining % jumps to the
            // scripted critical level regardless of flight time so far.
            pct = (int) scenario.batteryFaultPct(bootSec);
            voltage = DronePhysics.VOLT_EMPTY - 0.15;
        }
        int load = state.flying() ? 600 : 350;
        send(new SysStatus(0, 0, 0, load, (int) (voltage * 1000),
                state.armed() ? 180 : 0, pct));
    }

    // ------------------------------------------------------------------
    // RADIO_STATUS (E1): link quality as geometry sees it
    // ------------------------------------------------------------------

    /** RSSI below this for this long -> one low-link WARNING (throttled). */
    private static final double LINK_WARN_DBM = -90.0;
    private static final long LINK_WARN_SUSTAIN_MS = 5_000;
    private static final long LINK_WARN_THROTTLE_MS = 60_000;
    /** Last RSSI sample [dBm] and low-link state for the warning logic. */
    private double lastRssiDbm = 0;
    private long lowLinkSinceMs;
    private long lastLinkWarnMs;

    /**
     * 1 Hz link report: the radio module's view of the downlink. rssi is
     * SiK-style raw (2x dB), remrssi mirrors it (symmetric link at this
     * abstraction), txbuf is free (UDP has no real buffer pressure), noise
     * is a quiet channel. Also drives the low-link warning: sustained
     * -90 dBm for 5 s emits one WARNING, re-armed after 60 s.
     */
    private void sendRadioStatus() throws IOException {
        double dbm = radio.rssiDbm(physics.north(), physics.east(), physics.alt());
        lastRssiDbm = dbm;
        long now = System.currentTimeMillis();
        if (dbm < LINK_WARN_DBM) {
            if (lowLinkSinceMs == 0) {
                lowLinkSinceMs = now;
            } else if (now - lowLinkSinceMs > LINK_WARN_SUSTAIN_MS
                    && now - lastLinkWarnMs > LINK_WARN_THROTTLE_MS) {
                lastLinkWarnMs = now;
                SimLog.warn(String.format("link quality poor: %.0f dBm", dbm));
                pushStatus(MavEnums.MAV_SEVERITY_WARNING,
                        String.format("Link quality poor: %.0f dBm - check range/terrain", dbm));
            }
        } else {
            lowLinkSinceMs = 0;
        }
        int raw = RadioEnvironment.toSikUnits(dbm);
        send(new RadioStatus(raw, raw, 100, 20, 20, 0, 0));
    }

    /** Truth HTTP exposes the same RSSI for e2e assertions (debug aid). */
    public double currentRssiDbm() {
        return lastRssiDbm;
    }

    private void sendGlobalPosition() throws IOException {
        double bootSec = (System.currentTimeMillis() - bootUnixMs) / 1000.0;
        int hdg = (int) Math.round(Math.toDegrees(DronePhysics.normalizeAngle(physics.yawRad())) * 100);
        // With GPS noise the reported fix wanders; with GPS loss it freezes at
        // the last good spot (an EKF without external aid coasts on dead-reckoning,
        // the position simply stops updating for the fault test's purpose).
        double outLat;
        double outLon;
        if (scenario.gpsLost(bootSec)) {
            outLat = lastGoodLat;
            outLon = lastGoodLon;
        } else {
            double noise = scenario.gpsNoiseRadius(bootSec);
            outLat = physics.reportedLat(noise);
            outLon = physics.reportedLon(noise);
            lastGoodLat = outLat;
            lastGoodLon = outLon;
        }
        // Reported altitude carries the barometer drift scenario offset;
        // a bad baro makes the GCS altitude read slowly diverge from truth.
        double altReported = physics.alt() + scenario.baroDriftM(bootSec);
        send(new GlobalPositionInt((int) physics.bootMillis(),
                (int) Math.round(outLat * 1e7),
                (int) Math.round(outLon * 1e7),
                (int) Math.round(altReported * 1000),
                (int) Math.round(altReported * 1000),
                (int) Math.round(vxNorth() * 100),
                (int) Math.round(vyEast() * 100),
                (int) Math.round(-physics.vz() * 100), // NED: down positive
                hdg == 0 ? 0 : hdg % 36000));
    }

    /** Last valid GPS fix, frozen during GPS loss. */
    private double lastGoodLat;
    private double lastGoodLon;

    private void sendAttitude() throws IOException {
        double bootSec = (System.currentTimeMillis() - bootUnixMs) / 1000.0;
        // Sensor-fault scenarios corrupt the REPORTED attitude only; the
        // vehicle keeps flying on true values (an EKF with a biased gyro).
        double biasRad = Math.toRadians(scenario.imuBiasDeg(bootSec));
        double magRad = Math.toRadians(scenario.magWanderDeg(bootSec));
        send(new Attitude((int) physics.bootMillis(),
                (float) (physics.rollRad() + biasRad * 0.3),
                (float) (physics.pitchRad() + biasRad * 0.2),
                (float) DronePhysics.normalizeAngle(physics.yawRad() + biasRad + magRad),
                0f, 0f, 0f));
    }

    private void sendVfrHud() throws IOException {
        double bootSec = (System.currentTimeMillis() - bootUnixMs) / 1000.0;
        int throttle = state.flying() ? 55 : (state.armed() ? 10 : 0);
        double altReported = physics.alt() + scenario.baroDriftM(bootSec);
        send(new VfrHud((float) physics.groundSpeed(), (float) physics.groundSpeed(),
                (float) altReported, (float) physics.vz(), physics.headingDeg(), throttle));
    }

    private void sendGpsRawInt() throws IOException {
        double bootSec = (System.currentTimeMillis() - bootUnixMs) / 1000.0;
        boolean lost = scenario.gpsLost(bootSec);
        double noise = lost ? 0 : scenario.gpsNoiseRadius(bootSec);
        // fixType 0 = NO_FIX, satellites drop to 0 while lost.
        send(new GpsRawInt(System.currentTimeMillis() * 1000L,
                (int) Math.round((lost ? lastGoodLat : physics.reportedLat(noise)) * 1e7),
                (int) Math.round((lost ? lastGoodLon : physics.reportedLon(noise)) * 1e7),
                (int) Math.round(physics.alt() * 1000),
                lost ? (short) 9999 : 90,
                lost ? (short) 9999 : 80,
                (int) Math.round(physics.groundSpeed() * 100),
                (int) Math.round(Math.toDegrees(DronePhysics.normalizeAngle(physics.yawRad())) * 100),
                lost ? 0 : 3,
                lost ? 0 : 12, 0));
    }

    private void sendSystemTime() throws IOException {
        send(new SystemTimeMsg((System.currentTimeMillis() - bootUnixMs) * 1000L,
                (int) physics.bootMillis()));
    }

    private void sendMissionCurrent() throws IOException {
        int missionState = switch (state) {
            case MISSION -> MavEnums.MISSION_STATE_ACTIVE;
            case RTL -> missionNotifiedComplete
                    ? MavEnums.MISSION_STATE_COMPLETE : MavEnums.MISSION_STATE_ACTIVE;
            // Failsafe hover pauses the mission until GPS returns.
            case HOLD -> MavEnums.MISSION_STATE_PAUSED;
            default -> missions.hasMission()
                    ? MavEnums.MISSION_STATE_NOT_STARTED : MavEnums.MISSION_STATE_NO_MISSION;
        };
        send(new MissionCurrent(currentSeq, missions.size(), missionState, 0,
                0, 0, 0));
    }

    /** Send HOME_POSITION once a peer connects (common GCS convenience). */
    private void maybeSendHome() throws IOException {
        send(new HomePosition((int) Math.round(config.lat * 1e7),
                (int) Math.round(config.lon * 1e7), 0));
    }

    // ------------------------------------------------------------------
    // status text + battery warning
    // ------------------------------------------------------------------

    private void pushStatus(int severity, String text) {
        try {
            send(new Statustext(severity, text, 0, 0));
            lastStatus = text;
        } catch (IOException e) {
            SimLog.error("statustext send failed", e);
        }
    }

    private void checkBattery() throws IOException {
        double bootSec = (System.currentTimeMillis() - bootUnixMs) / 1000.0;
        int pct = physics.batteryRemainingPct();
        if (scenario.batteryFault(bootSec)) {
            pct = (int) scenario.batteryFaultPct(bootSec);
        }
        if (pct <= 20 && !batteryWarned) {
            batteryWarned = true;
            SimLog.warn("battery low: " + pct + "%");
            pushStatus(MavEnums.MAV_SEVERITY_WARNING, "Battery low: " + pct + "%");
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Horizontal velocity NED north component (m/s). */
    private double vxNorth() {
        return physics.groundSpeed() * Math.cos(physics.yawRad());
    }

    /** Horizontal velocity NED east component (m/s). */
    private double vyEast() {
        return physics.groundSpeed() * Math.sin(physics.yawRad());
    }

    /** Telemetry goes to the last known peer (learned from any inbound packet). */
    private void send(MavlinkMessage msg) throws IOException {
        MavlinkFrame frame = msg.toFrame(config.sysid, COMPONENT_ID,
                frameSeq.getAndIncrement() & 0xFF);
        SocketAddress peer = transport.getLastPeer();
        if (peer != null) {
            transport.send(frame, peer);
        }
    }

    /** True when a GCS has been seen (used for one-shot home position push). */
    private boolean homeSent;

    /** Called from onFrame when a heartbeat from a GCS arrives for the first time. */
    private void onFirstPeerSeen() {
        if (homeSent) {
            return;
        }
        homeSent = true;
        try {
            maybeSendHome();
        } catch (IOException e) {
            SimLog.error("home position send failed", e);
        }
    }

    public synchronized FlightState state() {
        return state;
    }

    public UdpMavlinkTransport transport() {
        return transport;
    }
}

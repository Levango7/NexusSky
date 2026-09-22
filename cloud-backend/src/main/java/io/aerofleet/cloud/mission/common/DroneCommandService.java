package io.aerofleet.cloud.mission.common;

import io.aerofleet.cloud.gateway.UdpGateway;
import io.aerofleet.cloud.telemetry.PendingAcks;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.CommandAck;
import io.aerofleet.mavlink.messages.CommandLong;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.messages.MissionAckMsg;
import io.aerofleet.mavlink.messages.MissionCountMsg;
import io.aerofleet.mavlink.messages.MissionItemInt;
import io.aerofleet.mavlink.messages.MissionRequestInt;
import io.aerofleet.mavlink.messages.MissionRequestList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Command channel to the drones: MAVLink COMMAND_LONG + mission protocol client.
 *
 * <p>Every method targets an explicit system id. Frames are routed per-drone via
 * {@link UdpGateway#send(int, MavlinkFrame)} (route learned from inbound frames),
 * and ack waiters are correlated by (message id, discriminator, sysid) so two
 * concurrent uploads to different drones never mix.
 *
 * <p>Command flow: COMMAND_LONG --5s--> COMMAND_ACK, one retry on timeout/reject.
 * Mission flow: MISSION_COUNT --10s--> MISSION_REQUEST_INT --&gt; MISSION_ITEM_INT
 * per requested seq --&gt; MISSION_ACK (overall 10s budget, upload aborts on NACK).
 */
@Service
public class DroneCommandService {

    private static final Logger log = LoggerFactory.getLogger(DroneCommandService.class);

    private static final long ACK_TIMEOUT_MS = 5_000;
    private static final int ACK_RETRIES = 2;          // initial try + 1 retry
    private static final long MISSION_TIMEOUT_MS = 10_000;

    /** Target component 1 = autopilot on the drone side. */
    private static final int TARGET_COMP = 1;

    private final UdpGateway gateway;
    private final PendingAcks pendings;
    private final io.aerofleet.cloud.gateway.DeviceRegistry registry;
    /** Fallback sysid when the registry is empty (skeleton: first online drone wins). */
    private final int defaultTargetSysid;
    private final AtomicInteger sequence = new AtomicInteger();

    /** Thrown when a command/mission exchange fails (timeout or negative ack). */
    public static class CommandException extends RuntimeException {
        public final int ackResult;

        public CommandException(String message, int ackResult) {
            super(message);
            this.ackResult = ackResult;
        }
    }

    public DroneCommandService(UdpGateway gateway, PendingAcks pendings,
                               io.aerofleet.cloud.gateway.DeviceRegistry registry,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${aerofleet.drone-sysid:1}") int defaultTargetSysid) {
        this.gateway = gateway;
        this.pendings = pendings;
        this.registry = registry;
        this.defaultTargetSysid = defaultTargetSysid;
    }

    /**
     * Resolve the outbound target system for a REST request. The caller passes
     * the path sysid; when it is unknown to the registry (not 0/255 and never
     * seen online) we reject rather than guess - a command to a stale id must
     * not fall through to an unrelated drone. Legacy fallback: sysid 0 means
     * "first online drone" (single-drone convenience).
     */
    private int resolveTarget(int sysid) {
        if (sysid == 0) {
            var online = registry.all().stream().filter(s -> s.online).findFirst();
            return online.map(s -> s.sysid).orElse(defaultTargetSysid);
        }
        if (registry.get(sysid) == null) {
            throw new CommandException("unknown drone sysid " + sysid, -1);
        }
        return sysid;
    }

    // =====================================================================
    // MAV_CMD wrappers (per-drone)
    // =====================================================================

    /**
     * Virtual joystick passthrough: forward MANUAL_CONTROL axes to a drone.
     * Fire-and-forget (no ACK in the protocol); callers send at ~10 Hz.
     */
    public void manualControl(int sysid, int x, int y, int z, int r) {
        int target = resolveTarget(sysid);
        send(target, new io.aerofleet.mavlink.messages.ManualControl(
                clampAxis(x), clampAxis(y), Math.max(0, Math.min(1000, z)),
                clampAxis(r), 0, target, TARGET_COMP));
    }

    private static int clampAxis(int v) {
        return Math.max(-1000, Math.min(1000, v));
    }

    public int arm(int sysid) {
        return command(sysid, MavEnums.MAV_CMD_COMPONENT_ARM_DISARM, 1, 0, 0, 0, 0, 0, 0);
    }

    public int disarm(int sysid) {
        return command(sysid, MavEnums.MAV_CMD_COMPONENT_ARM_DISARM, 0, 0, 0, 0, 0, 0, 0);
    }

    public int startMission(int sysid) {
        // PX4: MAV_CMD_MISSION_START(300). Some stacks reject it - fall back to
        // MAV_CMD_DO_SET_MODE(176) auto mode, which PX4/ArduPilot both accept.
        try {
            return command(sysid, MavEnums.MAV_CMD_MISSION_START, 0, 0, 0, 0, 0, 0, 0);
        } catch (CommandException e) {
            log.warn("MISSION_START rejected for sysid={} ({}), falling back to DO_SET_MODE auto",
                    sysid, e.getMessage());
            // base_mode = SAFETY_ARMED|GUIDED|AUTO (0x94), custom_mode left 0
            return command(sysid, MavEnums.MAV_CMD_DO_SET_MODE,
                    MavEnums.MAV_MODE_FLAG_SAFETY_ARMED
                            | MavEnums.MAV_MODE_FLAG_AUTO_ENABLED
                            | MavEnums.MAV_MODE_FLAG_GUIDED_ENABLED, 0, 0, 0, 0, 0, 0);
        }
    }

    public int rtl(int sysid) {
        return command(sysid, MavEnums.MAV_CMD_NAV_RETURN_TO_LAUNCH, 0, 0, 0, 0, 0, 0, 0);
    }

    public int takeoff(int sysid, double alt) {
        // NAV_TAKEOFF: param7 = altitude (m), param4 = yaw (0 = unchanged)
        return command(sysid, MavEnums.MAV_CMD_NAV_TAKEOFF, 0, 0, 0, 0, 0, 0, (float) alt);
    }

    /**
     * Send one COMMAND_LONG to a specific drone and wait for its COMMAND_ACK.
     * Retries once on timeout or non-accepted result.
     *
     * @return MAV_RESULT accepted by the drone
     */
    public int command(int sysid, int mavCommand, float p1, float p2, float p3,
                        float p4, float p5, float p6, float p7) {
        int target = resolveTarget(sysid);
        for (int attempt = 1; attempt <= ACK_RETRIES; attempt++) {
            var future = pendings.expectCommandAck(mavCommand, target);
            try {
                CommandAck ack;
                try {
                    gateway.send(target, new CommandLong(target, TARGET_COMP, mavCommand, 0,
                            p1, p2, p3, p4, p5, p6, p7)
                            .toFrame(UdpGateway.GCS_SYSID, UdpGateway.GCS_COMPID,
                                    nextSeq()));
                } catch (IOException e) {
                    pendings.remove(CommandAck.ID, mavCommand, target, e);
                    throw new UncheckedIOException("send failed for command " + mavCommand, e);
                }
                try {
                    ack = future.get(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (ExecutionException e) {
                    pendings.remove(CommandAck.ID, mavCommand, target, e);
                    throw new CommandException("command " + mavCommand + " failed: "
                            + rootMessage(e), -1);
                }
                if (ack.result == MavEnums.MAV_RESULT_ACCEPTED) {
                    log.info("Command {} to sysid={} accepted (attempt {})",
                            mavCommand, target, attempt);
                    return ack.result;
                }
                if (ack.result == MavEnums.MAV_RESULT_IN_PROGRESS && attempt == 1) {
                    // some stacks ack IN_PROGRESS before finishing: give it one more window
                    continue;
                }
                if (attempt == ACK_RETRIES) {
                    throw new CommandException(
                            "command " + mavCommand + " rejected with MAV_RESULT=" + ack.result,
                            ack.result);
                }
                log.warn("Command {} to sysid={} attempt {} got result {}, retrying",
                        mavCommand, target, attempt, ack.result);
            } catch (TimeoutException e) {
                if (attempt == ACK_RETRIES) {
                    pendings.remove(CommandAck.ID, mavCommand, target,
                            new TimeoutException("ack timeout"));
                    throw new CommandException(
                            "command " + mavCommand + " timed out waiting for COMMAND_ACK", -1);
                }
                pendings.remove(CommandAck.ID, mavCommand, target, e);
                log.warn("Command {} to sysid={} attempt {} timed out, retrying",
                        mavCommand, target, attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pendings.remove(CommandAck.ID, mavCommand, target, e);
                throw new CommandException("command interrupted: " + mavCommand, -1);
            }
        }
        throw new CommandException("command " + mavCommand + " failed", -1); // unreachable
    }

    // =====================================================================
    // Mission Protocol client
    // =====================================================================

    /**
     * Full mission upload state machine for one drone. Items must already be
     * translated to MISSION_ITEM_INT payloads ({@link #toMissionItems}).
     *
     * @throws CommandException on timeout or negative MISSION_ACK
     */
    public MissionUploadResult uploadMission(int sysid, List<MissionItemInt> items) {
        int target = resolveTarget(sysid);
        if (items.isEmpty()) {
            return MissionUploadResult.failure("mission has no items");
        }
        long deadline = System.currentTimeMillis() + MISSION_TIMEOUT_MS;

        // Phase 1: announce the count. target_system MUST be the drone's real
        // sysid - PX4 rejects broadcasts (0) during mission transfer.
        // Loss recovery: MISSION_COUNT is re-announced every 2.5s until the drone
        // asks for the first item (a GCS surviving a lossy link does exactly this).
        var reqFuture = pendings.expectMissionRequest(target);
        Integer firstSeq = null;
        try {
            long nextAnnounce = System.currentTimeMillis();
            while (firstSeq == null) {
                long now = System.currentTimeMillis();
                if (now >= deadline) {
                    throw new TimeoutException();
                }
                if (now >= nextAnnounce) {
                    send(target, new MissionCountMsg(items.size(), target, TARGET_COMP,
                            MavEnums.MAV_MISSION_TYPE_MISSION, 0));
                    nextAnnounce = now + 2_500;
                }
                try {
                    firstSeq = reqFuture.get(
                            Math.max(1, Math.min(500, deadline - now)), TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // loop: re-announce until deadline
                }
            }
        } catch (TimeoutException e) {
            pendings.removeMissionRequest(target, e);
            return MissionUploadResult.failure("timeout waiting for MISSION_REQUEST");
        } catch (ExecutionException e) {
            pendings.removeMissionRequest(target, e);
            return MissionUploadResult.failure("mission request failed: " + rootMessage(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pendings.removeMissionRequest(target, e);
            return MissionUploadResult.failure("interrupted");
        }
        log.info("Mission upload to sysid={} started: {} items, first requested seq={}",
                target, items.size(), firstSeq);

        // Phase 2: serve requested items. Drone drives the exchange (may ask
        // out of order); waiters for BOTH response kinds are armed up-front so
        // a fast reply can never fall into a registration gap (we poll at
        // 500ms, the sim answers in ~5ms on loopback).
        var ackFuture = pendings.expectMissionAck(target);
        var reqFuture2 = pendings.expectMissionRequest(target);
        try {
            int sent = 0;
            int expectedSeq = firstSeq;
            long nextResend = System.currentTimeMillis();
            while (true) {
                if (expectedSeq < 0 || expectedSeq >= items.size()) {
                    throw new CommandException(
                            "drone requested invalid seq " + expectedSeq, -1);
                }
                long now = System.currentTimeMillis();
                if (now >= nextResend) {
                    // Loss recovery: (re)send the current item every 2.5s until
                    // the drone asks for the next one or finalizes the ACK.
                    send(target, items.get(expectedSeq));
                    sent++;
                    nextResend = now + 2_500;
                }
                long waitMs = remainingMs(deadline);
                if (waitMs <= 0) {
                    throw new TimeoutException();
                }
                Object resp = waitForAny(target, reqFuture2, ackFuture,
                        Math.min(waitMs, Math.max(1, nextResend - now)));
                if (resp instanceof Integer next) {
                    expectedSeq = next;
                    // arm the next request waiter before consuming, keep
                    // exactly one outstanding at any time
                    reqFuture2 = pendings.expectMissionRequest(target);
                    nextResend = System.currentTimeMillis();
                } else if (resp instanceof MissionAckMsg ack) {
                    return finishMission(ack, sent, items.size());
                }
                // resp == null: resend window reached without a reply, loop
            }
        } catch (TimeoutException e) {
            pendings.remove(MissionAckMsg.ID, 0, target, e);
            pendings.removeMissionRequest(target, e);
            return MissionUploadResult.failure("mission upload timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pendings.remove(MissionAckMsg.ID, 0, target, e);
            pendings.removeMissionRequest(target, e);
            return MissionUploadResult.failure("interrupted");
        } catch (CommandException e) {
            pendings.remove(MissionAckMsg.ID, 0, target, e);
            pendings.removeMissionRequest(target, e);
            return MissionUploadResult.rejected(e.ackResult, e.getMessage());
        }
    }

    /**
     * Block until the outstanding request waiter or the ack waiter completes,
     * or the (capped) window elapses. Both waiters are pre-registered by the
     * caller, so no reply can be lost in a registration window.
     *
     * @return the response object, or null when the window elapsed silently
     *         (caller decides: resend or give up via its own deadline).
     */
    private Object waitForAny(int target,
                              java.util.concurrent.Future<Integer> reqFuture,
                              java.util.concurrent.Future<MissionAckMsg> ackFuture,
                              long waitMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline) {
            if (ackFuture.isDone()) {
                try {
                    return ackFuture.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new CommandException("mission ack failed: "
                            + rootMessage(e), -1);
                }
            }
            try {
                Integer next = reqFuture.get(
                        Math.max(1, Math.min(200, deadline - System.currentTimeMillis())),
                        TimeUnit.MILLISECONDS);
                return next;
            } catch (TimeoutException ignored) {
                // re-check ackFuture, keep waiting until the window ends
            } catch (java.util.concurrent.ExecutionException e) {
                throw new CommandException("mission request failed", -1);
            }
        }
        return null; // window elapsed without a response: caller resends/gives up
    }

    private MissionUploadResult finishMission(MissionAckMsg ack, int sent, int total) {
        if (ack.type == MavEnums.MAV_MISSION_ACCEPTED) {
            log.info("Mission accepted: {} of {} items uploaded", sent, total);
            return MissionUploadResult.ok(total);
        }
        log.warn("Mission rejected: MAV_MISSION_RESULT={} after {} items", ack.type, sent);
        return MissionUploadResult.rejected(ack.type,
                "drone rejected mission with MAV_MISSION_RESULT=" + ack.type);
    }

    // =====================================================================
    // Mission download (read back the stored mission)
    // =====================================================================

    /**
     * Pull the currently stored mission from a drone: MISSION_REQUEST_LIST ->
     * MISSION_COUNT -> per-seq MISSION_REQUEST_INT -> MISSION_ITEM_INT ->
     * MISSION_ACK. Items come back as MISSION_ITEM_INT payloads.
     *
     * @return the downloaded items (empty list when the drone has none)
     * @throws CommandException on timeout / denied / invalid exchange
     */
    public java.util.List<MissionItemInt> downloadMission(int sysid) {
        int target = resolveTarget(sysid);
        long deadline = System.currentTimeMillis() + MISSION_TIMEOUT_MS;

        // Phase 1: ask for the count.
        var countFuture = pendings.expectMissionCount(target);
        try {
            send(target, new MissionRequestList(
                    target, TARGET_COMP, MavEnums.MAV_MISSION_TYPE_MISSION));
            MissionCountMsg count;
            try {
                count = countFuture.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw e;
            } catch (ExecutionException e) {
                throw new CommandException("mission count failed: " + rootMessage(e), -1);
            }
            if (count == null || count.count <= 0) {
                return List.of();
            }
            int total = count.count;
            log.info("Mission download from sysid={}: {} items", target, total);

            // Phase 2: request every item by seq.
            List<MissionItemInt> items = new ArrayList<>(total);
            for (int seq = 0; seq < total; seq++) {
                if (System.currentTimeMillis() >= deadline) {
                    throw new TimeoutException("mission download timed out");
                }
                var itemFuture = pendings.expectMissionItem(target, seq);
                send(target, new MissionRequestInt(seq, target, TARGET_COMP,
                        MavEnums.MAV_MISSION_TYPE_MISSION));
                MissionItemInt item;
                try {
                    item = itemFuture.get(5, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    // one retry per item, like a GCS on a lossy link
                    itemFuture = pendings.expectMissionItem(target, seq);
                    send(target, new MissionRequestInt(seq, target, TARGET_COMP,
                            MavEnums.MAV_MISSION_TYPE_MISSION));
                    item = itemFuture.get(5, TimeUnit.SECONDS);
                }
                items.add(item);
            }

            // Phase 3: finalize.
            send(target, new MissionAckMsg(target, TARGET_COMP,
                    MavEnums.MAV_MISSION_ACCEPTED, MavEnums.MAV_MISSION_TYPE_MISSION, 0));
            return items;
        } catch (TimeoutException e) {
            throw new CommandException("mission download timed out", -1);
        } catch (ExecutionException e) {
            throw new CommandException("mission download failed: " + rootMessage(e), -1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CommandException("interrupted", -1);
        }
    }

    /**
     * Translate REST mission body into MAVLink items for a target drone.
     * Supported cmds: waypoint / takeoff / rtl (case-insensitive).
     */
    public List<MissionItemInt> toMissionItems(List<MissionItemRequest> requests, int targetSystem) {
        List<MissionItemInt> items = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            MissionItemRequest r = requests.get(i);
            String cmd = r.cmd() == null ? "" : r.cmd().trim().toLowerCase();
            int latE7 = (int) Math.round(r.lat() * 1e7);
            int lonE7 = (int) Math.round(r.lon() * 1e7);
            switch (cmd) {
                case "waypoint" -> items.add(new MissionItemInt(targetSystem, TARGET_COMP, i,
                        MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT, MavEnums.MAV_CMD_NAV_WAYPOINT,
                        0, 0, (float) r.holdTime(), 0f, 0f, 0f,
                        latE7, lonE7, (float) r.alt(), MavEnums.MAV_MISSION_TYPE_MISSION));
                case "takeoff" -> items.add(new MissionItemInt(targetSystem, TARGET_COMP, i,
                        MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT, MavEnums.MAV_CMD_NAV_TAKEOFF,
                        0, 0, 0f, 0f, 0f, 0f,
                        latE7, lonE7, (float) r.alt(), MavEnums.MAV_MISSION_TYPE_MISSION));
                case "rtl" -> items.add(new MissionItemInt(targetSystem, TARGET_COMP, i,
                        MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT, MavEnums.MAV_CMD_NAV_RETURN_TO_LAUNCH,
                        0, 0, 0f, 0f, 0f, 0f,
                        latE7, lonE7, 0f, MavEnums.MAV_MISSION_TYPE_MISSION));
                case "capture" -> items.add(new MissionItemInt(targetSystem, TARGET_COMP, i,
                        MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT, MavEnums.MAV_CMD_IMAGE_START_CAPTURE,
                        0, 0,
                        (float) r.holdTime(),  // param1: interval s (0 = single shot)
                        1f,                   // param2: total images per item = 1
                        0f, 0f,               // param3, param4
                        latE7, lonE7, (float) r.alt(), MavEnums.MAV_MISSION_TYPE_MISSION));
                default -> throw new IllegalArgumentException(
                        "unsupported mission cmd '" + r.cmd() + "' at index " + i);
            }
        }
        return items;
    }

    private void send(int target, MavlinkMessage msg) {
        try {
            gateway.send(target, msg.toFrame(UdpGateway.GCS_SYSID, UdpGateway.GCS_COMPID, nextSeq()));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to send " + msg.getClass().getSimpleName(), e);
        }
    }

    private static long remainingMs(long deadline) {
        return deadline - System.currentTimeMillis();
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) {
            r = r.getCause();
        }
        return r.getMessage() != null ? r.getMessage() : r.getClass().getSimpleName();
    }

    private int nextSeq() {
        return sequence.getAndIncrement() & 0xFF;
    }
}

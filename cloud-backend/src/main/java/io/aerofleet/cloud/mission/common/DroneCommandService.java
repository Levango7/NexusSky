package io.aerofleet.cloud.mission.common;

import io.aerofleet.cloud.geofence.GeofenceInterceptService;
import io.aerofleet.cloud.geofence.InterceptLog;
import io.aerofleet.cloud.geofence.InterceptLogStore;
import io.aerofleet.cloud.geofence.InterceptVerdict;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.gateway.UdpGateway;
import io.aerofleet.cloud.telemetry.PendingAcks;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.CommandAck;
import io.aerofleet.mavlink.messages.CommandInt;
import io.aerofleet.mavlink.messages.CommandLong;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.messages.MissionAckMsg;
import io.aerofleet.mavlink.messages.MissionCountMsg;
import io.aerofleet.mavlink.messages.MissionItemInt;
import io.aerofleet.mavlink.messages.MissionRequestInt;
import io.aerofleet.mavlink.messages.MissionRequestList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * ArduCopter GUIDED 的 custom_mode 编号（DO_SET_MODE param2；param1=1 custom-mode 使能）。
     * 与 {@code TelemetrySnapshotListener} 的 ArduCopter 模式表 `case 4 -> "GUIDED"` 同源，
     * 证据见 docs/sitl-integration.md §11（P2-F1：AUTO 下 arm 被拒 ⇒ 必须先进 GUIDED）。
     */
    static final int ARDUPILOT_GUIDED_MODE_ID = 4;

    /** AP 地面启动任务自动补 TAKEOFF 的兜底高度（首条用户项 alt ≤ 0 时用）。 */
    static final float DEFAULT_TAKEOFF_ALT_M = 10f;

    /** MAV_LANDED_STATE（EXTENDED_SYS_STATE）：1=ON_GROUND、2=IN_AIR（3=TAKEOFF、4=LANDING）。 */
    private static final int LANDED_STATE_ON_GROUND = 1;
    private static final int LANDED_STATE_IN_AIR = 2;

    /** landedState 未知时判定"仍在地面"的海拔阈值（relativeAlt 超过它即视为空中）。 */
    private static final double AIRBORNE_ALT_M = 1.0;

    private final UdpGateway gateway;
    private final PendingAcks pendings;
    private final io.aerofleet.cloud.gateway.DeviceRegistry registry;
    /** Fallback sysid when the registry is empty (skeleton: first online drone wins). */
    private final int defaultTargetSysid;
    private final AtomicInteger sequence = new AtomicInteger();

    /** 围栏拦截服务（可选注入，不可用时拦截链跳过）。 */
    @Autowired(required = false)
    private GeofenceInterceptService geofenceIntercept;

    /** 拦截日志存储（可选注入，不可用时拦截链跳过日志记录）。 */
    @Autowired(required = false)
    private InterceptLogStore interceptLogStore;

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
        // 部署相关键（审计 §12.5）：请求里没指明 sysid 时命令发给谁——启动时留一行，
        // 换环境忘改时能从日志直接看出来，不用翻源码。
        log.info("指令下行默认目标 sysid={}（aerofleet.drone-sysid；请求显式带 sysid 时以请求为准）",
                defaultTargetSysid);
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

    /**
     * Arm。对 **ArduPilot 且当前处于 AUTO** 的目标先切 GUIDED 再 arm——
     * SITL 实录音："Auto mode not armable"（AUTO 下 arm 必被拒），切 GUIDED 后成功
     * （docs/sitl-integration.md §11.2-⑥，P2-F1 方案 A）。非 AP / 非 AUTO 目标行为不变。
     *
     * <p>切换只做一次、失败不吞：切未获确认仍继续 arm，让固件如实拒绝（错误语义不打折）。
     */
    public int arm(int sysid) {
        int target = resolveTarget(sysid);
        DroneSnapshot snap = registry.get(target);
        if (snap != null
                && snap.autopilot == MavEnums.MAV_AUTOPILOT_ARDUPILOTMEGA
                && "AUTO".equals(snap.mode)) {
            try {
                command(target, MavEnums.MAV_CMD_DO_SET_MODE,
                        MavEnums.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED, ARDUPILOT_GUIDED_MODE_ID,
                        0, 0, 0, 0, 0);
                log.info("ArduPilot sysid={} 从 AUTO 切 GUIDED 后再 arm（P2-F1 方案 A）", target);
            } catch (CommandException e) {
                log.warn("ArduPilot sysid={} 切 GUIDED 未获确认（{}）——仍尝试 arm，由固件如实拒绝",
                        target, e.getMessage());
            }
        }
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

    // double→float 不是疏漏：MAVLink COMMAND_LONG 的 param 在线路上就是 4 字节 float，
    // 窄化是协议要求的（CodeQL java/tainted-numeric-cast #2 在此是必然而非缺陷）。
    @SuppressWarnings("java/tainted-numeric-cast")
    public int takeoff(int sysid, double alt) {
        // NAV_TAKEOFF: param7 = altitude (m), param4 = yaw (0 = unchanged)
        return command(sysid, MavEnums.MAV_CMD_NAV_TAKEOFF, 0, 0, 0, 0, 0, 0, (float) alt);
    }

    /**
     * 必须用 COMMAND_INT 发送的命令（携带经纬度、需要 1e-7 度级精度）。
     * <p>真机契约：ArduPilot 对 {@code MAV_CMD_DO_REPOSITION} 用 COMMAND_LONG 发送一律回
     * {@code MAV_RESULT=8}（COMMAND_INT_ONLY）——AP SITL 实测，非 GUIDED 与 GUIDED 悬停中
     * 两种状态下结果相同，是报文格式要求而非模式问题（docs/sitl-integration.md §11.2-⑨）。
     * 此前编队修正/队形机动因此在该固件上完全不工作。今后同类"带位置命令"在此登记。
     */
    private static final java.util.Set<Integer> COMMAND_INT_REQUIRED =
            java.util.Set.of(MavEnums.MAV_CMD_DO_REPOSITION);

    /**
     * 无坐标任务命令登记表：这类命令的任务帧必须走 {@code MAV_FRAME_MISSION(2)}。
     * <p>
     * PX4 的任务解析对帧是硬校验（mavlink_mission.cpp parse_mavlink_mission_item）：
     * 带坐标帧（0/3/5/6）进「位置命令」分支，命令不在位置列表里即整单回
     * {@code MAV_MISSION_UNSUPPORTED}——RTL(20) 用 frame=3 上传因此在 PX4 上把
     * **整个任务**打回（SITL 实测 MISSION_ACK result=3，且 PX4 随即中止传输）；
     * ArduPilot 对无坐标命令根本不看帧。今后同类无坐标命令在此登记。
     */
    private static final java.util.Set<Integer> NO_COORDINATE_MISSION_CMDS = java.util.Set.of(
            MavEnums.MAV_CMD_NAV_RETURN_TO_LAUNCH,
            MavEnums.MAV_CMD_IMAGE_START_CAPTURE);

    /**
     * Send one command to a specific drone and wait for its COMMAND_ACK.
     * Retries once on timeout or non-accepted result.
     *
     * <p>默认走 COMMAND_LONG；{@link #COMMAND_INT_REQUIRED} 中的命令自动改走 COMMAND_INT
     * （p5/p6 作为 lat/lon 转 x/y*1e7、p7 作为相对高 z，frame=GLOBAL_RELATIVE_ALT_INT）——
     * 单一收口点：REST raw 与编队等所有调用方都由此受益，无需各自记得换报文格式。
     *
     * @return MAV_RESULT accepted by the drone
     */
    public int command(int sysid, int mavCommand, float p1, float p2, float p3,
                        float p4, float p5, float p6, float p7) {
        int target = resolveTarget(sysid);

        // 拦截链校验：仅 ARM(param1=1) 和 TAKEOFF 命令需要拦截
        if (isInterceptRequired(mavCommand, p1)) {
            InterceptVerdict verdict = performInterceptCheck(target, mavCommand, p1);
            if (verdict != null && verdict.isDenied()) {
                // DENY：不发送命令，返回拦截拒绝结果
                log.warn("Command {} to sysid={} intercepted (DENY): reason={}",
                        mavCommand, target, verdict.getDenyReason());
                return MavEnums.MAV_RESULT_DENIED;
            }
            // ALLOW：继续正常下发路径
        }

        // 带位置命令改走 COMMAND_INT（一次性转换，失败即抛，不消耗重试）
        CommandInt intMsg = null;
        if (COMMAND_INT_REQUIRED.contains(mavCommand)) {
            if (!Float.isFinite(p5) || !Float.isFinite(p6)) {
                throw new CommandException("command " + mavCommand
                        + " requires finite lat/lon (lat=" + p5 + ", lon=" + p6 + ")", -1);
            }
            // toIntExact：溢出抛 ArithmeticException 而非静默截断（纬度 |p*1e7| ≤ 9e8 < 2^31，
            // 纵深防御与 IncidentDispatchService 同款）。
            intMsg = new CommandInt(target, TARGET_COMP,
                    MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT_INT, mavCommand, 0, 0,
                    p1, p2, p3, p4,
                    Math.toIntExact(Math.round(p5 * 1e7)), Math.toIntExact(Math.round(p6 * 1e7)), p7);
            log.info("Command {} to sysid={} routed as COMMAND_INT (geo payload, frame=REL_ALT_INT)",
                    mavCommand, target);
        }

        for (int attempt = 1; attempt <= ACK_RETRIES; attempt++) {
            var future = pendings.expectCommandAck(mavCommand, target);
            try {
                CommandAck ack;
                try {
                    MavlinkMessage outMsg = intMsg != null
                            ? intMsg
                            : new CommandLong(target, TARGET_COMP, mavCommand, 0,
                                    p1, p2, p3, p4, p5, p6, p7);
                    gateway.send(target, outMsg
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
    // 拦截链（Geofence Intercept Chain）
    // =====================================================================

    /**
     * 判断命令是否需要经过拦截链校验。
     * <p>
     * 仅以下命令需要拦截：
     * <ul>
     *   <li>MAV_CMD_COMPONENT_ARM_DISARM(400)，且 param1=1（ARM 操作）；DISARM(param1=0) 不拦截</li>
     *   <li>MAV_CMD_NAV_TAKEOFF(22)</li>
     * </ul>
     *
     * @param mavCommand MAVLink 命令 ID
     * @param p1         命令的第一个参数（ARM/DISARM 的 param1 区分 ARM=1/DISARM=0）
     * @return true 表示该命令需要拦截校验
     */
    private boolean isInterceptRequired(int mavCommand, float p1) {
        if (mavCommand == MavEnums.MAV_CMD_COMPONENT_ARM_DISARM) {
            // 仅 ARM(param1=1) 需要拦截，DISARM(param1=0) 不拦截
            return p1 == 1.0f;
        }
        if (mavCommand == MavEnums.MAV_CMD_NAV_TAKEOFF) {
            return true;
        }
        return false;
    }

    /**
     * 执行拦截链校验：获取无人机位置 → 调用 geofenceIntercept.check() → 记录日志。
     * <p>
     * 故障安全原则（FR-10）：拦截服务不可用或校验异常时，按 DENY(INTERCEPT_ERROR) 处理。
     *
     * @param target     目标无人机 sysid
     * @param mavCommand MAVLink 命令 ID
     * @param p1         命令的第一个参数
     * @return 拦截判决结果；null 表示拦截服务不可用（放行）
     */
    private InterceptVerdict performInterceptCheck(int target, int mavCommand, float p1) {
        // 拦截服务未注入 → 跳过拦截链（向后兼容）
        if (geofenceIntercept == null) {
            return null;
        }

        // 构造命令名称用于拦截日志
        String commandName = resolveCommandName(mavCommand, p1);

        // 从 DeviceRegistry 获取无人机当前位置
        DroneSnapshot snapshot = registry.get(target);
        double lat = Double.NaN;
        double lon = Double.NaN;
        if (snapshot != null) {
            lat = snapshot.lat;
            lon = snapshot.lon;
        }

        InterceptVerdict verdict;
        try {
            verdict = geofenceIntercept.check(target, commandName, lat, lon);
        } catch (Exception e) {
            // 异常 → DENY(INTERCEPT_ERROR)（故障安全）
            log.error("Intercept check exception for sysid={} command={}: {}",
                    target, commandName, e.getMessage(), e);
            verdict = InterceptVerdict.deny(InterceptVerdict.DenyReason.INTERCEPT_ERROR, null);
        }

        // 记录拦截日志
        if (interceptLogStore != null) {
            try {
                InterceptLog logEntry = InterceptLog.fromVerdict(
                        target, commandName, lat, lon, verdict);
                interceptLogStore.record(logEntry);
            } catch (Exception e) {
                log.warn("Intercept log record failed for sysid={} command={}: {}",
                        target, commandName, e.getMessage());
            }
        }

        return verdict;
    }

    /**
     * 将 MAVLink 命令 ID 和参数映射为人类可读的命令名称。
     *
     * @param mavCommand MAVLink 命令 ID
     * @param p1         命令的第一个参数
     * @return 命令名称字符串
     */
    private static String resolveCommandName(int mavCommand, float p1) {
        if (mavCommand == MavEnums.MAV_CMD_COMPONENT_ARM_DISARM) {
            return p1 == 1.0f ? "ARM" : "DISARM";
        }
        if (mavCommand == MavEnums.MAV_CMD_NAV_TAKEOFF) {
            return "TAKEOFF";
        }
        return "CMD_" + mavCommand;
    }

    // =====================================================================
    // Mission Protocol client
    // =====================================================================

    /**
     * Full mission upload state machine for one drone. Items must already be
     * translated to MISSION_ITEM_INT payloads ({@link #toMissionItems}).
     *
     * <p>对 ArduPilot 目标会先经两道线上适配：{@link #withArduPilotHomeSlot}（索引 0 保留给
     * HOME）与 {@link #withArduPilotGroundTakeoff}（**地面**任务必须以 TAKEOFF 开头，
     * 否则 AUTO 地面启动 init 失败——SITL 实录见 §11.2-⑦，P2-F1 方案 A）。
     *
     * @throws CommandException on timeout or negative MISSION_ACK
     */
    public MissionUploadResult uploadMission(int sysid, List<MissionItemInt> items) {
        int target = resolveTarget(sysid);
        if (items.isEmpty()) {
            return MissionUploadResult.failure("mission has no items");
        }
        List<MissionItemInt> wireItems = withArduPilotHomeSlot(target, items);
        wireItems = withArduPilotGroundTakeoff(target, wireItems);
        wireItems = normalizeMissionItems(wireItems);
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
                    send(target, new MissionCountMsg(wireItems.size(), target, TARGET_COMP,
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
        log.info("Mission upload to sysid={} started: {} wire items ({} user items), first requested seq={}",
                target, wireItems.size(), items.size(), firstSeq);

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
                if (expectedSeq < 0 || expectedSeq >= wireItems.size()) {
                    throw new CommandException(
                            "drone requested invalid seq " + expectedSeq, -1);
                }
                long now = System.currentTimeMillis();
                if (now >= nextResend) {
                    // Loss recovery: (re)send the current item every 2.5s until
                    // the drone asks for the next one or finalizes the ACK.
                    send(target, wireItems.get(expectedSeq));
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
                    return finishMission(ack, sent, wireItems.size(), items.size());
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

    private MissionUploadResult finishMission(MissionAckMsg ack, int sent, int wireTotal, int userTotal) {
        if (ack.type == MavEnums.MAV_MISSION_ACCEPTED) {
            log.info("Mission accepted: {} of {} wire items uploaded ({} user items)",
                    sent, wireTotal, userTotal);
            // API 口径回的是用户条目数：ArduPilot 的 home 占位槽是协议实现细节，
            // 不该出现在「已上传 N 个航点」里。
            return MissionUploadResult.ok(userTotal);
        }
        log.warn("Mission rejected: MAV_MISSION_RESULT={} after {} items", ack.type, sent);
        return MissionUploadResult.rejected(ack.type,
                "drone rejected mission with MAV_MISSION_RESULT=" + ack.type);
    }

    /**
     * ArduPilot 把任务索引 0 保留给 HOME：AP_Mission 对写入索引 0 的条目直接忽略
     * （「Writing index zero is not allowed, it must be home」），真正命令从索引 1
     * 起（AP_MISSION_FIRST_REAL_COMMAND=1）。真机 SITL 实测：上传 [takeoff, rtl]
     * 读回 [home, rtl]——放在 seq 0 的首条命令被静默丢弃；单点任务（如语音 FLY_TO）
     * 在 AP 上等于没传。PX4 无此约定。
     *
     * <p>对 ArduPilot 目标前置一个 home 占位项（内容会被固件自身的 home 覆盖，用
     * 当前遥测位置填 x/y/lat 只为读回时直观），用户条目整体后移一位。
     * autopilot 未知（-1）或非 AP 时原样返回。
     */
    List<MissionItemInt> withArduPilotHomeSlot(int target, List<MissionItemInt> items) {
        DroneSnapshot snap = registry.get(target);
        if (snap == null || snap.autopilot != MavEnums.MAV_AUTOPILOT_ARDUPILOTMEGA) {
            return items;
        }
        List<MissionItemInt> wire = new ArrayList<>(items.size() + 1);
        wire.add(new MissionItemInt(target, TARGET_COMP, 0,
                MavEnums.MAV_FRAME_GLOBAL, MavEnums.MAV_CMD_NAV_WAYPOINT, 0, 0,
                0f, 0f, 0f, 0f,
                toE7(snap.lat), toE7(snap.lon),
                Double.isFinite(snap.amslAlt) ? (float) snap.amslAlt : 0f,
                MavEnums.MAV_MISSION_TYPE_MISSION));
        for (int i = 0; i < items.size(); i++) {
            MissionItemInt it = items.get(i);
            wire.add(new MissionItemInt(it.targetSystem, it.targetComponent, i + 1,
                    it.frame, it.command, it.current, it.autocontinue,
                    it.param1, it.param2, it.param3, it.param4,
                    it.x, it.y, it.z, it.missionType));
        }
        return wire;
    }

    /**
     * ArduPilot **地面启动**约束（P2-F1 方案 A）：AUTO 从地面启动要求任务首条命令是
     * {@code NAV_TAKEOFF}，否则 MISSION_START 回 {@code MAV_RESULT=4}、回退 DO_SET_MODE
     * 回 {@code 2}，STATUSTEXT "Mode change to Auto failed: init failed"——单点"去某点"
     * 任务从地面起不来（SITL 实录 §11.2-⑦）。对**尚在地面**（判据 {@link #isOnGround}——
     * **armed ≠ 空中**：dispatch 先 arm 再上传，上传时已 armed 但仍在地面）的 AP 目标，
     * 若首条用户命令不是 TAKEOFF，在 home 槽之后插入一条 TAKEOFF，高度取首条用户项
     * 的 alt（≤0 时兜底 {@link #DEFAULT_TAKEOFF_ALT_M}），frame 随首条用户项
     * （相对高任务保持相对口径）。用户条目顺序与字段原样保留，仅整体后移一位。
     *
     * <p>已在空中（landedState=2..4，或状态未知时 relativeAlt 超阈值）替换任务**不插**：
     * 该约束只针对地面启动，插入反而扰动在途任务。
     * 非 AP / autopilot 未知 / 未注册目标 / 空任务原样返回；入参不被修改。
     *
     * @param wire 已经过 {@link #withArduPilotHomeSlot} 的线上列表（AP 目标首项即 home 槽）
     */
    List<MissionItemInt> withArduPilotGroundTakeoff(int target, List<MissionItemInt> wire) {
        DroneSnapshot snap = registry.get(target);
        if (snap == null
                || snap.autopilot != MavEnums.MAV_AUTOPILOT_ARDUPILOTMEGA
                || wire.size() < 2) {          // 无用户条目（或尚未加 home 槽）：没有可起飞的目标点
            return wire;
        }
        if (!isOnGround(snap)) {
            return wire;                       // 已在空中：替换任务不插
        }
        MissionItemInt head = wire.get(1);     // AP 目标上 home 槽必在索引 0
        if (head.command == MavEnums.MAV_CMD_NAV_TAKEOFF) {
            return wire;                       // 用户自带 TAKEOFF：不重复插
        }
        float alt = head.z;
        if (!Float.isFinite(alt) || alt <= 0f) {
            log.warn("ArduPilot sysid={} 地面任务首条航点 alt={} 非法，TAKEOFF 兜底 {}m",
                    target, alt, DEFAULT_TAKEOFF_ALT_M);
            alt = DEFAULT_TAKEOFF_ALT_M;
        }
        log.info("ArduPilot sysid={} 地面任务前置 TAKEOFF（alt={}m，P2-F1 方案 A）", target, alt);
        List<MissionItemInt> out = new ArrayList<>(wire.size() + 1);
        for (int i = 0; i < wire.size(); i++) {
            if (i == 1) {
                out.add(new MissionItemInt(target, TARGET_COMP, 1,
                        head.frame, MavEnums.MAV_CMD_NAV_TAKEOFF, 0, 0,
                        0f, 0f, 0f, 0f, head.x, head.y, alt, MavEnums.MAV_MISSION_TYPE_MISSION));
            }
            MissionItemInt it = wire.get(i);
            out.add(new MissionItemInt(it.targetSystem, it.targetComponent, i == 0 ? 0 : i + 1,
                    it.frame, it.command, it.current, it.autocontinue,
                    it.param1, it.param2, it.param3, it.param4,
                    it.x, it.y, it.z, it.missionType));
        }
        return out;
    }

    /**
     * 任务项规范化（单一收口点）：所有上传路径（REST 任务、事件派飞、出警派飞）
     * 都从这里过一遍，改写为各固件共同接受的线上形态——
     * <ul>
     *   <li>无坐标命令（{@link #NO_COORDINATE_MISSION_CMDS}）→ {@code MAV_FRAME_MISSION(2)}：
     *       PX4 硬校验要求，AP 不看帧但照存；</li>
     *   <li>{@code MAV_FRAME_GLOBAL_RELATIVE_ALT(3)} → {@code ..._INT(6)}：语义相同
     *       （都是相对高），但 MISSION_ITEM_INT 线上按规范应带 _INT 帧；</li>
     *   <li>{@code autocontinue} 一律置 1：PX4 尊重该位（mission.cpp「switch to next
     *       waypoint if autocontinue flag set」——置 0 则到点悬停不再前进，SITL 实测
     *       起飞后停在 20m）；AP 与 drone-sim 都不消费该位，故历来自带 0 也全绿——
     *       又一条"sim 测不出"。停驻语义走 {@code param1}（NAV_WAYPOINT time_inside），
     *       不靠该位。</li>
     * </ul>
     * 绝对高项（frame=0/5）原样保留——AP home 占位槽 frame=0 带「绝对高」语义，
     * 改成 6 会把 home 高度从 AMSL 变成相对高。入参不被修改；无需改写的项原引用返回。
     */
    static List<MissionItemInt> normalizeMissionItems(List<MissionItemInt> items) {
        List<MissionItemInt> out = new ArrayList<>(items.size());
        for (MissionItemInt it : items) {
            int frame = NO_COORDINATE_MISSION_CMDS.contains(it.command)
                    ? MavEnums.MAV_FRAME_MISSION
                    : (it.frame == MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT
                            ? MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT_INT
                            : it.frame);
            boolean autocontinue = it.autocontinue != 0;
            if (frame == it.frame && autocontinue) {
                out.add(it);
            } else {
                out.add(new MissionItemInt(it.targetSystem, it.targetComponent, it.seq,
                        frame, it.command, it.current, 1,
                        it.param1, it.param2, it.param3, it.param4,
                        it.x, it.y, it.z, it.missionType));
            }
        }
        return out;
    }

    /**
     * 目标是否**仍在地面**（决定是否注入地面启动 TAKEOFF 前置）。判据优先级：
     * ① {@code landedState} 有效（MAV_LANDED_STATE 1..4）：1=ON_GROUND 为地面；
     * 2..4（IN_AIR/TAKEOFF/LANDING）为空中；
     * ② 未知（-1=未收到 EXTENDED_SYS_STATE / 0=UNDEFINED）时用 {@code relativeAlt}
     * 兜底：超过 {@link #AIRBORNE_ALT_M} 视为空中，NaN 视为未知按地面处理
     * （地面启动约束宁多插一条 TAKEOFF，不因遥测缺口跳过——正是 SITL 抓回的错误）。
     */
    private static boolean isOnGround(DroneSnapshot snap) {
        if (snap.landedState == LANDED_STATE_ON_GROUND) {
            return true;
        }
        if (snap.landedState >= LANDED_STATE_IN_AIR && snap.landedState <= 4) {
            return false;
        }
        return !(snap.relativeAlt > AIRBORNE_ALT_M);
    }

    private static int toE7(double degrees) {
        return Double.isFinite(degrees) ? (int) Math.round(degrees * 1e7) : 0;
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

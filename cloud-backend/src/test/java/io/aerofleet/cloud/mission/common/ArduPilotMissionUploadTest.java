package io.aerofleet.cloud.mission.common;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.gateway.UdpGateway;
import io.aerofleet.cloud.telemetry.PendingAcks;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.MissionAckMsg;
import io.aerofleet.mavlink.messages.MissionCountMsg;
import io.aerofleet.mavlink.messages.MissionItemInt;
import io.aerofleet.mavlink.messages.MissionRequestInt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * ArduPilot 任务槽 0 = HOME 的兼容回归（SITL 首验实测）。
 *
 * <p>AP_Mission 对写入索引 0 的条目直接忽略（"Writing index zero is not
 * allowed, it must be home"），真实命令从索引 1 起（AP_MISSION_FIRST_REAL_COMMAND=1）。
 * 实测证据：上传 [takeoff, rtl] 读回 [home, rtl]——seq 0 的命令被静默丢弃。
 * 修复后对 ArduPilot 目标前置 home 占位项，用户条目整体后移一位；PX4 不受影响。
 *
 * <p>用 mock 网关 + 真 PendingAcks 驱动完整握手：MISSION_COUNT →（我们）应答
 * MISSION_REQUEST_INT(seq) → 抓取 GCS 发出的 MISSION_ITEM_INT → MISSION_ACK。
 */
class ArduPilotMissionUploadTest {

    private final List<MavlinkFrame> sent = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private PendingAcks pendings;
    private DeviceRegistry registry;
    private DroneCommandService svc;

    @BeforeEach
    void setUp() throws Exception {
        UdpGateway gateway = mock(UdpGateway.class);
        doAnswer(inv -> {
            sent.add(inv.getArgument(1));
            return null;
        }).when(gateway).send(anyInt(), any(MavlinkFrame.class));
        pendings = new PendingAcks();
        registry = new DeviceRegistry();
        svc = new DroneCommandService(gateway, pendings, registry, 1);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------
    // withArduPilotHomeSlot 单元行为
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ArduPilot 目标：前置 home 占位项，用户条目整体后移一位")
    void homeSlot_prependedForArduPilot() {
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.autopilot = MavEnums.MAV_AUTOPILOT_ARDUPILOTMEGA;
        s.lat = -35.3632622;
        s.lon = 149.1652375;
        s.amslAlt = 584.08;
        List<MissionItemInt> user = userItems();

        List<MissionItemInt> wire = svc.withArduPilotHomeSlot(1, user);

        assertThat(wire).hasSize(3);
        MissionItemInt home = wire.get(0);
        assertThat(home.seq).isZero();
        assertThat(home.command).isEqualTo(MavEnums.MAV_CMD_NAV_WAYPOINT);
        assertThat(home.frame).isEqualTo(MavEnums.MAV_FRAME_GLOBAL);
        assertThat(home.x).isEqualTo(-353632622);
        assertThat(home.y).isEqualTo(1491652375);
        assertThat(home.z).isEqualTo((float) 584.08);
        // 用户条目字段原样保留，仅 seq 平移
        for (int i = 0; i < user.size(); i++) {
            MissionItemInt src = user.get(i);
            MissionItemInt dst = wire.get(i + 1);
            assertThat(dst.seq).isEqualTo(i + 1);
            assertThat(dst.command).isEqualTo(src.command);
            assertThat(dst.frame).isEqualTo(src.frame);
            assertThat(dst.z).isEqualTo(src.z);
        }
        // 入参不被修改
        assertThat(user.get(0).seq).isZero();
    }

    @Test
    @DisplayName("PX4 / 未知固件：原样返回，不插 home 槽")
    void homeSlot_skippedForPx4AndUnknown() {
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.autopilot = MavEnums.MAV_AUTOPILOT_PX4;
        List<MissionItemInt> user = userItems();
        assertThat(svc.withArduPilotHomeSlot(1, user)).isSameAs(user);

        s.autopilot = -1;   // 尚未收到心跳
        assertThat(svc.withArduPilotHomeSlot(1, user)).isSameAs(user);

        assertThat(svc.withArduPilotHomeSlot(9, user)).isSameAs(user);   // 未注册目标
    }

    @Test
    @DisplayName("位置未上报（NaN）：home 占位项坐标退化为 0，不抛异常")
    void homeSlot_nanPositionFallsBackToZero() {
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.autopilot = MavEnums.MAV_AUTOPILOT_ARDUPILOTMEGA;
        // lat/lon/amslAlt 保持 NaN

        List<MissionItemInt> wire = svc.withArduPilotHomeSlot(1, userItems());

        MissionItemInt home = wire.get(0);
        assertThat(home.x).isZero();
        assertThat(home.y).isZero();
        assertThat(home.z).isZero();
    }

    // ------------------------------------------------------------------
    // 全握手：线上 MISSION_COUNT 数量与逐条内容
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ArduPilot 全握手：网上发 3 条（home+2 用户），API 回报 2 条用户航点")
    void upload_arduPilot_sendsHomeSlotOnWire() throws Exception {
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.autopilot = MavEnums.MAV_AUTOPILOT_ARDUPILOTMEGA;
        s.lat = -35.3632622;
        s.lon = 149.1652375;
        s.amslAlt = 584.08;
        s.online = true;

        MissionUploadResult result = runFullHandshake(userItems());

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.uploaded()).isEqualTo(2);        // API 口径 = 用户条目数

        List<MissionItemInt> items = capturedItems();
        assertThat(items).hasSize(3);
        assertThat(items.get(0).command).isEqualTo(MavEnums.MAV_CMD_NAV_WAYPOINT);     // home 占位
        assertThat(items.get(1).command).isEqualTo(MavEnums.MAV_CMD_NAV_TAKEOFF);      // 用户条目 0 → seq 1
        assertThat(items.get(2).command).isEqualTo(MavEnums.MAV_CMD_NAV_RETURN_TO_LAUNCH);
    }

    @Test
    @DisplayName("PX4 全握手：回归——仍为 2 条、seq 0 即首条命令")
    void upload_px4_noHomeSlotRegression() throws Exception {
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.autopilot = MavEnums.MAV_AUTOPILOT_PX4;
        s.online = true;

        MissionUploadResult result = runFullHandshake(userItems());

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.uploaded()).isEqualTo(2);
        List<MissionItemInt> items = capturedItems();
        assertThat(items).hasSize(2);
        assertThat(items.get(0).command).isEqualTo(MavEnums.MAV_CMD_NAV_TAKEOFF);
        assertThat(items.get(1).command).isEqualTo(MavEnums.MAV_CMD_NAV_RETURN_TO_LAUNCH);
    }

    @Test
    @DisplayName("PX4 全握手线上项：位置=6、RTL=2、autocontinue=1（修复前全 3/0，PX4 整单被拒/到点即停）")
    void upload_px4_wireItemsNormalized() throws Exception {
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.autopilot = MavEnums.MAV_AUTOPILOT_PX4;
        s.online = true;

        MissionUploadResult result = runFullHandshake(userItems());

        assertThat(result.status()).isEqualTo("ok");
        List<MissionItemInt> items = capturedItems();
        assertThat(items.get(0).frame).isEqualTo(MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT_INT);
        assertThat(items.get(1).frame).isEqualTo(MavEnums.MAV_FRAME_MISSION);
        // autocontinue：PX4 置 0 时到点即停（SITL 实测起飞后悬停不奔航点）
        assertThat(items).allSatisfy(it -> assertThat(it.autocontinue).isEqualTo(1));
    }

    // ------------------------------------------------------------------
    // 地面启动 TAKEOFF 前置（P2-F1 方案 A）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AP 地面任务：home 槽后、首条用户项前插 TAKEOFF（高度=首条航点 alt，frame 随航点）")
    void groundTakeoff_insertedBetweenHomeAndFirstUserItem() {
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.autopilot = MavEnums.MAV_AUTOPILOT_ARDUPILOTMEGA;
        s.landedState = 1;   // ON_GROUND

        List<MissionItemInt> wire = svc.withArduPilotGroundTakeoff(1,
                svc.withArduPilotHomeSlot(1, List.of(dispatchWaypoint(50f))));

        assertThat(wire).hasSize(3);
        assertThat(wire.get(0).command).isEqualTo(MavEnums.MAV_CMD_NAV_WAYPOINT);   // home 槽
        assertThat(wire.get(1).command).isEqualTo(MavEnums.MAV_CMD_NAV_TAKEOFF);
        assertThat(wire.get(1).frame).isEqualTo(MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT);
        assertThat(wire.get(1).z).isEqualTo(50f);
        assertThat(wire.get(1).seq).isEqualTo(1);
        assertThat(wire.get(2).command).isEqualTo(MavEnums.MAV_CMD_NAV_WAYPOINT);
        assertThat(wire.get(2).z).isEqualTo(50f);
        assertThat(wire.get(2).seq).isEqualTo(2);
    }

    @Test
    @DisplayName("arm 后仍在地面（armed=true + ON_GROUND）：照插——armed ≠ 空中（SITL 抓回的回归）")
    void groundTakeoff_insertedWhenArmedButStillOnGround() {
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.autopilot = MavEnums.MAV_AUTOPILOT_ARDUPILOTMEGA;
        s.armed = true;       // dispatch：先 arm 再上传——上传时已 armed
        s.landedState = 1;    // …但机体仍在地面（MAV_LANDED_STATE=ON_GROUND）

        List<MissionItemInt> wire = svc.withArduPilotHomeSlot(1, List.of(dispatchWaypoint(50f)));
        List<MissionItemInt> out = svc.withArduPilotGroundTakeoff(1, wire);

        assertThat(out).hasSize(3);
        assertThat(out.get(1).command).isEqualTo(MavEnums.MAV_CMD_NAV_TAKEOFF);
        assertThat(out.get(1).z).isEqualTo(50f);
    }

    @Test
    @DisplayName("已在空中：替换任务不插——landedState=IN_AIR 与 relativeAlt 兜底都识别")
    void groundTakeoff_skippedWhenInAir() {
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.autopilot = MavEnums.MAV_AUTOPILOT_ARDUPILOTMEGA;
        List<MissionItemInt> wire = svc.withArduPilotHomeSlot(1, List.of(dispatchWaypoint(50f)));

        s.landedState = 2;    // IN_AIR
        assertThat(svc.withArduPilotGroundTakeoff(1, wire)).isSameAs(wire);

        s.landedState = -1;   // 未收到 EXTENDED_SYS_STATE：relativeAlt 兜底判空中
        s.relativeAlt = 30.0;
        assertThat(svc.withArduPilotGroundTakeoff(1, wire)).isSameAs(wire);

        s.relativeAlt = 0.0;  // 兜底回落判地面：插
        assertThat(svc.withArduPilotGroundTakeoff(1, wire)).hasSize(3);
    }

    @Test
    @DisplayName("首条用户项已是 TAKEOFF：不重复插（线上仍是 home+takeoff+…）")
    void groundTakeoff_skippedWhenUserAlreadyStartsWithTakeoff() {
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.autopilot = MavEnums.MAV_AUTOPILOT_ARDUPILOTMEGA;
        s.landedState = 1;

        List<MissionItemInt> wire = svc.withArduPilotHomeSlot(1, userItems());
        assertThat(svc.withArduPilotGroundTakeoff(1, wire)).isSameAs(wire);
    }

    @Test
    @DisplayName("PX4 / autopilot 未知 / 未注册目标：原样返回")
    void groundTakeoff_skippedForNonArduPilot() {
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.autopilot = MavEnums.MAV_AUTOPILOT_PX4;
        List<MissionItemInt> wire = List.of(dispatchWaypoint(50f));
        assertThat(svc.withArduPilotGroundTakeoff(1, wire)).isSameAs(wire);

        s.autopilot = -1;   // 尚未收到心跳
        assertThat(svc.withArduPilotGroundTakeoff(1, wire)).isSameAs(wire);

        assertThat(svc.withArduPilotGroundTakeoff(9, wire)).isSameAs(wire);   // 未注册目标
    }

    @Test
    @DisplayName("首条航点 alt ≤ 0：TAKEOFF 兜底默认高度，不插 0 高度")
    void groundTakeoff_altFallbackWhenNonPositive() {
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.autopilot = MavEnums.MAV_AUTOPILOT_ARDUPILOTMEGA;
        s.landedState = 1;

        List<MissionItemInt> wire = svc.withArduPilotGroundTakeoff(1,
                svc.withArduPilotHomeSlot(1, List.of(dispatchWaypoint(0f))));

        assertThat(wire.get(1).command).isEqualTo(MavEnums.MAV_CMD_NAV_TAKEOFF);
        assertThat(wire.get(1).z).isEqualTo(DroneCommandService.DEFAULT_TAKEOFF_ALT_M);
        assertThat(wire.get(2).z).isEqualTo(0f);   // 用户项字段原样保留
    }

    @Test
    @DisplayName("AP 地面全握手（先 arm 后上传：armed+ON_GROUND）：线上 [home, takeoff, wp]，API 回报 1 条")
    void upload_arduPilotGroundDispatch_prependsTakeoffOnWire() throws Exception {
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.autopilot = MavEnums.MAV_AUTOPILOT_ARDUPILOTMEGA;
        s.armed = true;        // 镜像真实 dispatch 顺序：arm 先于上传
        s.landedState = 1;     // 仍在地面
        s.lat = -35.3632622;
        s.lon = 149.1652375;
        s.amslAlt = 584.08;
        s.online = true;

        MissionUploadResult result = runFullHandshake(List.of(dispatchWaypoint(50f)));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.uploaded()).isEqualTo(1);        // API 口径 = 用户条目数
        List<MissionItemInt> items = capturedItems();
        assertThat(items).hasSize(3);
        assertThat(items.get(0).command).isEqualTo(MavEnums.MAV_CMD_NAV_WAYPOINT);   // home 槽
        assertThat(items.get(1).command).isEqualTo(MavEnums.MAV_CMD_NAV_TAKEOFF);    // 前置起飞
        assertThat(items.get(1).z).isEqualTo(50f);
        assertThat(items.get(2).command).isEqualTo(MavEnums.MAV_CMD_NAV_WAYPOINT);   // 派飞目标点
        assertThat(items.get(2).z).isEqualTo(50f);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** 派飞形态的单点任务项（GLOBAL_RELATIVE_ALT；与 IncidentDispatchService.dispatch 同构）。 */
    private MissionItemInt dispatchWaypoint(float alt) {
        return new MissionItemInt(1, 0, 0,
                MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT, MavEnums.MAV_CMD_NAV_WAYPOINT,
                0, 0, 30f, 0f, 0f, 0f, -353632622, 1491652375, alt,
                MavEnums.MAV_MISSION_TYPE_MISSION);
    }

    private List<MissionItemInt> userItems() {
        return svc.toMissionItems(List.of(
                new MissionItemRequest("takeoff", -35.363262, 149.165237, 20.0, 0),
                new MissionItemRequest("rtl", 0, 0, 0, 0)), 1);
    }

    /**
     * 驱动一次完整上传：等 MISSION_COUNT（读线上声明数）→ 逐 seq 应答
     * MISSION_REQUEST_INT → 回 MISSION_ACK(ACCEPTED)。
     *
     * <p>每个 seq 的请求只投一次，以 offer 返回 true（有等待者消费）为准；
     * 不能"投到条目出现为止"——重复投递会让服务端的下一轮等待者再消费一次，
     * 条目被重发。等待者的注册顺序保证无竞态：Phase1 等待者常驻（吸收 seq 0），
     * 之后每个请求等待者都在对应条目发出前注册（吸收 seq k）。
     */
    private MissionUploadResult runFullHandshake(List<MissionItemInt> user) throws Exception {
        Future<MissionUploadResult> fut = executor.submit(() -> svc.uploadMission(1, user));

        await().atMost(Duration.ofSeconds(5))
                .until(() -> !framesOf(MissionCountMsg.ID).isEmpty());
        MissionCountMsg count = MissionCountMsg.decode(framesOf(MissionCountMsg.ID).get(0));

        for (int seq = 0; seq < count.count; seq++) {
            int s = seq;
            await().atMost(Duration.ofSeconds(5)).until(() ->
                    pendings.offer(MissionRequestInt.ID, new MissionRequestInt(s, 255, 190, 0), 1));
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> pendings.offer(MissionAckMsg.ID,
                new MissionAckMsg(255, 190, MavEnums.MAV_MISSION_ACCEPTED, 0, 0), 1));
        return fut.get(5, TimeUnit.SECONDS);
    }

    private List<MavlinkFrame> framesOf(int messageId) {
        return sent.stream().filter(f -> f.getMessageId() == messageId).toList();
    }

    private List<MissionItemInt> capturedItems() {
        return framesOf(MissionItemInt.ID).stream()
                .map(MissionItemInt::decode)
                .sorted(java.util.Comparator.comparingInt(it -> it.seq))
                .toList();
    }
}

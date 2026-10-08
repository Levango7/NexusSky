package io.aerofleet.cloud.dock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DockService（spec R1/R2/R3）：OSD 摄取、命令裁决、离线巡检。
 * 用 mock 仓库（不引 Spring 上下文），网关用记录型 stub 验证下发与回滚。
 */
@DisplayName("DockService — OSD 摄取 / 命令裁决 / 离线巡检")
class DockServiceTest {

    private DockRepository docks;
    private DockStateLogRepository stateLog;
    private DockScheduleRepository schedules;
    private DockGateway gateway;
    private DockProperties props;
    private DockService service;

    /** 内存版实体仓库：只实现本测试需要的方法。 */
    private DockEntity stored;

    @BeforeEach
    void setUp() {
        docks = mock(DockRepository.class);
        stateLog = mock(DockStateLogRepository.class);
        schedules = mock(DockScheduleRepository.class);
        gateway = mock(DockGateway.class);
        props = new DockProperties();
        service = new DockService(docks, stateLog, schedules,
                new DockStateMachine(), gateway, props);

        stored = new DockEntity();
        stored.id = 1L;
        stored.name = "A区机巢";
        stored.sn = "DOCK-001";
        stored.state = DockState.IDLE;
        stored.droneSysid = null;

        when(docks.findBySn("DOCK-001")).thenReturn(Optional.of(stored));
        when(docks.findById(1L)).thenReturn(Optional.of(stored));
        when(docks.save(any(DockEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(docks.findAll()).thenAnswer(inv -> java.util.List.of(stored));
        when(stateLog.save(any(DockStateLogEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gateway.transportName()).thenReturn("sim");
    }

    // ------------------------------------------------------------------
    // OSD 摄取
    // ------------------------------------------------------------------

    @Test
    @DisplayName("OSD 心跳：首次心跳 OFFLINE → IDLE（上线），温度/电量落地")
    void osdHeartbeatBringsOnline() {
        stored.state = DockState.OFFLINE;

        DockEntity result = service.ingestOsd("DOCK-001", null, 32.5, 88, 9);

        assertThat(result.state).isEqualTo(DockState.IDLE);
        assertThat(result.temperatureC).isEqualTo(32.5);
        assertThat(result.batteryPct).isEqualTo(88);
        assertThat(result.droneSysid).isEqualTo(9);
        assertThat(result.lastHeartbeatMs).isGreaterThan(0);
        verify(stateLog).save(any(DockStateLogEntity.class));
    }

    @Test
    @DisplayName("温度超临界：置 FAULT 并记录原因")
    void criticalTemperatureTripsFault() {
        stored.temperatureC = 20.0; // 加热前的旧值
        DockEntity result = service.ingestOsd("DOCK-001", null, 75.0, 80, null);

        assertThat(result.state).isEqualTo(DockState.FAULT);
        assertThat(result.temperatureC).isEqualTo(75.0);
    }

    @Test
    @DisplayName("温度告警区（>=warn <crit）：状态不变、写 temp-warning 日志")
    void warnTemperatureKeepsStateAndLogs() {
        DockEntity result = service.ingestOsd("DOCK-001", null, 60.0, 80, null);

        assertThat(result.state).isEqualTo(DockState.IDLE);
        ArgumentCaptor<DockStateLogEntity> cap = ArgumentCaptor.forClass(DockStateLogEntity.class);
        verify(stateLog).save(cap.capture());
        assertThat(cap.getValue().reason).startsWith("temp-warning:");
    }

    @Test
    @DisplayName("FAULT 不被 OSD 自报覆盖（防自动痊愈）")
    void faultNotClearedByOsd() {
        stored.state = DockState.FAULT;
        DockEntity result = service.ingestOsd("DOCK-001", DockState.IDLE, 30.0, 80, null);
        assertThat(result.state).isEqualTo(DockState.FAULT);
    }

    @Test
    @DisplayName("过渡态按自然后继推进（OPENING→OPEN）")
    void transitionalFollowsNaturalSuccessor() {
        stored.state = DockState.OPENING;
        DockEntity result = service.ingestOsd("DOCK-001", DockState.OPEN, null, null, null);
        assertThat(result.state).isEqualTo(DockState.OPEN);
    }

    @Test
    @DisplayName("未知 SN → IllegalArgumentException（控制器映射 404）")
    void unknownSnRejected() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> service.ingestOsd("NOPE", null, null, null, null));
    }

    // ------------------------------------------------------------------
    // 命令
    // ------------------------------------------------------------------

    @Test
    @DisplayName("开门：合法态下发并进入 OPENING")
    void openDoorFromIdle() {
        when(gateway.sendCommand(eq("DOCK-001"), eq(DockCommand.OPEN_DOOR), any()))
                .thenReturn(new DockGateway.DockReply(1, 2, 3, 0, null, "{}"));

        Map<String, Object> out = service.sendCommand(1L, DockCommand.OPEN_DOOR);

        assertThat(stored.state).isEqualTo(DockState.OPENING);
        assertThat(out).containsEntry("state", "OPENING");
        verify(gateway).sendCommand(eq("DOCK-001"), eq(DockCommand.OPEN_DOOR), any());
    }

    @Test
    @DisplayName("非法态开门：抛 IllegalDockTransition、不下发、状态不变")
    void openDoorFromWrongStateRejected() {
        stored.state = DockState.OFFLINE;

        assertThatExceptionOfType(IllegalDockTransitionException.class)
                .isThrownBy(() -> service.sendCommand(1L, DockCommand.OPEN_DOOR));
        verify(gateway, never()).sendCommand(anyString(), any(), any());
        assertThat(stored.state).isEqualTo(DockState.OFFLINE);
    }

    @Test
    @DisplayName("通道失败：回滚过渡态、异常上抛（不留下卡死的 OPENING）")
    void transportFailureRollsBackState() {
        when(gateway.sendCommand(anyString(), any(), any()))
                .thenThrow(new DockGateway.DockGatewayException("connection refused"));

        assertThatExceptionOfType(DockGateway.DockGatewayException.class)
                .isThrownBy(() -> service.sendCommand(1L, DockCommand.OPEN_DOOR));
        assertThat(stored.state).isEqualTo(DockState.IDLE);
    }

    @Test
    @DisplayName("机巢侧拒绝（result!=0）：回滚过渡态并抛通道异常")
    void dockSideRejectRollsBack() {
        when(gateway.sendCommand(anyString(), any(), any()))
                .thenReturn(new DockGateway.DockReply(1, 2, 3, 1, "state=FAULT", "{}"));

        assertThatExceptionOfType(DockGateway.DockGatewayException.class)
                .isThrownBy(() -> service.sendCommand(1L, DockCommand.OPEN_DOOR));
        assertThat(stored.state).isEqualTo(DockState.IDLE);
    }

    // ------------------------------------------------------------------
    // 离线巡检
    // ------------------------------------------------------------------

    @Test
    @DisplayName("心跳超时（>3 周期）→ OFFLINE")
    void offlineSweepMarksStaleDocks() {
        stored.state = DockState.IDLE;
        stored.lastHeartbeatMs = System.currentTimeMillis()
                - props.getOfflineAfterPeriods() * props.getHeartbeatPeriodMs() - 1000;

        service.offlineSweep();

        assertThat(stored.state).isEqualTo(DockState.OFFLINE);
    }

    @Test
    @DisplayName("心跳新鲜：巡检不动状态")
    void offlineSweepKeepsFreshDocks() {
        stored.state = DockState.CHARGING;
        stored.lastHeartbeatMs = System.currentTimeMillis();

        service.offlineSweep();

        assertThat(stored.state).isEqualTo(DockState.CHARGING);
    }

    // ------------------------------------------------------------------
    // 定时任务 CRUD
    // ------------------------------------------------------------------

    @Test
    @DisplayName("创建定时任务：cron 非法 → 400 语义（IllegalArgumentException）")
    void createScheduleRejectsBadCron() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> service.createSchedule(1L, "晨巡", "not-a-cron", null, true));
    }

    @Test
    @DisplayName("创建定时任务：合法写入、首次触发点由调度器登记（nextDueMs 为空）")
    void createScheduleOk() {
        when(schedules.save(any(DockScheduleEntity.class))).thenAnswer(inv -> {
            DockScheduleEntity s = inv.getArgument(0);
            s.id = 7L;
            return s;
        });

        DockScheduleEntity s = service.createSchedule(1L, "晨巡", "0 0 6 * * *", "[[22.59,113.93,60]]", true);

        assertThat(s.id).isEqualTo(7L);
        assertThat(s.nextDueMs).isNull();
        assertThat(s.enabled).isTrue();
    }
}
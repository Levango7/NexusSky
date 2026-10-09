package io.aerofleet.cloud.roc;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** E1 ROC：席位语义 + 警情三因子评分 + 派飞链。 */
@DisplayName("ROC 一控多机")
class RocServiceTest {

    private DeviceRegistry registry;
    private DroneCommandService commands;
    private RocSeatService seats;
    private IncidentDispatchService incidents;

    @BeforeEach
    void setUp() {
        registry = mock(DeviceRegistry.class);
        commands = mock(DroneCommandService.class);
        seats = new RocSeatService(registry);
        incidents = new IncidentDispatchService(seats, registry, commands);
        when(registry.isKnownDevice(anyInt())).thenReturn(true);
    }

    private DroneSnapshot drone(int sysid, boolean online, boolean armed,
                                int battery, double lat, double lon, String mode) {
        DroneSnapshot s = new DroneSnapshot(sysid);
        s.online = online;
        s.armed = armed;
        s.battery = battery;
        s.lat = lat;
        s.lon = lon;
        s.mode = mode;
        return s;
    }

    // ------------------------------------------------------------------
    // R1 席位
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("席位语义")
    class SeatTest {
        @Test
        void 创建与重复拒绝() {
            Map<String, Object> s = seats.create("op-1");
            assertThat(s).containsEntry("operatorName", "op-1");
            assertThatThrownBy(() -> seats.create("op-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        void 机队绑定与视图() {
            long id = (Long) seats.create("op-2").get("seatId");
            when(registry.get(9)).thenReturn(drone(9, true, false, 80, 22.59, 113.93, "STANDBY"));
            seats.bindFleet(id, List.of(9, 10));
            Map<String, Object> view = seats.view(id);
            assertThat(view.get("fleetSize")).isEqualTo(2);
        }

        @Test
        void 机队上限9拒绝() {
            long id = (Long) seats.create("op-3").get("seatId");
            List<Integer> ten = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
            assertThatThrownBy(() -> seats.bindFleet(id, ten))
                    .hasMessageContaining("seat limit 9");
        }

        @Test
        void 未知设备拒绝() {
            long id = (Long) seats.create("op-4").get("seatId");
            when(registry.isKnownDevice(99)).thenReturn(false);
            assertThatThrownBy(() -> seats.bindFleet(id, List.of(99)))
                    .hasMessageContaining("unknown sysids: [99]");
        }

        @Test
        void 指令子集越席位拒绝() {
            long id = (Long) seats.create("op-5").get("seatId");
            seats.bindFleet(id, List.of(9));
            assertThatThrownBy(() -> seats.targetsOf(id, List.of(8)))
                    .hasMessageContaining("not in seat");
        }
    }

    // ------------------------------------------------------------------
    // R3 警情三因子评分
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("警情三因子评分")
    class ScoreTest {
        @Test
        void 距离近者胜() {
            // 两机同电量同空闲，距离近者分高
            when(registry.get(9)).thenReturn(drone(9, true, false, 80, 22.5910, 113.9340, "STANDBY"));
            when(registry.get(10)).thenReturn(drone(10, true, false, 80, 22.5900, 113.9330, "STANDBY"));
            long id = (Long) seats.create("op").get("seatId");
            seats.bindFleet(id, List.of(9, 10));
            List<Map<String, Object>> scored = incidents.scoreFleet(seats.seatOf(id), 22.5901, 113.9331);
            assertThat(scored).hasSize(2);
            assertThat(((Number) scored.get(0).get("sysid")).intValue()).isEqualTo(10);   // 近者
        }

        @Test
        void 电量足者胜() {
            // 同距离，80% 电量胜 20%
            when(registry.get(9)).thenReturn(drone(9, true, false, 20, 22.5901, 113.9331, "STANDBY"));
            when(registry.get(10)).thenReturn(drone(10, true, false, 80, 22.5901, 113.9331, "STANDBY"));
            long id = (Long) seats.create("op").get("seatId");
            seats.bindFleet(id, List.of(9, 10));
            List<Map<String, Object>> scored = incidents.scoreFleet(seats.seatOf(id), 22.5901, 113.9331);
            assertThat(((Number) scored.get(0).get("sysid")).intValue()).isEqualTo(10);
        }

        @Test
        void 执行中与离线不参与() {
            when(registry.get(9)).thenReturn(drone(9, true, true, 90, 22.59, 113.93, "MISSION"));
            when(registry.get(10)).thenReturn(drone(10, false, false, 90, 22.59, 113.93, "STANDBY"));
            when(registry.get(11)).thenReturn(drone(11, true, false, 90, 22.59, 113.93, "STANDBY"));
            long id = (Long) seats.create("op").get("seatId");
            seats.bindFleet(id, List.of(9, 10, 11));
            List<Map<String, Object>> scored = incidents.scoreFleet(seats.seatOf(id), 22.59, 113.93);
            assertThat(scored).hasSize(1);
            assertThat(((Number) scored.get(0).get("sysid")).intValue()).isEqualTo(11);
        }

        @Test
        void 全忙则建议为空不硬塞() {
            when(registry.get(9)).thenReturn(drone(9, true, true, 90, 22.59, 113.93, "MISSION"));
            long id = (Long) seats.create("op").get("seatId");
            seats.bindFleet(id, List.of(9));
            Map<String, Object> inc = incidents.report(id, 22.590, 113.930, "P1", "测试警情");
            assertThat(inc.get("suggestedSysid")).isNull();
            assertThat((String) inc.get("suggestionReason")).contains("no available");
        }

        @Test
        void 非法优先级拒绝() {
            long id = (Long) seats.create("op").get("seatId");
            assertThatThrownBy(() -> incidents.report(id, 22.59, 113.93, "P9", "x"))
                    .hasMessageContaining("P0|P1|P2");
        }

        @Test
        void 警情坐标越界拒绝() {
            long id = (Long) seats.create("op").get("seatId");
            assertThatThrownBy(() -> incidents.report(id, 91.0, 113.93, "P1", "x"))
                    .hasMessageContaining("out of range");
            assertThatThrownBy(() -> incidents.report(id, 22.59, 181.0, "P1", "x"))
                    .hasMessageContaining("out of range");
        }

        @Test
        void 驻留时长越界拒绝() {
            when(registry.get(9)).thenReturn(drone(9, true, false, 90, 22.5910, 113.9340, "STANDBY"));
            long seatId = (Long) seats.create("op").get("seatId");
            seats.bindFleet(seatId, List.of(9));
            long incidentId = (Long) incidents.report(seatId, 22.5910, 113.9340, "P1", "x").get("incidentId");
            assertThatThrownBy(() -> incidents.dispatch(seatId, incidentId, 999))
                    .hasMessageContaining("holdSec");
        }
    }

    // ------------------------------------------------------------------
    // R3 派飞
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("确认派飞")
    class DispatchTest {
        @Test
        void 派飞链_机械arm上传启动() {
            when(registry.get(9)).thenReturn(drone(9, true, false, 90, 22.5910, 113.9340, "STANDBY"));
            long seatId = (Long) seats.create("op").get("seatId");
            seats.bindFleet(seatId, List.of(9));
            Map<String, Object> inc = incidents.report(seatId, 22.5910, 113.9340, "P0", "火情");
            long incidentId = (Long) inc.get("incidentId");

            Map<String, Object> out = incidents.dispatch(seatId, incidentId, 30);

            assertThat(out.get("status")).isEqualTo("DISPATCHED");
            org.mockito.Mockito.verify(commands).arm(9);
            org.mockito.Mockito.verify(commands).uploadMission(anyInt(), anyList());
            org.mockito.Mockito.verify(commands).startMission(9);
        }

        @Test
        void 派飞失败状态FAILED不留半执行() {
            when(registry.get(9)).thenReturn(drone(9, true, false, 90, 22.5910, 113.9340, "STANDBY"));
            doThrow(new RuntimeException("link down")).when(commands).arm(anyInt());
            long seatId = (Long) seats.create("op").get("seatId");
            seats.bindFleet(seatId, List.of(9));
            Map<String, Object> inc = incidents.report(seatId, 22.5910, 113.9340, "P1", "x");
            long incidentId = (Long) inc.get("incidentId");

            assertThatThrownBy(() -> incidents.dispatch(seatId, incidentId, 30))
                    .hasMessageContaining("link down");
            Map<String, Object> listed = incidents.listIncidents(seatId).get(0);
            assertThat(listed.get("status")).isEqualTo("FAILED");
        }

        @Test
        void 无建议派飞拒绝() {
            when(registry.get(9)).thenReturn(drone(9, true, true, 90, 22.59, 113.93, "MISSION"));
            long seatId = (Long) seats.create("op").get("seatId");
            seats.bindFleet(seatId, List.of(9));
            Map<String, Object> inc = incidents.report(seatId, 22.590, 113.930, "P2", "x");
            long incidentId = (Long) inc.get("incidentId");
            assertThatThrownBy(() -> incidents.dispatch(seatId, incidentId, 30))
                    .hasMessageContaining("no suggestion");
        }
    }
}

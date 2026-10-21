package io.aerofleet.cloud.mission.delivery;

import io.aerofleet.cloud.mission.common.DroneCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/v1/delivery/by-sysid/{sysid} 与
 * POST /api/v1/delivery/by-sysid/{sysid}/control 单测。
 *
 * <p><b>为什么要有这两个端点</b>：
 * <ul>
 *   <li>前端按"当前选中的无人机"轮询配送序列（SprayPanel 每秒一次），
 *       后端原来只按**序列 id** 查 ⇒ 前端调的是并不存在的 /delivery/sequence/{sysid}，
 *       404 被 .catch(() => null) 静默吞掉 ⇒ 面板永远空（2026-10-21 实测）。</li>
 *   <li>前端的"夹爪开/关"按钮调的是并不存在的 /spray/gripper；后端的夹爪命令
 *       （MAV_CMD_NEXUS_GRIPPER_CONTROL：START=抓取 / DROP=释放）本来就有，
 *       但只挂在"按序列 id"的控制端点上 ⇒ 这一层"按 sysid 找最新序列再控制"由服务端做。</li>
 * </ul>
 */
@DisplayName("DeliveryController 按 sysid 查/控配送序列")
class DeliveryBySysidTest {

    private DeliveryService service;
    private DeliveryController controller;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(DeliveryService.class);
        controller = new DeliveryController(service, Mockito.mock(DroneCommandService.class));
    }

    private DeliverySequence seq(int deliveryId, int sysid) {
        return new DeliverySequence(deliveryId, sysid, List.of());
    }

    private DeliveryController.ControlRequest controlReq(String action) {
        DeliveryController.ControlRequest req = new DeliveryController.ControlRequest();
        req.action = action;
        return req;
    }

    @Test
    void returnsLatestSequenceOfThatDrone() {
        Mockito.when(service.sequencesByTenant())
                .thenReturn(List.of(seq(1, 7), seq(2, 8), seq(3, 7)));

        ResponseEntity<Map<String, Object>> resp = controller.bySysid(7);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("deliveryId", 3);
    }

    @Test
    void doesNotReturnAnotherDroneSequence() {
        Mockito.when(service.sequencesByTenant()).thenReturn(List.of(seq(2, 8)));

        ResponseEntity<Map<String, Object>> resp = controller.bySysid(7);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void controlBySysidControlsLatestSequenceOfThatDrone() {
        Mockito.when(service.sequencesByTenant())
                .thenReturn(List.of(seq(1, 7), seq(4, 8), seq(2, 7)));
        Mockito.when(service.control(Mockito.eq(2), Mockito.eq("START")))
                .thenReturn(Map.of(2, DeliveryService.AckResult.ok()));

        ResponseEntity<Map<String, Object>> resp = controller.controlBySysid(7, controlReq("START"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("deliveryId", 2);
        Mockito.verify(service).control(2, "START");
    }

    @Test
    void controlBySysidDoesNotControlAnotherDroneSequence() {
        Mockito.when(service.sequencesByTenant()).thenReturn(List.of(seq(4, 8)));
        Mockito.when(service.control(Mockito.anyInt(), Mockito.anyString()))
                .thenReturn(Map.of());

        ResponseEntity<Map<String, Object>> resp = controller.controlBySysid(7, controlReq("START"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Mockito.verify(service, Mockito.never()).control(Mockito.anyInt(), Mockito.anyString());
    }

    @Test
    void viewShapeMatchesIdLookup() {
        Mockito.when(service.sequencesByTenant()).thenReturn(List.of(seq(5, 7)));
        Mockito.when(service.sequence(5)).thenReturn(seq(5, 7));

        Map<String, Object> bySysid = controller.bySysid(7).getBody();
        Map<String, Object> byId = controller.get(5);

        assertThat(bySysid).containsOnlyKeys(byId.keySet());
        assertThat(bySysid).containsEntry("deliveryId", 5);
        assertThat(bySysid).containsKey("state");
        assertThat(bySysid).containsKey("sites");
    }
}

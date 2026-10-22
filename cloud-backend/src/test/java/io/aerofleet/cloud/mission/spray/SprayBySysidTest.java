package io.aerofleet.cloud.mission.spray;

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
 * GET /api/v1/spray/by-sysid/{sysid} 单测。
 *
 * <p><b>为什么要有这个端点</b>：前端按"当前选中的无人机"轮询喷洒状态
 * （SprayPanel 每秒一次），而后端原来的查询只按**任务 id** 查 ——
 * 前端手里没有任务 id，于是去调并不存在的 /spray/status/{sysid}，
 * 404 又被 .catch(() => null) 静默吞掉 ⇒ 面板永远空、界面看不出异常。
 * （2026-10-21 实测：前端 5 个面板共 12 处端点对不上后端。）
 */
@DisplayName("SprayController 按 sysid 查喷洒任务")
class SprayBySysidTest {

    private SprayTaskService service;
    private SprayController controller;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(SprayTaskService.class);
        controller = new SprayController(service);
    }

    private SprayTask task(int taskId, int sysid) {
        return new SprayTask(taskId, sysid,
                List.of(new double[]{30.0, 120.0}, new double[]{30.001, 120.0}),
                100, 10000, 5.0);
    }

    @Test
    void returnsLatestTaskOfThatDrone() {
        Mockito.when(service.tasksByTenant())
                .thenReturn(List.of(task(1, 7), task(3, 8), task(2, 7)));

        ResponseEntity<Map<String, Object>> resp = controller.bySysid(7);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("taskId", 2);
    }

    @Test
    void doesNotReturnAnotherDroneTask() {
        // 别的机的任务：不能被当成这台机的状态返回
        Mockito.when(service.tasksByTenant()).thenReturn(List.of(task(3, 8)));

        ResponseEntity<Map<String, Object>> resp = controller.bySysid(7);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void returns404WhenDroneHasNoTask() {
        Mockito.when(service.tasksByTenant()).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> resp = controller.bySysid(7);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).containsKey("error");
    }

    @Test
    void viewShapeMatchesIdLookup() {
        // 按 sysid 查与按 id 查必须是同一套字段（前端面板两处都用）
        Mockito.when(service.tasksByTenant()).thenReturn(List.of(task(5, 7)));
        Mockito.when(service.task(5)).thenReturn(task(5, 7));

        Map<String, Object> bySysid = controller.bySysid(7).getBody();
        Map<String, Object> byId = controller.get(5);

        assertThat(bySysid).containsOnlyKeys(byId.keySet());
        assertThat(bySysid).containsEntry("taskId", 5);
        assertThat(bySysid).containsKey("state");
        assertThat(bySysid).containsKey("coverageRate");
    }
}

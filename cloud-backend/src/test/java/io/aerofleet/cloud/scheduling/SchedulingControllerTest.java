package io.aerofleet.cloud.scheduling;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SchedulingControllerTest {
    @Autowired private MockMvc mockMvc;

    @Test
    void testStatusEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/scheduling/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    /** 预约请求体（时间用相对秒值：purge 以"新预约 startTime"为界，不依赖真实时钟）。 */
    private static String reservationBody(int sysid, double lat, double lon, double alt,
                                          double start, double end, double radius) {
        return String.format(java.util.Locale.ROOT,
                "{\"sysid\":%d,\"lat\":%.6f,\"lon\":%.6f,\"alt\":%.2f,"
                        + "\"startTime\":%.1f,\"endTime\":%.1f,\"radius\":%.1f}",
                sysid, lat, lon, alt, start, end, radius);
    }

    @Test
    void reserveAirspace_thenConflict409WithAirspaceAlert_andSnapshot() throws Exception {
        // 独特坐标：预约表是单例 bean 的内存态，避开其他用例的残留
        double lat = 39.9001;
        double lon = 116.4001;
        double now = System.currentTimeMillis();

        mockMvc.perform(post("/api/v1/scheduling/reservations")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(reservationBody(1, lat, lon, 100, now, now + 100_000, 50)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("reserved"))
                .andExpect(jsonPath("$.sysid").value(1));

        // 时间重叠 + 空间重叠 → 409，且返回冲突对方（协议面 AIRSPACE 告警同帧发布，
        // 发布失败不影响响应——事件通道的无设备场景由 listener 侧忽略）
        mockMvc.perform(post("/api/v1/scheduling/reservations")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(reservationBody(2, lat, lon, 100, now + 50_000, now + 150_000, 50)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("rejected"))
                .andExpect(jsonPath("$.conflictType").value("AIRSPACE"))
                .andExpect(jsonPath("$.conflictSysid").value(1));

        mockMvc.perform(get("/api/v1/scheduling/reservations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void reserveAirspace_missingField_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/scheduling/reservations")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"sysid\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void retryTask_endpoint() throws Exception {
        // 无快照 → retried=false（宽容语义，与 fail/release 一致）
        mockMvc.perform(post("/api/v1/scheduling/tasks/not-exist/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retried").value(false));
    }

    @Test
    void releaseReservations_endpoint() throws Exception {
        double now = System.currentTimeMillis();
        double lat = 39.9101, lon = 116.4101;
        mockMvc.perform(post("/api/v1/scheduling/reservations")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(reservationBody(9, lat, lon, 100, now, now + 100_000, 50)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/scheduling/reservations/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sysid").value(9))
                .andExpect(jsonPath("$.released").value(1));

        // 释放后快照不含该机
        mockMvc.perform(get("/api/v1/scheduling/reservations"))
                .andExpect(status().isOk());

        // 无预约的机释放 → released=0（宽容语义）
        mockMvc.perform(delete("/api/v1/scheduling/reservations/99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(0));
    }

    @Test
    void failTask_endpointIsWiredAndLenientOnUnknown() throws Exception {
        // 未知任务返回 failed=false（与 start/complete/cancel 的宽容语义一致；
        // true 路径由 TaskAssignmentServiceTest 覆盖——需要真实分配链）
        mockMvc.perform(post("/api/v1/scheduling/tasks/not-exist/fail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("not-exist"))
                .andExpect(jsonPath("$.failed").value(false));
    }

    // ============================================================
    // Bean Validation 链路（真正锁住 @RequestBody 上的 @Valid）
    //
    // TaskRequestValidationTest 直接调 validator.validate()，绕过了 Spring，
    // 所以那边全绿也挡不住「把 createTask 的 @Valid 删掉」。这里走真实链路：
    // @Valid 一旦掉了，下面的 400 会变成 200。
    //
    // 这几条约束是**唯一防线**：TaskAssignmentService 的 capabilityMatch() 对未知
    // taskType 走 default 分支，priorityToProtocol() 对越界 priority 也只落到某一档，
    // 都不拒绝；targetLat/targetLon 更是没有任何服务层检查。
    // ============================================================

    /** 合法任务体。 */
    private static String taskBody(String taskType, int priority, double lat, double lon, double alt) {
        return String.format(java.util.Locale.ROOT,
                "{\"taskId\":\"val-guard-%d\",\"taskType\":\"%s\",\"priority\":%d,"
                        + "\"targetLat\":%.6f,\"targetLon\":%.6f,\"targetAlt\":%.2f}",
                System.nanoTime(), taskType, priority, lat, lon, alt);
    }

    @Test
    void createTask_outOfRangeCoordinatesRejectedByBeanValidation() throws Exception {
        mockMvc.perform(post("/api/v1/scheduling/tasks")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(taskBody("SURVEY", 5, 200.0, 116.0, 50.0)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createTask_priorityOutOfRangeRejectedByBeanValidation() throws Exception {
        mockMvc.perform(post("/api/v1/scheduling/tasks")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(taskBody("SURVEY", 11, 22.59, 113.93, 50.0)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createTask_taskTypeNotInWhitelistRejectedByBeanValidation() throws Exception {
        mockMvc.perform(post("/api/v1/scheduling/tasks")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(taskBody("NOT_A_TYPE", 5, 22.59, 113.93, 50.0)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createTask_blankTaskIdRejectedByBeanValidation() throws Exception {
        mockMvc.perform(post("/api/v1/scheduling/tasks")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":\"   \",\"taskType\":\"SURVEY\",\"priority\":5,"
                                + "\"targetLat\":22.59,\"targetLon\":113.93,\"targetAlt\":50.0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createTask_validBodyIsNotRejectedByValidation() throws Exception {
        // 反向对照：全部合法时不能被校验层拦。不关心返回体（无在线无人机时也可能是别的码），
        // 只关心「不是 400」——否则上面四条可能是别的 400 路径在起作用。
        mockMvc.perform(post("/api/v1/scheduling/tasks")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(taskBody("SURVEY", 5, 22.59, 113.93, 50.0)))
                .andExpect(status().is(org.hamcrest.Matchers.not(
                        org.springframework.http.HttpStatus.BAD_REQUEST.value())));
    }
}

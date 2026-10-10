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

        mockMvc.perform(post("/api/v1/scheduling/reservations")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(reservationBody(1, lat, lon, 100, 0, 100, 50)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("reserved"))
                .andExpect(jsonPath("$.sysid").value(1));

        // 时间重叠 + 空间重叠 → 409，且返回冲突对方（协议面 AIRSPACE 告警同帧发布，
        // 发布失败不影响响应——事件通道的无设备场景由 listener 侧忽略）
        mockMvc.perform(post("/api/v1/scheduling/reservations")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(reservationBody(2, lat, lon, 100, 50, 150, 50)))
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
    void failTask_endpointIsWiredAndLenientOnUnknown() throws Exception {
        // 未知任务返回 failed=false（与 start/complete/cancel 的宽容语义一致；
        // true 路径由 TaskAssignmentServiceTest 覆盖——需要真实分配链）
        mockMvc.perform(post("/api/v1/scheduling/tasks/not-exist/fail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("not-exist"))
                .andExpect(jsonPath("$.failed").value(false));
    }
}

package io.aerofleet.cloud.api.controller;

import io.aerofleet.cloud.vision.CaptureService;
import io.aerofleet.cloud.vision.OrbitJobManager;
import io.aerofleet.cloud.vision.OrbitService;
import io.aerofleet.cloud.vision.TargetTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 视觉单拍端点的 HTTP 状态码语义（P1-2 假成功回归门）。
 * <p>
 * 缺陷：{@code captureAndLocate} 宽捕 {@code catch (Exception e)} 后返回
 * {@code Map.of(status=error, ...)}，Spring 默认序列化为 <b>HTTP 200</b>。
 * 调用方看到 200 就当成功，视觉管道其实已失败（拉不到 JPEG / 解算异常），
 * 这是典型的「假成功」。修复：内部失败必须返回 5xx，让传输层就能区分成败。
 * <p>
 * 用 standaloneSetup 而非全上下文：standalone 不注册 {@code RoleInterceptor}，
 * 可直接打到控制器方法本身，聚焦状态码语义而非鉴权。
 */
@DisplayName("Vision capture 端点失败返回 5xx（不假成功）")
class VisionCaptureHttpStatusTest {

    private CaptureService capture;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        capture = mock(CaptureService.class);
        VisionController controller = new VisionController(capture,
                mock(OrbitService.class), mock(OrbitJobManager.class), mock(TargetTracker.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("内部管道异常 → HTTP 500，而非 200 假成功")
    void failureReturns500() throws Exception {
        when(capture.captureAndLocate(anyInt(), any())).thenThrow(new RuntimeException("boom"));

        mockMvc.perform(post("/api/v1/vision/drones/7/capture"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    @DisplayName("成功 → HTTP 200 + detections")
    void successReturns200() throws Exception {
        when(capture.captureAndLocate(anyInt(), any()))
                .thenReturn(Map.of("detections", List.of()));

        mockMvc.perform(post("/api/v1/vision/drones/7/capture"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detections").isArray());
    }
}

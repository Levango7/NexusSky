package io.aerofleet.cloud.vision;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link CvEvalController} 测试（F1，spec E3/E4）：standalone MockMvc。
 */
@DisplayName("CvEvalController CV 评测 API")
class CvEvalControllerTest {

    private CvEvalService eval;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        eval = new CvEvalService(100, 5.0);
        mockMvc = MockMvcBuilders.standaloneSetup(new CvEvalController(eval)).build();
    }

    @Test
    @DisplayName("metrics 空窗口 → frames=0，指标为 null（非 100%/0% 假象）")
    void metricsEmptyWindow() throws Exception {
        mockMvc.perform(get("/api/v1/cv-eval/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frames").value(0))
                .andExpect(jsonPath("$.recall").doesNotExist())
                .andExpect(jsonPath("$.reference.recall").value(0.85));
    }

    @Test
    @DisplayName("record 后 metrics 返回聚合指标")
    void metricsAfterRecords() throws Exception {
        eval.record(1, "pixels", 2, java.util.List.of(1.0, 9.0), 42.5);

        mockMvc.perform(get("/api/v1/cv-eval/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frames").value(1))
                .andExpect(jsonPath("$.recall").value(0.5))
                .andExpect(jsonPath("$.falseDetectionRatio").value(0.5))
                .andExpect(jsonPath("$.latencyAvgMs").value(42.5))
                .andExpect(jsonPath("$.perFrame[0].source").value("pixels"));
    }

    @Test
    @DisplayName("source 过滤参数生效")
    void metricsSourceFilter() throws Exception {
        eval.record(1, "truth", 1, java.util.List.of(1.0), 5.0);
        eval.record(2, "external", 1, java.util.List.of(9.0), 8.0);

        mockMvc.perform(get("/api/v1/cv-eval/metrics").param("source", "external"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frames").value(1))
                .andExpect(jsonPath("$.perFrame[0].frameSeq").value(2));
    }

    @Test
    @DisplayName("reset 清空窗口")
    void resetClearsWindow() throws Exception {
        eval.record(1, "truth", 1, java.util.List.of(1.0), 5.0);

        mockMvc.perform(post("/api/v1/cv-eval/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mockMvc.perform(get("/api/v1/cv-eval/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frames").value(0));
        assertThat(eval.snapshot()).isEmpty();
    }
}

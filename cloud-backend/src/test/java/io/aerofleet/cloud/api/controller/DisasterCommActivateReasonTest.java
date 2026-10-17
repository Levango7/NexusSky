package io.aerofleet.cloud.api.controller;

import io.aerofleet.cloud.api.service.DisasterCommService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/v1/disaster/activate} 的请求体在 **HTTP 层**的绑定。
 *
 * <p>为什么必须单独有一个走 MockMvc 的类：{@code DisasterCommControllerTest} 调的是
 * {@code controller.activate(null)} —— 直接 Java 调用，{@code @RequestBody} 这一层
 * <b>结构上从未被执行过</b>。控制器当时既无参数也无 {@code @RequestBody}，前端发来的
 * {@code reason} 被 Spring 整个丢弃、服务端硬编码 {@code "manual"}，而面板上的下拉框仍让
 * 用户从 5 个选项里选 —— 这个缺陷因此完全测不出来：直接调用根本不会经过绑定，而
 * {@code DisasterCommService} 层的单测看到的又永远是硬编码值。
 *
 * <p>本类用 {@code MockMvcBuilders.standaloneSetup}：不需要 Spring 上下文、不需要数据库，
 * 但会走完整的 {@code @RequestBody} 绑定 —— 正好补上被跳过的那一层。
 */
@DisplayName("DisasterCommController 激活请求体的 HTTP 层绑定")
class DisasterCommActivateReasonTest {

    /** 前端 DisasterCommPanel 下拉框实际提供的 5 个选项（逐字照抄，含选项 value）。 */
    private static final String[] FRONTEND_OPTIONS = {
            "manual", "heartbeat_timeout", "terrain_change", "earthquake", "mudslide"
    };

    private MockMvc mvc;
    private DisasterCommService service;

    @BeforeEach
    void setUp() {
        service = new DisasterCommService(null);
        mvc = MockMvcBuilders.standaloneSetup(new DisasterCommController(service)).build();
    }

    private static String body(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    @Test
    @DisplayName("下拉框每个选项都被接受，且 triggerReason 回显的就是用户选的那个")
    void everyFrontendOptionIsAcceptedAndEchoed() throws Exception {
        for (String reason : FRONTEND_OPTIONS) {
            mvc.perform(post("/api/v1/disaster/activate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(reason)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.triggerReason").value(reason));
        }
    }

    @Test
    @DisplayName("reason 落进了服务端状态，不只是响应里回显了一下")
    void reasonIsPersistedInServiceState() throws Exception {
        mvc.perform(post("/api/v1/disaster/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("earthquake")))
                .andExpect(status().isOk());
        // 再从 /status 读一次：面板 L367 显示的「触发原因」走的就是这条路
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/disaster/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triggerReason").value("earthquake"));
        org.assertj.core.api.Assertions.assertThat(service.getDisasterStatus().get("triggerReason"))
                .isEqualTo("earthquake");
    }

    @Test
    @DisplayName("不带 body 的旧调用方仍返回 manual（向后兼容，既有测试依赖这一点）")
    void missingBodyFallsBackToManual() throws Exception {
        mvc.perform(post("/api/v1/disaster/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triggerReason").value("manual"));
    }

    @Test
    @DisplayName("空串 / null reason 归一化为 manual，而不是留下一个空显示")
    void blankReasonFallsBackToManual() throws Exception {
        mvc.perform(post("/api/v1/disaster/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triggerReason").value("manual"));

        mvc.perform(post("/api/v1/disaster/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triggerReason").value("manual"));
    }

    @Test
    @DisplayName("未知 reason 返回 400 并回列允许取值，不静默改写成 manual")
    void unknownReasonIsRejectedWith400() throws Exception {
        mvc.perform(post("/api/v1/disaster/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("bogus")))
                .andExpect(status().isBadRequest())
                // `error` 键必须有：api.js 的 jsonFetch 在 !res.ok 时只读 body.error，
                // 缺了它界面只能显示 "HTTP 400"，把「未知哪个原因」这条信息丢掉。
                .andExpect(jsonPath("$.error").value("未知的触发原因: bogus"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.allowed").isArray());

        // 被拒之后状态没被改坏
        org.assertj.core.api.Assertions.assertThat(service.getDisasterStatus().get("mode"))
                .isEqualTo("inactive");
    }

    @Test
    @DisplayName("非字符串 reason（如数字）同样 400，不做隐式强转后蒙混过关")
    void nonStringReasonIsRejectedWith400() throws Exception {
        mvc.perform(post("/api/v1/disaster/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("允许取值同时覆盖 MAVLink 序数词汇与前端灾害类型（两条路径词汇不同）")
    void allowedSetCoversBothVocabularies() {
        org.assertj.core.api.Assertions
                .assertThat(DisasterCommService.ALLOWED_TRIGGER_REASONS)
                .contains("manual", "heartbeat_timeout", "terrain_change", "auto",
                        "earthquake", "mudslide");
        // 前端提供的 5 个选项必须是允许集合的子集，否则下拉框里有选项点了就 400
        org.assertj.core.api.Assertions
                .assertThat(DisasterCommService.ALLOWED_TRIGGER_REASONS)
                .contains(FRONTEND_OPTIONS);
    }
}
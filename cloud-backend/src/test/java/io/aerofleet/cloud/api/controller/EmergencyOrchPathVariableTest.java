package io.aerofleet.cloud.api.controller;

import io.aerofleet.cloud.api.service.EmergencyOrchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/v1/emergency/scenarios/{type}/start} 在 **HTTP 层**的路径变量解析。
 *
 * <p>为什么必须单独有一个走 MockMvc 的类：{@code EmergencyOrchControllerTest} 的头注释
 * 写明「直接实例化 Controller + Service（无 MockMvc / Spring 上下文）」，它调的是
 * {@code controller.startScenario(0, body)} —— 直接 Java 调用 + int 字面量。
 * 于是 {@code @PathVariable("type") int} 这一层转换<b>结构上从未被执行过</b>，
 * 前端传字符串类型的缺陷因此完全测不出来。
 *
 * <p>本类用 {@code MockMvcBuilders.standaloneSetup}：不需要 Spring 上下文、不需要数据库，
 * 但会走完整的路径变量绑定 —— 正好补上被跳过的那一层。
 */
@DisplayName("EmergencyOrchController 路径变量的 HTTP 层解析")
class EmergencyOrchPathVariableTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ApplicationEventPublisher noop = event -> { };
        EmergencyOrchService service = new EmergencyOrchService(null, noop);
        mvc = MockMvcBuilders.standaloneSetup(new EmergencyOrchController(service)).build();
    }

    private static String validBody() {
        return "{\"centerLat\":399000000,\"centerLon\":1163000000,\"radius\":5000,\"droneIds\":[1,2,3]}";
    }

    @Test
    @DisplayName("数字 type 能被解析成 int，返回 200 与 planId（后端契约就是 int）")
    void numericTypeResolvesTo200() throws Exception {
        mvc.perform(post("/api/v1/emergency/scenarios/0/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").isNumber())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    @DisplayName("字符串 type（前端实际发送的 'earthquake'）被拒 400 —— 这是缺陷的实测证据")
    void stringTypeIsRejectedWith400() throws Exception {
        mvc.perform(post("/api/v1/emergency/scenarios/earthquake/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("同一组字符串 type 都必须被拒：前端 SCENARIO_TYPES 的四个值全部命中同一条路径")
    void allFrontendScenarioStringsAreRejected() throws Exception {
        for (String type : new String[] { "earthquake", "mudslide", "fire", "custom" }) {
            mvc.perform(post("/api/v1/emergency/scenarios/" + type + "/start")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validBody()))
                    .andExpect(status().isBadRequest());
        }
    }
}
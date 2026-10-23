package io.aerofleet.cloud.surveillance;

import io.aerofleet.cloud.alarm.AlarmEventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * {@link SurveillanceController} SSE 订阅生命周期契约。
 *
 * <p>SSE 订阅不是"返回一个 emitter 就完了"：控制器在 emitter 上注册了
 * 完成回调，**回调体必须注销厂商订阅**——否则断线的浏览器在厂商客户端的事件
 * 订阅表里留下一条永远清不掉的登记。此前没有任何测试触发过这些回调，
 * "注销厂商订阅"成为存活变异体（订阅表只增不减）。
 *
 * <p>两条验证通道配合：
 * <ul>
 *   <li><b>未知设备直调</b>——返回的 emitter 已以错误终结，对已完成的 emitter
 *       再 send 必抛 IllegalStateException（流终止契约，无需容器）；</li>
 *   <li><b>MockMvc async 路径</b>——对<b>已初始化</b>的 emitter（MVC 已挂住
 *       返回值处理器），{@code AsyncContext.complete()} 会真实触发 onCompletion
 *       回调 ⇒ 核对 {@code adapter.unsubscribeEvents(handle)} 被调用且订阅表清空。
 *       未初始化的 emitter 上 complete() 不会同步触发回调——这是第一版直调
 *       测试撞到的事实，必须走 MockMvc 的 async 完成事件。</li>
 * </ul>
 *
 * <p>未在本类覆盖的两个存活变异体（分诊见 docs/mutation-testing.md 第四轮）：
 * 建立连接时的立即注释 send（无容器下 Maven 测不到头部提交时点）与 onTimeout
 * 回调（只有容器超时才会触发，MockAsyncContext 无法模拟 fire）。
 */
@DisplayName("SurveillanceController SSE 订阅生命周期契约")
class SurveillanceControllerSseContractTest {

    private static final String DEVICE_ID = "cam-onvif";
    private static final String HANDLE = "192.168.1.100:80";
    private static final String EVENTS_URL = "/api/v1/surveillance/devices/" + DEVICE_ID + "/events";

    private SurveillanceDeviceRegistry registry;
    private OnvifClient onvifSpy;
    private SurveillanceController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        registry = new SurveillanceDeviceRegistry();
        // spy 包住真实客户端：默认真实行为放行，仅用于核对交互
        onvifSpy = Mockito.spy(new OnvifClient());
        VendorAdapterRegistry adapterRegistry = new VendorAdapterRegistry(
                List.of(new OnvifVendorAdapter(onvifSpy), new Gb28181Adapter(
                        new Gb28181CatalogParser(), "34010000002000000001", "127.0.0.1:5060")));
        controller = new SurveillanceController(registry, onvifSpy,
                new RapidDeployService(onvifSpy, registry), adapterRegistry,
                Mockito.mock(AlarmEventStore.class), new MjpegStreamService());
        mockMvc = standaloneSetup(controller).build();

        SurveillanceDevice d = new SurveillanceDevice(DEVICE_ID, "前端球机",
                SurveillanceDevice.Vendor.ONVIF, "192.168.1.100", 80, "admin", "pass");
        registry.register(d);
    }

    @Test
    @DisplayName("订阅不存在设备：返回的 emitter 立即以错误终止（再 send 必抛）")
    void subscribeUnknownDevice_streamTerminatesWithError() {
        SseEmitter emitter = controller.subscribeEvents("nope");

        assertThat(emitter).isNotNull();
        assertThatThrownBy(() -> emitter.send(SseEmitter.event().comment("ping")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("订阅建立成功：厂商订阅表登记；AsyncContext.complete 触发注销")
    void completeFiresAdapterUnsubscribe() throws Exception {
        MvcResult result = mockMvc.perform(get(EVENTS_URL))
                .andExpect(request().asyncStarted())
                .andReturn();

        // mock 容器的 async 完成事件 → MVC 真实触发 emitter 的 onCompletion 回调
        result.getRequest().getAsyncContext().complete();

        verify(onvifSpy, times(1)).unsubscribeEvents(HANDLE);
        assertThat(onvifSpy.activeSubscriberCount()).isZero();
    }

    @Test
    @DisplayName("同一设备断开重订阅：再登记 → 再注销，事件订阅表不遗留")
    void resubscribeAfterCompletionCleansUpAgain() throws Exception {
        MvcResult first = mockMvc.perform(get(EVENTS_URL))
                .andExpect(request().asyncStarted())
                .andReturn();
        first.getRequest().getAsyncContext().complete();
        assertThat(onvifSpy.activeSubscriberCount()).isZero();

        MvcResult second = mockMvc.perform(get(EVENTS_URL))
                .andExpect(request().asyncStarted())
                .andReturn();
        second.getRequest().getAsyncContext().complete();

        verify(onvifSpy, times(2)).unsubscribeEvents(HANDLE);
        assertThat(onvifSpy.activeSubscriberCount()).isZero();
    }
}

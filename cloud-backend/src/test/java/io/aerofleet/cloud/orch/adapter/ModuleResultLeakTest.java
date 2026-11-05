package io.aerofleet.cloud.orch.adapter;

import io.aerofleet.cloud.api.service.EmergencyOrchService;
import io.aerofleet.cloud.delivery2.DeliveryController2;
import io.aerofleet.cloud.mission.formation.FormationService;
import io.aerofleet.cloud.mapping.MappingController;
import io.aerofleet.cloud.show.ShowController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * orch 适配器 ModuleResult.errorMessage 不得粘底层异常文本（泄露第三批回归门）。
 * <p>
 * {@code ModuleResult.fail(..., e.getMessage())} 的消息落
 * {@code TaskStepEntity.failReason}（持久化）并经 StepFailEvent → WS/SSE
 * {@code step-fail} 帧推给订阅客户端——所以适配器 catch 里只允许
 * <b>服务端自建短句</b>；底层异常（含堆栈）进日志。判据同 #246/#248：
 * canary 串不进对外消息。
 */
@DisplayName("orch 适配器 errorMessage 不含底层异常细节")
class ModuleResultLeakTest {

    /** 底层异常消息里的内部细节（host/端口/凭据名）。 */
    private static final String CANARY =
            "connect to 10.9.9.9:16666 failed (redis token redis-secret-xyz)";

    private static void assertAuthored(ModuleResult r) {
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getErrorMessage()).doesNotContain(CANARY);
        assertThat(r.getErrorMessage()).doesNotContain("10.9.9.9");
        assertThat(r.getErrorMessage()).contains("失败");
    }

    @Test
    @DisplayName("DeliveryAdapter getTaskStatus 失败 → errorMessage 自建短句")
    void deliveryStatusFailureKeepsMessageAuthored() {
        DeliveryController2 ctrl = mock(DeliveryController2.class);
        when(ctrl.getStatus(anyString())).thenThrow(new RuntimeException(CANARY));
        ModuleResult r = new DeliveryAdapter(ctrl).getTaskStatus("t-9");
        assertAuthored(r);
    }

    @Test
    @DisplayName("ShowAdapter getTaskStatus 失败 → errorMessage 自建短句")
    void showStatusFailureKeepsMessageAuthored() {
        ShowController ctrl = mock(ShowController.class);
        when(ctrl.getTask(anyString())).thenThrow(new RuntimeException(CANARY));
        ModuleResult r = new ShowAdapter(ctrl).getTaskStatus("t-9");
        assertAuthored(r);
    }

    @Test
    @DisplayName("MappingAdapter getTaskStatus 失败 → errorMessage 自建短句")
    void mappingStatusFailureKeepsMessageAuthored() {
        MappingController ctrl = mock(MappingController.class);
        when(ctrl.getTask(anyString())).thenThrow(new RuntimeException(CANARY));
        ModuleResult r = new MappingAdapter(ctrl).getTaskStatus("t-9");
        assertAuthored(r);
    }

    @Test
    @DisplayName("EmergencyAdapter getTaskStatus 失败 → errorMessage 自建短句")
    void emergencyStatusFailureKeepsMessageAuthored() {
        EmergencyOrchService svc = mock(EmergencyOrchService.class);
        when(svc.getPlan(anyLong())).thenThrow(new RuntimeException(CANARY));
        ModuleResult r = new EmergencyAdapter(svc).getTaskStatus("7");
        assertAuthored(r);
    }

    @Test
    @DisplayName("FormationAdapter getTaskStatus 失败 → errorMessage 自建短句")
    void formationStatusFailureKeepsMessageAuthored() {
        FormationService svc = mock(FormationService.class);
        when(svc.formation(anyInt())).thenThrow(new RuntimeException(CANARY));
        ModuleResult r = new FormationAdapter(svc).getTaskStatus("9");
        assertAuthored(r);
    }
}

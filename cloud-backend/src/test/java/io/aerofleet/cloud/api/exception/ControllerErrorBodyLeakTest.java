package io.aerofleet.cloud.api.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.alarm.AlarmEventStore;
import io.aerofleet.cloud.api.controller.CellTowerController;
import io.aerofleet.cloud.api.controller.MultispectralController;
import io.aerofleet.cloud.api.controller.ThermalController;
import io.aerofleet.cloud.api.controller.VisionController;
import io.aerofleet.cloud.api.service.CellTowerTopologyService;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.UdpGateway;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.cloud.roc.IncidentDispatchService;
import io.aerofleet.cloud.roc.RocController;
import io.aerofleet.cloud.roc.RocSeatService;
import io.aerofleet.cloud.surveillance.MjpegStreamService;
import io.aerofleet.cloud.surveillance.OnvifClient;
import io.aerofleet.cloud.surveillance.RapidDeployService;
import io.aerofleet.cloud.surveillance.SurveillanceController;
import io.aerofleet.cloud.surveillance.SurveillanceDevice;
import io.aerofleet.cloud.surveillance.SurveillanceDeviceRegistry;
import io.aerofleet.cloud.surveillance.Vendor;
import io.aerofleet.cloud.surveillance.VendorAdapter;
import io.aerofleet.cloud.surveillance.VendorAdapterRegistry;
import io.aerofleet.cloud.vision.MultispectralTaskService;
import io.aerofleet.cloud.vision.OrbitJobManager;
import io.aerofleet.cloud.vision.OrbitService;
import io.aerofleet.cloud.vision.TargetTracker;
import io.aerofleet.cloud.vision.ThermalTaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 控制器边界错误响应不得回显底层异常文本（信息泄露回归门）。
 * <p>
 * 判据：宽捕 {@code catch (Exception e)} 会把 IO/Redis/Onvif/SQL 等基础设施异常
 * 一并吞进来；这些异常的消息含内部 host、URL、SQL 片段等，原样拼进响应体等于
 * 把内部拓扑/主机名/凭据名暴露给客户端。{@code ApiExceptionHandler} 生产口径对此
 * 已有定论（500 一律通用文案），控制器宽捕处必须与它一致：
 * <b>领域异常（IAE/ISE，消息由服务端自建）保留；宽捕尾巴一律通用文案</b>。
 * <p>
 * 每个用例的 CANARY 串模拟「底层异常消息里的内部细节」。
 */
@DisplayName("控制器错误响应不含底层异常细节（信息泄露回归门）")
class ControllerErrorBodyLeakTest {

    /** 底层异常消息里的内部细节（host/端口/凭据名）。 */
    private static final String CANARY =
            "connect to 10.9.9.9:16666 failed (redis token redis-secret-xyz)";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------
    // CellTower：宽捕 + e.getMessage()（UDP 发送 IOException）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PUT /celltowers/{sysid}/config 发送失败 → 502 且不含内部细节")
    void cellTowerConfigSendFailureReturnsGenericBody() throws Exception {
        UdpGateway gateway = mock(UdpGateway.class);
        doThrow(new IOException(CANARY)).when(gateway).send(anyInt(), any());
        CellTowerController controller =
                new CellTowerController(new CellTowerTopologyService(), gateway);

        var body = new CellTowerController.ConfigRequest();
        body.cellType = 0;
        body.txPowerDbm = 20;
        body.maxTerminals = 10;
        body.frequencyChannel = 5;

        var resp = controller.configureTower(7, body);

        assertThat(resp.getStatusCode().value()).isEqualTo(502);
        assertThat(errorText(resp.getBody())).doesNotContain(CANARY);
        assertThat(errorText(resp.getBody())).doesNotContain("10.9.9.9");
    }

    @Test
    @DisplayName("POST /celltowers/{sysid}/handover 发送失败 → 502 且不含内部细节")
    void cellTowerHandoverSendFailureReturnsGenericBody() throws Exception {
        UdpGateway gateway = mock(UdpGateway.class);
        doThrow(new IOException(CANARY)).when(gateway).send(anyInt(), any());
        CellTowerController controller =
                new CellTowerController(new CellTowerTopologyService(), gateway);

        var body = new CellTowerController.HandoverRequest();
        body.toSysid = 9;

        var resp = controller.triggerHandover(7, body);

        assertThat(resp.getStatusCode().value()).isEqualTo(502);
        assertThat(errorText(resp.getBody())).doesNotContain(CANARY);
        assertThat(errorText(resp.getBody())).doesNotContain("10.9.9.9");
    }

    // ------------------------------------------------------------------
    // Surveillance：宽捕 + e.getMessage()（ONVIF / 适配器 IOException）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /surveillance/discover 失败 → 502 且不含内部细节")
    void surveillanceDiscoverFailureReturnsGenericBody() throws Exception {
        OnvifClient onvif = mock(OnvifClient.class);
        when(onvif.discoverDevices(anyString())).thenThrow(new RuntimeException(CANARY));
        SurveillanceController controller =
                new SurveillanceController(new SurveillanceDeviceRegistry(), onvif,
                        new RapidDeployService(onvif, null), new VendorAdapterRegistry(List.of()),
                        mock(AlarmEventStore.class), new MjpegStreamService());

        var resp = controller.discover(MAPPER.readTree("{\"subnet\":\"192.168.31.0/24\"}"));

        assertThat(resp.getStatusCode().value()).isEqualTo(502);
        assertThat(errorText(resp.getBody())).doesNotContain(CANARY);
    }

    @Test
    @DisplayName("POST /surveillance/rapid-deploy 失败 → 502 且不含内部细节")
    void surveillanceRapidDeployFailureReturnsGenericBody() throws Exception {
        RapidDeployService deploy = mock(RapidDeployService.class);
        when(deploy.scanAndDeploy(anyString(), any(), any()))
                .thenThrow(new RuntimeException(CANARY));
        SurveillanceController controller =
                new SurveillanceController(new SurveillanceDeviceRegistry(), mock(OnvifClient.class),
                        deploy, new VendorAdapterRegistry(List.of()),
                        mock(AlarmEventStore.class), new MjpegStreamService());

        var resp = controller.rapidDeploy(
                MAPPER.readTree("{\"subnet\":\"192.168.31.0/24\"}"));

        assertThat(resp.getStatusCode().value()).isEqualTo(502);
        assertThat(errorText(resp.getBody())).doesNotContain(CANARY);
    }

    @Test
    @DisplayName("POST /surveillance/devices/{id}/ptz 失败 → 502 且不含内部细节")
    void surveillancePtzFailureReturnsGenericBody() throws Exception {
        VendorAdapter adapter = mock(VendorAdapter.class);
        when(adapter.getVendor()).thenReturn(Vendor.HIKVISION);
        doThrow(new RuntimeException(CANARY)).when(adapter).ptzControl(any(), anyString());
        var registry = new SurveillanceDeviceRegistry();
        registry.register(new SurveillanceDevice(
                "cam-1", "cam-cam-1", SurveillanceDevice.Vendor.HIKVISION,
                "192.168.1.100", 80, "a", "b"));
        SurveillanceController controller =
                new SurveillanceController(registry, mock(OnvifClient.class),
                        mock(RapidDeployService.class),
                        new VendorAdapterRegistry(List.of(adapter)),
                        mock(AlarmEventStore.class), new MjpegStreamService());

        var resp = controller.ptzControl("cam-1", MAPPER.readTree("{\"cmd\":\"zoomIn\"}"));

        assertThat(resp.getStatusCode().value()).isEqualTo(502);
        assertThat(errorText(resp.getBody())).doesNotContain(CANARY);
    }

    // ------------------------------------------------------------------
    // Thermal / Multispectral：宽捕 + e.getMessage()（服务内部异常）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Thermal createTask 内部异常 → 500 且不含内部细节")
    void thermalCreateTaskInternalErrorReturnsGenericBody() {
        ThermalTaskService service = mock(ThermalTaskService.class);
        when(service.create(anyInt(), any(), any(Double.class)))
                .thenThrow(new RuntimeException(CANARY));
        ThermalController controller = new ThermalController(service, mock(DeviceRegistry.class));

        var resp = controller.createTask(Map.of("sysid", 7, "hotspotThreshold", 60.0));

        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        assertThat(errorText(resp.getBody())).doesNotContain(CANARY);
    }

    @Test
    @DisplayName("Thermal createTask 领域异常（IAE）仍回自建消息（400）")
    void thermalCreateTaskDomainErrorStillReturnsOwnMessage() {
        ThermalTaskService service = mock(ThermalTaskService.class);
        when(service.create(anyInt(), any(), any(Double.class)))
                .thenThrow(new IllegalArgumentException("sysid 7 not registered"));
        ThermalController controller = new ThermalController(service, mock(DeviceRegistry.class));

        var resp = controller.createTask(Map.of("sysid", 7, "hotspotThreshold", 60.0));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(errorText(resp.getBody())).isEqualTo("sysid 7 not registered");
    }

    @Test
    @DisplayName("Multispectral createTask 内部异常 → 500 且不含内部细节")
    void multispectralCreateTaskInternalErrorReturnsGenericBody() {
        MultispectralTaskService service = mock(MultispectralTaskService.class);
        when(service.create(anyInt(), any(), any()))
                .thenThrow(new IllegalStateException(new IOException(CANARY)));
        MultispectralController controller = new MultispectralController(service);

        var resp = controller.createTask(Map.of("sysid", 7, "bands", List.of("NIR", "RED")));

        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        assertThat(errorText(resp.getBody())).doesNotContain(CANARY);
    }

    // ------------------------------------------------------------------
    // Roc 席位批量：逐机 reason 回显 e.getMessage()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Roc 批量指令逐机 reason 不含内部细节（整体 200）")
    void rocBatchPerDeviceReasonDoesNotEchoInternalDetail() {
        DeviceRegistry devices = new DeviceRegistry();
        devices.registerIfAbsent(7);
        RocSeatService seats = new RocSeatService(devices);
        long seatId = (long) (Number) seats.create("op-7").get("seatId");
        seats.bindFleet(seatId, List.of(7));

        DroneCommandService commands = mock(DroneCommandService.class);
        doThrow(new DroneCommandService.CommandException(
                "mission ack failed: " + CANARY, 3)).when(commands).arm(7);
        RocController controller =
                new RocController(seats, mock(IncidentDispatchService.class), commands);

        var resp = controller.batchCommand(seatId, Map.of("action", "arm", "sysids", List.of(7)));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results =
                (List<Map<String, Object>>) resp.getBody().get("targets");
        assertThat(results).hasSize(1);
        assertThat(String.valueOf(results.get(0).get("reason"))).doesNotContain(CANARY);
    }

    // ------------------------------------------------------------------
    // Vision：captureAndLocate 宽捕尾巴回显 e.getMessage()（返回体 result 字段）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Vision captureAndLocate 失败 → result 不含内部细节")
    void visionCaptureFailureDoesNotEchoInternalDetail() throws Exception {
        io.aerofleet.cloud.vision.CaptureService capture =
                mock(io.aerofleet.cloud.vision.CaptureService.class);
        when(capture.captureAndLocate(anyInt(), any()))
                .thenThrow(new RuntimeException(CANARY));
        VisionController controller = new VisionController(capture,
                mock(io.aerofleet.cloud.vision.OrbitService.class),
                mock(OrbitJobManager.class), mock(io.aerofleet.cloud.vision.TargetTracker.class));

        Map<String, Object> result = controller.captureAndLocate(7, null);

        assertThat(result.get("status")).isEqualTo("error");
        assertThat(String.valueOf(result.get("result"))).doesNotContain(CANARY);
    }

    // ------------------------------------------------------------------

    private static String errorText(Map<String, Object> body) {
        return String.valueOf(body.get("error"));
    }
}

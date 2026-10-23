package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OnvifVendorAdapter} 的入参校验与委托一致性契约
 * （P1 变异审计 #6 收口）。
 *
 * <p>四个公共方法都先 {@code validateDevice(device)} 再委托 {@link OnvifClient}。
 * 此前的测试只用"合法设备"走通，校验调用本身成为存活变异体（删掉调用后，
 * null 会以内联 NPE 的形态漏到 onvifClient 的参数求值处，类型与语义都错）。
 * 这里用 null 设备 / 空 ip 设备反例逐方法钉死异常类型与消息。
 *
 * <p>PTZ 还要验证"适配器只是转接、不改语义"：适配器返回值必须与直连
 * {@code OnvifClient} 同参调用一个字节不差——防止有人在适配层"顺手"加工返回值。
 */
@DisplayName("OnvifVendorAdapter 入参校验与委托一致性")
class OnvifVendorAdapterContractTest {

    private final OnvifClient client = new OnvifClient();
    private final OnvifVendorAdapter adapter = new OnvifVendorAdapter(client);

    private static SurveillanceDevice device() {
        return new SurveillanceDevice("onvif-1", "前端球机", SurveillanceDevice.Vendor.ONVIF,
                "192.168.1.50", 8899, "admin", "p@ss");
    }

    @Test
    @DisplayName("getStreamUrl(null,…) 抛 IllegalArgumentException（不是 NPE）")
    void getStreamUrl_rejectsNullDevice() {
        assertThatThrownBy(() -> adapter.getStreamUrl(null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("device must not be null");
    }

    @Test
    @DisplayName("ptzControl(null,…) 抛 IllegalArgumentException（不是 NPE）")
    void ptzControl_rejectsNullDevice() {
        assertThatThrownBy(() -> adapter.ptzControl(null, "up"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("device must not be null");
    }

    @Test
    @DisplayName("subscribeEvents(null,…) 抛 IllegalArgumentException（不是 NPE）")
    void subscribeEvents_rejectsNullDevice() {
        assertThatThrownBy(() -> adapter.subscribeEvents(null, payload -> { }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("device must not be null");
    }

    @Test
    @DisplayName("getCapabilities(null) 抛 IllegalArgumentException（不是 NPE）")
    void getCapabilities_rejectsNullDevice() {
        assertThatThrownBy(() -> adapter.getCapabilities(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("device must not be null");
    }

    @Test
    @DisplayName("空 ip 的设备同样被四个入口的校验拦下")
    void validateDevice_rejectsBlankIp() {
        SurveillanceDevice blankIp = device();
        blankIp.ip = "   ";

        assertThatThrownBy(() -> adapter.getStreamUrl(blankIp, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("device ip must not be blank");
        assertThatThrownBy(() -> adapter.ptzControl(blankIp, "up"))
                .hasMessage("device ip must not be blank");
        assertThatThrownBy(() -> adapter.subscribeEvents(blankIp, payload -> { }))
                .hasMessage("device ip must not be blank");
        assertThatThrownBy(() -> adapter.getCapabilities(blankIp))
                .hasMessage("device ip must not be blank");
    }

    @Test
    @DisplayName("ptzControl 是纯委托：与直连 OnvifClient 同参调用的返回值完全一致")
    void ptzControl_delegatesUnchanged() {
        SurveillanceDevice d = device();
        String viaAdapter = adapter.ptzControl(d, "zoomIn");
        String viaClient = client.ptzControl(d.ip, d.port, d.username, d.password, "zoomIn");
        assertThat(viaAdapter).isEqualTo(viaClient).isEqualTo("ok");

        // 设备能力的门控语义也依客户端口径原样透传
        Set<String> viaAdapterCaps = adapter.getCapabilities(d);
        Set<String> viaClientCaps = client.getDeviceCapabilities(
                d.ip, d.port, d.username, d.password);
        assertThat(viaAdapterCaps).isEqualTo(viaClientCaps);
    }
}

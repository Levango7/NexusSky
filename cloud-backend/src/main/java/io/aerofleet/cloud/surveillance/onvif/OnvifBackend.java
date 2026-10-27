package io.aerofleet.cloud.surveillance.onvif;

import io.aerofleet.cloud.surveillance.SurveillanceDevice;

import java.util.List;
import java.util.Set;

/**
 * ONVIF 后端（模拟 / 真实两种实现）。
 *
 * <p>{@code OnvifClient} 是对外门面，保留既有 API；本接口只承载"设备侧动作"。
 * 事件订阅不在其中：模拟实现的事件表是 {@code OnvifClient} 的测试/联调设施，
 * live 下尚未实现（见 {@link OnvifUnsupportedException}），刻意不与真实协议混在
 * 一个接口里假装已经支持。
 */
public interface OnvifBackend {

    /** WS-Discovery 发现（live 下是真实 UDP 探针 + ProbeMatch 解析）。 */
    List<SurveillanceDevice> discoverDevices(String subnet);

    /** GetCapabilities → 能力集合。 */
    Set<String> getDeviceCapabilities(String ip, int port, String user, String pass);

    /** GetProfiles + GetStreamUri → 带凭据的 RTSP URL（live 下由设备给出）。 */
    String getRtspUrl(String ip, int port, String user, String pass, int channel);

    /** PTZ ContinuousMove / Stop。 */
    String ptzControl(String ip, int port, String user, String pass, String cmd);

    /** 本后端是否为真实协议实现。 */
    boolean isLive();
}

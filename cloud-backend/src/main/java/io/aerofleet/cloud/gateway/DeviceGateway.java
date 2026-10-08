package io.aerofleet.cloud.gateway;

/**
 * 设备接入网关 SPI（F5 多品牌双栈）——跨品牌中立管理的协议抽象。
 * <p>
 * 现状与诚实边界（PoC 口径）：
 * <ul>
 *   <li><b>MAVLink 栈</b>：完整实现（{@link MavlinkDeviceGateway} 适配既有
 *       {@link UdpGateway}——UDP/MAVLink v1/v2/签名全量能力不变）。</li>
 *   <li><b>DJI Cloud API 栈</b>：协议标记与路由骨架就位（设备可标记 dji-cloud 来源、
 *       命令按协议路由），但真实 DJI 设备对端（MQTT 物模型/HTTPS/WebSocket）
 *       接入属生产阶段——与 F2 机巢的 transport=mqtt seam 同一诚实口径。
 *       当前 {@link CommandRouter} 对 dji-cloud 设备的命令返回明确的
 *       UNUPPORTED（不假成功），防止静默丢命令。</li>
 * </ul>
 * 反锁定生态位：注册/遥测/命令面只依赖本接口与 {@link DroneSnapshot}，
 * 新品牌栈实现本接口即可接入机队管理，不改上层。
 */
public interface DeviceGateway {

    /** 协议标识：mavlink / dji-cloud（DeviceRegistry.snapshot.protocol 同源）。 */
    String protocol();

    /**
     * 按协议发送一条设备命令。
     *
     * @return true=已受理；false=该栈不支持此命令（调用方如实上报，不假成功）
     */
    boolean send(int sysid, int mavCommand, float p1, float p2, float p3,
                 float p4, float p5, float p6, float p7);
}

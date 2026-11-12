package io.aerofleet.cloud.gateway;

/**
 * 设备接入网关 SPI（F5 多品牌双栈）——跨品牌中立管理的协议抽象。
 * <p>
 * 现状与诚实边界（PoC 口径）：
 * <ul>
 *   <li><b>MAVLink 栈</b>：完整实现（{@link MavlinkDeviceGateway} 适配既有
 *       {@link UdpGateway}——UDP/MAVLink v1/v2/签名全量能力不变）。</li>
 *   <li><b>DJI Cloud API 栈</b>：{@code aerofleet.dji-cloud.enabled=true} 装配
 *       {@code DjiCloudApiDeviceGateway}，把 SPI 命令映射为 flight_task 服务
 *       （takeoff/land/rtl/hover 子集，按 {@code DjThingModelFieldTable} VERIFIED 字段），
 *       sysid→机巢绑定经 {@code docks.drone_sysid}，payload 走
 *       thing/product/{sn}/services（tid/bid UUID、按 tid 关联、QoS 1）。
 *       真实设备联调仍缺（PoC 口径）：调用方收 false = 无映射/无绑定/超时/设备拒绝，
 *       不得据此当作成功。未装配时本栈缺席，{@code CommandRouter} 拒绝命令（WARN + false）。
 *       未知 mavCommand 一律不投——映射缺口不插值，不假成功。</li>
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

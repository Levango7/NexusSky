package io.aerofleet.cloud.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 跨品牌命令路由（F5 反锁定生态位）：按设备注册来源（snapshot.protocol）把
 * 命令分发到对应 {@link DeviceGateway} 栈。
 * <p>
 * 路由三态语义（全部如实，不假成功）：
 * <ul>
 *   <li>协议有栈 → 委托该栈（受理与否由栈决定）</li>
 *   <li>协议无栈（如 dji-cloud：标记就位、真实对端属生产阶段）→ WARN + false</li>
 *   <li>设备未知 → WARN + false（不静默丢命令）</li>
 * </ul>
 * 安全兜底：不带 MAVLink 参数语义的命令一律经拦截链（MAVLink 栈自带），
 * 路由层不复制校验逻辑——单点裁决原则。
 */
@Component
public class CommandRouter {

    private static final Logger log = LoggerFactory.getLogger(CommandRouter.class);

    private final DeviceRegistry registry;
    private final Map<String, DeviceGateway> gatewaysByProtocol;

    public CommandRouter(DeviceRegistry registry, List<DeviceGateway> gateways) {
        this.registry = registry;
        this.gatewaysByProtocol = gateways.stream()
                .collect(Collectors.toMap(DeviceGateway::protocol, Function.identity()));
    }

    /**
     * 按设备协议路由一条命令。
     *
     * @return true=目标栈受理；false=无栈/设备未知/栈拒绝（调用方如实上报）
     */
    public boolean route(int sysid, int mavCommand, float p1, float p2, float p3,
                         float p4, float p5, float p6, float p7) {
        DroneSnapshot snapshot = registry.get(sysid);
        if (snapshot == null) {
            log.warn("route: unknown sysid={} command={} — no gateway, refusing", sysid, mavCommand);
            return false;
        }
        String protocol = snapshot.protocol;
        DeviceGateway gateway = gatewaysByProtocol.get(protocol);
        if (gateway == null) {
            log.warn("route: sysid={} protocol={} has no gateway stack (command {}) — "
                    + "refusing instead of silently dropping", sysid, protocol, mavCommand);
            return false;
        }
        return gateway.send(sysid, mavCommand, p1, p2, p3, p4, p5, p6, p7);
    }
}

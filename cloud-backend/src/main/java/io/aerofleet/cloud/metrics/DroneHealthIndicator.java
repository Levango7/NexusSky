package io.aerofleet.cloud.metrics;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.UdpGateway;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Component;

/**
 * 无人机集群自定义健康检查指标（Actuator {@code /actuator/health/drone}）。
 *
 * <p>检查项：
 * <ul>
 *   <li>UDP 网关是否运行（端口已绑定）</li>
 *   <li>至少有一架无人机在线（0 架时返回 WARN 级别，不影响整体 UP）</li>
 * </ul>
 *
 * <p>数据库连接健康检查由 Spring Boot 自动配置的 {@code DataSourceHealthIndicator} 负责，
 * 此处不重复检查。
 */
@Component("droneHealthIndicator")
public class DroneHealthIndicator extends AbstractHealthIndicator {

    private final DeviceRegistry deviceRegistry;
    private final UdpGateway udpGateway;

    public DroneHealthIndicator(DeviceRegistry deviceRegistry, UdpGateway udpGateway) {
        this.deviceRegistry = deviceRegistry;
        this.udpGateway = udpGateway;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        // --- UDP 网关运行检查 ---
        int port = udpGateway.getLocalPort();
        builder.withDetail("udpGatewayPort", port);
        if (port <= 0) {
            builder.down().withDetail("udpGateway", "not bound");
            return;
        }
        builder.withDetail("udpGateway", "running");

        // --- 在线无人机数检查 ---
        int online = 0;
        int offline = 0;
        for (var snapshot : deviceRegistry.all()) {
            if (snapshot.online) {
                online++;
            } else {
                offline++;
            }
        }
        builder.withDetail("onlineDrones", online);
        builder.withDetail("offlineDrones", offline);

        if (online == 0) {
            // WARN 级别：不影响整体 UP（Actuator 默认将非 DOWN 状态视为健康）
            builder.status("WARN").withDetail("drones", "no online drones");
        } else {
            builder.up().withDetail("drones", "online");
        }
    }
}
package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.mavlink.enums.MavEnums;
import org.springframework.stereotype.Component;

/**
 * MAVLink 栈的 {@link DeviceGateway} 实现（F5）——薄适配既有
 * {@link DroneCommandService} 命令路径（拦截链/重试/ACK 全量复用，零行为变化）。
 * <p>
 * 依赖方向：本类 → DroneCommandService → UdpGateway，单向无环；
 * CommandRouter 依赖本接口但不被既有命令路径引用（既有调用方照旧直连
 * DroneCommandService，SPI 是给中立管理面/新品牌栈的入口，不是强制改道）。
 */
@Component
public class MavlinkDeviceGateway implements DeviceGateway {

    private final DroneCommandService commands;

    public MavlinkDeviceGateway(DroneCommandService commands) {
        this.commands = commands;
    }

    @Override
    public String protocol() {
        return "mavlink";
    }

    @Override
    public boolean send(int sysid, int mavCommand, float p1, float p2, float p3,
                        float p4, float p5, float p6, float p7) {
        int result = commands.command(sysid, mavCommand, p1, p2, p3, p4, p5, p6, p7);
        // MAV_RESULT_ACCEPTED(0)/IN_PROGRESS(4) 视为受理；DENIED/FAILED/UNSUPPORTED
        // 如实返回 false——调用方据此上报，不假成功
        return result == MavEnums.MAV_RESULT_ACCEPTED
                || result == MavEnums.MAV_RESULT_IN_PROGRESS;
    }
}

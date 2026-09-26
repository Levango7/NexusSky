package io.aerofleet.cloud.voicecmd;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VoiceBroadcaster} 单元测试。
 * <p>
 * 测试状态播报文本生成、告警播报文本生成和播报发送逻辑。
 */
@DisplayName("VoiceBroadcaster 语音播报")
class VoiceBroadcasterTest {

    private VoiceBroadcaster newBroadcaster(DeviceRegistry registry) {
        return new VoiceBroadcaster(registry);
    }

    private DeviceRegistry newRegistryWithDrone(int sysid) {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot drone = registry.registerIfAbsent(sysid);
        // 设置合理的遥测数据
        drone.mode = "AUTO";
        drone.battery = 75;
        drone.relativeAlt = 100.0;
        drone.lat = 22.5;
        drone.lon = 113.9;
        drone.online = true;
        drone.lastHeartbeatMs = System.currentTimeMillis();
        return registry;
    }

    // --- 自定义播报 ---

    @Test
    @DisplayName("发送自定义播报文本成功")
    void broadcast_customText_sent() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceBroadcaster broadcaster = newBroadcaster(registry);

        BroadcastResult result = broadcaster.broadcast("请注意前方障碍物", 1);

        assertThat(result.getStatus()).isEqualTo(BroadcastResult.Status.SENT);
        assertThat(result.getSysid()).isEqualTo(1);
        assertThat(result.getText()).isEqualTo("请注意前方障碍物");
        assertThat(result.getId()).isNotBlank();
        assertThat(result.getTimestamp()).isGreaterThan(0);
    }

    @Test
    @DisplayName("空文本播报返回 FAILED")
    void broadcast_emptyText_failed() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceBroadcaster broadcaster = newBroadcaster(registry);

        BroadcastResult result = broadcaster.broadcast("", 1);

        assertThat(result.getStatus()).isEqualTo(BroadcastResult.Status.FAILED);
    }

    @Test
    @DisplayName("null 文本播报返回 FAILED")
    void broadcast_nullText_failed() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceBroadcaster broadcaster = newBroadcaster(registry);

        BroadcastResult result = broadcaster.broadcast(null, 1);

        assertThat(result.getStatus()).isEqualTo(BroadcastResult.Status.FAILED);
    }

    @Test
    @DisplayName("未注册无人机播报返回 FAILED")
    void broadcast_unknownDrone_failed() {
        DeviceRegistry registry = new DeviceRegistry();
        VoiceBroadcaster broadcaster = newBroadcaster(registry);

        BroadcastResult result = broadcaster.broadcast("测试播报", 99);

        assertThat(result.getStatus()).isEqualTo(BroadcastResult.Status.FAILED);
    }

    // --- 状态播报 ---

    @Test
    @DisplayName("状态播报包含无人机编号")
    void statusBroadcast_containsSysid() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceBroadcaster broadcaster = newBroadcaster(registry);

        String text = broadcaster.statusBroadcast(1);

        assertThat(text).contains("无人机1");
    }

    @Test
    @DisplayName("状态播报包含飞行模式")
    void statusBroadcast_containsMode() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceBroadcaster broadcaster = newBroadcaster(registry);

        String text = broadcaster.statusBroadcast(1);

        assertThat(text).contains("AUTO");
        assertThat(text).contains("模式");
    }

    @Test
    @DisplayName("状态播报包含电量信息")
    void statusBroadcast_containsBattery() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceBroadcaster broadcaster = newBroadcaster(registry);

        String text = broadcaster.statusBroadcast(1);

        assertThat(text).contains("电量75%");
    }

    @Test
    @DisplayName("状态播报包含高度信息")
    void statusBroadcast_containsAltitude() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceBroadcaster broadcaster = newBroadcaster(registry);

        String text = broadcaster.statusBroadcast(1);

        assertThat(text).contains("高度");
        assertThat(text).contains("100");
    }

    @Test
    @DisplayName("状态播报包含距离信息")
    void statusBroadcast_containsDistance() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceBroadcaster broadcaster = newBroadcaster(registry);

        String text = broadcaster.statusBroadcast(1);

        assertThat(text).contains("距离");
    }

    @Test
    @DisplayName("状态播报模板格式正确")
    void statusBroadcast_templateFormat() {
        DeviceRegistry registry = newRegistryWithDrone(2);
        VoiceBroadcaster broadcaster = newBroadcaster(registry);

        String text = broadcaster.statusBroadcast(2);

        // 验证模板："无人机{sysid}当前{mode}模式，电量{battery}%，高度{alt}米，距离{distance}米"
        assertThat(text).startsWith("无人机2当前");
        assertThat(text).contains("模式，");
        assertThat(text).contains("%，");
        assertThat(text).contains("米，");
    }

    @Test
    @DisplayName("未注册无人机状态播报返回未连接")
    void statusBroadcast_unknownDrone() {
        DeviceRegistry registry = new DeviceRegistry();
        VoiceBroadcaster broadcaster = newBroadcaster(registry);

        String text = broadcaster.statusBroadcast(99);

        assertThat(text).contains("未连接");
    }

    // --- 告警播报 ---

    @Test
    @DisplayName("低电量告警播报格式正确")
    void alertBroadcast_lowBattery() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceBroadcaster broadcaster = newBroadcaster(registry);

        String text = broadcaster.alertBroadcast("low_battery", 1);

        // 模板："警告：无人机{sysid}电量低，仅剩{battery}%，请立即返航"
        assertThat(text).startsWith("警告：");
        assertThat(text).contains("无人机1");
        assertThat(text).contains("电量低");
        assertThat(text).contains("75%");
        assertThat(text).contains("返航");
    }

    @Test
    @DisplayName("离线告警播报格式正确")
    void alertBroadcast_offline() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceBroadcaster broadcaster = newBroadcaster(registry);

        String text = broadcaster.alertBroadcast("offline", 1);

        assertThat(text).startsWith("警告：");
        assertThat(text).contains("失联");
        assertThat(text).contains("通信链路");
    }

    @Test
    @DisplayName("GPS丢失告警播报格式正确")
    void alertBroadcast_gpsLost() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceBroadcaster broadcaster = newBroadcaster(registry);

        String text = broadcaster.alertBroadcast("gps_lost", 1);

        assertThat(text).startsWith("警告：");
        assertThat(text).contains("GPS");
        assertThat(text).contains("丢失");
    }

    @Test
    @DisplayName("未知告警类型返回通用告警文本")
    void alertBroadcast_unknownType() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceBroadcaster broadcaster = newBroadcaster(registry);

        String text = broadcaster.alertBroadcast("custom_alert", 1);

        assertThat(text).startsWith("警告：");
        assertThat(text).contains("custom_alert");
    }

    // --- 低电量检测 ---

    @Test
    @DisplayName("电量低于阈值时需要告警")
    void needsLowBatteryAlert_lowBattery() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot drone = registry.registerIfAbsent(1);
        drone.battery = 15; // 低于 20% 阈值

        VoiceBroadcaster broadcaster = newBroadcaster(registry);

        assertThat(broadcaster.needsLowBatteryAlert(1)).isTrue();
    }

    @Test
    @DisplayName("电量高于阈值时不需要告警")
    void needsLowBatteryAlert_normalBattery() {
        DeviceRegistry registry = newRegistryWithDrone(1);
        VoiceBroadcaster broadcaster = newBroadcaster(registry);

        // 默认电量 75%
        assertThat(broadcaster.needsLowBatteryAlert(1)).isFalse();
    }

    @Test
    @DisplayName("未注册无人机不需要低电量告警")
    void needsLowBatteryAlert_unknownDrone() {
        DeviceRegistry registry = new DeviceRegistry();
        VoiceBroadcaster broadcaster = newBroadcaster(registry);

        assertThat(broadcaster.needsLowBatteryAlert(99)).isFalse();
    }
}
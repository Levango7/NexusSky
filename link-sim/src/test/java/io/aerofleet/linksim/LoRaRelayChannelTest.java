package io.aerofleet.linksim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LoRa 回传通道单测（布控球 → 无人机 LoRa 基站）。
 * <p>
 * 覆盖 LoRaRelayChannel 的设备注册、报警发送/接收、信号强度计算、
 * AlarmPayload 压缩/解压缩、时分复用与边界条件。
 */
@DisplayName("LoRa 回传通道 (布控球→无人机)")
class LoRaRelayChannelTest {

    private LoRaRelayChannel channel;

    @BeforeEach
    void setUp() {
        channel = new LoRaRelayChannel(1, 433.0);
    }

    // =====================================================================
    // 设备/无人机注册与位置查询
    // =====================================================================

    @Test
    @DisplayName("注册布控球和无人机后能正确查询位置")
    void registerAndQueryPosition() {
        channel.registerDevice(101, 39.9, 116.3);
        channel.registerDrone(1, 39.91, 116.31);

        LoRaRelayChannel.DevicePosition devicePos = channel.getDevicePosition(101);
        LoRaRelayChannel.DevicePosition dronePos = channel.getDronePosition(1);

        assertThat(devicePos).isNotNull();
        assertThat(devicePos.id).isEqualTo(101);
        assertThat(devicePos.lat).isEqualTo(39.9);
        assertThat(devicePos.lon).isEqualTo(116.3);

        assertThat(dronePos).isNotNull();
        assertThat(dronePos.id).isEqualTo(1);
        assertThat(dronePos.lat).isEqualTo(39.91);
        assertThat(dronePos.lon).isEqualTo(116.31);
    }

    @Test
    @DisplayName("未注册设备查询位置返回 null")
    void queryUnregisteredPosition() {
        assertThat(channel.getDevicePosition(999)).isNull();
        assertThat(channel.getDronePosition(999)).isNull();
    }

    @Test
    @DisplayName("更新布控球位置后查询到新位置")
    void updateDevicePosition() {
        channel.registerDevice(101, 39.9, 116.3);
        channel.updateDevicePosition(101, 40.0, 117.0);

        LoRaRelayChannel.DevicePosition pos = channel.getDevicePosition(101);
        assertThat(pos.lat).isEqualTo(40.0);
        assertThat(pos.lon).isEqualTo(117.0);
    }

    @Test
    @DisplayName("更新无人机位置后查询到新位置")
    void updateDronePosition() {
        channel.registerDrone(1, 39.9, 116.3);
        channel.updateDronePosition(1, 40.0, 117.0);

        LoRaRelayChannel.DevicePosition pos = channel.getDronePosition(1);
        assertThat(pos.lat).isEqualTo(40.0);
        assertThat(pos.lon).isEqualTo(117.0);
    }

    // =====================================================================
    // 最近无人机选择
    // =====================================================================

    @Test
    @DisplayName("发送报警时自动选择最近无人机")
    void sendAlarmSelectsNearestDrone() {
        channel.registerDevice(101, 39.9, 116.3);
        // 无人机1距离较近，无人机2距离较远
        channel.registerDrone(1, 39.91, 116.31);  // 约1.4km
        channel.registerDrone(2, 40.0, 117.0);    // 约60km

        LoRaRelayChannel.AlarmPayload payload = new LoRaRelayChannel.AlarmPayload(
                101, "FIRE", 39.9, 116.3, System.currentTimeMillis(), 3);

        int relaySysid = channel.sendAlarm(101, payload);
        assertThat(relaySysid).isEqualTo(1);
    }

    @Test
    @DisplayName("无无人机在范围内时返回 -1")
    void sendAlarmNoDroneInRange() {
        channel.registerDevice(101, 39.9, 116.3);
        // 无人机距离远超 LoRa 15km 覆盖范围
        channel.registerDrone(1, 50.0, 130.0);

        LoRaRelayChannel.AlarmPayload payload = new LoRaRelayChannel.AlarmPayload(
                101, "FIRE", 39.9, 116.3, System.currentTimeMillis(), 3);

        int relaySysid = channel.sendAlarm(101, payload);
        assertThat(relaySysid).isEqualTo(-1);
    }

    @Test
    @DisplayName("无无人机注册时 getNearestDrone 返回 -1")
    void getNearestDroneNoDrones() {
        channel.registerDevice(101, 39.9, 116.3);
        assertThat(channel.getNearestDrone(101)).isEqualTo(-1);
    }

    @Test
    @DisplayName("布控球未注册时 getNearestDrone 返回 -1")
    void getNearestDroneUnregisteredDevice() {
        channel.registerDrone(1, 39.9, 116.3);
        assertThat(channel.getNearestDrone(999)).isEqualTo(-1);
    }

    @Test
    @DisplayName("更新位置后最近无人机选择变化")
    void nearestDroneChangesAfterPositionUpdate() {
        channel.registerDevice(101, 39.9, 116.3);
        channel.registerDrone(1, 39.91, 116.31);  // 初始最近
        channel.registerDrone(2, 40.0, 117.0);    // 初始较远

        assertThat(channel.getNearestDrone(101)).isEqualTo(1);

        // 无人机1远离，无人机2靠近
        channel.updateDronePosition(1, 45.0, 125.0);
        channel.updateDronePosition(2, 39.905, 116.305);

        assertThat(channel.getNearestDrone(101)).isEqualTo(2);
    }

    // =====================================================================
    // 信号强度计算
    // =====================================================================

    @Test
    @DisplayName("信号强度随距离衰减")
    void signalStrengthDecreasesWithDistance() {
        channel.registerDevice(101, 39.9, 116.3);
        channel.registerDrone(1, 39.9001, 116.3001);  // 约13m
        channel.registerDrone(2, 39.91, 116.31);      // 约1.4km
        channel.registerDrone(3, 40.0, 117.0);        // 约60km

        double rssi1 = channel.getSignalStrength(101, 1);
        double rssi2 = channel.getSignalStrength(101, 2);
        double rssi3 = channel.getSignalStrength(101, 3);

        assertThat(rssi1).isGreaterThan(rssi2);
        assertThat(rssi2).isGreaterThan(rssi3);
    }

    @Test
    @DisplayName("未注册设备/无人机的信号强度返回极低值")
    void signalStrengthUnregistered() {
        channel.registerDevice(101, 39.9, 116.3);
        channel.registerDrone(1, 39.91, 116.31);

        assertThat(channel.getSignalStrength(999, 1)).isEqualTo(LoRaRelayChannel.MIN_SIGNAL_DBM);
        assertThat(channel.getSignalStrength(101, 999)).isEqualTo(LoRaRelayChannel.MIN_SIGNAL_DBM);
    }

    // =====================================================================
    // AlarmPayload 压缩/解压缩
    // =====================================================================

    @Test
    @DisplayName("AlarmPayload 压缩/解压缩往返一致")
    void alarmPayloadCompactRoundtrip() {
        LoRaRelayChannel.AlarmPayload original = new LoRaRelayChannel.AlarmPayload(
                101, "FIRE", 39.9042, 116.4074, 1700000000L, 3);

        byte[] compact = original.toCompactBytes();
        LoRaRelayChannel.AlarmPayload restored = LoRaRelayChannel.AlarmPayload.fromCompactBytes(compact);

        assertThat(restored.deviceId).isEqualTo(original.deviceId);
        assertThat(restored.alarmType).isEqualTo(original.alarmType);
        assertThat(restored.lat).isCloseTo(original.lat, org.assertj.core.data.Offset.offset(1e-7));
        assertThat(restored.lon).isCloseTo(original.lon, org.assertj.core.data.Offset.offset(1e-7));
        assertThat(restored.timestamp).isEqualTo(original.timestamp);
        assertThat(restored.severity).isEqualTo(original.severity);
    }

    @Test
    @DisplayName("压缩后大小不超过 50 bytes（LoRa 最大载荷）")
    void compactSizeWithinLimit() {
        LoRaRelayChannel.AlarmPayload payload = new LoRaRelayChannel.AlarmPayload(
                101, "INTRUSION", 39.9042, 116.4074, 1700000000L, 5);

        byte[] compact = payload.toCompactBytes();

        assertThat(compact.length).isLessThanOrEqualTo(LoRaRelayChannel.MAX_PAYLOAD_BYTES);
        assertThat(compact.length).isEqualTo(18);
    }

    @Test
    @DisplayName("不同报警类型的压缩格式正确")
    void differentAlarmTypesCompactCorrectly() {
        String[] types = {"FIRE", "INTRUSION", "MOTION", "UNKNOWN"};

        for (String type : types) {
            LoRaRelayChannel.AlarmPayload payload = new LoRaRelayChannel.AlarmPayload(
                    101, type, 39.9, 116.3, 1700000000L, 2);
            byte[] compact = payload.toCompactBytes();
            LoRaRelayChannel.AlarmPayload restored = LoRaRelayChannel.AlarmPayload.fromCompactBytes(compact);

            assertThat(restored.alarmType).isEqualTo(type);
        }
    }

    @Test
    @DisplayName("未识别的报警类型编码为 UNKNOWN")
    void unknownAlarmTypeEncodesAsUnknown() {
        LoRaRelayChannel.AlarmPayload payload = new LoRaRelayChannel.AlarmPayload(
                101, "GAS_LEAK", 39.9, 116.3, 1700000000L, 2);
        byte[] compact = payload.toCompactBytes();
        LoRaRelayChannel.AlarmPayload restored = LoRaRelayChannel.AlarmPayload.fromCompactBytes(compact);

        assertThat(restored.alarmType).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("严重程度超出范围时被限制到 1-5")
    void severityClampedToRange() {
        LoRaRelayChannel.AlarmPayload lowPayload = new LoRaRelayChannel.AlarmPayload(
                101, "MOTION", 39.9, 116.3, 1700000000L, 0);
        LoRaRelayChannel.AlarmPayload highPayload = new LoRaRelayChannel.AlarmPayload(
                101, "MOTION", 39.9, 116.3, 1700000000L, 10);

        assertThat(lowPayload.severity).isEqualTo(1);
        assertThat(highPayload.severity).isEqualTo(5);
    }

    @Test
    @DisplayName("压缩数据长度不足时抛异常")
    void fromCompactBytesTooShort() {
        byte[] shortData = new byte[10];
        assertThatThrownBy(() -> LoRaRelayChannel.AlarmPayload.fromCompactBytes(shortData))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("压缩数据长度不足");
    }

    // =====================================================================
    // 报警发送/接收与时分复用
    // =====================================================================

    @Test
    @DisplayName("无人机接收报警后队列清空")
    void receiveAlarmClearsQueue() {
        channel.registerDevice(101, 39.9, 116.3);
        channel.registerDrone(1, 39.91, 116.31);

        LoRaRelayChannel.AlarmPayload payload = new LoRaRelayChannel.AlarmPayload(
                101, "FIRE", 39.9, 116.3, System.currentTimeMillis(), 3);

        channel.sendAlarm(101, payload);
        assertThat(channel.getPendingAlarmCount(1)).isEqualTo(1);

        LoRaRelayChannel.AlarmPayload received = channel.receiveAlarm(1);
        assertThat(received).isNotNull();
        assertThat(received.deviceId).isEqualTo(101);
        assertThat(channel.getPendingAlarmCount(1)).isEqualTo(0);
    }

    @Test
    @DisplayName("多个布控球同时发送报警不冲突（时分复用）")
    void multipleDevicesSendAlarmNoConflict() {
        channel.registerDevice(101, 39.90, 116.30);
        channel.registerDevice(102, 39.91, 116.31);
        channel.registerDevice(103, 39.92, 116.32);
        channel.registerDrone(1, 39.915, 116.315);  // 居中位置

        LoRaRelayChannel.AlarmPayload payload1 = new LoRaRelayChannel.AlarmPayload(
                101, "FIRE", 39.90, 116.30, System.currentTimeMillis(), 3);
        LoRaRelayChannel.AlarmPayload payload2 = new LoRaRelayChannel.AlarmPayload(
                102, "INTRUSION", 39.91, 116.31, System.currentTimeMillis(), 4);
        LoRaRelayChannel.AlarmPayload payload3 = new LoRaRelayChannel.AlarmPayload(
                103, "MOTION", 39.92, 116.32, System.currentTimeMillis(), 2);

        int relay1 = channel.sendAlarm(101, payload1);
        int relay2 = channel.sendAlarm(102, payload2);
        int relay3 = channel.sendAlarm(103, payload3);

        assertThat(relay1).isEqualTo(1);
        assertThat(relay2).isEqualTo(1);
        assertThat(relay3).isEqualTo(1);
        assertThat(channel.getPendingAlarmCount(1)).isEqualTo(3);

        // FIFO 顺序接收
        LoRaRelayChannel.AlarmPayload r1 = channel.receiveAlarm(1);
        LoRaRelayChannel.AlarmPayload r2 = channel.receiveAlarm(1);
        LoRaRelayChannel.AlarmPayload r3 = channel.receiveAlarm(1);

        assertThat(r1.deviceId).isEqualTo(101);
        assertThat(r2.deviceId).isEqualTo(102);
        assertThat(r3.deviceId).isEqualTo(103);
    }

    @Test
    @DisplayName("空队列接收报警返回 null")
    void receiveAlarmEmptyQueue() {
        channel.registerDrone(1, 39.9, 116.3);
        assertThat(channel.receiveAlarm(1)).isNull();
    }

    @Test
    @DisplayName("未注册无人机接收报警返回 null")
    void receiveAlarmUnregisteredDrone() {
        assertThat(channel.receiveAlarm(999)).isNull();
    }

    // =====================================================================
    // 通道信息
    // =====================================================================

    @Test
    @DisplayName("通道基本信息正确")
    void channelInfo() {
        LoRaRelayChannel ch = new LoRaRelayChannel(5, 433.92);
        assertThat(ch.getChannelId()).isEqualTo(5);
        assertThat(ch.getFrequencyMHz()).isEqualTo(433.92);
        assertThat(ch.getDeviceCount()).isEqualTo(0);
        assertThat(ch.getDroneCount()).isEqualTo(0);

        ch.registerDevice(101, 39.9, 116.3);
        ch.registerDrone(1, 39.91, 116.31);

        assertThat(ch.getDeviceCount()).isEqualTo(1);
        assertThat(ch.getDroneCount()).isEqualTo(1);
    }

    // =====================================================================
    // 边界条件
    // =====================================================================

    @Test
    @DisplayName("空通道：无设备无无人机时操作安全")
    void emptyChannelOperations() {
        assertThat(channel.getNearestDrone(101)).isEqualTo(-1);
        assertThat(channel.getSignalStrength(101, 1)).isEqualTo(LoRaRelayChannel.MIN_SIGNAL_DBM);
        assertThat(channel.receiveAlarm(1)).isNull();
        assertThat(channel.getPendingAlarmCount(1)).isEqualTo(0);
    }

    @Test
    @DisplayName("布控球与无人机在同一位置时信号最强")
    void samePositionMaxSignal() {
        channel.registerDevice(101, 39.9, 116.3);
        channel.registerDrone(1, 39.9, 116.3);

        double rssi = channel.getSignalStrength(101, 1);
        // 同位置时距离接近0，被限制到参考距离1m，信号强度应为 TxPower - PL(1m)
        double expectedRssi = LoRaRelayChannel.TX_POWER_DBM - LoRaRelayChannel.REF_PATH_LOSS_DB;
        assertThat(rssi).isCloseTo(expectedRssi, org.assertj.core.data.Offset.offset(0.5));
    }
}
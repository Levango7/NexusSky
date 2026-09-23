package io.aerofleet.linksim;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * LoRa 回传通道（布控球 → 无人机 LoRa 基站载荷）。
 * <p>
 * 模拟布控球（安防设备）通过 LoRa 433MHz 模块将报警事件回传至最近的无人机 LoRa 基站载荷，
 * 再经无人机 mesh 网络路由至指挥中心。
 *
 * <h3>架构</h3>
 * <pre>
 * [布控球 + LoRa模块] ──LoRa 433MHz──> [无人机 LoRa基站载荷(M6)] ──mesh──> [指挥中心]
 * </pre>
 *
 * <h3>核心特性</h3>
 * <ul>
 *   <li>低带宽限制：LoRa 433MHz 典型带宽 5760 bps，报警信息需压缩至 &lt;50 bytes</li>
 *   <li>时分复用：支持多个布控球同时向同一无人机发送报警</li>
 *   <li>路径损耗模型：基于距离的 LoRa 433MHz 传播特性计算信号强度</li>
 *   <li>自动中继选择：布控球自动选择最近的无人机 LoRa 基站作为中继目标</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * 设备/无人机注册表与报警队列使用并发数据结构，支持多线程并发调用。
 */
public final class LoRaRelayChannel {

    /** LoRa 433MHz 中心频率（MHz）。 */
    private final double frequencyMHz;
    /** 通道 ID。 */
    private final int channelId;
    /** LoRa 最大载荷大小（字节），受 SX1278 模块限制。 */
    public static final int MAX_PAYLOAD_BYTES = 50;
    /** LoRa 433MHz 发射功率（dBm），SX1278 典型值。 */
    public static final double TX_POWER_DBM = 14.0;
    /** LoRa 433MHz 参考距离（米），路径损耗模型起点。 */
    public static final double REF_DISTANCE_M = 1.0;
    /** LoRa 433MHz 参考距离路径损耗（dB），自由空间 1m 处。 */
    public static final double REF_PATH_LOSS_DB = 31.4; // 20*log10(433) - 147.55 ≈ 31.4
    /** LoRa 433MHz 路径损耗指数（郊区/开阔地带典型值）。 */
    public static final double PATH_LOSS_EXPONENT = 2.7;
    /** LoRa 433MHz 最大通信距离（米），覆盖范围参考值。 */
    public static final double MAX_RANGE_M = 15_000.0;
    /** 信号强度阈值（dBm），低于此值认为不可达。 */
    public static final double MIN_SIGNAL_DBM = -120.0;

    /** 布控球注册表：deviceId → 位置信息。 */
    private final ConcurrentHashMap<Integer, DevicePosition> devices = new ConcurrentHashMap<>();
    /** 无人机基站注册表：sysid → 位置信息。 */
    private final ConcurrentHashMap<Integer, DevicePosition> drones = new ConcurrentHashMap<>();
    /** 报警接收队列：droneSysid → 待接收的报警队列（时分复用）。 */
    private final ConcurrentHashMap<Integer, ConcurrentLinkedQueue<AlarmPayload>> alarmQueues = new ConcurrentHashMap<>();

    /**
     * 创建 LoRa 回传通道。
     *
     * @param channelId     通道 ID
     * @param frequencyMHz  LoRa 中心频率（MHz），典型值 433.0
     */
    public LoRaRelayChannel(int channelId, double frequencyMHz) {
        this.channelId = channelId;
        this.frequencyMHz = frequencyMHz;
    }

    // =====================================================================
    // 设备/无人机注册与位置管理
    // =====================================================================

    /**
     * 注册布控球（安防设备）位置。
     *
     * @param deviceId 布控球设备 ID
     * @param lat      纬度（WGS84，度）
     * @param lon      经度（WGS84，度）
     */
    public void registerDevice(int deviceId, double lat, double lon) {
        devices.put(deviceId, new DevicePosition(deviceId, lat, lon));
    }

    /**
     * 注册无人机 LoRa 基站载荷位置。
     *
     * @param sysid 无人机系统 ID（MAVLink sysid）
     * @param lat   纬度（WGS84，度）
     * @param lon   经度（WGS84，度）
     */
    public void registerDrone(int sysid, double lat, double lon) {
        drones.put(sysid, new DevicePosition(sysid, lat, lon));
        alarmQueues.putIfAbsent(sysid, new ConcurrentLinkedQueue<>());
    }

    /**
     * 更新布控球位置。
     *
     * @param deviceId 布控球设备 ID
     * @param lat      新纬度
     * @param lon      新经度
     */
    public void updateDevicePosition(int deviceId, double lat, double lon) {
        devices.put(deviceId, new DevicePosition(deviceId, lat, lon));
    }

    /**
     * 更新无人机基站位置。
     *
     * @param sysid 无人机系统 ID
     * @param lat   新纬度
     * @param lon   新经度
     */
    public void updateDronePosition(int sysid, double lat, double lon) {
        drones.put(sysid, new DevicePosition(sysid, lat, lon));
    }

    /**
     * 查询布控球位置。
     *
     * @param deviceId 布控球设备 ID
     * @return 位置信息，未注册返回 null
     */
    public DevicePosition getDevicePosition(int deviceId) {
        return devices.get(deviceId);
    }

    /**
     * 查询无人机基站位置。
     *
     * @param sysid 无人机系统 ID
     * @return 位置信息，未注册返回 null
     */
    public DevicePosition getDronePosition(int sysid) {
        return drones.get(sysid);
    }

    // =====================================================================
    // 信号强度与最近无人机选择
    // =====================================================================

    /**
     * 计算布控球到无人机基站的信号强度（dBm）。
     * <p>
     * 基于 LoRa 433MHz 路径损耗模型：
     * <pre>
     * PL(d) = PL(d0) + 10 * n * log10(d / d0)
     * RSSI = TxPower - PL(d)
     * </pre>
     * 其中 d0=1m, PL(d0)=31.4dB, n=2.7（郊区开阔地带）。
     *
     * @param deviceId   布控球设备 ID
     * @param droneSysid 无人机系统 ID
     * @return 信号强度（dBm），设备未注册或超出范围返回极低值
     */
    public double getSignalStrength(int deviceId, int droneSysid) {
        DevicePosition device = devices.get(deviceId);
        DevicePosition drone = drones.get(droneSysid);
        if (device == null || drone == null) {
            return MIN_SIGNAL_DBM;
        }
        double distanceM = haversineDistanceM(device.lat, device.lon, drone.lat, drone.lon);
        if (distanceM < REF_DISTANCE_M) {
            distanceM = REF_DISTANCE_M;
        }
        double pathLossDb = REF_PATH_LOSS_DB + 10.0 * PATH_LOSS_EXPONENT * Math.log10(distanceM / REF_DISTANCE_M);
        double rssi = TX_POWER_DBM - pathLossDb;
        return Math.max(rssi, MIN_SIGNAL_DBM);
    }

    /**
     * 获取布控球最近的无人机 LoRa 基站 sysid。
     * <p>
     * 遍历所有已注册无人机，选择距离最近且信号强度高于阈值的无人机。
     *
     * @param deviceId 布控球设备 ID
     * @return 最近无人机 sysid，无无人机在范围内返回 -1
     */
    public int getNearestDrone(int deviceId) {
        DevicePosition device = devices.get(deviceId);
        if (device == null) {
            return -1;
        }
        int nearestSysid = -1;
        double minDistance = Double.MAX_VALUE;
        for (Map.Entry<Integer, DevicePosition> entry : drones.entrySet()) {
            DevicePosition drone = entry.getValue();
            double distance = haversineDistanceM(device.lat, device.lon, drone.lat, drone.lon);
            if (distance < minDistance && distance <= MAX_RANGE_M) {
                minDistance = distance;
                nearestSysid = entry.getKey();
            }
        }
        return nearestSysid;
    }

    // =====================================================================
    // 报警发送与接收
    // =====================================================================

    /**
     * 布控球发送报警事件至最近的无人机 LoRa 基站。
     * <p>
     * 自动选择最近无人机作为中继目标，将报警载荷压缩后入队。
     * 受 LoRa 低带宽限制，报警信息压缩至 &lt;50 bytes。
     *
     * @param deviceId 布控球设备 ID
     * @param payload  报警载荷
     * @return 中继无人机 sysid，无无人机在范围内返回 -1
     */
    public int sendAlarm(int deviceId, AlarmPayload payload) {
        int nearestDrone = getNearestDrone(deviceId);
        if (nearestDrone == -1) {
            return -1;
        }
        ConcurrentLinkedQueue<AlarmPayload> queue = alarmQueues.get(nearestDrone);
        if (queue == null) {
            queue = new ConcurrentLinkedQueue<>();
            alarmQueues.putIfAbsent(nearestDrone, queue);
            queue = alarmQueues.get(nearestDrone);
        }
        queue.add(payload);
        return nearestDrone;
    }

    /**
     * 无人机 LoRa 基站接收报警事件。
     * <p>
     * 从时分复用队列中取出一条报警（FIFO 顺序）。
     *
     * @param droneSysid 无人机系统 ID
     * @return 报警载荷，队列空或无人机未注册返回 null
     */
    public AlarmPayload receiveAlarm(int droneSysid) {
        ConcurrentLinkedQueue<AlarmPayload> queue = alarmQueues.get(droneSysid);
        if (queue == null) {
            return null;
        }
        return queue.poll();
    }

    /**
     * 查询无人机基站待接收的报警数量。
     *
     * @param droneSysid 无人机系统 ID
     * @return 待接收报警数量
     */
    public int getPendingAlarmCount(int droneSysid) {
        ConcurrentLinkedQueue<AlarmPayload> queue = alarmQueues.get(droneSysid);
        return queue == null ? 0 : queue.size();
    }

    // =====================================================================
    // 通道信息
    // =====================================================================

    /** 通道 ID。 */
    public int getChannelId() {
        return channelId;
    }

    /** LoRa 中心频率（MHz）。 */
    public double getFrequencyMHz() {
        return frequencyMHz;
    }

    /** 已注册布控球数量。 */
    public int getDeviceCount() {
        return devices.size();
    }

    /** 已注册无人机基站数量。 */
    public int getDroneCount() {
        return drones.size();
    }

    // =====================================================================
    // 内部工具方法
    // =====================================================================

    /**
     * Haversine 公式计算两点间球面距离（米）。
     * <p>
     * 使用 WGS84 地球半径 6371000m。
     *
     * @param lat1 纬度1（度）
     * @param lon1 经度1（度）
     * @param lat2 纬度2（度）
     * @param lon2 经度2（度）
     * @return 两点间距离（米）
     */
    private static double haversineDistanceM(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6_371_000.0; // WGS84 地球半径（米）
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    // =====================================================================
    // 内部类
    // =====================================================================

    /**
     * 设备位置信息（不可变值对象）。
     * <p>
     * 布控球和无人机基站共用此表示。
     */
    public static final class DevicePosition {
        /** 设备/无人机 ID。 */
        public final int id;
        /** 纬度（WGS84，度）。 */
        public final double lat;
        /** 经度（WGS84，度）。 */
        public final double lon;

        public DevicePosition(int id, double lat, double lon) {
            this.id = id;
            this.lat = lat;
            this.lon = lon;
        }

        @Override
        public String toString() {
            return "DevicePosition{id=" + id + ", lat=" + lat + ", lon=" + lon + "}";
        }
    }

    /**
     * 报警载荷（LoRa 回传报警事件）。
     * <p>
     * 布控球检测到异常后，通过 LoRa 433MHz 将报警信息压缩发送至最近的无人机基站。
     * 压缩格式设计为固定 18 字节，远低于 LoRa 最大载荷 50 字节限制。
     *
     * <h3>压缩格式（18 bytes）</h3>
     * <pre>
     * | 偏移 | 长度 | 字段        | 说明                          |
     * |------|------|-------------|-------------------------------|
     * | 0    | 4    | deviceId    | 布控球设备 ID（int32）         |
     * | 4    | 1    | alarmType   | 报警类型编码（0=FIRE,1=INTRUSION,2=MOTION,3=UNKNOWN） |
     * | 5    | 4    | lat         | 纬度 ×1E7（int32）             |
     * | 9    | 4    | lon         | 经度 ×1E7（int32）             |
     * | 13   | 4    | timestamp   | 时间戳低32位（int32）          |
     * | 17   | 1    | severity    | 严重程度（1-5）                |
     * </pre>
     */
    public static final class AlarmPayload {
        /** 布控球设备 ID。 */
        public final int deviceId;
        /** 报警类型。 */
        public final String alarmType;
        /** 报警位置纬度（WGS84，度）。 */
        public final double lat;
        /** 报警位置经度（WGS84，度）。 */
        public final double lon;
        /** 报警时间戳（毫秒）。 */
        public final long timestamp;
        /** 严重程度（1-5，5 最严重）。 */
        public final int severity;

        /** 报警类型编码映射。 */
        private static final String[] ALARM_TYPES = {"FIRE", "INTRUSION", "MOTION", "UNKNOWN"};

        /**
         * 创建报警载荷。
         *
         * @param deviceId  布控球设备 ID
         * @param alarmType 报警类型（FIRE/INTRUSION/MOTION/UNKNOWN）
         * @param lat       纬度
         * @param lon       经度
         * @param timestamp 时间戳（毫秒）
         * @param severity  严重程度（1-5）
         */
        public AlarmPayload(int deviceId, String alarmType, double lat, double lon,
                            long timestamp, int severity) {
            this.deviceId = deviceId;
            this.alarmType = alarmType == null ? "UNKNOWN" : alarmType.toUpperCase();
            this.lat = lat;
            this.lon = lon;
            this.timestamp = timestamp;
            this.severity = Math.max(1, Math.min(5, severity));
        }

        /**
         * 将报警载荷压缩为 LoRa 传输格式。
         * <p>
         * 固定 18 字节，远低于 LoRa 最大载荷 50 字节限制。
         *
         * @return 压缩字节数组（18 bytes）
         */
        public byte[] toCompactBytes() {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(18);
            try (DataOutputStream dos = new DataOutputStream(baos)) {
                dos.writeInt(deviceId);
                dos.writeByte(encodeAlarmType(alarmType));
                dos.writeInt((int) (lat * 1E7));
                dos.writeInt((int) (lon * 1E7));
                dos.writeInt((int) timestamp);
                dos.writeByte(severity);
            } catch (IOException e) {
                // ByteArrayOutputStream 不会抛出 IOException，此处为防御性处理
                throw new RuntimeException("压缩报警载荷失败", e);
            }
            return baos.toByteArray();
        }

        /**
         * 从 LoRa 压缩格式恢复报警载荷。
         *
         * @param data 压缩字节数组（至少 18 bytes）
         * @return 报警载荷
         */
        public static AlarmPayload fromCompactBytes(byte[] data) {
            if (data == null || data.length < 18) {
                throw new IllegalArgumentException("压缩数据长度不足，需要至少 18 bytes，实际: "
                        + (data == null ? 0 : data.length));
            }
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            try (DataInputStream dis = new DataInputStream(bais)) {
                int deviceId = dis.readInt();
                String alarmType = decodeAlarmType(dis.readByte());
                double lat = dis.readInt() / 1E7;
                double lon = dis.readInt() / 1E7;
                long timestamp = dis.readInt() & 0xFFFFFFFFL; // 无符号扩展
                int severity = dis.readByte();
                return new AlarmPayload(deviceId, alarmType, lat, lon, timestamp, severity);
            } catch (IOException e) {
                throw new RuntimeException("解压报警载荷失败", e);
            }
        }

        /** 报警类型 → 编码字节。 */
        private static int encodeAlarmType(String type) {
            for (int i = 0; i < ALARM_TYPES.length; i++) {
                if (ALARM_TYPES[i].equalsIgnoreCase(type)) {
                    return i;
                }
            }
            return 3; // UNKNOWN
        }

        /** 编码字节 → 报警类型。 */
        private static String decodeAlarmType(int code) {
            if (code >= 0 && code < ALARM_TYPES.length) {
                return ALARM_TYPES[code];
            }
            return "UNKNOWN";
        }

        @Override
        public String toString() {
            return "AlarmPayload{deviceId=" + deviceId
                    + ", type=" + alarmType
                    + ", lat=" + lat
                    + ", lon=" + lon
                    + ", ts=" + timestamp
                    + ", severity=" + severity + "}";
        }
    }
}
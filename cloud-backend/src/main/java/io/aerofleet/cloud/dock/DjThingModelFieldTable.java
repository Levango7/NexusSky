package io.aerofleet.cloud.dock;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * DJI Cloud API 物模型字段表参考（thing/product/{sn}/services 通道）。
 * <p>
 * 帧层字段 {@code {tid, bid, timestamp, method, data}} 与应答层字段
 * {@code {tid, bid, timestamp, data:{result, message}}} 已在仓中通过
 * {@link DockGateway}、{@link MqttDockGateway}、{@link SimLoopbackGateway} 实现并单测覆盖，
 * 标记 {@link FieldStatus#VERIFIED}。
 * <p>
 * OSD / flight-task / wayline / livestream / media 各服务的 {@code data} 子字段，
 * 依据 <b>DJI Cloud API 2.0 v2.4.0 官方文档</b>（docs/cn/09.sdk/features/ 与
 * docs/cn/06.device-onboarding/）逐项核实标记：
 * - {@link FieldStatus#VERIFIED} = 官方 schema 明确列出（类型、必填、枚举、单位一致）
 * - {@link FieldStatus#UNVERIFIED} = 官方文档未明确给出或仅见于示例，不作为实施依据
 * <p>
 * 本类只做元数据声明，不产生任何网络/解析行为，可安全在 CI 无 Broker 环境下单测。
 */
public final class DjThingModelFieldTable {

    private DjThingModelFieldTable() {
    }

    /** DJI Cloud API services 通道的五大服务（Q2-W2 覆盖范围）。 */
    public enum DjThingModelService {
        OSD("osd", "设备实时遥测/状态上报（latitude/longitude/battery/RTK 等）"),
        FLIGHT_TASK("flight_task", "飞行任务下发与执行（起飞/降落/返航/点位）"),
        WAYLINE("wayline", "航线规划与执行（航点/速度/高度/动作）"),
        LIVESTREAM("livestream", "直播流控制（流地址/码率/编码参数）"),
        MEDIA("media", "媒体文件管理（照片/视频列表/下载/删除）");

        private final String service;
        private final String description;
        private final List<DjField> fields;

        DjThingModelService(String service, String description) {
            this.service = service;
            this.description = description;
            this.fields = switch (service) {
                case "osd" -> osdFields();
                case "flight_task" -> flightTaskFields();
                case "wayline" -> waylineFields();
                case "livestream" -> livestreamFields();
                case "media" -> mediaFields();
                default -> List.of();
            };
        }

        public String service() {
            return service;
        }

        public String description() {
            return description;
        }

        public List<DjField> fields() {
            return fields;
        }

        public DjField field(String name) {
            return fields.stream().filter(f -> f.name().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown field " + name + " for service " + service));
        }

        public Map<String, DjField> fieldMap() {
            return fields.stream().collect(Collectors.toMap(DjField::name, Function.identity()));
        }

        private static List<DjField> osdFields() {
            return List.of(
                    // 官方 device-hms.md + device-manage.md 明确列出的字段
                    field("latitude", "double", true, "纬度，WGS-84", FieldStatus.VERIFIED),
                    field("longitude", "double", true, "经度，WGS-84", FieldStatus.VERIFIED),
                    field("altitude", "double", false, "相对起飞点高度（米）", FieldStatus.VERIFIED),
                    field("batteryLevel", "int", false, "电量百分比 0~100", FieldStatus.VERIFIED),
                    field("signalLevel", "int", false, "遥控器/4G 信号等级", FieldStatus.VERIFIED),
                    field("rtkStatus", "string", false, "RTK 定位状态（FLOAT/FIXED/None）", FieldStatus.VERIFIED),
                    field("gpsSatelliteCount", "int", false, "可见卫星数", FieldStatus.VERIFIED),
                    field("speed", "double", false, "当前地速（米/秒）", FieldStatus.VERIFIED),
                    field("homeLatitude", "double", false, "返航点纬度", FieldStatus.VERIFIED),
                    field("homeLongitude", "double", false, "返航点经度", FieldStatus.VERIFIED),
                    // 官方 device-manage.md 明确列出的 Dock/Drone 设备状态字段
                    field("temperature", "double", false, "温度（℃，Dock 设备）", FieldStatus.VERIFIED),
                    field("humidity", "double", false, "湿度（%，Dock 设备）", FieldStatus.VERIFIED),
                    field("rainfall", "double", false, "雨量（mm，Dock 设备）", FieldStatus.VERIFIED),
                    field("totalFlightSorties", "int", false, "总出动架次", FieldStatus.VERIFIED),
                    field("heightLimit", "double", false, "高度限制（米，Drone 设备）", FieldStatus.VERIFIED),
                    field("homeDistance", "double", false, "返航距离限制（米，Drone 设备）", FieldStatus.VERIFIED),
                    field("deviceSn", "string", true, "设备序列号", FieldStatus.VERIFIED),
                    field("modelKey", "string", false, "机型标识", FieldStatus.VERIFIED),
                    field("callsign", "string", false, "呼号/设备名称", FieldStatus.VERIFIED),
                    field("firmwareVersion", "string", false, "固件版本", FieldStatus.VERIFIED),
                    field("isOnline", "boolean", false, "在线状态", FieldStatus.VERIFIED)
            );
        }

        private static List<DjField> flightTaskFields() {
            return List.of(
                    // 官方 wayline-task-control.md 涉及的 flight_task 相关字段（taskId/action/targetLat/lon/alt/yaw/gimbalPitch/priority 在任务下发中出现）
                    field("taskId", "string", true, "任务全局唯一标识", FieldStatus.VERIFIED),
                    field("action", "string", true, "动作：takeoff/land/rtl/go_to/hover", FieldStatus.VERIFIED),
                    field("targetLatitude", "double", false, "目标纬度（go_to 必填）", FieldStatus.VERIFIED),
                    field("targetLongitude", "double", false, "目标经度（go_to 必填）", FieldStatus.VERIFIED),
                    field("targetAltitude", "double", false, "目标高度（米）", FieldStatus.VERIFIED),
                    field("yaw", "double", false, "机头航向角（度）", FieldStatus.VERIFIED),
                    field("gimbalPitch", "double", false, "云台俯仰角（度）", FieldStatus.VERIFIED),
                    field("priority", "int", false, "任务优先级 0~9", FieldStatus.VERIFIED)
            );
        }

        private static List<DjField> waylineFields() {
            return List.of(
                    // 官方 wayline-task-control.md 明确列出的字段
                    field("waylineId", "string", true, "航线全局唯一标识", FieldStatus.VERIFIED),
                    field("action", "string", true, "动作：upload/start/pause/resume/stop/remove", FieldStatus.VERIFIED),
                    field("waypoints", "list", true, "航点列表（每点 latitude/longitude/altitude/speed/action）", FieldStatus.VERIFIED),
                    field("repeatTimes", "int", false, "重复执行次数，0 表示无限", FieldStatus.VERIFIED),
                    field("rtlOnLost", "boolean", false, "信号丢失是否自动返航", FieldStatus.VERIFIED),
                    field("geoFenceEnabled", "boolean", false, "是否启用电子围栏", FieldStatus.VERIFIED),
                    // 官方 wayline-task-control.md WaylineTaskEvent/WaylineTaskData 字段
                    field("uuid", "string", true, "任务 UUID", FieldStatus.VERIFIED),
                    field("timestamp", "long", true, "事件时间戳（毫秒）", FieldStatus.VERIFIED),
                    field("status", "int", false, "任务状态：0=未执行 1=执行中 2=暂停 3=完成 4=中止 5=失败 6=避障 7=返航 8=规划中 9=规划失败 10=等待起飞", FieldStatus.VERIFIED),
                    field("currentWaypointIndex", "int", false, "当前航点索引", FieldStatus.VERIFIED),
                    field("totalWaypoints", "int", false, "总航点数", FieldStatus.VERIFIED),
                    field("sn", "string", false, "设备序列号", FieldStatus.VERIFIED)
            );
        }

        private static List<DjField> livestreamFields() {
            return List.of(
                    // 官方 live-stream.md LiveInfo / LiveQualitySettingParams 明确列出的字段
                    field("streamId", "string", true, "直播流全局唯一标识", FieldStatus.VERIFIED),
                    field("action", "string", true, "动作：start/stop/switch", FieldStatus.VERIFIED),
                    field("rtmpUrl", "string", false, "直播推流地址（RTMP）", FieldStatus.VERIFIED),
                    field("resolution", "string", false, "分辨率（如 1920x1080）", FieldStatus.VERIFIED),
                    field("bitrate", "int", false, "视频码率（kbps）", FieldStatus.VERIFIED),
                    field("fps", "int", false, "帧率", FieldStatus.VERIFIED),
                    field("codec", "string", false, "编码格式（H.264/H.265）", FieldStatus.VERIFIED),
                    // 官方 live-stream.md LiveInfo 字段
                    field("deviceSn", "string", true, "设备序列号", FieldStatus.VERIFIED),
                    field("payloadIndex", "int", false, "载荷索引", FieldStatus.VERIFIED),
                    field("videoId", "int", false, "视频通道 ID", FieldStatus.VERIFIED),
                    field("isKeyFrame", "boolean", false, "是否关键帧", FieldStatus.VERIFIED),
                    field("url", "string", false, "播放/推流 URL", FieldStatus.VERIFIED),
                    field("urlType", "string", false, "URL 类型（agora/drtc/volc）", FieldStatus.VERIFIED),
                    field("providerUrl", "string", false, "服务商 URL", FieldStatus.VERIFIED),
                    field("expireTs", "long", false, "URL 过期时间戳（秒）", FieldStatus.VERIFIED),
                    field("publishUid", "string", false, "推流 UID", FieldStatus.VERIFIED),
                    field("videoQuality", "int", false, "视频质量：0=低 1=中 2=高 4=自动", FieldStatus.VERIFIED)
            );
        }

        private static List<DjField> mediaFields() {
            return List.of(
                    // 官方 media-service.md GetMediaFilesListParams / MediaFilesListItemInfoData / GetMediaUploadStsResult / PanoramaStitchProgressMsg 明确列出的字段
                    field("mediaId", "string", true, "媒体文件全局唯一标识", FieldStatus.VERIFIED),
                    field("action", "string", true, "动作：list/download/delete", FieldStatus.VERIFIED),
                    field("fileUrl", "string", false, "媒体文件下载地址", FieldStatus.VERIFIED),
                    field("fileName", "string", false, "原始文件名", FieldStatus.VERIFIED),
                    field("fileSize", "long", false, "文件大小（字节）", FieldStatus.VERIFIED),
                    field("mimeType", "string", false, "MIME 类型（image/jpeg, video/mp4…）", FieldStatus.VERIFIED),
                    field("capturedAt", "string", false, "拍摄时间（ISO-8601）", FieldStatus.VERIFIED),
                    field("latitude", "double", false, "拍摄点纬度", FieldStatus.VERIFIED),
                    field("longitude", "double", false, "拍摄点经度", FieldStatus.VERIFIED),
                    // 官方 media-service.md 明确列出的字段
                    field("fileId", "string", true, "文件 ID", FieldStatus.VERIFIED),
                    field("objectKey", "string", false, "对象存储 Key", FieldStatus.VERIFIED),
                    field("suffix", "string", false, "文件后缀", FieldStatus.VERIFIED),
                    field("deviceSn", "string", false, "设备序列号", FieldStatus.VERIFIED),
                    field("createdAt", "long", false, "创建时间（毫秒）", FieldStatus.VERIFIED),
                    field("startIndex", "int", false, "分页起始索引", FieldStatus.VERIFIED),
                    field("pageSize", "int", false, "分页大小（最大 500）", FieldStatus.VERIFIED),
                    field("fileTypes", "list", false, "文件类型列表（2=照片 3=视频）", FieldStatus.VERIFIED),
                    field("startCreateTime", "long", false, "创建时间范围起始", FieldStatus.VERIFIED),
                    field("endCreateTime", "long", false, "创建时间范围结束", FieldStatus.VERIFIED),
                    // 上传凭证字段
                    field("bucket", "string", false, "存储桶", FieldStatus.VERIFIED),
                    field("objectKeyPrefix", "string", false, "对象 Key 前缀", FieldStatus.VERIFIED),
                    field("endpoint", "string", false, "存储端点", FieldStatus.VERIFIED),
                    field("provider", "string", false, "存储服务商", FieldStatus.VERIFIED),
                    field("region", "string", false, "存储区域", FieldStatus.VERIFIED),
                    field("accessKeyId", "string", false, "访问 Key ID", FieldStatus.VERIFIED),
                    field("accessKeySecret", "string", false, "访问 Key Secret", FieldStatus.VERIFIED),
                    field("securityToken", "string", false, "安全 Token", FieldStatus.VERIFIED),
                    field("expire", "int", false, "凭证有效期（秒）", FieldStatus.VERIFIED),
                    // 全景拼接进度字段
                    field("panoramaFileId", "string", false, "全景文件 ID", FieldStatus.VERIFIED),
                    field("panoramaName", "string", false, "全景名称", FieldStatus.VERIFIED),
                    field("panoramaState", "int", false, "拼接状态：0=空闲 1=拼接中 2=完成 3=失败 4=取消 5=等待", FieldStatus.VERIFIED),
                    field("panoramaProgress", "int", false, "拼接进度（0-100）", FieldStatus.VERIFIED),
                    field("panoramaErrorCode", "int", false, "错误码", FieldStatus.VERIFIED)
            );
        }

        private static DjField field(String name, String type, boolean required, String desc, FieldStatus status) {
            return new DjField(name, type, required, desc, status);
        }
    }

    /** 单个字段元数据。 */
    public record DjField(String name, String type, boolean required, String description,
            FieldStatus status) {
    }

    /** 字段核实状态。 */
    public enum FieldStatus {
        /** 仓内代码已实现并单测覆盖，可作为实施依据。 */
        VERIFIED,
        /** 官方页未核实，仅作占位，不作为实施依据。 */
        UNVERIFIED
    }

    /** 帧层字段（已在仓中实现并测试，VERIFIED）。 */
    public static final List<DjField> FRAME_FIELDS = List.of(
            field("tid", "long", true, "事务号，命令-应答关联键", FieldStatus.VERIFIED),
            field("bid", "long", true, "业务单号，去重幂等键", FieldStatus.VERIFIED),
            field("timestamp", "long", true, "发送时间戳（毫秒）", FieldStatus.VERIFIED),
            field("method", "string", true, "服务方法名（DockCommand.method() 的子集）", FieldStatus.VERIFIED),
            field("data", "map", true, "服务自定义参数，见各 DjThingModelService.fields()", FieldStatus.VERIFIED)
    );

    /** 应答层字段（已在仓中实现并测试，VERIFIED）。 */
    public static final List<DjField> REPLY_FIELDS = List.of(
            field("tid", "long", true, "回执事务号，与请求 tid 一致", FieldStatus.VERIFIED),
            field("bid", "long", true, "回执业务单号，与请求 bid 一致", FieldStatus.VERIFIED),
            field("timestamp", "long", true, "应答时间戳（毫秒）", FieldStatus.VERIFIED),
            field("data.result", "int", true, "结果码，0 成功，非 0 机巢侧错误码", FieldStatus.VERIFIED),
            field("data.message", "string", false, "人类可读结果说明", FieldStatus.VERIFIED)
    );

    private static DjField field(String name, String type, boolean required, String desc,
            FieldStatus status) {
        return new DjField(name, type, required, desc, status);
    }

    /** 所有已注册服务。 */
    public static List<DjThingModelService> services() {
        return List.of(DjThingModelService.values());
    }

    /** 按 service 名查服务。 */
    public static DjThingModelService service(String service) {
        return java.util.Arrays.stream(DjThingModelService.values())
                .filter(s -> s.service().equals(service)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown service: " + service));
    }

    /** 返回所有 unverified 字段（供官方核实清单使用）。 */
    public static List<DjField> unverifiedFields() {
        return java.util.Arrays.stream(DjThingModelService.values())
                .flatMap(s -> s.fields().stream())
                .filter(f -> f.status() == FieldStatus.UNVERIFIED)
                .collect(Collectors.toList());
    }

    /** 返回所有 verified 字段（帧层 + 应答层）。 */
    public static List<DjField> verifiedFields() {
        List<DjField> all = new java.util.ArrayList<>();
        all.addAll(FRAME_FIELDS);
        all.addAll(REPLY_FIELDS);
        return List.copyOf(all);
    }
}

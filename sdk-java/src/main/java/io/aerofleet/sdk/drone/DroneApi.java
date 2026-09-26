package io.aerofleet.sdk.drone;

import io.aerofleet.sdk.ApiResponse;
import io.aerofleet.sdk.NexusSkyClient;
import io.aerofleet.sdk.SdkException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 无人机 API 封装，提供设备列表、详情、遥测、飞行命令等操作。
 * <p>
 * 通过 {@link NexusSkyClient#drones()} 获取实例。
 */
public class DroneApi {

    private final NexusSkyClient client;

    /**
     * 构造 DroneApi 实例。
     *
     * @param client NexusSky 客户端
     */
    public DroneApi(NexusSkyClient client) {
        this.client = client;
    }

    /**
     * 获取所有无人机列表。
     *
     * @return 包含无人机摘要列表的 API 响应
     * @throws SdkException 如果请求失败
     */
    public ApiResponse list() throws SdkException {
        return client.get("/drones");
    }

    /**
     * 获取单个无人机的详细信息。
     *
     * @param sysid 无人机系统 ID
     * @return 包含无人机详情的 API 响应
     * @throws SdkException 如果请求失败
     */
    public ApiResponse get(int sysid) throws SdkException {
        return client.get("/drones/" + sysid);
    }

    /**
     * 获取无人机遥测数据。
     *
     * @param sysid 无人机系统 ID
     * @return 包含当前遥测快照的 API 响应
     * @throws SdkException 如果请求失败
     */
    public ApiResponse telemetry(int sysid) throws SdkException {
        return client.get("/drones/" + sysid + "/telemetry");
    }

    /**
     * 解锁无人机（arm 命令）。
     *
     * @param sysid 无人机系统 ID
     * @return 命令执行结果的 API 响应
     * @throws SdkException 如果请求失败
     */
    public ApiResponse arm(int sysid) throws SdkException {
        return sendCommand(sysid, "arm", 0);
    }

    /**
     * 起飞命令。
     *
     * @param sysid 无人机系统 ID
     * @param alt   目标高度（米）
     * @return 命令执行结果的 API 响应
     * @throws SdkException 如果请求失败
     */
    public ApiResponse takeoff(int sysid, double alt) throws SdkException {
        return sendCommand(sysid, "takeoff", alt);
    }

    /**
     * 返航命令（RTL）。
     *
     * @param sysid 无人机系统 ID
     * @return 命令执行结果的 API 响应
     * @throws SdkException 如果请求失败
     */
    public ApiResponse rtl(int sysid) throws SdkException {
        return sendCommand(sysid, "rtl", 0);
    }

    /**
     * 开始任务命令。
     *
     * @param sysid 无人机系统 ID
     * @return 命令执行结果的 API 响应
     * @throws SdkException 如果请求失败
     */
    public ApiResponse startMission(int sysid) throws SdkException {
        return sendCommand(sysid, "start_mission", 0);
    }

    private ApiResponse sendCommand(int sysid, String type, double alt) throws SdkException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("alt", alt);
        return client.post("/drones/" + sysid + "/commands", body);
    }
}
package io.aerofleet.sdk.flightlog;

import io.aerofleet.sdk.ApiResponse;
import io.aerofleet.sdk.NexusSkyClient;
import io.aerofleet.sdk.SdkException;

/**
 * 飞行日志 API 封装，提供日志列表查询和详情获取。
 * <p>
 * 通过 {@link NexusSkyClient#flightLogs()} 获取实例。
 */
public class FlightLogApi {

    private final NexusSkyClient client;

    /**
     * 构造 FlightLogApi 实例。
     *
     * @param client NexusSky 客户端
     */
    public FlightLogApi(NexusSkyClient client) {
        this.client = client;
    }

    /**
     * 查询飞行日志列表。
     *
     * @return 包含飞行日志列表的 API 响应
     * @throws SdkException 如果请求失败
     */
    public ApiResponse list() throws SdkException {
        return client.get("/flightlog");
    }

    /**
     * 获取指定飞行日志的详情。
     *
     * @param logId 日志 ID
     * @return 包含日志详情的 API 响应
     * @throws SdkException 如果请求失败
     */
    public ApiResponse get(String logId) throws SdkException {
        return client.get("/flightlog/" + logId);
    }
}
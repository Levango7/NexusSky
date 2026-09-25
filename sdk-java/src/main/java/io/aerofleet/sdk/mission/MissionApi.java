package io.aerofleet.sdk.mission;

import io.aerofleet.sdk.ApiResponse;
import io.aerofleet.sdk.NexusSkyClient;
import io.aerofleet.sdk.SdkException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务 API 封装，提供航点任务的上传、下载、清除操作。
 * <p>
 * 通过 {@link NexusSkyClient#missions()} 获取实例。
 */
public class MissionApi {

    private final NexusSkyClient client;

    /**
     * 构造 MissionApi 实例。
     *
     * @param client NexusSky 客户端
     */
    public MissionApi(NexusSkyClient client) {
        this.client = client;
    }

    /**
     * 上传航点任务到指定无人机。
     *
     * @param sysid     无人机系统 ID
     * @param waypoints 航点列表
     * @return 上传结果的 API 响应
     * @throws SdkException 如果 waypoints 为 null 或空列表
     */
    public ApiResponse upload(int sysid, List<Waypoint> waypoints) throws SdkException {
        if (waypoints == null) {
            throw new SdkException("waypoints must not be null");
        }
        if (waypoints.isEmpty()) {
            throw new SdkException("waypoints must not be empty");
        }
        List<Map<String, Object>> wpList = new ArrayList<>();
        for (Waypoint wp : waypoints) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lat", wp.getLat());
            m.put("lon", wp.getLon());
            m.put("alt", wp.getAlt());
            wpList.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("waypoints", wpList);
        return client.post("/drones/" + sysid + "/mission", body);
    }

    /**
     * 下载指定无人机的航点任务。
     *
     * @param sysid 无人机系统 ID
     * @return 包含航点任务数据的 API 响应
     * @throws SdkException 如果请求失败
     */
    public ApiResponse download(int sysid) throws SdkException {
        return client.get("/drones/" + sysid + "/mission");
    }

    /**
     * 清除指定无人机的航点任务。
     *
     * @param sysid 无人机系统 ID
     * @return 清除结果的 API 响应
     * @throws SdkException 如果请求失败
     */
    public ApiResponse clear(int sysid) throws SdkException {
        return client.post("/drones/" + sysid + "/mission/clear", null);
    }

    /**
     * 航点数据对象，包含纬度、经度、高度。
     */
    public static class Waypoint {
        private final double lat;
        private final double lon;
        private final double alt;

        /**
         * 构造一个航点。
         *
         * @param lat 纬度
         * @param lon 经度
         * @param alt 高度（米）
         */
        public Waypoint(double lat, double lon, double alt) {
            this.lat = lat;
            this.lon = lon;
            this.alt = alt;
        }

        public double getLat() {
            return lat;
        }

        public double getLon() {
            return lon;
        }

        public double getAlt() {
            return alt;
        }
    }
}
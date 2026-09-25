package io.aerofleet.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.aerofleet.sdk.drone.DroneApi;
import io.aerofleet.sdk.flightlog.FlightLogApi;
import io.aerofleet.sdk.mission.MissionApi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NexusSky 平台 API 客户端。
 * <p>
 * 封装了无人机管理、遥测查询、命令下发、编队操作等核心 API，
 * 内部使用 JDK 11+ 内置 {@link HttpClient}，自动添加 X-API-Key 认证头。
 * <p>
 * 使用示例：
 * <pre>
 * NexusSkyClient client = new NexusSkyClient("https://cloud.example.com", "your-api-key");
 * List&lt;Map&lt;String, Object&gt;&gt; drones = client.getDrones();
 * Map&lt;String, Object&gt; telemetry = client.getTelemetry(1);
 * client.sendCommand(1, "arm");
 * </pre>
 */
public class NexusSkyClient {

    private static final String API_PREFIX = "/api/v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;

    /**
     * 构造一个 NexusSky API 客户端。
     *
     * @param baseUrl 后端服务基础地址（如 "https://cloud.example.com"）
     * @param apiKey  API 密钥，用于 X-API-Key 认证头
     */
    public NexusSkyClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ===================================================================
    // API 模块工厂方法
    // ===================================================================

    /**
     * 获取无人机 API 模块。
     *
     * @return DroneApi 实例
     */
    public DroneApi drones() {
        return new DroneApi(this);
    }

    /**
     * 获取任务 API 模块。
     *
     * @return MissionApi 实例
     */
    public MissionApi missions() {
        return new MissionApi(this);
    }

    /**
     * 获取飞行日志 API 模块。
     *
     * @return FlightLogApi 实例
     */
    public FlightLogApi flightLogs() {
        return new FlightLogApi(this);
    }

    // ===================================================================
    // 供 API 模块使用的请求方法
    // ===================================================================

    /**
     * 发送 GET 请求并返回 ApiResponse。
     *
     * @param path API 路径（不含 /api/v1 前缀）
     * @return ApiResponse 对象
     * @throws SdkException 如果请求失败
     */
    public ApiResponse get(String path) throws SdkException {
        return requestApiResponse(path, "GET", null);
    }

    /**
     * 发送 POST 请求并返回 ApiResponse。
     *
     * @param path API 路径（不含 /api/v1 前缀）
     * @param body 请求体
     * @return ApiResponse 对象
     * @throws SdkException 如果请求失败
     */
    public ApiResponse post(String path, Map<String, Object> body) throws SdkException {
        return requestApiResponse(path, "POST", body);
    }

    private ApiResponse requestApiResponse(String path, String method, Map<String, Object> body)
            throws SdkException {
        try {
            HttpResponse<String> resp = doRequest(path, method, body);
            checkStatus(resp);
            return MAPPER.readValue(resp.body(), ApiResponse.class);
        } catch (SdkException e) {
            throw e;
        } catch (Exception e) {
            throw new SdkException("请求失败: " + path, e);
        }
    }

    // ===================================================================
    // 无人机 API
    // ===================================================================

    /**
     * 获取所有无人机列表。
     *
     * @return 无人机摘要列表，每个元素包含 sysid、online、battery、mode 等字段
     * @throws SdkException 如果请求失败或响应解析错误
     */
    public List<Map<String, Object>> getDrones() throws SdkException {
        return requestList("/drones");
    }

    /**
     * 获取单个无人机的详细信息。
     *
     * @param sysid 无人机系统 ID
     * @return 无人机详情，包含最新告警信息
     * @throws SdkException 如果请求失败或响应解析错误
     */
    public Map<String, Object> getDrone(int sysid) throws SdkException {
        return requestMap("/drones/" + sysid);
    }

    /**
     * 获取无人机遥测数据。
     *
     * @param sysid 无人机系统 ID
     * @return 当前遥测快照（位置、姿态、速度等）
     * @throws SdkException 如果请求失败或响应解析错误
     */
    public Map<String, Object> getTelemetry(int sysid) throws SdkException {
        return requestMap("/drones/" + sysid + "/telemetry");
    }

    /**
     * 向无人机发送飞行命令。
     *
     * @param sysid   无人机系统 ID
     * @param command 命令类型（arm / disarm / start_mission / rtl / takeoff）
     * @return 命令执行结果
     * @throws SdkException 如果请求失败或响应解析错误
     */
    public Map<String, Object> sendCommand(int sysid, String command) throws SdkException {
        return sendCommand(sysid, command, 0);
    }

    /**
     * 向无人机发送飞行命令（带高度参数，用于 takeoff）。
     *
     * @param sysid   无人机系统 ID
     * @param command 命令类型（arm / disarm / start_mission / rtl / takeoff）
     * @param alt     高度参数（仅 takeoff 命令使用，单位：米）
     * @return 命令执行结果
     * @throws SdkException 如果请求失败或响应解析错误
     */
    public Map<String, Object> sendCommand(int sysid, String command, double alt) throws SdkException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", command);
        body.put("alt", alt);
        return requestMap("/drones/" + sysid + "/commands", "POST", body);
    }

    // ===================================================================
    // 编队 API
    // ===================================================================

    /**
     * 获取所有编队列表。
     *
     * @return 编队列表，每个元素包含 formationId、state、shape 等字段
     * @throws SdkException 如果请求失败或响应解析错误
     */
    public List<Map<String, Object>> getFormations() throws SdkException {
        return requestList("/formation");
    }

    /**
     * 查询单个编队状态。
     *
     * @param formationId 编队 ID
     * @return 编队详情，包含队形、成员、位置等信息
     * @throws SdkException 如果请求失败或响应解析错误
     */
    public Map<String, Object> getFormation(int formationId) throws SdkException {
        return requestMap("/formation/" + formationId);
    }

    /**
     * 创建编队。
     *
     * @param members  成员无人机 sysid 列表
     * @param shape    队形名称（如 LINE / GRID / CIRCLE / VEE）
     * @param spacing  间距（米）
     * @param heading  航向角（度）
     * @param refLat   参考点纬度
     * @param refLon   参考点经度
     * @param refAlt   参考点高度（米）
     * @return 创建结果，包含 formationId、assignments、state、leader
     * @throws SdkException 如果请求失败或响应解析错误
     */
    public Map<String, Object> createFormation(List<Integer> members, String shape,
                                               double spacing, double heading,
                                               double refLat, double refLon, double refAlt)
            throws SdkException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("members", members);
        body.put("shape", shape);
        body.put("spacing", spacing);
        body.put("heading", heading);
        body.put("refLat", refLat);
        body.put("refLon", refLon);
        body.put("refAlt", refAlt);
        return requestMap("/formation", "POST", body);
    }

    /**
     * 创建编队（使用自定义请求体）。
     *
     * @param payload 编队创建请求体，包含 members、shape、spacing 等字段
     * @return 创建结果
     * @throws SdkException 如果请求失败或响应解析错误
     */
    public Map<String, Object> createFormation(Map<String, Object> payload) throws SdkException {
        return requestMap("/formation", "POST", payload);
    }

    /**
     * 向编队下发命令。
     *
     * @param formationId 编队 ID
     * @param type        命令类型（TAKEOFF / TRANSITION / LIGHTS / RTL / DISSOLVE）
     * @param alt         高度参数（仅 TAKEOFF 使用）
     * @return 命令执行结果
     * @throws SdkException 如果请求失败或响应解析错误
     */
    public Map<String, Object> commandFormation(int formationId, String type, double alt)
            throws SdkException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("alt", alt);
        return requestMap("/formation/" + formationId + "/command", "POST", body);
    }

    /**
     * 队形平滑变换。
     *
     * @param formationId 编队 ID
     * @param newShape    新队形名称
     * @param steps       插值步数（&gt;= 1）
     * @return 变换结果，包含各成员航点
     * @throws SdkException 如果请求失败或响应解析错误
     */
    public Map<String, Object> transitionFormation(int formationId, String newShape, int steps)
            throws SdkException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("newShape", newShape);
        body.put("steps", steps);
        return requestMap("/formation/" + formationId + "/transition", "POST", body);
    }

    /**
     * 解散编队。
     *
     * @param formationId 编队 ID
     * @return 解散结果
     * @throws SdkException 如果请求失败或响应解析错误
     */
    public Map<String, Object> dissolveFormation(int formationId) throws SdkException {
        return requestMap("/formation/" + formationId + "/dissolve", "POST", null);
    }

    // ===================================================================
    // 内部请求方法
    // ===================================================================

    private List<Map<String, Object>> requestList(String path) throws SdkException {
        try {
            HttpResponse<String> resp = doRequest(path, "GET", null);
            checkStatus(resp);
            return MAPPER.readValue(resp.body(), new TypeReference<List<Map<String, Object>>>() {});
        } catch (SdkException e) {
            throw e;
        } catch (Exception e) {
            throw new SdkException("请求失败: " + path, e);
        }
    }

    private Map<String, Object> requestMap(String path) throws SdkException {
        return requestMap(path, "GET", null);
    }

    private Map<String, Object> requestMap(String path, String method, Map<String, Object> body)
            throws SdkException {
        try {
            HttpResponse<String> resp = doRequest(path, method, body);
            checkStatus(resp);
            return MAPPER.readValue(resp.body(), new TypeReference<Map<String, Object>>() {});
        } catch (SdkException e) {
            throw e;
        } catch (Exception e) {
            throw new SdkException("请求失败: " + path, e);
        }
    }

    private HttpResponse<String> doRequest(String path, String method, Map<String, Object> body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + API_PREFIX + path))
                .header("X-API-Key", apiKey)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30));

        if ("POST".equals(method) && body != null) {
            String json = MAPPER.writeValueAsString(body);
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
        } else if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.GET();
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void checkStatus(HttpResponse<String> resp) throws SdkException {
        if (resp.statusCode() >= 400) {
            String errorMsg = "HTTP " + resp.statusCode();
            try {
                Map<String, Object> errBody = MAPPER.readValue(
                        resp.body(), new TypeReference<Map<String, Object>>() {});
                Object err = errBody.get("error");
                if (err != null) {
                    errorMsg = err.toString();
                }
            } catch (Exception ignored) {
                // 响应体不是 JSON，使用默认错误消息
            }
            throw new SdkException(errorMsg, resp.statusCode());
        }
    }
}
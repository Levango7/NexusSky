package io.aerofleet.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 通用 API 响应包装类，解析后端返回的 JSON 结构。
 * <p>
 * 典型响应格式：
 * <pre>{"status": "ok", "data": {...}}</pre>
 * 或列表响应：
 * <pre>{"status": "ok", "data": [...]}</pre>
 * 或错误时：
 * <pre>{"status": "error", "error": "..."}</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiResponse {

    private final String status;
    private final Object data;

    /**
     * 构造一个 API 响应对象。
     *
     * @param status 响应状态（"ok" 或 "error"）
     * @param data   响应数据体（可以是 Map、List 或其他类型）
     */
    @JsonCreator
    public ApiResponse(@JsonProperty("status") String status,
                       @JsonProperty("data") Object data) {
        this.status = status;
        this.data = data;
    }

    /**
     * 返回响应状态。
     *
     * @return "ok" 表示成功，"error" 表示失败
     */
    public String getStatus() {
        return status;
    }

    /**
     * 判断响应是否成功。
     *
     * @return true 如果 status 为 "ok"
     */
    public boolean isOk() {
        return "ok".equalsIgnoreCase(status);
    }

    /**
     * 返回响应数据体。
     *
     * @return 响应数据（可能是 Map、List 或其他类型）
     */
    public Object getData() {
        return data;
    }

    /**
     * 返回响应数据体作为 Map。
     *
     * @return 数据体 Map，如果数据体不是 Map 则返回 null
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDataAsMap() {
        return data instanceof Map ? (Map<String, Object>) data : null;
    }

    /**
     * 返回响应数据体作为 List。
     *
     * @return 数据体 List，如果数据体不是 List 则返回 null
     */
    @SuppressWarnings("unchecked")
    public List<Object> getDataAsList() {
        return data instanceof List ? (List<Object>) data : null;
    }

    /**
     * 从数据体中获取指定字段（仅当 data 为 Map 时有效）。
     *
     * @param key 字段名
     * @return 字段值，不存在时返回 null
     */
    public Object get(String key) {
        Map<String, Object> map = getDataAsMap();
        return map != null ? map.get(key) : null;
    }

    /**
     * 从数据体中获取指定字段并转换为字符串。
     *
     * @param key 字段名
     * @return 字段值的字符串表示，不存在时返回 null
     */
    public String getString(String key) {
        Object val = get(key);
        return val != null ? val.toString() : null;
    }

    /**
     * 从数据体中获取指定字段并转换为整数。
     *
     * @param key 字段名
     * @return 字段值的整数表示，不存在或无法转换时返回 -1
     */
    public int getInt(String key) {
        Object val = get(key);
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        return -1;
    }
}

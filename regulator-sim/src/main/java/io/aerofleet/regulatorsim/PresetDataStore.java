package io.aerofleet.regulatorsim;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 预置测试数据存储：模拟 UOM 平台的实名注册数据。
 * <p>
 * 默认内置 3 条预置数据：
 * <ul>
 *   <li>TEST-001 — 已实名（张三）</li>
 *   <li>TEST-002 — 未实名</li>
 *   <li>TEST-003 — 已实名（李四）</li>
 * </ul>
 * 支持通过 {@link #loadFromFile(String)} 从 JSON 文件加载自定义数据。
 * 线程安全（{@link ConcurrentHashMap}）。
 */
public final class PresetDataStore {

    /** 预置数据条目。 */
    public static final class PresetEntry {
        public final String serialNo;
        public final String certNo;
        public final String status;
        public final String owner;
        public final String registerDate;

        public PresetEntry(String serialNo, String certNo, String status,
                           String owner, String registerDate) {
            this.serialNo = serialNo;
            this.certNo = certNo;
            this.status = status;
            this.owner = owner;
            this.registerDate = registerDate;
        }
    }

    private final Map<String, PresetEntry> data = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建并加载默认预置数据。
     */
    public PresetDataStore() {
        loadDefault();
    }

    /**
     * 加载默认预置数据（3 条）。
     */
    public void loadDefault() {
        data.clear();
        data.put("TEST-001", new PresetEntry("TEST-001", "CERT-001", "VERIFIED", "张三", "2026-01-15"));
        data.put("TEST-002", new PresetEntry("TEST-002", "CERT-002", "UNVERIFIED", null, null));
        data.put("TEST-003", new PresetEntry("TEST-003", "CERT-003", "VERIFIED", "李四", "2026-02-20"));
    }

    /**
     * 从 JSON 文件加载预置数据，替换当前数据。
     * <p>
     * JSON 格式为 PresetEntry 列表：
     * <pre>
     * [{"serialNo":"TEST-001","certNo":"CERT-001","status":"VERIFIED","owner":"张三","registerDate":"2026-01-15"}, ...]
     * </pre>
     *
     * @param path JSON 文件路径
     * @throws IOException 文件读取或解析失败
     */
    public void loadFromFile(String path) throws IOException {
        List<PresetEntry> entries = objectMapper.readValue(
                new File(path), new TypeReference<List<PresetEntry>>() {});
        data.clear();
        for (PresetEntry entry : entries) {
            data.put(entry.serialNo, entry);
        }
    }

    /**
     * 按 serialNo 查询预置数据。
     *
     * @param serialNo 产品序列号
     * @return 匹配的预置条目，不存在时返回 null（用于 NOT_FOUND 场景）
     */
    public PresetEntry find(String serialNo) {
        return data.get(serialNo);
    }

    /**
     * 返回当前预置数据条数。
     *
     * @return 预置数据条数
     */
    public int size() {
        return data.size();
    }
}
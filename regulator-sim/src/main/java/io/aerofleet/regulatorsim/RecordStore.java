package io.aerofleet.regulatorsim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 上报记录存储：保存 activate/cancel/telemetry 三类上报记录。
 * <p>
 * 线程安全（{@link ConcurrentHashMap} + {@link CopyOnWriteArrayList}）。
 * 支持 {@link #clear()} 重置，用于测试间清理。
 */
public final class RecordStore {

    /** 上报记录条目。 */
    public static final class Record {
        public final String type;
        public final String serialNo;
        public final long timestamp;
        public final Map<String, Object> data;

        public Record(String type, String serialNo, long timestamp, Map<String, Object> data) {
            this.type = type;
            this.serialNo = serialNo;
            this.timestamp = timestamp;
            this.data = data;
        }
    }

    private final Map<String, List<Record>> records = new ConcurrentHashMap<>();

    /**
     * 添加一条上报记录。
     *
     * @param type   记录类型：activation / cancellation / telemetry
     * @param record 记录条目
     */
    public void add(String type, Record record) {
        records.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(record);
    }

    /**
     * 查询指定类型的全部记录。
     *
     * @param type 记录类型：activation / cancellation / telemetry
     * @return 对应类型记录列表（无记录时返回空列表）
     */
    public List<Record> query(String type) {
        List<Record> result = records.get(type);
        return result != null ? result : Collections.<Record>emptyList();
    }

    /**
     * 清空所有记录（测试间清理）。
     */
    public void clear() {
        records.clear();
    }

    /**
     * 返回指定类型记录条数。
     *
     * @param type 记录类型
     * @return 记录条数
     */
    public int count(String type) {
        List<Record> list = records.get(type);
        return list != null ? list.size() : 0;
    }
}
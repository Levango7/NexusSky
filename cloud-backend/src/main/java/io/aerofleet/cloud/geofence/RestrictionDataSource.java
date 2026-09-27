package io.aerofleet.cloud.geofence;

import java.util.List;

/**
 * 限飞区数据源接口：从外部来源（如官方限飞区数据库、第三方 API）获取限飞区数据。
 * <p>
 * 实现方负责从特定数据源拉取限飞区列表，并将其转换为 {@link RestrictionZone} 对象。
 * 所有 {@link RestrictionZone} 的 fenceType 恒为 {@link FenceType#KEEP_OUT KEEP_OUT}。
 * <p>
 * 设计为可扩展接口（FR-11），未来可支持多种数据源（官方数据库、第三方 API、本地配置等）。
 */
public interface RestrictionDataSource {

    /**
     * 获取数据源唯一标识。
     *
     * @return 数据源 ID（如 "caac-official"、"mock"）
     */
    String getSourceId();

    /**
     * 从数据源拉取限飞区列表。
     * <p>
     * 所有返回的 {@link RestrictionZone} 的 fenceType 恒为 {@link FenceType#KEEP_OUT KEEP_OUT}。
     *
     * @return 限飞区列表（可能为空，但不应为 null）
     * @throws Exception 数据源访问异常（网络错误、解析错误等）
     */
    List<RestrictionZone> fetch() throws Exception;
}
package io.aerofleet.cloud.sensing;

import java.util.List;

/**
 * 非合作目标感知源 SPI（E2/E4）——新来源接入点（可替换数据源原则）。
 * <p>
 * 实现方：
 * <ul>
 *   <li>{@link CounterDroneRadarSource}（E2，反制雷达侦测网，**只接侦测不做反制**）；</li>
 *   <li>{@link FiveGSensingSource}（E4，5G-A 通感基站，接入点预留）；</li>
 *   <li>真实设备（厂商雷达 SDK / 真实 ISAC 网元）实现本接口即可接入，
 *       聚合层（{@link SensingTrackService}）零改动。</li>
 * </ul>
 * 拉取语义：poll() 返回**自上次轮询以来的增量/更新航迹**（同一 trackId 出现多次表示更新）。
 */
public interface AerialSensingSource {

    /** 来源类型（SensingTrack.TYPE_*）。 */
    String sourceType();

    /** 来源设备标识（雷达站号/基站号）。 */
    String sourceId();

    /** 拉取增量/更新航迹（实现必须并行安全）。 */
    List<SensingTrack> poll();
}

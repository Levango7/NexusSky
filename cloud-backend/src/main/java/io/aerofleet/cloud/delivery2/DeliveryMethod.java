package io.aerofleet.cloud.delivery2;

/**
 * 配送方式枚举：空投 / 着陆交付 / 绳索降下。
 */
public enum DeliveryMethod {
    /** 空中投放：无人机在目标上方悬停后释放负载。 */
    AIR_DROP,
    /** 着陆交付：无人机降落于目标地点后卸载负载。 */
    LAND_DELIVER,
    /** 绳索降下：无人机悬停后通过绳索将负载缓慢降至地面。 */
    ROPE_LOWER
}
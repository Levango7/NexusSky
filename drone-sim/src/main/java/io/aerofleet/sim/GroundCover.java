package io.aerofleet.sim;

/**
 * 地面覆盖类型枚举（M3 感知成像增强，FR-06）。
 * 用于 {@link SimulatedMultispectralSource#synthesizeBands} 合成 NIR/Red 波段值。
 * <ul>
 *   <li>VEGETATION：植被（NIR 高 Red 低，NDVI>0.2）</li>
 *   <li>BARE_SOIL：裸土（NIR≈Red，NDVI≈0）</li>
 *   <li>WATER：水体（NIR 低 Red 高，NDVI<0）</li>
 * </ul>
 */
public enum GroundCover {
    VEGETATION,
    BARE_SOIL,
    WATER
}
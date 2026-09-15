package io.aerofleet.sim;

/**
 * 多光谱载荷抽象接口（M3 感知成像增强，FR-05）。
 * <p>
 * 多波段遥感载荷的接入点：输入 NIR/Red 等波段影像，输出 NDVI（归一化植被指数）
 * 等植被指数与波段统计。
 * <p>
 * 实现类：{@link SimulatedMultispectralSource}（模拟器合成波段 + NDVI 计算）。
 * 未来接真多光谱传感器只需新增实现类，不改感知主流程。
 */
public interface MultispectralSource {

    /**
     * 计算 NDVI 矩阵 + 统计摘要（FR-05）。
     * <p>
     * NDVI = (NIR - Red) / (NIR + Red)，取值 [-1, 1]。
     * 分母为 0 时 NDVI=0（避免除零）。
     *
     * @param nirBand NIR 波段影像（非 null，矩形矩阵）
     * @param redBand Red 波段影像（非 null，与 nirBand 同维度）
     * @return NDVI 矩阵 + 均值/最小/最大/植被覆盖率（NDVI>0.2 占比）
     */
    NdviResult computeNdvi(double[][] nirBand, double[][] redBand);

    /**
     * 波段合成（返回合成影像）。
     *
     * @param bands   多波段影像数组（bands[i] = 第 i 个波段矩阵）
     * @param weights 权重数组（与 bands 同长度）
     * @return 合成影像（加权求和）
     */
    double[][] bandComposite(double[][][] bands, double[] weights);

    /** NDVI 计算结果（矩阵 + 统计摘要）。 */
    record NdviResult(double[][] ndviMatrix, double mean, double min, double max,
                      double vegetationCoverage) {
    }
}
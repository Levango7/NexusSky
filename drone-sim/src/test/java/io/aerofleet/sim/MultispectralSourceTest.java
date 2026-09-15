package io.aerofleet.sim;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SimulatedMultispectralSource 单测（M3 感知成像增强，FR-05/FR-06）。
 */
class MultispectralSourceTest {

    private static double[][] uniform(int h, int w, double v) {
        double[][] m = new double[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                m[y][x] = v;
            }
        }
        return m;
    }

    @Test
    void ndviCalculationCorrect() {
        // NIR=200, Red=100 → NDVI = (200-100)/(200+100) = 0.333
        double[][] nir = uniform(4, 4, 200);
        double[][] red = uniform(4, 4, 100);
        SimulatedMultispectralSource src = new SimulatedMultispectralSource();
        MultispectralSource.NdviResult r = src.computeNdvi(nir, red);
        assertEquals(0.333, r.mean(), 0.001, "NDVI mean should be 0.333");
        assertEquals(0.333, r.min(), 0.001);
        assertEquals(0.333, r.max(), 0.001);
        // NDVI=0.333 > 0.2 → 植被覆盖率=1.0
        assertEquals(1.0, r.vegetationCoverage(), 0.001);
    }

    @Test
    void ndviWaterNegative() {
        // NIR=100, Red=200 → NDVI = (100-200)/(100+200) = -0.333
        double[][] nir = uniform(4, 4, 100);
        double[][] red = uniform(4, 4, 200);
        SimulatedMultispectralSource src = new SimulatedMultispectralSource();
        MultispectralSource.NdviResult r = src.computeNdvi(nir, red);
        assertEquals(-0.333, r.mean(), 0.001, "NDVI mean should be -0.333");
        assertEquals(0.0, r.vegetationCoverage(), 0.001, "non-vegetation coverage=0");
    }

    @Test
    void ndviDivisionByZeroHandled() {
        // NIR=0, Red=0 → 分母=0 → NDVI=0
        double[][] nir = uniform(2, 2, 0);
        double[][] red = uniform(2, 2, 0);
        SimulatedMultispectralSource src = new SimulatedMultispectralSource();
        MultispectralSource.NdviResult r = src.computeNdvi(nir, red);
        assertEquals(0.0, r.mean(), 0.001);
    }

    @Test
    void synthesizeBandsVegetation() {
        GroundCover[][] cover = {
            {GroundCover.VEGETATION, GroundCover.BARE_SOIL},
            {GroundCover.WATER, GroundCover.VEGETATION}
        };
        SimulatedMultispectralSource src = new SimulatedMultispectralSource(new Random(42));
        double[][][] bands = src.synthesizeBands(2, 2, cover);
        double[][] nir = bands[0];
        double[][] red = bands[1];
        // 植被：NIR 高 Red 低
        assertTrue(nir[0][0] > red[0][0], "vegetation NIR should exceed Red");
        // 裸土：NIR ≈ Red
        assertEquals(nir[0][1], red[0][1], 40, "bare soil NIR ≈ Red");
        // 水体：NIR 低 Red 高
        assertTrue(nir[1][0] < red[1][0], "water NIR should be less than Red");
    }

    @Test
    void vegetationCoverageCorrect() {
        // 4 像素：2 植被 + 1 裸土 + 1 水体 → 覆盖率=0.5
        GroundCover[][] cover = {
            {GroundCover.VEGETATION, GroundCover.BARE_SOIL},
            {GroundCover.WATER, GroundCover.VEGETATION}
        };
        SimulatedMultispectralSource src = new SimulatedMultispectralSource(new Random(42));
        double[][][] bands = src.synthesizeBands(2, 2, cover);
        MultispectralSource.NdviResult r = src.computeNdvi(bands[0], bands[1]);
        // 植被 NDVI>0.2，裸土≈0，水体<0 → 覆盖率=2/4=0.5
        assertEquals(0.5, r.vegetationCoverage(), 0.1,
                "vegetation coverage should be 0.5 (2 veg / 4 total)");
    }

    @Test
    void bandCompositeWorks() {
        double[][] b1 = uniform(2, 2, 100);
        double[][] b2 = uniform(2, 2, 200);
        SimulatedMultispectralSource src = new SimulatedMultispectralSource();
        double[][] out = src.bandComposite(new double[][][]{b1, b2}, new double[]{0.5, 0.5});
        assertEquals(150, out[0][0], 0.001);
    }

    @Test
    void nullBandsRejected() {
        SimulatedMultispectralSource src = new SimulatedMultispectralSource();
        assertThrows(IllegalArgumentException.class, () -> src.computeNdvi(null, null));
    }
}
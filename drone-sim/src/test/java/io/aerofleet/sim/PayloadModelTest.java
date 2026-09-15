package io.aerofleet.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PayloadModel 负载聚合单测（FR-22）。
 * <p>
 * 覆盖 totalWeight / totalVolume / combinedCenterOfGravity 聚合计算 + add/remove/clear。
 */
class PayloadModelTest {

    // ---- FR-22 聚合重量 ----

    @Test
    void totalWeightSumsAllItems() {
        PayloadModel pm = new PayloadModel();
        pm.add(new PayloadItem(1, 2.0, 1.0, 0.0));
        pm.add(new PayloadItem(2, 3.0, 1.5, 0.0));
        assertEquals(5.0, pm.totalWeight(), 1e-9);
        assertEquals(2, pm.count());
    }

    @Test
    void totalWeightEmptyIsZero() {
        PayloadModel pm = new PayloadModel();
        assertEquals(0.0, pm.totalWeight(), 1e-9);
        assertEquals(0, pm.count());
    }

    // ---- FR-22 聚合体积 ----

    @Test
    void totalVolumeSumsAllItems() {
        PayloadModel pm = new PayloadModel();
        pm.add(new PayloadItem(1, 2.0, 1.0, 0.0));
        pm.add(new PayloadItem(2, 3.0, 1.5, 0.0));
        assertEquals(2.5, pm.totalVolume(), 1e-9);
    }

    // ---- FR-22 合成重心 ----

    @Test
    void combinedCenterOfGravityWeightedAverage() {
        PayloadModel pm = new PayloadModel();
        // item1: weight=2.0, cog=0.1 → 0.2
        // item2: weight=3.0, cog=0.4 → 1.2
        // combined = (0.2 + 1.2) / (2.0 + 3.0) = 1.4 / 5.0 = 0.28
        pm.add(new PayloadItem(1, 2.0, 1.0, 0.1));
        pm.add(new PayloadItem(2, 3.0, 1.5, 0.4));
        assertEquals(0.28, pm.combinedCenterOfGravity(), 1e-9);
    }

    @Test
    void combinedCenterOfGravitySingleItem() {
        PayloadModel pm = new PayloadModel();
        pm.add(new PayloadItem(1, 2.0, 1.0, 0.5));
        assertEquals(0.5, pm.combinedCenterOfGravity(), 1e-9);
    }

    @Test
    void combinedCenterOfGravityEmptyIsZero() {
        PayloadModel pm = new PayloadModel();
        assertEquals(0.0, pm.combinedCenterOfGravity(), 1e-9);
    }

    @Test
    void combinedCenterOfGravityZeroWeightIsZero() {
        PayloadModel pm = new PayloadModel();
        pm.add(new PayloadItem(1, 0.0, 1.0, 0.5));
        assertEquals(0.0, pm.combinedCenterOfGravity(), 1e-9);
    }

    // ---- remove ----

    @Test
    void removeDecreasesTotals() {
        PayloadModel pm = new PayloadModel();
        PayloadItem item1 = new PayloadItem(1, 2.0, 1.0, 0.1);
        PayloadItem item2 = new PayloadItem(2, 3.0, 1.5, 0.4);
        pm.add(item1);
        pm.add(item2);
        assertEquals(5.0, pm.totalWeight(), 1e-9);

        pm.remove(item1);
        assertEquals(3.0, pm.totalWeight(), 1e-9);
        assertEquals(1.5, pm.totalVolume(), 1e-9);
        assertEquals(0.4, pm.combinedCenterOfGravity(), 1e-9);
        assertEquals(1, pm.count());
    }

    // ---- clear ----

    @Test
    void clearEmptiesAll() {
        PayloadModel pm = new PayloadModel();
        pm.add(new PayloadItem(1, 2.0, 1.0, 0.1));
        pm.add(new PayloadItem(2, 3.0, 1.5, 0.4));
        pm.clear();
        assertEquals(0.0, pm.totalWeight(), 1e-9);
        assertEquals(0.0, pm.totalVolume(), 1e-9);
        assertEquals(0.0, pm.combinedCenterOfGravity(), 1e-9);
        assertEquals(0, pm.count());
    }

    // ---- items() 不可变拷贝 ----

    @Test
    void itemsReturnsImmutableCopy() {
        PayloadModel pm = new PayloadModel();
        pm.add(new PayloadItem(1, 2.0, 1.0, 0.1));
        var items = pm.items();
        assertEquals(1, items.size());
        assertThrows(UnsupportedOperationException.class, () -> items.add(new PayloadItem(2, 1, 1, 0)));
    }
}
package io.aerofleet.cloud.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BoundedHistory 单元测试：有界历史缓冲。
 * <p>
 * 覆盖要点：add/size/capacity 驱逐/迭代快照/null 安全/边界条件。
 * 遵循华为防御性测试实践：异常与边界场景优先于正常流程。
 */
@DisplayName("BoundedHistory 有界历史缓冲")
class BoundedHistoryTest {

    // ==================== 异常场景测试（最高优先级） ====================

    @Test
    @DisplayName("BH-构造-容量为负-不抛异常（ArrayDeque 静默接受，且永不驱逐）")
    void testConstructor_negativeCapacity_noExceptionAndNeverEvicts() {
        // 源码实际行为：Math.min(-1,64)=-1，ArrayDeque(-1) 不抛异常（分配默认容量 8）；
        // 且 size>=0 永远不等于负 capacity，故永不驱逐，表现为无界增长。
        BoundedHistory<Integer> h = new BoundedHistory<>(-1);
        h.add(1);
        h.add(2);
        h.add(3);
        assertThat(h.size()).isEqualTo(3);
        assertThat(h.toList()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("BH-add-元素为 null-抛 NullPointerException")
    void testAdd_nullItem_throwNullPointerException() {
        BoundedHistory<String> h = new BoundedHistory<>(4);
        assertThatThrownBy(() -> h.add(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ==================== 边界条件测试 ====================

    @Test
    @DisplayName("BH-size-新建缓冲-返回 0")
    void testSize_newBuffer_returnZero() {
        BoundedHistory<Integer> h = new BoundedHistory<>(8);
        assertThat(h.size()).isZero();
    }

    @Test
    @DisplayName("BH-toList-空缓冲-返回空列表")
    void testToList_emptyBuffer_returnEmptyList() {
        BoundedHistory<Integer> h = new BoundedHistory<>(8);
        assertThat(h.toList()).isEmpty();
    }

    @Test
    @DisplayName("BH-容量为 1-添加两个元素-仅保留最后一个")
    void testCapacity_one_addTwo_keepOnlyLast() {
        BoundedHistory<String> h = new BoundedHistory<>(1);
        h.add("a");
        h.add("b");
        assertThat(h.size()).isEqualTo(1);
        assertThat(h.toList()).containsExactly("b");
    }

    @Test
    @DisplayName("BH-容量为 0-添加元素-元素被保留（源码实际行为）")
    void testCapacity_zero_add_elementRetained() {
        // capacity=0 时 size==capacity 触发 pollFirst（空队列无操作），随后 addLast 仍入队
        BoundedHistory<String> h = new BoundedHistory<>(0);
        h.add("x");
        assertThat(h.size()).isEqualTo(1);
        assertThat(h.toList()).containsExactly("x");
    }

    // ==================== 正常业务流程测试 ====================

    @Test
    @DisplayName("BH-add-未达容量-size 递增")
    void testAdd_belowCapacity_sizeIncrements() {
        BoundedHistory<Integer> h = new BoundedHistory<>(3);
        h.add(1);
        assertThat(h.size()).isEqualTo(1);
        h.add(2);
        assertThat(h.size()).isEqualTo(2);
        h.add(3);
        assertThat(h.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("BH-add-达到容量-丢弃最老元素")
    void testAdd_atCapacity_evictOldest() {
        BoundedHistory<Integer> h = new BoundedHistory<>(3);
        h.add(1);
        h.add(2);
        h.add(3);
        h.add(4); // 应丢弃 1
        assertThat(h.size()).isEqualTo(3);
        assertThat(h.toList()).containsExactly(2, 3, 4);
    }

    @Test
    @DisplayName("BH-add-连续超出容量多个-仅保留最近 N 个")
    void testAdd_farBeyondCapacity_keepRecentN() {
        BoundedHistory<Integer> h = new BoundedHistory<>(3);
        for (int i = 1; i <= 10; i++) {
            h.add(i);
        }
        assertThat(h.size()).isEqualTo(3);
        assertThat(h.toList()).containsExactly(8, 9, 10);
    }

    @Test
    @DisplayName("BH-toList-返回最老在前的时间顺序快照")
    void testToList_returnSnapshotOldestFirst() {
        BoundedHistory<String> h = new BoundedHistory<>(5);
        h.add("first");
        h.add("second");
        h.add("third");
        List<String> snap = h.toList();
        assertThat(snap).containsExactly("first", "second", "third");
    }

    @Test
    @DisplayName("BH-toList-返回副本-修改不影响原缓冲")
    void testToList_returnsCopy_mutationDoesNotAffectSource() {
        BoundedHistory<Integer> h = new BoundedHistory<>(5);
        h.add(1);
        h.add(2);
        List<Integer> snap = h.toList();
        snap.clear();
        // 原缓冲不受影响
        assertThat(h.size()).isEqualTo(2);
        assertThat(h.toList()).containsExactly(1, 2);
    }

    @Test
    @DisplayName("BH-toList-多次调用-每次返回独立副本")
    void testToList_multipleCalls_returnIndependentCopies() {
        BoundedHistory<Integer> h = new BoundedHistory<>(5);
        h.add(1);
        h.add(2);
        List<Integer> a = h.toList();
        List<Integer> b = h.toList();
        assertThat(a).isNotSameAs(b);
        assertThat(a).containsExactlyElementsOf(b);
        a.add(99);
        assertThat(b).doesNotContain(99);
    }

    @Nested
    @DisplayName("不同泛型类型")
    class GenericTypeTests {

        @Test
        @DisplayName("BH-泛型-String 类型正常工作")
        void testStringType() {
            BoundedHistory<String> h = new BoundedHistory<>(2);
            h.add("alpha");
            h.add("beta");
            h.add("gamma");
            assertThat(h.toList()).containsExactly("beta", "gamma");
        }

        @Test
        @DisplayName("BH-泛型-自定义记录类型正常工作")
        void testRecordType() {
            record Point(int x, int y) {}
            BoundedHistory<Point> h = new BoundedHistory<>(2);
            h.add(new Point(1, 1));
            h.add(new Point(2, 2));
            h.add(new Point(3, 3));
            List<Point> pts = h.toList();
            assertThat(pts).hasSize(2);
            assertThat(pts.get(0).x()).isEqualTo(2);
            assertThat(pts.get(1).y()).isEqualTo(3);
        }
    }
}
package io.aerofleet.sim;

import io.aerofleet.mavlink.messages.MissionItemInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MissionStore 单元测试：验证任务航点存储与 Mission Protocol 上传状态机。
 *
 * <p>测试模式：直接实例化 + AssertJ 断言，不依赖 Spring 上下文。
 * 覆盖上传状态机（beginUpload/onItem/commit/abort）、边界条件（count 上下限、seq 越界）、
 * null 安全、重复/乱序项处理及查询接口（get/all/size/summary）。
 *
 * <p>测试顺序遵循防御性测试原则：边界/异常场景优先，再覆盖正常业务流程。
 */
@DisplayName("MissionStore 任务航点存储")
class MissionStoreTest {

    /** MAX_ITEMS 常量，与源码保持一致。 */
    private static final int MAX_ITEMS = 500;

    private MissionStore store;

    @BeforeEach
    void setUp() {
        store = new MissionStore(1);
    }

    // ==================== 辅助方法 ====================

    /** 创建一个最小可用的 MissionItemInt，仅 seq 变化，坐标固定为苏黎世附近。 */
    private static MissionItemInt item(int seq) {
        return new MissionItemInt(
                1, 1, seq, 0, 16, 0, 1,
                0f, 0f, 0f, 0f,
                471234567, 85432100, 100.0f, 0);
    }

    /** 创建带 command 的 MissionItemInt，用于 summary 验证。 */
    private static MissionItemInt item(int seq, int command) {
        return new MissionItemInt(
                1, 1, seq, 0, command, 0, 1,
                0f, 0f, 0f, 0f,
                471234567, 85432100, 100.0f, 0);
    }

    // ==================== 初始状态 ====================

    @Test
    @DisplayName("新建 store 初始状态：未上传、无任务、size=0")
    void newStore_initialState() {
        assertThat(store.isUploading()).isFalse();
        assertThat(store.hasMission()).isFalse();
        assertThat(store.size()).isZero();
        assertThat(store.nextExpectedSeq()).isZero();
        assertThat(store.sysid()).isEqualTo(1);
    }

    @Test
    @DisplayName("不同 sysid 构造后 sysid() 返回正确值")
    void constructor_preservesSysid() {
        assertThat(new MissionStore(255).sysid()).isEqualTo(255);
        assertThat(new MissionStore(0).sysid()).isZero();
    }

    // ==================== beginUpload 边界条件（防御性优先） ====================

    @Nested
    @DisplayName("beginUpload 边界条件")
    class BeginUploadBoundary {

        @Test
        @DisplayName("count < 0 返回 -1 且不改变状态")
        void beginUpload_negativeCount_returnsMinusOne() {
            int result = store.beginUpload(-1);
            assertThat(result).isEqualTo(-1);
            assertThat(store.isUploading()).isFalse();
            assertThat(store.nextExpectedSeq()).isZero();
        }

        @Test
        @DisplayName("count = Integer.MIN_VALUE 返回 -1")
        void beginUpload_minInt_returnsMinusOne() {
            assertThat(store.beginUpload(Integer.MIN_VALUE)).isEqualTo(-1);
        }

        @Test
        @DisplayName("count > MAX_ITEMS(500) 返回 -1 且不改变状态")
        void beginUpload_overMax_returnsMinusOne() {
            int result = store.beginUpload(MAX_ITEMS + 1);
            assertThat(result).isEqualTo(-1);
            assertThat(store.isUploading()).isFalse();
        }

        @Test
        @DisplayName("count = Integer.MAX_VALUE 返回 -1")
        void beginUpload_maxInt_returnsMinusOne() {
            assertThat(store.beginUpload(Integer.MAX_VALUE)).isEqualTo(-1);
        }

        @Test
        @DisplayName("count = 0 返回 0 且 uploading=false（空任务）")
        void beginUpload_zero_returnsZeroAndNotUploading() {
            int result = store.beginUpload(0);
            assertThat(result).isZero();
            assertThat(store.isUploading()).isFalse();
            assertThat(store.nextExpectedSeq()).isZero();
        }

        @Test
        @DisplayName("count = 1（下界有效值）返回 0 且 uploading=true")
        void beginUpload_one_returnsZeroAndUploading() {
            int result = store.beginUpload(1);
            assertThat(result).isZero();
            assertThat(store.isUploading()).isTrue();
        }

        @Test
        @DisplayName("count = MAX_ITEMS(500)（上界有效值）返回 0 且 uploading=true")
        void beginUpload_maxItems_returnsZeroAndUploading() {
            int result = store.beginUpload(MAX_ITEMS);
            assertThat(result).isZero();
            assertThat(store.isUploading()).isTrue();
        }
    }

    // ==================== onItem 异常/边界场景 ====================

    @Nested
    @DisplayName("onItem 未在上传状态")
    class OnItemNotUploading {

        @Test
        @DisplayName("未 beginUpload 直接 onItem 返回 0（请求从头开始）")
        void onItem_withoutUpload_returnsZero() {
            int result = store.onItem(item(0));
            assertThat(result).isZero();
            assertThat(store.isUploading()).isFalse();
            assertThat(store.hasMission()).isFalse();
        }

        @Test
        @DisplayName("abortUpload 后 onItem 返回 0")
        void onItem_afterAbort_returnsZero() {
            store.beginUpload(3);
            store.abortUpload();
            assertThat(store.onItem(item(0))).isZero();
        }
    }

    @Nested
    @DisplayName("onItem 重复与乱序项")
    class OnItemDuplicateAndOutOfOrder {

        @Test
        @DisplayName("重复 seq（< nextExpectedSeq）返回当前期望 seq")
        void onItem_duplicateSeq_returnsNextExpected() {
            store.beginUpload(3);
            store.onItem(item(0));              // 期望 0 → 返回 1
            int result = store.onItem(item(0)); // 重复 0 → 返回 1
            assertThat(result).isEqualTo(1);
            assertThat(store.nextExpectedSeq()).isEqualTo(1);
        }

        @Test
        @DisplayName("未来 seq（> nextExpectedSeq）返回当前期望 seq")
        void onItem_futureSeq_returnsNextExpected() {
            store.beginUpload(3);
            int result = store.onItem(item(2)); // 跳过 0，请求 2 → 返回 0
            assertThat(result).isZero();
            assertThat(store.nextExpectedSeq()).isZero();
        }

        @Test
        @DisplayName("乱序后正确 seq 仍能被接收")
        void onItem_outOfOrderThenCorrectSeq_accepted() {
            store.beginUpload(3);
            assertThat(store.onItem(item(2))).isZero();   // 未来项，重请求 0
            assertThat(store.onItem(item(0))).isEqualTo(1); // 正确 0
            assertThat(store.onItem(item(1))).isEqualTo(2); // 正确 1
            assertThat(store.onItem(item(2))).isEqualTo(-1); // 正确 2 → 完成
            assertThat(store.hasMission()).isTrue();
        }
    }

    // ==================== onItem 正常上传流程 ====================

    @Nested
    @DisplayName("onItem 正常上传流程")
    class OnItemNormalFlow {

        @Test
        @DisplayName("单项任务：beginUpload(1) + onItem(0) → 立即完成返回 -1")
        void singleItemMission_completesImmediately() {
            store.beginUpload(1);
            int result = store.onItem(item(0));
            assertThat(result).isEqualTo(-1);
            assertThat(store.isUploading()).isFalse();
            assertThat(store.hasMission()).isTrue();
            assertThat(store.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("多项任务：逐项上传至完成")
        void multiItemMission_uploadsToCompletion() {
            store.beginUpload(3);
            assertThat(store.onItem(item(0))).isEqualTo(1);
            assertThat(store.onItem(item(1))).isEqualTo(2);
            assertThat(store.onItem(item(2))).isEqualTo(-1);
            assertThat(store.hasMission()).isTrue();
            assertThat(store.isUploading()).isFalse();
            assertThat(store.size()).isEqualTo(3);
        }

        @Test
        @DisplayName("上传完成后 nextExpectedSeq 等于 count")
        void afterCompletion_nextExpectedSeqEqualsCount() {
            store.beginUpload(4);
            for (int i = 0; i < 4; i++) {
                store.onItem(item(i));
            }
            assertThat(store.nextExpectedSeq()).isEqualTo(4);
        }
    }

    // ==================== commit / abortUpload ====================

    @Nested
    @DisplayName("commit 与 abortUpload")
    class CommitAndAbort {

        @Test
        @DisplayName("commit 后 hasMission=true 且 uploading=false")
        void commit_setsHasMissionClearsUploading() {
            store.beginUpload(2);
            store.onItem(item(0));
            store.commit();
            assertThat(store.hasMission()).isTrue();
            assertThat(store.isUploading()).isFalse();
        }

        @Test
        @DisplayName("commit 后无 items 时 hasMission() 返回 false（hasMission && !items.isEmpty()）")
        void commit_emptyItems_hasMissionReturnsFalse() {
            store.commit();
            // hasMission 标志为 true，但 items 为空，故 hasMission() 返回 false
            assertThat(store.hasMission()).isFalse();
            assertThat(store.size()).isZero();
        }

        @Test
        @DisplayName("abortUpload 重置全部上传状态")
        void abortUpload_resetsAllState() {
            store.beginUpload(3);
            store.onItem(item(0));
            store.onItem(item(1));
            store.abortUpload();
            assertThat(store.isUploading()).isFalse();
            assertThat(store.nextExpectedSeq()).isZero();
            assertThat(store.size()).isZero();
        }

        @Test
        @DisplayName("abortUpload 后可重新 beginUpload")
        void abortUpload_thenBeginUploadAgain() {
            store.beginUpload(2);
            store.onItem(item(0));
            store.abortUpload();
            assertThat(store.beginUpload(2)).isZero();
            assertThat(store.isUploading()).isTrue();
        }
    }

    // ==================== get 边界条件 ====================

    @Nested
    @DisplayName("get 查询边界")
    class GetBoundary {

        @Test
        @DisplayName("get(-1) 返回 null")
        void get_negativeSeq_returnsNull() {
            assertThat(store.get(-1)).isNull();
        }

        @Test
        @DisplayName("get(0) 空列表返回 null")
        void get_emptyList_returnsNull() {
            assertThat(store.get(0)).isNull();
        }

        @Test
        @DisplayName("get(size) 越界返回 null")
        void get_atSize_returnsNull() {
            store.beginUpload(2);
            store.onItem(item(0));
            store.onItem(item(1));
            assertThat(store.get(2)).isNull();
        }

        @Test
        @DisplayName("get(有效 seq) 返回对应 item")
        void get_validSeq_returnsItem() {
            store.beginUpload(2);
            store.onItem(item(0));
            store.onItem(item(1));
            MissionItemInt result = store.get(0);
            assertThat(result).isNotNull();
            assertThat(result.seq).isZero();
        }

        @Test
        @DisplayName("get(Integer.MIN_VALUE) 返回 null")
        void get_minInt_returnsNull() {
            assertThat(store.get(Integer.MIN_VALUE)).isNull();
        }
    }

    // ==================== all / size / summary ====================

    @Nested
    @DisplayName("all / size / summary 查询")
    class QueryMethods {

        @Test
        @DisplayName("all() 空列表返回空集合")
        void all_empty_returnsEmptyList() {
            assertThat(store.all()).isEmpty();
        }

        @Test
        @DisplayName("all() 上传后返回包含所有 item 的列表")
        void all_afterUpload_returnsAllItems() {
            store.beginUpload(2);
            store.onItem(item(0));
            store.onItem(item(1));
            assertThat(store.all()).hasSize(2);
            assertThat(store.all().get(0).seq).isZero();
            assertThat(store.all().get(1).seq).isEqualTo(1);
        }

        @Test
        @DisplayName("size() 上传过程中反映当前已接收数量")
        void size_reflectsReceivedCount() {
            store.beginUpload(3);
            assertThat(store.size()).isZero();
            store.onItem(item(0));
            assertThat(store.size()).isEqualTo(1);
            store.onItem(item(1));
            assertThat(store.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("summary() 空任务包含 'items=0'")
        void summary_empty_containsZeroItems() {
            assertThat(store.summary()).contains("items=0");
        }

        @Test
        @DisplayName("summary() 有任务包含 seq 与 cmd 信息")
        void summary_withItems_containsSeqAndCmd() {
            store.beginUpload(1);
            store.onItem(item(0, 16));
            String summary = store.summary();
            assertThat(summary).contains("items=1");
            // summary 格式: [<seq> cmd=<command> lat=... lon=... alt=...]
            assertThat(summary).contains("[0 cmd=16");
            assertThat(summary).contains("lat=47.1234567");
            assertThat(summary).contains("alt=100.0");
        }

        @Test
        @DisplayName("summary() 多项任务包含每项信息")
        void summary_multipleItems_containsAll() {
            store.beginUpload(2);
            store.onItem(item(0, 16));
            store.onItem(item(1, 17));
            String summary = store.summary();
            assertThat(summary).contains("cmd=16");
            assertThat(summary).contains("cmd=17");
        }
    }

    // ==================== hasMission 组合逻辑 ====================

    @Nested
    @DisplayName("hasMission 组合逻辑")
    class HasMissionLogic {

        @Test
        @DisplayName("上传完成且有 items → hasMission=true")
        void hasMission_afterCompleteUpload_true() {
            store.beginUpload(1);
            store.onItem(item(0));
            assertThat(store.hasMission()).isTrue();
        }

        @Test
        @DisplayName("上传未完成 → hasMission=false")
        void hasMission_duringUpload_false() {
            store.beginUpload(2);
            store.onItem(item(0));
            assertThat(store.hasMission()).isFalse();
        }

        @Test
        @DisplayName("abortUpload 后 → hasMission=false")
        void hasMission_afterAbort_false() {
            store.beginUpload(1);
            store.onItem(item(0));
            store.abortUpload();
            assertThat(store.hasMission()).isFalse();
        }
    }
}
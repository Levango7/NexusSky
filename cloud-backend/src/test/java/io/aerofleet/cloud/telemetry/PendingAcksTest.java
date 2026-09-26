package io.aerofleet.cloud.telemetry;

import io.aerofleet.mavlink.messages.CommandAck;
import io.aerofleet.mavlink.messages.MissionAckMsg;
import io.aerofleet.mavlink.messages.MissionCountMsg;
import io.aerofleet.mavlink.messages.MissionItemInt;
import io.aerofleet.mavlink.messages.MissionRequest;
import io.aerofleet.mavlink.messages.MissionRequestInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PendingAcks 单元测试：待确认命令 ACK 跟踪。
 * <p>
 * 覆盖要点：put/offer/remove/timeout/contains/size 及各 typed helper。
 * 遵循华为防御性测试实践：异常与边界场景优先。
 */
@DisplayName("PendingAcks 待确认 ACK 跟踪")
class PendingAcksTest {

    // ==================== 异常/边界场景测试（最高优先级） ====================

    @Test
    @DisplayName("PA-size-新建实例-返回 0")
    void testSize_newInstance_returnZero() {
        PendingAcks pa = new PendingAcks();
        assertThat(pa.size()).isZero();
    }

    @Test
    @DisplayName("PA-offer-无等待者-返回 false 且不影响 size")
    void testOffer_noWaiter_returnFalse() {
        PendingAcks pa = new PendingAcks();
        boolean matched = pa.offer(CommandAck.ID, new CommandAck(1, 0, -1, 0, 0, 0), 1);
        assertThat(matched).isFalse();
        assertThat(pa.size()).isZero();
    }

    @Test
    @DisplayName("PA-remove-不存在的 key-future 不受影响且 size 不变")
    void testRemove_nonExistentKey_noEffect() {
        PendingAcks pa = new PendingAcks();
        pa.remove(CommandAck.ID, 99, 1, new TimeoutException("stale"));
        assertThat(pa.size()).isZero();
    }

    @Test
    @DisplayName("PA-remove-null cause-抛 NullPointerException（源码未防御 null cause）")
    void testRemove_nullCause_throwNullPointerException() {
        PendingAcks pa = new PendingAcks();
        pa.put(CommandAck.ID, 1, 1, (o, s) -> true);
        assertThatThrownBy(() -> pa.remove(CommandAck.ID, 1, 1, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ==================== put / offer / remove 核心流程测试 ====================

    @Test
    @DisplayName("PA-put+offer-matcher 匹配-future 完成且 size 递减")
    void testPutThenOffer_matched_futureCompletes() throws Exception {
        PendingAcks pa = new PendingAcks();
        CompletableFuture<Object> f = pa.put(CommandAck.ID, 1, 1, (o, s) -> true);
        assertThat(pa.size()).isEqualTo(1);

        Object resp = new CommandAck(1, 0, -1, 0, 0, 0);
        boolean matched = pa.offer(CommandAck.ID, resp, 1);

        assertThat(matched).isTrue();
        assertThat(f).isCompleted();
        assertThat(f.get()).isSameAs(resp);
        assertThat(pa.size()).isZero();
    }

    @Test
    @DisplayName("PA-put+offer-messageId 不匹配-future 未完成")
    void testPutThenOffer_messageIdMismatch_futureNotCompleted() {
        PendingAcks pa = new PendingAcks();
        CompletableFuture<Object> f = pa.put(CommandAck.ID, 1, 1, (o, s) -> true);

        boolean matched = pa.offer(MissionAckMsg.ID, "other", 1);

        assertThat(matched).isFalse();
        assertThat(f).isNotCompleted();
        assertThat(pa.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("PA-put+offer-matcher 返回 false-future 未完成且返回 false")
    void testPutThenOffer_matcherRejects_futureNotCompleted() {
        PendingAcks pa = new PendingAcks();
        CompletableFuture<Object> f = pa.put(CommandAck.ID, 1, 1, (o, s) -> false);

        boolean matched = pa.offer(CommandAck.ID, new Object(), 1);

        assertThat(matched).isFalse();
        assertThat(f).isNotCompleted();
    }

    @Test
    @DisplayName("PA-put+remove-future 异常完成且 size 递减")
    void testPutThenRemove_futureCompletesExceptionally() {
        PendingAcks pa = new PendingAcks();
        CompletableFuture<Object> f = pa.put(CommandAck.ID, 1, 1, (o, s) -> true);

        Throwable cause = new TimeoutException("timeout");
        pa.remove(CommandAck.ID, 1, 1, cause);

        assertThat(f).isCompletedExceptionally();
        assertThat(pa.size()).isZero();
        assertThatThrownBy(f::get).hasCauseReference(cause);
    }

    @Test
    @DisplayName("PA-put-相同 key 覆盖-size 不增加且旧 future 未完成")
    void testPut_sameKeyOverwrite_sizeUnchanged() {
        PendingAcks pa = new PendingAcks();
        CompletableFuture<Object> f1 = pa.put(CommandAck.ID, 1, 1, (o, s) -> true);
        CompletableFuture<Object> f2 = pa.put(CommandAck.ID, 1, 1, (o, s) -> true);

        assertThat(pa.size()).isEqualTo(1);
        // 旧 future 被替换，未被完成
        assertThat(f1).isNotCompleted();
        assertThat(f2).isNotCompleted();

        // offer 应完成新 future
        Object resp = new Object();
        boolean matched = pa.offer(CommandAck.ID, resp, 1);
        assertThat(matched).isTrue();
        assertThat(f2).isCompleted();
        assertThat(f1).isNotCompleted();
    }

    @Test
    @DisplayName("PA-offer-多个等待者仅匹配其中一个-其余不受影响")
    void testOffer_multipleWaiters_onlyMatchedCompletes() throws Exception {
        PendingAcks pa = new PendingAcks();
        CompletableFuture<Object> fA = pa.put(CommandAck.ID, 1, 1, (o, s) -> s == 1);
        CompletableFuture<Object> fB = pa.put(CommandAck.ID, 2, 2, (o, s) -> s == 2);

        Object resp = new Object();
        pa.offer(CommandAck.ID, resp, 1);

        assertThat(fA).isCompleted();
        assertThat(fB).isNotCompleted();
        assertThat(pa.size()).isEqualTo(1);
    }

    // ==================== expectCommandAck 测试 ====================

    @Test
    @DisplayName("PA-expectCommandAck-匹配 command 与 sysid-future 完成")
    void testExpectCommandAck_matchedCompletes() throws Exception {
        PendingAcks pa = new PendingAcks();
        CompletableFuture<CommandAck> f = pa.expectCommandAck(16, 1);

        CommandAck ack = new CommandAck(16, 0, -1, 0, 0, 0);
        boolean matched = pa.offer(CommandAck.ID, ack, 1);

        assertThat(matched).isTrue();
        assertThat(f).isCompleted();
        assertThat(f.get()).isSameAs(ack);
        assertThat(pa.size()).isZero();
    }

    @Test
    @DisplayName("PA-expectCommandAck-command 不匹配-future 未完成")
    void testExpectCommandAck_commandMismatch_notCompleted() {
        PendingAcks pa = new PendingAcks();
        CompletableFuture<CommandAck> f = pa.expectCommandAck(16, 1);

        CommandAck ack = new CommandAck(99, 0, -1, 0, 0, 0);
        boolean matched = pa.offer(CommandAck.ID, ack, 1);

        assertThat(matched).isFalse();
        assertThat(f).isNotCompleted();
    }

    @Test
    @DisplayName("PA-expectCommandAck-sysid 不匹配-future 未完成")
    void testExpectCommandAck_sysidMismatch_notCompleted() {
        PendingAcks pa = new PendingAcks();
        CompletableFuture<CommandAck> f = pa.expectCommandAck(16, 1);

        CommandAck ack = new CommandAck(16, 0, -1, 0, 0, 0);
        boolean matched = pa.offer(CommandAck.ID, ack, 2); // 来自 sysid=2

        assertThat(matched).isFalse();
        assertThat(f).isNotCompleted();
    }

    @Test
    @DisplayName("PA-expectCommandAck-ANY_SYSID-任意 sysid 均匹配")
    void testExpectCommandAck_anySysid_matchesAny() throws Exception {
        PendingAcks pa = new PendingAcks();
        CompletableFuture<CommandAck> f = pa.expectCommandAck(16, PendingAcks.ANY_SYSID);

        CommandAck ack = new CommandAck(16, 0, -1, 0, 0, 0);
        boolean matched = pa.offer(CommandAck.ID, ack, 42);

        assertThat(matched).isTrue();
        assertThat(f).isCompleted();
    }

    @Test
    @DisplayName("PA-expectCommandAck-非 CommandAck 类型-future 未完成")
    void testExpectCommandAck_wrongType_notCompleted() {
        PendingAcks pa = new PendingAcks();
        CompletableFuture<CommandAck> f = pa.expectCommandAck(16, 1);

        boolean matched = pa.offer(CommandAck.ID, "not-an-ack", 1);

        assertThat(matched).isFalse();
        assertThat(f).isNotCompleted();
    }

    // ==================== expectMissionRequest 测试 ====================

    @Test
    @DisplayName("PA-expectMissionRequest-MissionRequestInt 匹配-future 完成且返回 seq")
    void testExpectMissionRequest_intVariant_completesWithSeq() throws Exception {
        PendingAcks pa = new PendingAcks();
        CompletableFuture<Integer> f = pa.expectMissionRequest(1);

        MissionRequestInt req = new MissionRequestInt(5, 1, 0, 0);
        boolean matched = pa.offer(MissionRequestInt.ID, req, 1);

        assertThat(matched).isTrue();
        assertThat(f).isCompleted();
        assertThat(f.get()).isEqualTo(5);
    }

    @Test
    @DisplayName("PA-expectMissionRequest-MissionRequest 匹配-future 完成且返回 seq")
    void testExpectMissionRequest_legacyVariant_completesWithSeq() throws Exception {
        PendingAcks pa = new PendingAcks();
        CompletableFuture<Integer> f = pa.expectMissionRequest(1);

        MissionRequest req = new MissionRequest(7, 1, 0, 0);
        boolean matched = pa.offer(MissionRequest.ID, req, 1);

        assertThat(matched).isTrue();
        assertThat(f).isCompleted();
        assertThat(f.get()).isEqualTo(7);
    }

    @Test
    @DisplayName("PA-expectMissionRequest-sysid 不匹配-future 未完成")
    void testExpectMissionRequest_sysidMismatch_notCompleted() {
        PendingAcks pa = new PendingAcks();
        CompletableFuture<Integer> f = pa.expectMissionRequest(1);

        MissionRequestInt req = new MissionRequestInt(5, 2, 0, 0);
        boolean matched = pa.offer(MissionRequestInt.ID, req, 2);

        assertThat(matched).isFalse();
        assertThat(f).isNotCompleted();
    }

    @Test
    @DisplayName("PA-expectMissionRequest-注册两个 key-size 为 2")
    void testExpectMissionRequest_registersTwoKeys() {
        PendingAcks pa = new PendingAcks();
        pa.expectMissionRequest(1);
        // 同时注册 MissionRequestInt.ID 与 MissionRequest.ID
        assertThat(pa.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("PA-removeMissionRequest-清理两个 key（返回的 future 不被异常完成）")
    void testRemoveMissionRequest_clearsBothKeys() {
        PendingAcks pa = new PendingAcks();
        CompletableFuture<Integer> f = pa.expectMissionRequest(1);
        assertThat(pa.size()).isEqualTo(2);

        pa.removeMissionRequest(1, new TimeoutException("stale"));

        // 两个 key 均被移除
        assertThat(pa.size()).isZero();
        // 注意：expectMissionRequest 返回的 f 是独立 future，
        // remove 只完成内部 Pending.future，不会异常完成 f。
        assertThat(f).isNotCompleted();
    }

    @Test
    @DisplayName("PA-removeMissionRequest-未注册的 sysid-无副作用")
    void testRemoveMissionRequest_unregisteredSysid_noEffect() {
        PendingAcks pa = new PendingAcks();
        pa.removeMissionRequest(99, new TimeoutException("stale"));
        assertThat(pa.size()).isZero();
    }

    // ==================== expectMissionAck 测试 ====================

    @Test
    @DisplayName("PA-expectMissionAck-匹配 sysid-future 完成")
    void testExpectMissionAck_matchedCompletes() throws Exception {
        PendingAcks pa = new PendingAcks();
        CompletableFuture<MissionAckMsg> f = pa.expectMissionAck(1);

        MissionAckMsg ack = new MissionAckMsg(0, 0, 0, 0, 0);
        boolean matched = pa.offer(MissionAckMsg.ID, ack, 1);

        assertThat(matched).isTrue();
        assertThat(f).isCompleted();
        assertThat(f.get()).isSameAs(ack);
    }

    @Test
    @DisplayName("PA-expectMissionAck-sysid 不匹配-future 未完成")
    void testExpectMissionAck_sysidMismatch_notCompleted() {
        PendingAcks pa = new PendingAcks();
        CompletableFuture<MissionAckMsg> f = pa.expectMissionAck(1);

        MissionAckMsg ack = new MissionAckMsg(0, 0, 0, 0, 0);
        boolean matched = pa.offer(MissionAckMsg.ID, ack, 2);

        assertThat(matched).isFalse();
        assertThat(f).isNotCompleted();
    }

    @Test
    @DisplayName("PA-expectMissionAck-ANY_SYSID-任意 sysid 均匹配")
    void testExpectMissionAck_anySysid_matchesAny() throws Exception {
        PendingAcks pa = new PendingAcks();
        CompletableFuture<MissionAckMsg> f = pa.expectMissionAck(PendingAcks.ANY_SYSID);

        MissionAckMsg ack = new MissionAckMsg(0, 0, 0, 0, 0);
        boolean matched = pa.offer(MissionAckMsg.ID, ack, 7);

        assertThat(matched).isTrue();
        assertThat(f).isCompleted();
    }

    // ==================== expectMissionCount 测试 ====================

    @Test
    @DisplayName("PA-expectMissionCount-匹配 sysid-future 完成")
    void testExpectMissionCount_matchedCompletes() throws Exception {
        PendingAcks pa = new PendingAcks();
        CompletableFuture<MissionCountMsg> f = pa.expectMissionCount(1);

        MissionCountMsg cnt = new MissionCountMsg(3, 1, 0, 0, 0);
        boolean matched = pa.offer(MissionCountMsg.ID, cnt, 1);

        assertThat(matched).isTrue();
        assertThat(f).isCompleted();
        assertThat(f.get().count).isEqualTo(3);
    }

    @Test
    @DisplayName("PA-expectMissionCount-sysid 不匹配-future 未完成")
    void testExpectMissionCount_sysidMismatch_notCompleted() {
        PendingAcks pa = new PendingAcks();
        CompletableFuture<MissionCountMsg> f = pa.expectMissionCount(1);

        MissionCountMsg cnt = new MissionCountMsg(3, 2, 0, 0, 0);
        boolean matched = pa.offer(MissionCountMsg.ID, cnt, 2);

        assertThat(matched).isFalse();
        assertThat(f).isNotCompleted();
    }

    // ==================== expectMissionItem 测试 ====================

    @Test
    @DisplayName("PA-expectMissionItem-匹配 seq 与 sysid-future 完成")
    void testExpectMissionItem_matchedCompletes() throws Exception {
        PendingAcks pa = new PendingAcks();
        CompletableFuture<MissionItemInt> f = pa.expectMissionItem(1, 3);

        MissionItemInt item = new MissionItemInt(1, 0, 3, 0, 16, 0, 1,
                0f, 0f, 0f, 0f, 477000000, 125500000, 50f, 0);
        boolean matched = pa.offer(MissionItemInt.ID, item, 1);

        assertThat(matched).isTrue();
        assertThat(f).isCompleted();
        assertThat(f.get().seq).isEqualTo(3);
    }

    @Test
    @DisplayName("PA-expectMissionItem-seq 不匹配-future 未完成")
    void testExpectMissionItem_seqMismatch_notCompleted() {
        PendingAcks pa = new PendingAcks();
        CompletableFuture<MissionItemInt> f = pa.expectMissionItem(1, 3);

        MissionItemInt item = new MissionItemInt(1, 0, 9, 0, 16, 0, 1,
                0f, 0f, 0f, 0f, 0, 0, 0f, 0);
        boolean matched = pa.offer(MissionItemInt.ID, item, 1);

        assertThat(matched).isFalse();
        assertThat(f).isNotCompleted();
    }

    @Test
    @DisplayName("PA-expectMissionItem-sysid 不匹配-future 未完成")
    void testExpectMissionItem_sysidMismatch_notCompleted() {
        PendingAcks pa = new PendingAcks();
        CompletableFuture<MissionItemInt> f = pa.expectMissionItem(1, 3);

        MissionItemInt item = new MissionItemInt(2, 0, 3, 0, 16, 0, 1,
                0f, 0f, 0f, 0f, 0, 0, 0f, 0);
        boolean matched = pa.offer(MissionItemInt.ID, item, 2);

        assertThat(matched).isFalse();
        assertThat(f).isNotCompleted();
    }

    // ==================== 并发安全测试 ====================

    @Test
    @DisplayName("PA-并发 put 与 offer-无异常且最终一致")
    void testConcurrentPutAndOffer_noException() throws Exception {
        PendingAcks pa = new PendingAcks();
        int n = 50;
        CompletableFuture<Void>[] futures = new CompletableFuture[n];

        for (int i = 0; i < n; i++) {
            final int idx = i;
            futures[i] = CompletableFuture.runAsync(() -> {
                CompletableFuture<CommandAck> f = pa.expectCommandAck(idx, idx);
                CommandAck ack = new CommandAck(idx, 0, -1, 0, 0, 0);
                pa.offer(CommandAck.ID, ack, idx);
                assertThat(f).isCompleted();
            });
        }
        CompletableFuture.allOf(futures).get(5, TimeUnit.SECONDS);
        assertThat(pa.size()).isZero();
    }
}
package io.aerofleet.cloud.mission.delivery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DeliverySequence 配送序列单测（FR-24~FR-25）。
 * <p>
 * 覆盖到达站点 PENDING→EN_ROUTE、markDropped→DROPPED、markSkipped、progress、skipRemaining。
 */
class DeliverySequenceTest {

    private static DeliverySite site(int index, double lat, double lon, double accuracy) {
        return new DeliverySite(index, lat, lon, 50, 1, 1.0, 0.5, accuracy,
                DeliverySiteState.PENDING, 0, 0);
    }

    // ---- FR-24 到达站点 PENDING→EN_ROUTE ----

    @Test
    void positionUpdateArrivesSiteTransitionsToEnRoute() {
        DeliverySite s0 = site(0, 30.0, 120.0, 5.0);
        DeliverySequence seq = new DeliverySequence(1, 1, List.of(s0));
        seq.start();
        assertEquals(DeliverySiteState.PENDING, seq.sites().get(0).state());

        // 在精度半径内 → EN_ROUTE
        seq.onPositionUpdate(30.0, 120.0);
        assertEquals(DeliverySiteState.EN_ROUTE, seq.sites().get(0).state());
        assertTrue(seq.sites().get(0).arriveTimeMs() > 0, "arrive time should be recorded");
    }

    @Test
    void positionUpdateOutsideAccuracyDoesNotTransition() {
        DeliverySite s0 = site(0, 30.0, 120.0, 5.0);
        DeliverySequence seq = new DeliverySequence(1, 1, List.of(s0));
        seq.start();

        // 距离 > 5m → 不转换
        seq.onPositionUpdate(30.001, 120.0);  // ~111m away
        assertEquals(DeliverySiteState.PENDING, seq.sites().get(0).state());
    }

    @Test
    void positionUpdateWhenNotRunningIsNoOp() {
        DeliverySite s0 = site(0, 30.0, 120.0, 5.0);
        DeliverySequence seq = new DeliverySequence(1, 1, List.of(s0));
        // not started
        seq.onPositionUpdate(30.0, 120.0);
        assertEquals(DeliverySiteState.PENDING, seq.sites().get(0).state());
    }

    // ---- FR-24 markDropped→DROPPED ----

    @Test
    void markDroppedTransitionsToDropped() {
        DeliverySite s0 = site(0, 30.0, 120.0, 5.0);
        DeliverySequence seq = new DeliverySequence(1, 1, List.of(s0));
        seq.start();
        seq.onPositionUpdate(30.0, 120.0);
        assertEquals(DeliverySiteState.EN_ROUTE, seq.sites().get(0).state());

        assertTrue(seq.markDropped(0));
        assertEquals(DeliverySiteState.DROPPED, seq.sites().get(0).state());
        assertTrue(seq.sites().get(0).dropTimeMs() > 0, "drop time should be recorded");
        assertEquals(DeliverySequence.State.COMPLETED, seq.state(), "all dropped → COMPLETED");
    }

    @Test
    void markDroppedRejectsNonEnRoute() {
        DeliverySite s0 = site(0, 30.0, 120.0, 5.0);
        DeliverySequence seq = new DeliverySequence(1, 1, List.of(s0));
        seq.start();
        // still PENDING
        assertFalse(seq.markDropped(0), "markDropped should reject PENDING");
        assertEquals(DeliverySiteState.PENDING, seq.sites().get(0).state());
    }

    @Test
    void markDroppedRejectsInvalidIndex() {
        DeliverySite s0 = site(0, 30.0, 120.0, 5.0);
        DeliverySequence seq = new DeliverySequence(1, 1, List.of(s0));
        seq.start();
        assertFalse(seq.markDropped(-1), "negative index");
        assertFalse(seq.markDropped(1), "out of range index");
    }

    // ---- FR-24 markSkipped ----

    @Test
    void markSkippedTransitionsToSkipped() {
        DeliverySite s0 = site(0, 30.0, 120.0, 5.0);
        DeliverySequence seq = new DeliverySequence(1, 1, List.of(s0));
        seq.start();
        assertTrue(seq.markSkipped(0));
        assertEquals(DeliverySiteState.SKIPPED, seq.sites().get(0).state());
    }

    @Test
    void markSkippedRejectsInvalidIndex() {
        DeliverySite s0 = site(0, 30.0, 120.0, 5.0);
        DeliverySequence seq = new DeliverySequence(1, 1, List.of(s0));
        assertFalse(seq.markSkipped(-1));
        assertFalse(seq.markSkipped(1));
    }

    // ---- FR-25 progress ----

    @Test
    void progressCalculatesDroppedPercentage() {
        DeliverySite s0 = site(0, 30.0, 120.0, 5.0);
        DeliverySite s1 = site(1, 30.001, 120.0, 5.0);
        DeliverySite s2 = site(2, 30.002, 120.0, 5.0);
        DeliverySequence seq = new DeliverySequence(1, 1, List.of(s0, s1, s2));
        seq.start();

        assertEquals(0.0, seq.progress(), 1e-9);
        assertEquals(3, seq.remainingSites());

        // drop site 0
        seq.onPositionUpdate(30.0, 120.0);
        seq.markDropped(0);
        assertEquals(100.0 / 3, seq.progress(), 1e-6);
        assertEquals(2, seq.remainingSites());

        // drop site 1
        seq.onPositionUpdate(30.001, 120.0);
        seq.markDropped(1);
        assertEquals(200.0 / 3, seq.progress(), 1e-6);
        assertEquals(1, seq.remainingSites());
    }

    @Test
    void progressEmptyIsZero() {
        DeliverySequence seq = new DeliverySequence(1, 1, List.of());
        assertEquals(0.0, seq.progress(), 1e-9);
    }

    // ---- FR-25 skipRemaining→COMPLETED_WITH_SKIPS ----

    @Test
    void skipRemainingWithPendingSitesGivesCompletedWithSkips() {
        DeliverySite s0 = site(0, 30.0, 120.0, 5.0);
        DeliverySite s1 = site(1, 30.001, 120.0, 5.0);
        DeliverySequence seq = new DeliverySequence(1, 1, List.of(s0, s1));
        seq.start();

        // drop site 0, site 1 still PENDING
        seq.onPositionUpdate(30.0, 120.0);
        seq.markDropped(0);
        assertEquals(DeliverySequence.State.RUNNING, seq.state());

        seq.skipRemaining();
        assertEquals(DeliverySiteState.SKIPPED, seq.sites().get(1).state());
        assertEquals(DeliverySequence.State.COMPLETED_WITH_SKIPS, seq.state());
    }

    @Test
    void skipRemainingAllDroppedGivesCompleted() {
        DeliverySite s0 = site(0, 30.0, 120.0, 5.0);
        DeliverySequence seq = new DeliverySequence(1, 1, List.of(s0));
        seq.start();
        seq.onPositionUpdate(30.0, 120.0);
        seq.markDropped(0);
        assertEquals(DeliverySequence.State.COMPLETED, seq.state());

        seq.skipRemaining();  // no pending → COMPLETED
        assertEquals(DeliverySequence.State.COMPLETED, seq.state());
    }

    // ---- fail ----

    @Test
    void failOverridesState() {
        DeliverySite s0 = site(0, 30.0, 120.0, 5.0);
        DeliverySequence seq = new DeliverySequence(1, 1, List.of(s0));
        seq.start();
        seq.fail();
        assertEquals(DeliverySequence.State.FAILED, seq.state());
    }

    // ---- 多站点完整流程 ----

    @Test
    void fullMultiSiteDeliveryFlow() {
        DeliverySite s0 = site(0, 30.0, 120.0, 5.0);
        DeliverySite s1 = site(1, 30.001, 120.0, 5.0);
        DeliverySequence seq = new DeliverySequence(1, 1, List.of(s0, s1));
        assertEquals(DeliverySequence.State.PENDING, seq.state());

        seq.start();
        assertEquals(DeliverySequence.State.RUNNING, seq.state());
        assertEquals(0, seq.currentIndex());

        seq.onPositionUpdate(30.0, 120.0);
        seq.markDropped(0);
        assertEquals(1, seq.currentIndex());

        seq.onPositionUpdate(30.001, 120.0);
        seq.markDropped(1);
        assertEquals(DeliverySequence.State.COMPLETED, seq.state());
        assertEquals(100.0, seq.progress(), 1e-9);
        assertEquals(0, seq.remainingSites());
    }
}
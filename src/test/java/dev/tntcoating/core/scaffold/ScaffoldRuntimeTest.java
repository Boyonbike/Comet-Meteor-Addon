package dev.tntcoating.core.scaffold;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.tntcoating.core.scaffold.ScaffoldRuntime.PacketKind.*;

class ScaffoldRuntimeTest {
    private final AtomicLong clock = new AtomicLong();
    private final ScaffoldRuntime runtime = new ScaffoldRuntime(clock::get);
    private void ms(long millis) { clock.addAndGet(millis * 1_000_000); }
    private ScaffoldRuntime.Attempt dispatch(long pos, boolean urgent, int packets) {
        var a = runtime.reserve(pos, urgent, 1, packets);
        assertNotNull(a); assertTrue(runtime.beginDispatch(a)); return a;
    }
    private ScaffoldRuntime.Attempt sent(long pos, boolean urgent) {
        var a = dispatch(pos, urgent, 2);
        runtime.observePacket(a, USE_ITEM); runtime.finishDispatch(a); return a;
    }
    @Test void preEnableTrafficAndExternalTrafficBetweenPlanningAndDispatchBlockSending() {
        runtime.setPacketCap(3);
        runtime.observePacket(null, OTHER);
        var a = runtime.reserve(1, false, 4, 2);
        assertNotNull(a); assertEquals(0, runtime.remainingPacketBudget());
        runtime.observePacket(null, MOVEMENT);
        assertFalse(runtime.beginDispatch(a));
        runtime.cancel(a, "budget");
        assertEquals(2, runtime.actualPackets()); assertEquals(0, runtime.reservedPackets());
        ms(999); assertNull(runtime.reserve(2, false, 4, 2));
        ms(1); assertNotNull(runtime.reserve(2, false, 4, 2));
    }
    @Test void actualPacketsReplaceCreditsAndCancelRefundsOnlyUnusedCredits() {
        var a = dispatch(1, false, 4);
        runtime.observePacket(a, SLOT); runtime.observePacket(a, USE_ITEM);
        assertEquals(2, runtime.actualPackets()); assertEquals(2, runtime.reservedPackets());
        runtime.cancel(a, "disabled");
        assertEquals(0, runtime.reservedPackets()); assertEquals(2, runtime.actualPackets());
        assertTrue(runtime.pending(1)); assertTrue(runtime.quarantine(1));
        runtime.authoritative(1, true, false);
        assertFalse(runtime.pending(1)); assertFalse(runtime.quarantine(1));
        assertEquals(1, runtime.stats().confirmed());
    }
    @Test void canceledUseDoesNotCountAsSentButPredictionRemainsUntrustedUntilAuthoritative() {
        var a = dispatch(3, false, 2);
        runtime.observePacket(a, SWING); runtime.finishDispatch(a);
        assertEquals(0, runtime.stats().sent()); assertFalse(runtime.pending(3));
        assertTrue(runtime.knownUnconfirmed(3));
        runtime.invalidateUnsent("inactive"); ms(40_000); runtime.maintain();
        assertTrue(runtime.quarantine(3));
        runtime.authoritative(3, false, true);
        assertFalse(runtime.quarantine(3));
        assertFalse(runtime.retryReady(3)); ms(150); assertTrue(runtime.retryReady(3));
    }
    @Test void onlyCurrentIdentityCanSendAndUnsentReservationsExpireAfter100ms() {
        var old = runtime.reserve(4, false, 1, 2); assertNotNull(old);
        ms(100); runtime.maintain(); assertEquals(0, runtime.pendingCount());
        var current = runtime.reserve(4, false, 2, 2); assertNotNull(current);
        assertFalse(runtime.beginDispatch(old));
        runtime.observePacket(old, USE_ITEM);
        assertEquals(0, runtime.stats().sent()); assertTrue(runtime.pending(4));
        assertTrue(runtime.beginDispatch(current));
    }
    @Test void spacingUsesMonotonicTimeAndSixPendingBound() {
        sent(1, false);
        assertNull(runtime.reserve(2, false, 1, 1));
        ms(49); assertNull(runtime.reserve(2, false, 1, 1));
        ms(1); sent(2, false);
        for (int i = 3; i <= 6; i++) { ms(50); sent(i, false); }
        ms(50); assertNull(runtime.reserve(7, false, 1, 1));
        assertEquals(6, runtime.pendingCount());
    }
    @Test void timeoutUsesLatencyEmaAndNormalRetryDoublesWithoutTickTime() {
        sent(1, false); assertEquals(1_000_000_000L, runtime.timeoutNanos());
        ms(400); runtime.authoritative(1, true, false);
        assertEquals(1_600_000_000L, runtime.timeoutNanos());
        sent(2, false); ms(1599); runtime.maintain(); assertTrue(runtime.pending(2));
        ms(1); runtime.maintain(); assertFalse(runtime.pending(2));
        assertEquals(1, runtime.stats().failures()); assertTrue(runtime.quarantine(2));
        assertFalse(runtime.retryReady(2)); ms(150); assertTrue(runtime.retryReady(2));
        sent(2, false); runtime.authoritative(2, false, true);
        ms(299); assertFalse(runtime.retryReady(2)); ms(1); assertTrue(runtime.retryReady(2));
    }
    @Test void urgentRetryIs50Then100msAndUsableOccupancyIsTruthfulNotOwnedAck() {
        sent(1, true); runtime.authoritative(1, false, true);
        ms(49); assertFalse(runtime.retryReady(1)); ms(1); assertTrue(runtime.retryReady(1));
        sent(1, true); runtime.authoritative(1, false, true);
        ms(99); assertFalse(runtime.retryReady(1)); ms(1); assertTrue(runtime.retryReady(1));
        runtime.authoritative(90, true, false);
        assertEquals(0, runtime.stats().confirmed());
        assertTrue(runtime.drainEvents().stream().anyMatch(e -> e.type() == ScaffoldRuntime.EventType.OCCUPANCY_USABLE && e.position() == 90 && e.attemptId() == 0));
    }
    @Test void confirmationWatchDetectsRollbackOnlyForTenSeconds() {
        sent(1, false); ms(100); runtime.authoritative(1, true, false);
        ms(9999); runtime.authoritative(1, false, true);
        assertEquals(1, runtime.stats().rollbacks()); assertFalse(runtime.retryReady(1));
        ms(150); sent(2, false); runtime.authoritative(2, true, false);
        ms(10_000); runtime.maintain(); runtime.authoritative(2, false, true);
        assertEquals(1, runtime.stats().rollbacks());
    }
    @Test void correctionSeparatesLossesAndPausesWhileKeepingQuarantine() {
        sent(1, false); ms(50);
        var unsent = runtime.reserve(2, false, 9, 2); assertNotNull(unsent);
        runtime.correction();
        assertEquals(0, runtime.pendingCount()); assertEquals(0, runtime.reservedPackets());
        assertEquals(0, runtime.stats().failures()); assertEquals(1, runtime.stats().correctionLosses());
        assertTrue(runtime.quarantine(1)); assertFalse(runtime.beginDispatch(unsent));
        ms(499); assertNull(runtime.reserve(3, false, 9, 2));
        ms(1); assertNotNull(runtime.reserve(3, false, 9, 2));
    }
    @Test void unresolvedQuarantineCannotBeEvictedAtCapacityAndOnlyWorldResetClearsIt() {
        for (int i = 0; i < 256; i++) { var a = dispatch(i, false, 1); runtime.finishDispatch(a); ms(150); }
        assertEquals(256, runtime.trackedPositions());
        assertNull(runtime.reserve(300, false, 1, 1));
        runtime.invalidateUnsent("preset"); assertTrue(runtime.quarantine(0));
        runtime.authoritative(0, false, true); ms(150);
        assertNotNull(runtime.reserve(300, false, 1, 1));
        runtime.resetWorld(); assertEquals(0, runtime.trackedPositions()); assertFalse(runtime.quarantine(1));
    }
    @Test void resetWorldDoesNotForgetConnectionTrafficOrReuseAttemptIds() {
        var old = dispatch(1, false, 2); runtime.observePacket(old, USE_ITEM);
        runtime.resetWorld();
        assertEquals(1, runtime.actualPackets());
        var newer = runtime.reserve(1, false, 1, 2); assertNotNull(newer);
        assertNotEquals(old.id(), newer.id()); runtime.observePacket(old, USE_ITEM);
        assertEquals(0, runtime.stats().sent());
    }
    @Test void externalPredictionQuarantineIsBoundedAndResolvedWithoutOwnedAttempt() {
        for (int i = 0; i < 256; i++) assertTrue(runtime.markQuarantined(i));
        assertFalse(runtime.markQuarantined(300)); assertTrue(runtime.knownUnconfirmed(0));
        runtime.authoritative(0, true, false);
        assertFalse(runtime.knownUnconfirmed(0)); assertTrue(runtime.markQuarantined(300));
        runtime.authoritative(300, false, true);
        assertFalse(runtime.knownUnconfirmed(300)); assertEquals(0, runtime.stats().failures());
    }
    @Test void expiredDispatchedPredictionSurvivesAndStaleCallbacksCannotSpendNewCredits() {
        var old = dispatch(1, false, 3); runtime.observePacket(old, SLOT);
        ms(100); runtime.maintain();
        assertEquals(0, runtime.pendingCount()); assertEquals(0, runtime.reservedPackets());
        assertTrue(runtime.knownUnconfirmed(1));
        var current = runtime.reserve(2, false, 2, 3); assertNotNull(current);
        runtime.observePacket(old, USE_ITEM); runtime.finishDispatch(old);
        assertEquals(3, runtime.reservedPackets()); assertEquals(2, runtime.actualPackets());
        assertEquals(0, runtime.stats().sent());
    }
    @Test void reservedOnlyCancellationDoesNotQuarantineOrInflateFailures() {
        var a = runtime.reserve(1, false, 1, 3); runtime.invalidateUnsent("disable");
        assertFalse(runtime.quarantine(1)); assertFalse(runtime.beginDispatch(a));
        assertEquals(0, runtime.stats().failures()); assertEquals(1, runtime.stats().cancellations());
        assertEquals(0, runtime.reservedPackets());
    }
    @Test void correctionClassifiesStillTrackedSentWorkWithoutChargingOrdinaryTimeoutFailures() {
        sent(1, false); ms(2000); runtime.correction();
        assertEquals(0, runtime.stats().failures()); assertEquals(1, runtime.stats().correctionLosses());
        runtime.authoritative(1, false, true); assertEquals(0, runtime.stats().failures());
    }
    @Test void eventQueueIsBoundedAndExpiresAfterTwoSeconds() {
        for (int i = 0; i < 600; i++) runtime.observePacket(null, OTHER);
        assertEquals(88, runtime.stats().droppedEvents());
        assertEquals(512, runtime.drainEvents().size());
        runtime.observePacket(null, MOVEMENT); ms(2000);
        assertTrue(runtime.drainEvents().isEmpty());
    }
    @Test void timeoutAndLaterRejectionCountAsOneFailureForTheSamePrediction() {
        sent(1, false); ms(1000); runtime.maintain();
        runtime.authoritative(1, false, true);
        assertEquals(1, runtime.stats().failures()); assertEquals(1, runtime.stats().timeouts());
    }
    @Test void replaceableUpdateBeforeDispatchCancelsPlanWithoutInventingPlacementFailure() {
        var a = runtime.reserve(1, false, 1, 3); assertNotNull(a);
        runtime.authoritative(1, false, true);
        assertFalse(runtime.beginDispatch(a)); assertFalse(runtime.quarantine(1));
        assertEquals(0, runtime.stats().sent()); assertEquals(0, runtime.stats().failures());
        assertEquals(0, runtime.reservedPackets()); assertTrue(runtime.retryReady(1));
    }
    @Test void lateUsableOccupancyAfterTimeoutStartsRollbackWatchWithoutInventingConfirmation() {
        sent(1, false); ms(1000); runtime.maintain();
        assertEquals(1, runtime.stats().timeouts()); assertEquals(1, runtime.stats().failures());
        assertTrue(runtime.knownUnconfirmed(1));
        runtime.authoritative(1, true, false);
        assertFalse(runtime.knownUnconfirmed(1)); assertTrue(runtime.retryReady(1));
        assertEquals(0, runtime.stats().confirmed()); assertEquals(0, runtime.stats().latencyMillis());
        assertTrue(runtime.drainEvents().stream().anyMatch(e -> e.type() == ScaffoldRuntime.EventType.OCCUPANCY_USABLE && e.position() == 1 && e.attemptId() == 0));
        ms(100); runtime.authoritative(1, false, true);
        assertEquals(1, runtime.stats().rollbacks()); assertEquals(2, runtime.stats().failures());
        assertFalse(runtime.retryReady(1)); ms(149); assertFalse(runtime.retryReady(1));
        ms(1); assertTrue(runtime.retryReady(1));
    }
    @Test void lateUsableOccupancyAfterCorrectionStartsRollbackWatchWithoutReclassifyingLoss() {
        sent(1, false); ms(100); runtime.correction();
        assertEquals(1, runtime.stats().correctionLosses()); assertEquals(0, runtime.stats().failures());
        runtime.authoritative(1, true, false);
        assertFalse(runtime.knownUnconfirmed(1)); assertEquals(0, runtime.stats().confirmed());
        ms(500); runtime.authoritative(1, false, true);
        assertEquals(1, runtime.stats().rollbacks()); assertEquals(1, runtime.stats().failures());
        assertEquals(1, runtime.stats().correctionLosses()); assertFalse(runtime.retryReady(1));
        ms(150); assertTrue(runtime.retryReady(1));
    }
    @Test void lateUsableOccupancyWatchExpiresTenSecondsAfterItsAuthoritativeObservation() {
        sent(1, false); ms(1000); runtime.maintain(); ms(5000);
        runtime.authoritative(1, true, false);
        ms(9999); runtime.authoritative(1, false, true);
        assertEquals(1, runtime.stats().rollbacks());
        ms(150); sent(2, false); runtime.correction();
        ms(1000); runtime.authoritative(2, true, false);
        ms(10000); runtime.authoritative(2, false, true);
        assertEquals(1, runtime.stats().rollbacks());
    }
}

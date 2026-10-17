package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TntBlastLifecycleTest {
    private enum Block { TNT, STONE }

    private static final TntBlastPlanner.Cell SOURCE = new TntBlastPlanner.Cell(-1, 0, 0);
    private static final TntBlastPlanner.Cell TARGET = new TntBlastPlanner.Cell(0, 0, 0);
    private static final TntBlastPlanner.Cell TERRAIN = new TntBlastPlanner.Cell(0, -1, 0);
    private static final TntBlastPlanner.Material DIRT = new TntBlastPlanner.Material(0.5f, true);

    private final Set<TntBlastPlanner.Cell> worldTnt = new HashSet<>();
    private final Set<TntBlastPlanner.Cell> loaded = new HashSet<>(Set.of(SOURCE));
    private final PlacementTracker<TntBlastPlanner.Cell, Block> tracker = new PlacementTracker<>(
        new PlacementTracker.Listener<>() {
            @Override public void accepted(TntBlastPlanner.Cell pos, int latencyTicks) {}
            @Override public void retryScheduled(TntBlastPlanner.Cell pos, boolean failure) {}
            @Override public void retryReady(TntBlastPlanner.Cell pos) {}
        });

    @Test void cancellingUnsentReservationReopensNeighbouringTerrain() {
        var attempt = tracker.reserve(SOURCE, 0, Block.TNT);
        assertTrue(covered());

        tracker.cancel(SOURCE, attempt);

        assertFalse(tracker.isPending(SOURCE));
        assertFalse(covered());
    }

    @Test void timedOutPredictionStopsCoveringEvenWhenWorldStillShowsTnt() {
        sendWithClientPrediction(0);
        assertTrue(tracker.unconfirmed(SOURCE));
        assertTrue(covered(), "A sent reservation should cover while waiting for confirmation");
        tracker.expire(19, 20, 3, pos -> true);
        assertTrue(covered());

        tracker.expire(20, 20, 3, pos -> true);

        assertFalse(tracker.isPending(SOURCE));
        assertTrue(tracker.unconfirmed(SOURCE));
        assertTrue(worldTnt.contains(SOURCE), "The client can retain a predicted TNT block after timeout");
        assertFalse(covered(), "A ghost prediction must not suppress needed TNT placement");
    }

    @Test void lateAuthoritativeConfirmationRestoresTimedOutSourceCoverage() {
        sendWithClientPrediction(0);
        tracker.expire(20, 20, 3, pos -> true);
        assertFalse(covered());

        tracker.update(SOURCE, true, false, tracker.attempt(SOURCE), 22, 3);

        assertFalse(tracker.unconfirmed(SOURCE));
        assertFalse(tracker.isPending(SOURCE));
        assertTrue(covered(), "Authoritatively confirmed world TNT can cover without a pending attempt");
    }

    @Test void authoritativeRollbackOfConfirmedTntReopensTerrain() {
        var attempt = sendWithClientPrediction(0);
        tracker.update(SOURCE, true, false, attempt, 2, 3);
        assertTrue(covered());

        worldTnt.remove(SOURCE);
        tracker.update(SOURCE, false, true, tracker.attempt(SOURCE), 5, 3);

        assertTrue(tracker.retryBlocked(SOURCE, 5));
        assertFalse(covered());
    }

    @Test void pendingOtherCoatingMaterialDoesNotCountAsTntCoverage() {
        tracker.reserve(SOURCE, 0, Block.STONE);
        worldTnt.add(SOURCE);

        assertFalse(covered(), "Pending material must match TNT before using the source's blast");
    }

    @Test void loadedGhostStaysUnconfirmedOutsideScanUntilItsChunkUnloads() {
        sendWithClientPrediction(0);
        tracker.expire(20, 20, 3, pos -> false);
        Set<TntBlastPlanner.Cell> inScanRange = Set.of();

        tracker.pruneUnconfirmed(pos -> inScanRange.contains(pos) || loaded.contains(pos));

        assertTrue(tracker.unconfirmed(SOURCE), "Moving the scan must not make a loaded ghost authoritative");
        assertFalse(covered());

        loaded.remove(SOURCE);
        worldTnt.remove(SOURCE);
        tracker.pruneUnconfirmed(pos -> inScanRange.contains(pos) || loaded.contains(pos));

        assertFalse(tracker.unconfirmed(SOURCE), "An unloaded, non-pending prediction can be forgotten");
        assertFalse(covered());
    }

    private PlacementTracker.Attempt<Block> sendWithClientPrediction(int tick) {
        var attempt = tracker.reserve(SOURCE, tick, Block.TNT);
        tracker.markSent(SOURCE, attempt, tick);
        worldTnt.add(SOURCE);
        return attempt;
    }

    /** Mirrors production eligibility while using the real tracker and ray planner. */
    private boolean covered() {
        Set<TntBlastPlanner.Cell> sources = new HashSet<>(worldTnt);
        sources.addAll(tracker.pendingPositions());
        return TntBlastPlanner.coveredByOthers(TARGET, Set.of(TERRAIN), sources, source -> {
            PlacementTracker.Attempt<Block> pending = tracker.attempt(source);
            boolean eligible = pending != null ? pending.block == Block.TNT
                : loaded.contains(source) && worldTnt.contains(source) && !tracker.unconfirmed(source);
            return eligible ? TntBlastPlanner.coverage(source,
                cell -> cell.equals(TERRAIN) ? DIRT : TntBlastPlanner.Material.AIR) : Set.of();
        });
    }
}

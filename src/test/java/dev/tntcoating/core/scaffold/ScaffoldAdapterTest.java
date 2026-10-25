package dev.tntcoating.core.scaffold;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ScaffoldAdapterTest {
    @Test void realSprintTickMotionReachesTheNextFootpathCellWithin150Milliseconds() {
        var snapshot = ScaffoldAdapter.snapshot(.5, .5, 1.62, -1, .28, 0, 1, 0, .3, 4.5, 0, 150_000_000);
        var plan = new ScaffoldPlanner().plan(ScaffoldPlanner.Preset.Narrow, snapshot, cell -> false);
        assertTrue(plan.candidates().stream().anyMatch(c -> c.cell().equals(new ScaffoldPlanner.Cell(1, -1, 0))),
            "0.28 blocks/client-tick is 5.6 blocks/second; the next cell is needed during this horizon");
    }
    @Test void changingSettingsOrManualSlotInvalidatesTheSameTickIntent() {
        Object world = new Object(), player = new Object();
        var context = new ScaffoldAdapter.DispatchContext(1, 2, world, player, 3, "Narrow");
        assertTrue(context.current(1, 2, world, player, 3, "Narrow"));
        assertFalse(context.current(1, 2, world, player, 3, "Wide"));
        assertFalse(context.current(1, 2, world, player, 4, "Narrow"));
        assertFalse(context.current(1, 2, new Object(), player, 3, "Narrow"));
        assertFalse(context.current(2, 2, world, player, 3, "Narrow"));
    }
    @Test void everyScanPassVisitsEachIndexOnceAndLeavesCapacityForOuterCells() {
        for (int start = 0; start < 8; start++) {
            var order = ScaffoldAdapter.scanOrder(3, start);
            assertEquals(3, new HashSet<>(order).size());
            assertEquals(Math.floorMod(start, 3), order.getFirst());
        }
        int urgent = ScaffoldAdapter.searchBudget(0, 0);
        int holes = ScaffoldAdapter.searchBudget(1, urgent);
        assertTrue(ScaffoldAdapter.searchBudget(2, urgent + holes) >= 30);
    }
    @Test void explicitLayersRemainScheduledAfterTheBaseGetsConfirmed() {
        var layers = new ScaffoldAdapter.Layers();
        var base = new ScaffoldPlanner.Candidate(new ScaffoldPlanner.Cell(1, 63, 0), 0, 10, 0, 0);
        assertEquals(3, layers.expand(List.of(base), 63, 3, false, cell -> true).size());
        var afterConfirmation = layers.expand(List.of(), 63, 3, false, cell -> true);
        assertEquals(2, afterConfirmation.size());
        assertTrue(afterConfirmation.stream().anyMatch(c -> c.cell().y() == 62 && c.tier() >= 2));
        assertTrue(afterConfirmation.stream().anyMatch(c -> c.cell().y() == 61));
        assertTrue(layers.expand(List.of(), 63, 3, false, cell -> false).isEmpty());
    }
    @Test void layerResetCannotCarryTargetsAcrossPresetOrPlaneChanges() {
        var layers = new ScaffoldAdapter.Layers();
        var base = new ScaffoldPlanner.Candidate(new ScaffoldPlanner.Cell(1, 63, 0), 0, 10, 0, 0);
        layers.expand(List.of(base), 63, 2, true, cell -> true);
        assertTrue(layers.expand(List.of(), 64, 2, true, cell -> true).isEmpty());
        layers.expand(List.of(base), 63, 2, true, cell -> true);
        layers.reset();
        assertTrue(layers.expand(List.of(), 63, 2, true, cell -> true).isEmpty());
    }
    @Test void narrowLayersReplaceAbandonedTrajectoryWhileKeepingCurrentConfirmedBases() {
        var layers = new ScaffoldAdapter.Layers();
        var east = new ScaffoldPlanner.Candidate(new ScaffoldPlanner.Cell(2, 63, 0), 1, 10, 0, 0);
        var north = new ScaffoldPlanner.Candidate(new ScaffoldPlanner.Cell(0, 63, -2), 1, 10, 0, 0);
        layers.replace(List.of(east), 63, cell -> true);
        layers.replace(List.of(north), 63, cell -> true);
        var planned = layers.expand(List.of(), 63, 2, false, cell -> true);
        assertEquals(1, planned.size());
        assertEquals(new ScaffoldPlanner.Cell(0, 62, -2), planned.getFirst().cell());
    }
}

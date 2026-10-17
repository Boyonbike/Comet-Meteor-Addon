package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class TntBlastPlannerTest {
    private static final TntBlastPlanner.Cell ORIGIN = new TntBlastPlanner.Cell(0, 0, 0);
    private static final TntBlastPlanner.Material DIRT = new TntBlastPlanner.Material(0.5f, true);
    private static final TntBlastPlanner.Material STONE = new TntBlastPlanner.Material(6, true);

    @Test void emptySpaceDoesNotClaimTerrainCoverage() {
        assertTrue(TntBlastPlanner.coverage(ORIGIN, cell -> TntBlastPlanner.Material.AIR).isEmpty());
    }

    @Test void oneTntCoversNeighbouringAndDiagonalFloorBlocksButNotDistantBlocks() {
        Set<TntBlastPlanner.Cell> coverage = TntBlastPlanner.coverage(ORIGIN,
            cell -> cell.y() == -1 ? DIRT : TntBlastPlanner.Material.AIR);

        assertTrue(coverage.contains(new TntBlastPlanner.Cell(0, -1, 0)));
        assertTrue(coverage.contains(new TntBlastPlanner.Cell(1, -1, 0)));
        assertTrue(coverage.contains(new TntBlastPlanner.Cell(1, -1, 1)));
        assertTrue(coverage.size() > 9, "One TNT should replace several placements across a dirt floor");
        assertFalse(coverage.contains(new TntBlastPlanner.Cell(4, -1, 0)));
        assertTrue(coverage.stream().allMatch(cell -> cell.y() == -1));
    }

    @Test void resistantTerrainHasLessCoverageThanDirt() {
        Set<TntBlastPlanner.Cell> dirt = TntBlastPlanner.coverage(ORIGIN,
            cell -> cell.y() < 0 ? DIRT : TntBlastPlanner.Material.AIR);
        Set<TntBlastPlanner.Cell> stone = TntBlastPlanner.coverage(ORIGIN,
            cell -> cell.y() < 0 ? STONE : TntBlastPlanner.Material.AIR);

        assertTrue(dirt.containsAll(stone));
        assertTrue(dirt.size() > stone.size());
        assertTrue(dirt.contains(new TntBlastPlanner.Cell(2, -1, 0)));
        assertFalse(stone.contains(new TntBlastPlanner.Cell(2, -1, 0)));
    }

    @Test void resistantNonTerrainLikeWaterShieldsBlocksBehindIt() {
        TntBlastPlanner.Cell target = new TntBlastPlanner.Cell(2, 0, 0);
        Function<TntBlastPlanner.Cell, TntBlastPlanner.Material> clear =
            cell -> cell.equals(target) ? DIRT : TntBlastPlanner.Material.AIR;
        assertTrue(TntBlastPlanner.coverage(ORIGIN, clear).contains(target));

        Set<TntBlastPlanner.Cell> shielded = TntBlastPlanner.coverage(ORIGIN,
            cell -> cell.x() == 1 ? new TntBlastPlanner.Material(100, false) : clear.apply(cell));
        assertTrue(shielded.isEmpty());
    }

    @Test void unknownOrOutOfBoundsCellsStopRays() {
        TntBlastPlanner.Cell target = new TntBlastPlanner.Cell(2, 0, 0);
        Set<TntBlastPlanner.Cell> coverage = TntBlastPlanner.coverage(ORIGIN,
            cell -> cell.x() == 1 ? TntBlastPlanner.Material.BLOCKED
                : cell.equals(target) ? DIRT : TntBlastPlanner.Material.AIR);

        assertFalse(coverage.contains(target));
    }

    @Test void translatingAcrossNegativeCoordinatesPreservesCoverage() {
        TntBlastPlanner.Cell translatedOrigin = new TntBlastPlanner.Cell(-20, -30, -40);
        Set<TntBlastPlanner.Cell> positive = TntBlastPlanner.coverage(ORIGIN,
            cell -> cell.y() == -1 ? DIRT : TntBlastPlanner.Material.AIR);
        Set<TntBlastPlanner.Cell> negative = TntBlastPlanner.coverage(translatedOrigin,
            cell -> cell.y() == translatedOrigin.y() - 1 ? DIRT : TntBlastPlanner.Material.AIR);
        Set<TntBlastPlanner.Cell> expected = new HashSet<>();
        for (TntBlastPlanner.Cell cell : positive) {
            expected.add(new TntBlastPlanner.Cell(cell.x() + translatedOrigin.x(),
                cell.y() + translatedOrigin.y(), cell.z() + translatedOrigin.z()));
        }

        assertEquals(expected, negative);
    }

    @Test void primedTntOriginDoesNotShieldOrCountAsTerrain() {
        TntBlastPlanner.Cell target = new TntBlastPlanner.Cell(1, 0, 0);
        Set<TntBlastPlanner.Cell> coverage = TntBlastPlanner.coverage(ORIGIN, cell -> {
            assertNotEquals(ORIGIN, cell, "The primed TNT cell must never be sampled");
            return cell.equals(target) ? DIRT : TntBlastPlanner.Material.AIR;
        });

        assertEquals(Set.of(target), coverage);
    }

    @Test void samplesEachCellOncePerPrediction() {
        Map<TntBlastPlanner.Cell, Integer> calls = new HashMap<>();
        TntBlastPlanner.coverage(ORIGIN, cell -> {
            calls.merge(cell, 1, Integer::sum);
            return TntBlastPlanner.Material.AIR;
        });

        assertFalse(calls.isEmpty());
        assertTrue(calls.values().stream().allMatch(count -> count == 1));
    }

    @Test void ownPendingReservationCannotCancelItsPlacement() {
        Set<TntBlastPlanner.Cell> required = Set.of(new TntBlastPlanner.Cell(0, -1, 0));

        assertFalse(TntBlastPlanner.coveredByOthers(ORIGIN, required, List.of(ORIGIN), source -> {
            fail("A placement's own coverage must not be consulted");
            return required;
        }));
    }

    @Test void targetsWithoutRequiredTerrainAreNotMarkedCovered() {
        assertFalse(TntBlastPlanner.coveredByOthers(ORIGIN, Set.of(), List.of(ORIGIN), source -> Set.of()));
    }

    @Test void sourcesCanJointlyCoverFloorAndWallAtTarget() {
        TntBlastPlanner.Cell floor = new TntBlastPlanner.Cell(0, -1, 0);
        TntBlastPlanner.Cell wall = new TntBlastPlanner.Cell(1, 0, 0);
        TntBlastPlanner.Cell floorSource = new TntBlastPlanner.Cell(-1, 0, 0);
        TntBlastPlanner.Cell wallSource = new TntBlastPlanner.Cell(0, 1, 0);
        Map<TntBlastPlanner.Cell, Set<TntBlastPlanner.Cell>> blasts = Map.of(
            floorSource, Set.of(floor), wallSource, Set.of(wall));

        assertTrue(TntBlastPlanner.coveredByOthers(ORIGIN, Set.of(floor, wall), blasts.keySet(), blasts::get));
    }

    @Test void floorCoverageStillRequiresPlacementWhenWallWouldRemain() {
        TntBlastPlanner.Cell floor = new TntBlastPlanner.Cell(0, -1, 0);
        TntBlastPlanner.Cell wall = new TntBlastPlanner.Cell(1, 0, 0);
        TntBlastPlanner.Cell source = new TntBlastPlanner.Cell(-1, 0, 0);

        assertFalse(TntBlastPlanner.coveredByOthers(ORIGIN, Set.of(floor, wall), List.of(source),
            ignored -> Set.of(floor)));
    }

    @Test void removingOrCancellingSourceReopensItsRequiredTerrain() {
        TntBlastPlanner.Cell floor = new TntBlastPlanner.Cell(0, -1, 0);
        TntBlastPlanner.Cell wall = new TntBlastPlanner.Cell(1, 0, 0);
        TntBlastPlanner.Cell floorSource = new TntBlastPlanner.Cell(-1, 0, 0);
        TntBlastPlanner.Cell wallSource = new TntBlastPlanner.Cell(0, 1, 0);
        Map<TntBlastPlanner.Cell, Set<TntBlastPlanner.Cell>> blasts = Map.of(
            floorSource, Set.of(floor), wallSource, Set.of(wall));
        Set<TntBlastPlanner.Cell> sources = new HashSet<>(blasts.keySet());
        Set<TntBlastPlanner.Cell> required = Set.of(floor, wall);
        assertTrue(TntBlastPlanner.coveredByOthers(ORIGIN, required, sources, blasts::get));

        sources.remove(wallSource);

        assertFalse(TntBlastPlanner.coveredByOthers(ORIGIN, required, sources, blasts::get));
    }

    @Test void greedyFloorPlacementSavesTntAndStillCoversEveryOriginalExposedBlock() {
        Set<TntBlastPlanner.Cell> terrain = new LinkedHashSet<>();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) terrain.add(new TntBlastPlanner.Cell(x, -1, z));
        }
        Map<TntBlastPlanner.Cell, Set<TntBlastPlanner.Cell>> cache = new HashMap<>();
        Function<TntBlastPlanner.Cell, Set<TntBlastPlanner.Cell>> coverage = source -> cache.computeIfAbsent(source,
            origin -> TntBlastPlanner.coverage(origin,
                cell -> terrain.contains(cell) ? DIRT : TntBlastPlanner.Material.AIR));
        Set<TntBlastPlanner.Cell> sources = new LinkedHashSet<>();
        for (TntBlastPlanner.Cell exposed : terrain) {
            TntBlastPlanner.Cell target = new TntBlastPlanner.Cell(exposed.x(), exposed.y() + 1, exposed.z());
            if (!TntBlastPlanner.coveredByOthers(target, Set.of(exposed), sources, coverage)) sources.add(target);
        }

        Set<TntBlastPlanner.Cell> removed = new HashSet<>();
        sources.forEach(source -> removed.addAll(coverage.apply(source)));
        assertTrue(sources.size() * 2 < terrain.size(), "A dirt floor should need substantially less TNT");
        assertTrue(removed.containsAll(terrain), "Every original exposed terrain block must remain covered");
    }
}

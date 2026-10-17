package dev.tntcoating.core;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StorageEspIndexTest {
    @Test void cellsUseFloorCoordinatesAndPreserveExtremePositions() {
        StorageEspIndex<String> index = new StorageEspIndex<>();
        int[] coordinates = {Integer.MIN_VALUE, -33_554_432, -2048, -9, -8, -1, 0, 7, 8, 2047, 33_554_431, Integer.MAX_VALUE};
        for (int coordinate : coordinates) {
            BlockPos pos = new BlockPos(coordinate, coordinate, coordinate);
            var key = StorageEspIndex.CellKey.of(pos);
            assertEquals(Math.floorDiv(coordinate, 8), key.x());
            assertEquals(Math.floorDiv(coordinate, 8), key.y());
            assertEquals(Math.floorDiv(coordinate, 8), key.z());
            assertEquals(coordinate - Math.floorMod(coordinate, 8), key.minX());
            assertEquals(key.minX(), key.minY());
            assertEquals(key.minX(), key.minZ());
            index.put(pos, Integer.toString(coordinate));
        }
        for (int coordinate : coordinates) {
            BlockPos pos = new BlockPos(coordinate, coordinate, coordinate);
            assertTrue(index.cell(StorageEspIndex.CellKey.of(pos)).values().contains(Integer.toString(coordinate)));
            index.remove(pos);
        }
        assertTrue(index.cells().stream().allMatch(cell -> cell.size() == 0));
    }

    @Test void eachCellHasAtMost512PositionsAndAdjacentCellsAreIndependent() {
        StorageEspIndex<Integer> index = new StorageEspIndex<>();
        for (int x = -8; x < 8; x++) {
            for (int y = -8; y < 8; y++) {
                for (int z = -8; z < 8; z++) index.put(new BlockPos(x, y, z), x * 256 + y * 16 + z);
            }
        }
        assertEquals(8, index.cells().size());
        for (var cell : index.cells()) {
            assertEquals(512, cell.size());
            assertEquals(512, cell.revision());
        }
        assertEquals(8, drain(index).size());
    }

    @Test void queueDeduplicatesChangesAndPreservesFirstDirtyOrder() {
        StorageEspIndex<String> index = new StorageEspIndex<>();
        BlockPos first = new BlockPos(-1, 0, 0);
        BlockPos second = new BlockPos(16, 0, 0);
        BlockPos third = new BlockPos(0, 24, 0);
        var firstKey = StorageEspIndex.CellKey.of(first);
        var secondKey = StorageEspIndex.CellKey.of(second);
        var thirdKey = StorageEspIndex.CellKey.of(third);
        index.put(first, "a");
        index.put(second, "b");
        index.put(first, "updated");
        index.dirty(secondKey);
        index.put(third, "c");
        assertEquals(List.of(firstKey, secondKey, thirdKey), drain(index));
        index.dirty(firstKey);
        assertEquals(firstKey, index.pollDirty());
        assertNull(index.pollDirty());
    }

    @Test void equalValuesAndMissingRemovalsDoNotInvalidateBatches() {
        StorageEspIndex<String> index = new StorageEspIndex<>();
        BlockPos pos = new BlockPos(1, 2, 3);
        var key = StorageEspIndex.CellKey.of(pos);
        index.put(pos, new String("same"));
        assertEquals(key, index.pollDirty());
        long revision = index.cell(key).revision();
        index.put(pos, new String("same"));
        index.remove(new BlockPos(2, 2, 3));
        index.remove(new BlockPos(32, 2, 3));
        assertEquals(revision, index.cell(key).revision());
        assertNull(index.pollDirty());
        index.remove(pos);
        assertEquals(revision + 1, index.cell(key).revision());
        assertEquals(0, index.cell(key).size());
        assertEquals(key, index.pollDirty());
        index.remove(pos);
        assertNull(index.pollDirty());
    }

    @Test void mutationsAndExplicitInvalidationsSupersedeAnInProgressBuild() {
        StorageEspIndex<String> index = new StorageEspIndex<>();
        BlockPos pos = new BlockPos(1, 2, 3);
        var key = StorageEspIndex.CellKey.of(pos);
        index.put(pos, "original");
        var cell = index.cell(index.pollDirty());
        long buildingRevision = cell.revision();
        index.put(pos, "changed");
        assertTrue(cell.revision() > buildingRevision);
        assertEquals(key, index.pollDirty());
        buildingRevision = cell.revision();
        index.dirty(key);
        assertTrue(cell.revision() > buildingRevision);
        buildingRevision = cell.revision();
        index.dirtyAll();
        assertTrue(cell.revision() > buildingRevision);
        assertEquals(List.of(key), drain(index));
    }

    @Test void chunkUnloadRemovesOnlyMatchingCellsAcrossHeightsAndKeepsCleanupQueued() {
        StorageEspIndex<String> index = new StorageEspIndex<>();
        List<StorageEspIndex.CellKey> removed = new ArrayList<>();
        List<StorageEspIndex.CellKey> kept = new ArrayList<>();
        for (int x : new int[] {-32, -17, -16, -9, -8, -1, 0, 15}) {
            for (int z : new int[] {-16, -1, 0, 15}) {
                for (int y : new int[] {-64, -1, 0, 8, 319}) {
                    BlockPos pos = new BlockPos(x, y, z);
                    var key = StorageEspIndex.CellKey.of(pos);
                    index.put(pos, pos.toString());
                    if (x >> 4 == -1 && z >> 4 == -1) removed.add(key);
                    else kept.add(key);
                }
            }
        }
        drain(index);
        var oldCell = index.cell(removed.getFirst());
        long revision = oldCell.revision();
        index.removeChunk(-1, -1);
        for (var key : removed) assertNull(index.cell(key));
        for (var key : kept) assertNotNull(index.cell(key));
        assertTrue(oldCell.revision() > revision);
        assertEquals(0, oldCell.size());
        assertEquals(new HashSet<>(removed), new HashSet<>(drain(index)));
        index.removeChunk(-1, -1);
        assertNull(index.pollDirty());
    }

    @Test void stableLargeStashReconciliationDoesNotRequestAdditionalModelBuilds() {
        StorageEspIndex<String> index = new StorageEspIndex<>();
        List<BlockPos> positions = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            BlockPos pos = new BlockPos(i % 100 - 50, i / 100 % 10 - 5, i / 1_000 - 5);
            positions.add(pos);
            index.put(pos, "storage-" + i);
        }
        int modelBuilds = drain(index).size();
        assertEquals(10_000, index.cells().stream().mapToInt(StorageEspIndex.Cell::size).sum());
        long revisionSum = index.cells().stream().mapToLong(StorageEspIndex.Cell::revision).sum();
        for (int pass = 0; pass < 20; pass++) {
            for (int i = 0; i < positions.size(); i++) index.put(positions.get(i), "storage-" + i);
            modelBuilds += drain(index).size();
        }
        assertEquals(index.cells().size(), modelBuilds);
        assertEquals(revisionSum, index.cells().stream().mapToLong(StorageEspIndex.Cell::revision).sum());
    }

    @Test void unloadingAndReloadingTheSameCellKeepsOneDirtyEntryAndReplacesCellIdentity() {
        StorageEspIndex<String> index = new StorageEspIndex<>();
        BlockPos pos = new BlockPos(-1, 64, -1);
        var key = StorageEspIndex.CellKey.of(pos);
        index.put(pos, "before unload");
        var previous = index.cell(index.pollDirty());
        long buildingRevision = previous.revision();

        index.removeChunk(-1, -1);
        index.put(pos, "after reload");
        var current = index.cell(key);
        assertNotSame(previous, current);
        // Revisions describe one cell instance, so a builder must also check cell identity.
        assertEquals(buildingRevision, current.revision());
        assertTrue(previous.revision() > buildingRevision);
        assertEquals(List.of("after reload"), new ArrayList<>(current.values()));
        assertEquals(List.of(key), drain(index));
    }

    @Test void collectionViewsCannotBypassInvalidationAndClearResetsAllWork() {
        StorageEspIndex<String> index = new StorageEspIndex<>();
        BlockPos pos = new BlockPos(0, 0, 0);
        var key = StorageEspIndex.CellKey.of(pos);
        index.put(pos, "storage");
        assertThrows(UnsupportedOperationException.class, () -> index.cells().clear());
        assertThrows(UnsupportedOperationException.class, () -> index.cell(key).values().clear());
        index.clear();
        assertTrue(index.cells().isEmpty());
        assertNull(index.cell(key));
        assertNull(index.pollDirty());
        index.removeChunk(0, 0);
        assertNull(index.pollDirty());
        index.put(pos, "fresh");
        assertEquals(1, index.cell(key).size());
        assertEquals(List.of(key), drain(index));
    }

    private static List<StorageEspIndex.CellKey> drain(StorageEspIndex<?> index) {
        List<StorageEspIndex.CellKey> keys = new ArrayList<>();
        StorageEspIndex.CellKey key;
        while ((key = index.pollDirty()) != null) keys.add(key);
        return keys;
    }
}

package dev.tntcoating.core;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class TntRedstoneSafetyTest {
    private static final BlockPos TARGET = new BlockPos(10, 64, -10);

    @BeforeAll static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void ordinaryTerrainAndNormalContainersRemainUsable() {
        Grid world = new Grid();
        world.blocks.put(TARGET.below(), Blocks.STONE.defaultBlockState());
        world.blocks.put(TARGET.east(), Blocks.CHEST.defaultBlockState());
        world.blocks.put(TARGET.west(), Blocks.BARREL.defaultBlockState());

        assertTrue(world.canPlace(TARGET));
    }

    @Test void livePowerRejectsWithoutANearbyVisibleComponent() {
        Grid world = new Grid();
        // The game's power callback also accounts for a strongly powered support block.
        world.blocks.put(TARGET.below(), Blocks.STONE.defaultBlockState());
        world.powered.add(TARGET);

        assertFalse(world.canPlace(TARGET));
    }

    @Test void inactiveControlsAreUnsafeEvenWhenTheirOutputIsZero() {
        for (Block block : new Block[] {Blocks.LEVER, Blocks.STONE_BUTTON, Blocks.OAK_BUTTON,
            Blocks.STONE_PRESSURE_PLATE, Blocks.OAK_PRESSURE_PLATE, Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE,
            Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE, Blocks.DAYLIGHT_DETECTOR, Blocks.SCULK_SENSOR,
            Blocks.CALIBRATED_SCULK_SENSOR, Blocks.TARGET, Blocks.REDSTONE_BLOCK}) {
            Grid world = new Grid();
            world.blocks.put(TARGET.east(), block.defaultBlockState());

            assertFalse(world.canPlace(TARGET), () -> "Missed component: " + block);
        }
    }

    @Test void wireInTheTargetCellCannotBeReplacedWithTnt() {
        Grid world = new Grid();
        world.blocks.put(TARGET, Blocks.REDSTONE_WIRE.defaultBlockState()
            .setValue(BlockStateProperties.POWER, 0));

        assertFalse(world.canPlace(TARGET));
    }

    @Test void standingAndWallTorchesAreUnsafeInBothLitStates() {
        for (Block block : new Block[] {Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH}) {
            for (boolean lit : new boolean[] {false, true}) {
                Grid world = new Grid();
                world.blocks.put(TARGET.above(), block.defaultBlockState()
                    .setValue(BlockStateProperties.LIT, lit));

                assertFalse(world.canPlace(TARGET), () -> "Missed torch: " + block + ", lit=" + lit);
            }
        }
    }

    @Test void unpoweredObserversAndDiodesAreUnsafe() {
        for (Block block : new Block[] {Blocks.OBSERVER, Blocks.REPEATER, Blocks.COMPARATOR}) {
            Grid world = new Grid();
            world.blocks.put(TARGET.north(), block.defaultBlockState()
                .setValue(BlockStateProperties.POWERED, false));

            assertFalse(world.canPlace(TARGET), () -> "Missed inactive component: " + block);
        }
    }

    @Test void closedTrappedChestsAreUnsafe() {
        Grid world = new Grid();
        world.blocks.put(TARGET.south(), Blocks.TRAPPED_CHEST.defaultBlockState());

        assertFalse(world.canPlace(TARGET));
    }

    @Test void inactiveRailsAndTripwireAreUnsafe() {
        for (Block block : new Block[] {Blocks.POWERED_RAIL, Blocks.ACTIVATOR_RAIL,
            Blocks.DETECTOR_RAIL, Blocks.TRIPWIRE, Blocks.TRIPWIRE_HOOK}) {
            Grid world = new Grid();
            world.blocks.put(TARGET.west(), block.defaultBlockState()
                .setValue(BlockStateProperties.POWERED, false));

            assertFalse(world.canPlace(TARGET), () -> "Missed inactive component: " + block);
        }
    }

    @Test void bufferIncludesVerticalOffsetsAndDiagonalCorners() {
        for (int x : new int[] {-2, 0, 2}) {
            for (int y : new int[] {-2, 0, 2}) {
                for (int z : new int[] {-2, 0, 2}) {
                    Grid world = new Grid();
                    BlockPos component = TARGET.offset(x, y, z);
                    world.blocks.put(component, Blocks.LEVER.defaultBlockState());

                    assertFalse(world.canPlace(TARGET), () -> "Missed buffer position: " + component);
                }
            }
        }
    }

    @Test void componentsBeyondTheBufferDoNotStopOrdinaryCoating() {
        for (BlockPos component : new BlockPos[] {TARGET.offset(3, 0, 0), TARGET.offset(-3, 0, 0),
            TARGET.offset(0, 3, 0), TARGET.offset(0, -3, 0), TARGET.offset(0, 0, 3),
            TARGET.offset(0, 0, -3), TARGET.offset(3, 3, 3)}) {
            Grid world = new Grid();
            world.blocks.put(component, Blocks.OBSERVER.defaultBlockState());

            assertTrue(world.canPlace(TARGET), () -> "Component outside buffer: " + component);
        }
    }

    @Test void unknownCellsInsideTheBufferFailClosed() {
        Grid world = new Grid();
        world.unloaded.add(TARGET.offset(-2, 2, -2));

        assertFalse(world.canPlace(TARGET));

        world.unloaded.clear();
        assertTrue(world.canPlace(TARGET), "Loading the buffer should allow a fresh check");
    }

    @Test void cellsOutsideBuildHeightAreSkippedBeforeReadingWorldState() {
        for (int y : new int[] {0, 319}) {
            BlockPos target = new BlockPos(10, y, -10);
            Predicate<BlockPos> outside = pos -> pos.getY() < 0 || pos.getY() >= 320;

            assertTrue(TntRedstoneSafety.canPlace(true, target, outside, pos -> {
                assertFalse(outside.test(pos), "Outside-height cell should not be queried");
                return Blocks.AIR.defaultBlockState();
            }, pos -> false));
        }
    }

    @Test void freshChecksNoticeNewComponentsRemovedComponentsAndPowerChanges() {
        Grid world = new Grid();
        BlockPos observer = TARGET.east();
        assertTrue(world.canPlace(TARGET));

        world.blocks.put(observer, Blocks.OBSERVER.defaultBlockState());
        assertFalse(world.canPlace(TARGET));

        world.blocks.remove(observer);
        assertTrue(world.canPlace(TARGET));

        world.powered.add(TARGET);
        assertFalse(world.canPlace(TARGET));
    }

    @Test void nonTntCoatingSkipsAllRedstoneWorldQueries() {
        assertTrue(TntRedstoneSafety.canPlace(false, TARGET,
            pos -> { throw new AssertionError("Unexpected build-height query"); },
            pos -> { throw new AssertionError("Unexpected block-state query"); },
            pos -> { throw new AssertionError("Unexpected power query"); }));
    }

    private static final class Grid {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final Set<BlockPos> unloaded = new HashSet<>();
        final Set<BlockPos> powered = new HashSet<>();

        boolean canPlace(BlockPos target) {
            return TntRedstoneSafety.canPlace(true, target, pos -> false,
                pos -> unloaded.contains(pos) ? null : blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()),
                powered::contains);
        }
    }
}

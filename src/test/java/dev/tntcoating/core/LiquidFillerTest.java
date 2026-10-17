package dev.tntcoating.core;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
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

class LiquidFillerTest {
    private static final BlockPos TARGET = new BlockPos(15, 64, 15);

    @BeforeAll static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void isolatedPlainWaterSourceCanBeFilled() {
        Grid world = new Grid();
        world.blocks.put(TARGET.below(), Blocks.STONE.defaultBlockState());

        assertTrue(world.fillable(TARGET));
    }

    @Test void targetMustBePlainSourceWater() {
        for (BlockState state : new BlockState[] {
            Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
            Blocks.LAVA.defaultBlockState(), flowingWater(1), flowingWater(7), flowingWater(8),
            waterloggedStairs(), Blocks.KELP.defaultBlockState(), Blocks.KELP_PLANT.defaultBlockState(),
            Blocks.SEAGRASS.defaultBlockState(), Blocks.TALL_SEAGRASS.defaultBlockState()
        }) {
            Grid world = new Grid();
            world.blocks.put(TARGET, state);

            assertFalse(world.fillable(TARGET), () -> "Incorrectly accepted target: " + state);
        }
    }

    @Test void sourceInAnyFaceDirectionPreventsFilling() {
        for (Direction direction : Direction.values()) {
            Grid world = new Grid();
            world.blocks.put(TARGET.relative(direction), Blocks.WATER.defaultBlockState());

            assertFalse(world.fillable(TARGET), () -> "Missed adjacent source: " + direction);
        }
    }

    @Test void waterloggedAndPlantSourcesAlsoPreventFilling() {
        for (BlockState source : new BlockState[] {
            waterloggedStairs(), Blocks.KELP.defaultBlockState(), Blocks.KELP_PLANT.defaultBlockState(),
            Blocks.SEAGRASS.defaultBlockState(), Blocks.TALL_SEAGRASS.defaultBlockState()
        }) {
            assertTrue(source.getFluidState().isSource(), () -> "Expected source fixture: " + source);
            for (Direction direction : Direction.values()) {
                Grid world = new Grid();
                world.blocks.put(TARGET.relative(direction), source);

                assertFalse(world.fillable(TARGET),
                    () -> "Missed adjacent source: " + source + " at " + direction);
            }
        }
    }

    @Test void flowingWaterInEveryFaceDirectionDoesNotJoinSources() {
        for (int level : new int[] {1, 7, 8, 15}) {
            Grid world = new Grid();
            BlockState flow = flowingWater(level);
            assertFalse(flow.getFluidState().isSource(), () -> "Expected flowing fixture: " + flow);
            for (Direction direction : Direction.values()) {
                world.blocks.put(TARGET.relative(direction), flow);
            }

            assertTrue(world.fillable(TARGET), () -> "Flowing water should be allowed: " + level);
        }
    }

    @Test void diagonalAndMoreDistantSourcesDoNotJoinTarget() {
        Grid world = new Grid();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (Math.abs(x) + Math.abs(y) + Math.abs(z) >= 2) {
                        world.blocks.put(TARGET.offset(x, y, z), Blocks.WATER.defaultBlockState());
                    }
                }
            }
        }
        for (Direction direction : Direction.values()) {
            world.blocks.put(TARGET.relative(direction, 2), Blocks.WATER.defaultBlockState());
        }

        assertTrue(world.fillable(TARGET));
    }

    @Test void neighboringLavaAndDryWaterloggableBlocksDoNotJoinTarget() {
        Grid world = new Grid();
        world.blocks.put(TARGET.east(), Blocks.LAVA.defaultBlockState());
        world.blocks.put(TARGET.west(), Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.WATERLOGGED, false));

        assertTrue(world.fillable(TARGET));
    }

    @Test void unknownTargetOrAnyInWorldNeighborPreventsFilling() {
        Grid world = new Grid();
        world.unloaded.add(TARGET);
        assertFalse(world.fillable(TARGET));

        for (Direction direction : Direction.values()) {
            world.unloaded.clear();
            world.unloaded.add(TARGET.relative(direction));

            assertFalse(world.fillable(TARGET), () -> "Unknown neighbor allowed: " + direction);
        }
    }

    @Test void unknownNeighborAcrossChunkBoundaryFailsUntilLoaded() {
        for (BlockPos edge : new BlockPos[] {new BlockPos(15, 64, 15), new BlockPos(0, 64, 0),
            new BlockPos(-16, 64, -16), new BlockPos(-1, 64, -1)}) {
            for (Direction direction : new Direction[] {Direction.NORTH, Direction.SOUTH,
                Direction.EAST, Direction.WEST}) {
                BlockPos neighbor = edge.relative(direction);
                if ((edge.getX() >> 4) == (neighbor.getX() >> 4)
                    && (edge.getZ() >> 4) == (neighbor.getZ() >> 4)) continue;
                Grid world = new Grid();
                world.blocks.put(edge, Blocks.WATER.defaultBlockState());
                world.unloaded.add(neighbor);

                assertFalse(world.fillable(edge), () -> "Unknown chunk neighbor allowed: " + neighbor);
                world.unloaded.clear();
                assertTrue(world.fillable(edge), "Loading the neighbor permits a fresh check");
            }
        }
    }

    @Test void freshChecksNoticeSourceFlowAndTargetChanges() {
        Grid world = new Grid();
        assertTrue(world.fillable(TARGET));

        world.blocks.put(TARGET.east(), Blocks.WATER.defaultBlockState());
        assertFalse(world.fillable(TARGET));

        world.blocks.put(TARGET.east(), flowingWater(1));
        assertTrue(world.fillable(TARGET));

        world.blocks.put(TARGET, flowingWater(1));
        assertFalse(world.fillable(TARGET));

        world.blocks.put(TARGET, Blocks.WATER.defaultBlockState());
        assertTrue(world.fillable(TARGET));

        world.blocks.put(TARGET, Blocks.TNT.defaultBlockState());
        assertFalse(world.fillable(TARGET));
    }

    @Test void outsideHeightNeighborsAreIgnoredBeforeReadingWorldState() {
        Predicate<BlockPos> outside = pos -> pos.getY() < -64 || pos.getY() >= 320;
        for (int y : new int[] {-64, 319}) {
            BlockPos target = new BlockPos(0, y, 0);

            assertTrue(LiquidFiller.isolatedWaterSource(target, outside, pos -> {
                assertFalse(outside.test(pos), "Outside-height cell should never be queried");
                return pos.equals(target) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
            }));
        }
    }

    @Test void outsideHeightTargetIsRejectedBeforeReadingWorldState() {
        Predicate<BlockPos> outside = pos -> pos.getY() < -64 || pos.getY() >= 320;
        for (int y : new int[] {-65, 320}) {
            assertFalse(LiquidFiller.isolatedWaterSource(new BlockPos(0, y, 0), outside,
                pos -> { throw new AssertionError("Outside-height target should never be queried"); }));
        }
    }

    private static BlockState flowingWater(int level) {
        return Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, level);
    }

    private static BlockState waterloggedStairs() {
        return Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
    }

    private static final class Grid {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final Set<BlockPos> unloaded = new HashSet<>();

        Grid() {
            blocks.put(TARGET, Blocks.WATER.defaultBlockState());
        }

        boolean fillable(BlockPos target) {
            return LiquidFiller.isolatedWaterSource(target, pos -> false,
                pos -> unloaded.contains(pos) ? null : blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()));
        }
    }
}

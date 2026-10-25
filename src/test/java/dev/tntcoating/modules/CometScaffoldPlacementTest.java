package dev.tntcoating.modules;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CometScaffoldPlacementTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void boundedRaysNeverOverdrawSharedBudget() {
        var rays = new CometScaffoldPlacement.Rays(2);
        assertTrue(rays.take()); assertTrue(rays.take()); assertFalse(rays.take());
        assertEquals(2, rays.used()); assertEquals(0, rays.remaining());
        assertFalse(new CometScaffoldPlacement.Rays(0).take());
        assertThrows(IllegalArgumentException.class, () -> new CometScaffoldPlacement.Rays(-1));
    }

    @Test void heldThenOffhandThenStablePreferredThenLargestValidStack() {
        ItemStack[] hotbar = emptyHotbar();
        hotbar[1] = new ItemStack(Items.STONE, 5);
        hotbar[2] = new ItemStack(Items.DIRT, 40);
        hotbar[4] = new ItemStack(Items.GLASS, 12);
        var off = new ItemStack(Items.COBBLESTONE, 7);
        assertEquals(1, choose(hotbar, off, 1, true, 4).slot());
        assertEquals(InteractionHand.OFF_HAND, choose(hotbar, off, 0, true, 4).hand());
        assertEquals(4, choose(hotbar, ItemStack.EMPTY, 0, true, 4).slot());
        assertEquals(2, choose(hotbar, ItemStack.EMPTY, 0, true, 8).slot());
        assertNull(choose(hotbar, ItemStack.EMPTY, 0, false, 4));
        assertEquals(-1, choose(hotbar, off, 0, false, 4).slot());
    }

    @Test void fullBlockPolicyRejectsFallingPartialAndItemsAndDenialWins() {
        assertTrue(CometScaffoldPlacement.acceptable(new ItemStack(Items.GLASS), List.of(), List.of(), true));
        for (var item : List.of(Items.SAND, Items.GRAVEL, Items.OAK_SLAB, Items.TORCH, Items.STICK))
            assertFalse(CometScaffoldPlacement.acceptable(new ItemStack(item), List.of(), List.of(), true), item.toString());
        assertTrue(CometScaffoldPlacement.acceptable(new ItemStack(Items.OAK_SLAB), List.of(), List.of(), false));
        assertFalse(CometScaffoldPlacement.acceptable(new ItemStack(Items.STONE), List.of(Blocks.DIRT), List.of(), true));
        assertFalse(CometScaffoldPlacement.acceptable(new ItemStack(Items.STONE), List.of(Blocks.STONE), List.of(Blocks.STONE), true));
    }

    @Test void outlineSamplesKeepPartialGeometryAndAllSixFaces() {
        var pos = new BlockPos(3, 20, -4);
        var slab = Blocks.OAK_SLAB.defaultBlockState().getShape(EmptyBlockGetter.INSTANCE, pos);
        for (Direction face : Direction.values()) {
            var points = CometScaffoldPlacement.faceSamples(pos, slab, face);
            assertFalse(points.isEmpty());
            for (var hit : points) {
                assertEquals(face, hit.getDirection()); assertEquals(pos, hit.getBlockPos());
                assertTrue(hit.getLocation().y >= 20 && hit.getLocation().y <= 20.5);
            }
        }
        assertEquals(20.5, CometScaffoldPlacement.faceSamples(pos, slab, Direction.UP).getFirst().getLocation().y);
        var snow = Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, 2);
        assertEquals(.25, CometScaffoldPlacement.faceSamples(BlockPos.ZERO,
            snow.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), Direction.UP).getFirst().getLocation().y);
        assertEquals(.125, snow.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).max(Direction.Axis.Y));
    }

    @Test void supportRequiresRealFootprintAtFeetAndRejectsCliffs() {
        AABB body = new AABB(.2, 1, .2, .8, 2.8, .8);
        assertEquals(1, CometScaffoldPlacement.supportHeight(body, List.of(new AABB(0, 0, 0, 1, 1, 1)), .6));
        assertTrue(Double.isNaN(CometScaffoldPlacement.supportHeight(body, List.of(new AABB(1, 0, 0, 2, 1, 1)), .6)));
        assertTrue(Double.isNaN(CometScaffoldPlacement.supportHeight(body, List.of(new AABB(0, -1, 0, 1, 0, 1)), .6)));
    }

    @Test void stepRequiresVanillaHeightAndClearRaisedBody() {
        AABB body = new AABB(.2, 1, .2, .8, 2.8, .8);
        AABB slab = new AABB(0, 0, 0, 1, 1.5, 1);
        assertEquals(1.5, CometScaffoldPlacement.supportHeight(body, List.of(slab), .6));
        assertTrue(Double.isNaN(CometScaffoldPlacement.supportHeight(body, List.of(slab), .4)));
        assertTrue(Double.isNaN(CometScaffoldPlacement.supportHeight(body,
            List.of(slab, new AABB(0, 3, 0, 1, 4, 1)), .6)));
    }

    @Test void orientationUsesActualFacePoint() {
        assertArrayEquals(new float[] {-90, 0}, CometScaffoldPlacement.angles(Vec3.ZERO, new Vec3(2, 0, 0)), 1e-4f);
        assertArrayEquals(new float[] {0, 45}, CometScaffoldPlacement.angles(Vec3.ZERO, new Vec3(0, -2, 2)), 1e-4f);
    }

    @Test void repeatedSearchSlicesReachLateSamplesAndKeepTargetCursorsIndependent() {
        var resolver = new CometScaffoldPlacement();
        var visited = new java.util.HashSet<Integer>();
        for (int tick = 0; tick < 6; tick++) {
            for (int sample = 0; sample < 16; sample++) visited.add(resolver.nextSample(BlockPos.ZERO, 96));
            assertEquals(tick, resolver.nextSample(BlockPos.ZERO.east(), 96));
        }
        assertEquals(96, visited.size());
        resolver.reset();
        assertEquals(0, resolver.nextSample(BlockPos.ZERO, 96));
    }

    @Test void cursorEvictionBoundsGeometryWithoutResettingOtherLiveTargets() {
        var resolver = new CometScaffoldPlacement();
        resolver.nextSample(BlockPos.ZERO, 10);
        resolver.nextSample(BlockPos.ZERO, 10);
        for (int x = 1; x <= 256; x++) resolver.nextSample(new BlockPos(x, 0, 0), 10);
        assertEquals(0, resolver.nextSample(BlockPos.ZERO, 10));
        assertEquals(1, resolver.nextSample(new BlockPos(256, 0, 0), 10));
    }

    @Test void nonCredibleFloorMayStillObstructRaisedBody() {
        AABB body = new AABB(.2, 1, .2, .8, 2.8, .8);
        AABB floor = new AABB(0, 0, 0, 1, 1.5, 1);
        assertTrue(Double.isNaN(CometScaffoldPlacement.supportHeight(body, List.of(), List.of(floor), .6)));
        assertTrue(Double.isNaN(CometScaffoldPlacement.supportHeight(body, List.of(floor),
            List.of(floor, new AABB(0, 3, 0, 1, 3.125, 1)), .6)));
    }

    @Test void stackBlockStateComponentsChangeActualPlacementCollision() {
        ItemStack slab = new ItemStack(Items.OAK_SLAB);
        slab.set(net.minecraft.core.component.DataComponents.BLOCK_STATE,
            new net.minecraft.world.item.component.BlockItemStateProperties(java.util.Map.of("type", "top")));
        var result = CometScaffoldPlacement.withStackState(Blocks.OAK_SLAB.defaultBlockState(), slab);
        assertEquals(.5, result.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).min(Direction.Axis.Y));
        assertEquals(1, result.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).max(Direction.Axis.Y));
    }

    @Test void finalPlacementStateCannotFallBackToRestoredVisualContext() throws Exception {
        // This compatibility guard catches the old live-pose fallback in placementContext.
        // Both search and verification must construct only the context carrying verified Hit aim.
        var node = new org.objectweb.asm.tree.ClassNode();
        try (var bytes = CometScaffoldPlacement.class.getResourceAsStream("CometScaffoldPlacement.class")) {
            assertNotNull(bytes);
            new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
        }
        var method = node.methods.stream().filter(m -> m.name.equals("placementContext")).findFirst().orElseThrow();
        var constructors = new java.util.ArrayList<String>();
        for (var instruction : method.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.TypeInsnNode type && type.getOpcode() == org.objectweb.asm.Opcodes.NEW)
                constructors.add(type.desc);
        }
        assertEquals(List.of("dev/tntcoating/modules/CometScaffoldPlacement$AimedContext"), constructors,
            "Final state verification must use the hit aim even after the bridge restores visual pose");
    }

    @Test void verifiedHitYawSelectsDirectionalStairGeometryEvenWhenVisualFacingDiffers() {
        var material = new CometScaffoldPlacement.Material(0, InteractionHand.MAIN_HAND, Items.OAK_STAIRS, Blocks.OAK_STAIRS);
        var click = new net.minecraft.world.phys.BlockHitResult(new Vec3(.5, 0, .5), Direction.UP, BlockPos.ZERO.below(), false);
        var sent = new CometScaffoldPlacement.Hit(BlockPos.ZERO, click, material, 180, 30, false, false);
        var visual = new CometScaffoldPlacement.Hit(BlockPos.ZERO, click, material, 0, 30, false, false);
        assertEquals(Direction.NORTH, CometScaffoldPlacement.placementHorizontal(sent));
        assertEquals(Direction.SOUTH, CometScaffoldPlacement.placementHorizontal(visual));
        // Vanilla stairs use context horizontal direction directly. Real bootstrap-provided states
        // show why substituting the restored visual yaw would verify different body collision.
        var sentState = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,
            CometScaffoldPlacement.placementHorizontal(sent));
        var visualState = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,
            CometScaffoldPlacement.placementHorizontal(visual));
        AABB bodyProbe = new AABB(.25, .7, .1, .75, .9, .2);
        assertTrue(sentState.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs().stream().anyMatch(bodyProbe::intersects));
        assertFalse(visualState.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs().stream().anyMatch(bodyProbe::intersects));
    }

    private static ItemStack[] emptyHotbar() {
        ItemStack[] stacks = new ItemStack[9]; java.util.Arrays.fill(stacks, ItemStack.EMPTY); return stacks;
    }
    private static CometScaffoldPlacement.Material choose(ItemStack[] hotbar, ItemStack offhand, int selected, boolean auto, int preferred) {
        return CometScaffoldPlacement.chooseMaterial(hotbar, offhand, selected, List.of(), List.of(), true, auto, preferred);
    }
}

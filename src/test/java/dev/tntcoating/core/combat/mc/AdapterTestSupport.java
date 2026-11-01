/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.Vec;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/** Test-only pinned-world scaffolding: bootstrap, an in-memory read-only {@link BlockGetter}, conversions. */
final class AdapterTestSupport {
    private AdapterTestSupport() {
    }

    /** One-time pinned registry bootstrap; no game or server is started. */
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    static final class FakeWorld implements BlockGetter {
        private final Map<BlockPos, BlockState> states = new HashMap<>();

        void set(int x, int y, int z, BlockState state) {
            states.put(new BlockPos(x, y, z), state);
        }

        BlockState raw(int x, int y, int z) {
            return states.getOrDefault(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return raw(pos.getX(), pos.getY(), pos.getZ());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }

    static Vec3 vec3(Vec vec) {
        return new Vec3(vec.x(), vec.y(), vec.z());
    }

    static AABB aabb(Box box) {
        return new AABB(box.min().x(), box.min().y(), box.min().z(),
            box.max().x(), box.max().y(), box.max().z());
    }

    /** The player-shaped box (0.6 wide, 1.8 tall) standing on {@code feet}. */
    static Box playerBox(Vec feet) {
        return new Box(feet.add(-0.3, 0.0, -0.3), feet.add(0.3, 1.8, 0.3));
    }

    static Box playerBox(double x, double y, double z) {
        return playerBox(new Vec(x, y, z));
    }

    static Cell cell(int x, int y, int z) {
        return new Cell(x, y, z);
    }
}

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.Cell;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatAdapterParityTest {
    @Test void plannedCoverDoesNotShieldSelfButConfirmedObsidianDoes() {
        var world = new AdapterTestSupport.FakeWorld();
        var adapter = new CombatDamageAdapter();
        var source = new dev.tntcoating.core.combat.Vec(0.5, 64.5, 0.5);
        var box = AdapterTestSupport.playerBox(2.5, 64.0, 0.5);
        var wall = java.util.List.of(new Cell(1, 64, 0), new Cell(1, 65, 0));
        var upper = dev.tntcoating.core.combat.ExposurePolicy.SELF_FRIEND_UPPER_BOUND;
        assertEquals(1.0, adapter.exposure(world, source, box, upper, wall), 1e-9);
        for (Cell cell : wall) world.set(cell.x(), cell.y(), cell.z(), Blocks.OBSIDIAN.defaultBlockState());
        assertEquals(0.0, adapter.exposure(world, source, box, upper, wall), 1e-9);
    }

    @BeforeAll static void bootstrap() { AdapterTestSupport.bootstrap(); }

    @Test void acceptsTheSuppliedBaseEvenWithAirUnderneath() {
        var world = new AdapterTestSupport.FakeWorld();
        world.set(0, 64, 0, Blocks.OBSIDIAN.defaultBlockState());
        assertTrue(MinecraftCombatClient.supportBelow(world, new Cell(0, 64, 0)));
    }

    @Test void rejectsAirAboveASolidBlock() {
        var world = new AdapterTestSupport.FakeWorld();
        world.set(0, 63, 0, Blocks.OBSIDIAN.defaultBlockState());
        assertFalse(MinecraftCombatClient.supportBelow(world, new Cell(0, 64, 0)));
    }
}

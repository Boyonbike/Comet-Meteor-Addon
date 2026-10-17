package dev.tntcoating.core;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShulkerUseGuardTest {
    private final ShulkerUseGuard guard = new ShulkerUseGuard();
    private final BlockPos first = new BlockPos(-10, 64, 20);
    private final BlockPos second = first.above();

    @Test void holdingUseAfterPlacingTheLastShulkerDoesNotOpenItWithEmptyHands() {
        guard.placed(first, true, true);

        for (int tick = 0; tick < 100; tick++) {
            guard.update(true);
            assertTrue(guard.shouldBlock(first, true, false, false));
        }
    }

    @Test void releasingAndClickingAgainOpensNormallyEvenBetweenTicks() {
        guard.placed(first, true, true);
        assertTrue(guard.shouldBlock(first, true, false, true));

        guard.clear(); // Matching mouse/keyboard release, before the next tick.
        guard.update(true); // A fresh click may already be held on that tick.

        assertFalse(guard.shouldBlock(first, true, false, true));
        guard.placed(second, true, true);
        assertTrue(guard.shouldBlock(second, true, false, true));
        assertFalse(guard.shouldBlock(first, true, false, true));
    }

    @Test void sneakingCanChainPlacementsAgainstShulkersButEmptyHandsStayProtected() {
        guard.placed(first, true, true);
        assertFalse(guard.shouldBlock(first, true, true, true));
        guard.placed(second, true, true);

        assertTrue(guard.shouldBlock(first, true, false, true));
        assertTrue(guard.shouldBlock(second, true, false, true));
        assertTrue(guard.shouldBlock(second, true, true, false));
    }

    @Test void otherContainersAndPreexistingShulkersRemainUsable() {
        guard.placed(first, true, true);
        assertFalse(guard.shouldBlock(second, true, false, true));
        assertFalse(guard.shouldBlock(first, false, false, true));

        guard.placed(second, false, true);
        assertFalse(guard.shouldBlock(second, true, false, true));
    }

    @Test void tickReleaseFallbackAndWorldOrModuleResetClearProtection() {
        guard.placed(first, true, true);
        guard.update(false);
        assertFalse(guard.shouldBlock(first, true, false, false));

        guard.placed(first, true, true);
        guard.clear();
        assertFalse(guard.shouldBlock(first, true, false, false));
    }

    @Test void programmaticPlacementWithoutHoldingUseDoesNotPreventTheNextClick() {
        guard.placed(first, true, false);
        guard.update(true);
        assertFalse(guard.shouldBlock(first, true, false, true));
    }

    @Test void mutablePlacementPositionsAreCopiedBeforeMinecraftReusesThem() {
        var cursor = new BlockPos.MutableBlockPos(first.getX(), first.getY(), first.getZ());
        guard.placed(cursor, true, true);
        cursor.set(second);

        assertTrue(guard.shouldBlock(first, true, false, true));
        assertFalse(guard.shouldBlock(second, true, false, true));
    }
}

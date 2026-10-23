package dev.tntcoating.modules;

import dev.tntcoating.core.SpeedminePolicy;
import dev.tntcoating.core.SpeedmineRange;
import dev.tntcoating.core.SpeedmineTargeting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SpeedmineTargetTest {
    @Test void phaseBedrockWinsWithoutConsultingSurround() {
        BlockPos phased = new BlockPos(4, 1, 0);
        var overlaps = SpeedmineTargeting.phaseCandidates(new AABB(4.2, 1, 0.2, 4.8, 2.8, 0.8));
        assertTrue(overlaps.contains(phased));
        var targets = SpeedmineTargeting.prioritizeTargets(true, true, () -> List.of(phased), () -> {
            fail("Surround must not displace a valid phase target");
            return List.of();
        });
        assertEquals(phased, SpeedmineTargeting.selectBedrockTarget(null, targets, phased::equals));
    }

    @Test void touchingFloorAndWallAreNotPhaseTargets() {
        var candidates = SpeedmineTargeting.phaseCandidates(new AABB(4, 1, 0, 5, 2.8, 1));
        assertEquals(List.of(new BlockPos(4, 1, 0), new BlockPos(4, 2, 0)), candidates);
        assertFalse(candidates.contains(new BlockPos(4, 0, 0)));
        assertFalse(candidates.contains(new BlockPos(5, 1, 0)));
    }

    @Test void partialOverlapIncludesBothBlocksAndHandlesNegativeCoordinates() {
        var candidates = SpeedmineTargeting.phaseCandidates(new AABB(-0.2, -64, -0.8, 0.4, -62.2, -0.2));
        assertEquals(4, candidates.size());
        assertTrue(candidates.contains(new BlockPos(-1, -64, -1)));
        assertTrue(candidates.contains(new BlockPos(0, -63, -1)));
        assertFalse(candidates.stream().anyMatch(pos -> pos.getZ() == 0));
    }

    @Test void rejectedPhaseTargetsAllowSurroundFallback() {
        var surround = List.of(new BlockPos(5, 1, 0));
        assertEquals(surround, SpeedmineTargeting.prioritizeTargets(true, true, List::of, () -> surround));
        assertTrue(SpeedmineTargeting.prioritizeTargets(true, false, List::of, () -> surround).isEmpty());
        assertEquals(surround, SpeedmineTargeting.prioritizeTargets(false, true, () -> {
            fail("Anti-phase is disabled"); return List.of();
        }, () -> surround));
    }

    @Test void friendsSelfSpectatorsAndDistantPlayersStayExcluded() {
        assertTrue(SpeedmineTargeting.eligibleEnemy(false, false, false, 10, 10));
        assertFalse(SpeedmineTargeting.eligibleEnemy(true, false, false, 0, 10));
        assertFalse(SpeedmineTargeting.eligibleEnemy(false, true, false, 2, 10));
        assertFalse(SpeedmineTargeting.eligibleEnemy(false, false, true, 2, 10));
        assertFalse(SpeedmineTargeting.eligibleEnemy(false, false, false, 10.01, 10));
    }

    @Test void selfProtectionStillIncludesBlocksBesideOurHitbox() {
        var self = new AABB(0.2, 1, 0.2, 0.8, 2.8, 0.8);
        assertTrue(SpeedmineTargeting.protectsSelf(self, new BlockPos(0, 1, 0)));
        assertTrue(SpeedmineTargeting.protectsSelf(self, new BlockPos(1, 1, 0)));
        assertTrue(SpeedmineTargeting.protectsSelf(self, new BlockPos(0, 0, 0)));
        assertFalse(SpeedmineTargeting.protectsSelf(self, new BlockPos(3, 1, 0)));
    }

    @Test void reachMeasuresNearestPointInsteadOfBlockCentre() {
        var target = new BlockPos(5, 1, 0);
        var eyes = new Vec3(0.5, 1.62, 0.5);
        assertTrue(SpeedmineRange.isInRange(4.5, target, eyes));
        assertFalse(SpeedmineRange.isInRange(4.49, target, eyes));
    }

    @Test void bedrockSelectionKeepsProgressAndOnlyUsesCurrentEligibleTargets() {
        var a = new BlockPos(4, 1, 0);
        var b = new BlockPos(5, 1, 0);
        var ordinary = new BlockPos(3, 1, 0);
        assertEquals(b, SpeedmineTargeting.selectBedrockTarget(b, List.of(ordinary, a, b), p -> !p.equals(ordinary)));
        assertEquals(a, SpeedmineTargeting.selectBedrockTarget(b, List.of(ordinary, a), a::equals));
        assertNull(SpeedmineTargeting.selectBedrockTarget(b, List.of(ordinary), p -> false));
        assertNull(SpeedmineTargeting.selectBedrockTarget(b, List.of(), p -> true));
    }

    @Test void releaseRestoresOnlyTheSlotStillOwnedBySpeedmine() {
        assertTrue(SpeedminePolicy.shouldRestoreSlot(2, 2));
        assertFalse(SpeedminePolicy.shouldRestoreSlot(2, 5), "Do not overwrite another module's swap");
        assertFalse(SpeedminePolicy.shouldRestoreSlot(-1, 2), "Disconnect clears ownership");
        assertFalse(SpeedminePolicy.shouldRestoreSlot(-1, -1));
    }
}

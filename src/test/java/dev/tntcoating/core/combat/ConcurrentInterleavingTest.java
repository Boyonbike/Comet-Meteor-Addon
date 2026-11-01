/*
 * Original Comet implementation by Boyonabike.
 * Independent: test written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static dev.tntcoating.core.combat.RiskTestSupport.FRIEND;
import static dev.tntcoating.core.combat.RiskTestSupport.FRIEND_TWO;
import static dev.tntcoating.core.combat.RiskTestSupport.SAFETY;
import static dev.tntcoating.core.combat.RiskTestSupport.SELF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Phase 6.4 stage 2 on-path interleaving: 1,000 seeds of trap/direct/defence-shaped traffic at the pure
 * decision boundary, with the real {@link CombatRiskBudget} and the real {@link FootprintConflict}. The
 * concurrency path is only exercised through a positive verdict, so no test-visible send can belong to a
 * conflicting admission; slot/rotation ownership stays single-owner; stale callbacks never send; and every
 * reservation keeps the same ceilings the budget itself prices.
 */
final class ConcurrentInterleavingTest {
    private static final int SEEDS = 1000;
    private static final int TICKS = 40;
    private static final Cell SOURCE = new Cell(40, 64, 0);
    private static final Vec CRYSTAL = SOURCE.center();
    private static final Vec FEET = new Vec(40.5, 63.0, 0.5);
    private static final Box SWEPT = Box.fromCell(SOURCE).expand(1.0);
    private static final CollisionView FLOOR = cell -> cell.y() < 64;
    private static final ExposureBound TRAP_EXPOSURE = RiskTestSupport.exposure(0.5,
        Map.of(FRIEND, 0.3));
    private static final EntityKey TRAP_TARGET = entity("trap-target");
    private static final EntityKey TRAP_CRYSTAL = entity("trap-crystal");
    private static final FootprintConflict.TrapView TRAP = new FootprintConflict.TrapView(
        TRAP_TARGET, FEET, Box.around(FEET, PistonGeometry.POSITION_TOLERANCE),
        List.of(new FootprintConflict.TrapBlock(new Cell(35, 64, 0), FootprintConflict.OBSIDIAN),
            new FootprintConflict.TrapBlock(new Cell(39, 64, 0), FootprintConflict.PISTON),
            new FootprintConflict.TrapBlock(new Cell(39, 64, 2), "minecraft:redstone_torch")),
        SOURCE, TRAP_CRYSTAL, CRYSTAL, SWEPT, TRAP_EXPOSURE, false, false);

    @Test
    void oneThousandSeedsNeverSendAConflictingDirectPlanAndRespectEveryBudgetCeiling() {
        long admitted = 0;
        long serialised = 0;
        long conflicts = 0;
        long sends = 0;
        long aborted = 0;
        long staleIgnored = 0;
        long admittedAndReserved = 0;
        List<String> violations = new ArrayList<>();

        for (int seed = 0; seed < SEEDS; seed++) {
            Random random = new Random(seed);
            CombatRiskBudget budget = new CombatRiskBudget((envelope, current) -> current);
            SafetyState state = RiskTestSupport.state(RiskTestSupport.known(20.0, 0.0, 1L),
                Map.of(FRIEND, RiskTestSupport.known(20.0, 0.0, 1L),
                    FRIEND_TWO, RiskTestSupport.unknown()));
            assertTrue(budget.reserve(1L, TRAP_EXPOSURE, new ServerTickInterval(0, 3), state)
                .isPresent(), "seed " + seed + ": the trap reservation must price");

            Map<Long, Callback> callbacks = new LinkedHashMap<>();
            boolean slotOwner = false;
            boolean rotationOwner = false;
            long serial = 0;
            long riskSerial = 100;

            for (int tick = 0; tick < TICKS; tick++) {
                // Completion callbacks: a current serial sends once, a stale serial never sends.
                List<Long> due = new ArrayList<>();
                for (Map.Entry<Long, Callback> entry : callbacks.entrySet()) {
                    if (entry.getValue().dueTick == tick) due.add(entry.getKey());
                }
                for (long id : due) {
                    Callback callback = callbacks.remove(id);
                    if (callback.serial != serial) {
                        staleIgnored++;
                        continue;
                    }
                    sends++;
                    if (!callback.compatible) {
                        violations.add("seed " + seed + " send without a compatible verdict");
                    }
                    if (callback.slot) slotOwner = false;
                    if (callback.rotation) rotationOwner = false;
                }
                if (random.nextDouble() < 0.15 && !callbacks.isEmpty()) {
                    // Abort the in-flight plan: its callback stays queued and must arrive stale, never
                    // send, and never release the tokens of a newer plan.
                    Callback callback = callbacks.values().iterator().next();
                    serial++;
                    aborted++;
                    slotOwner = false;
                    rotationOwner = false;
                    if (callback.serial == serial) {
                        violations.add("seed " + seed + " abort left a current serial");
                    }
                }

                boolean explosive = random.nextDouble() < 0.7;
                Vec explosion = explosive ? new Vec(60.0 * random.nextDouble(), 60.0 + 10.0 * random.nextDouble(),
                    30.0 * random.nextDouble()) : null;
                boolean needsSlot = random.nextDouble() < 0.25;
                boolean needsRotation = random.nextDouble() < 0.25;
                FootprintConflict.DirectView direct = new FootprintConflict.DirectView(
                    explosive ? Action.PLACE_CRYSTAL : Action.PLACE_BASE, explosion,
                    new Footprint(Set.of(), null, null, Set.of()), needsSlot, needsRotation);
                ExposureBound directExposure = exposure(random.nextDouble() * 2.0,
                    random, random.nextBoolean());
                boolean riskFits = CombatRiskGate.allows(budget, SELF, state,
                    CombatRiskGate.combine(directExposure, TRAP_EXPOSURE));
                FootprintConflict.Environment environment = new FootprintConflict.Environment(
                    slotOwner, rotationOwner, riskFits,
                    MotionSample.of(FEET.x(), FEET.y(), FEET.z(), 0.0, 0.0, 0.0, true), FLOOR);
                FootprintConflict.Result result = FootprintConflict.check(direct, TRAP, environment);

                switch (result.verdict()) {
                    case COMPATIBLE -> {
                        admitted++;
                        if (!result.compatible()) {
                            violations.add("seed " + seed + " non-compatible admission");
                        }
                        if (needsSlot && slotOwner) violations.add("seed " + seed + " double slot owner");
                        if (needsRotation && rotationOwner) {
                            violations.add("seed " + seed + " double rotation owner");
                        }
                        var riskId = budget.reserve(riskSerial++, directExposure,
                            new ServerTickInterval(tick, tick + 2), state);
                        if (riskId.isEmpty()) {
                            violations.add("seed " + seed + " admitted but the real budget refused");
                            break;
                        }
                        admittedAndReserved++;
                        if (slotOwner) slotOwner = false;
                        if (rotationOwner) rotationOwner = false;
                        if (needsSlot) slotOwner = true;
                        if (needsRotation) rotationOwner = true;
                        callbacks.put(serial, new Callback(tick + 2, serial, needsSlot, needsRotation, true));
                    }
                    case SERIALISE -> serialised++;
                    case CONFLICT -> conflicts++;
                }

                double selfBound = budget.current(SELF).value();
                if (!(20.0 - selfBound > SAFETY.minRemaining())) {
                    violations.add("seed " + seed + " self bound exceeded: " + selfBound);
                }
                for (EntityKey friend : List.of(FRIEND, FRIEND_TWO)) {
                    if (budget.current(friend).value() > SAFETY.maxFriend() + 1.0e-9) {
                        violations.add("seed " + seed + " friend bound exceeded: "
                            + budget.current(friend).value());
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(), () -> "violations: " + violations);
        assertEquals(0, violations.size());
        assertTrue(admitted > 0, "the on-path must be exercised");
        assertTrue(admittedAndReserved > 0, "admissions must reserve on the real budget");
        assertTrue(sends > 0, "admitted plans must be able to complete");
        assertTrue(serialised > 0, "resource and budget waits must occur");
        assertTrue(conflicts > 0, "conflicting footprints must occur");
        assertTrue(aborted > 0, "the abort path must be exercised");
        assertTrue(staleIgnored > 0, "stale callbacks must be exercised and never send");
    }

    /** A direct exposure: self bound in [0,2], two friends, one of them always present. */
    private static ExposureBound exposure(double selfUpper, Random random, boolean secondFriend) {
        Map<EntityKey, Double> friends = new LinkedHashMap<>();
        friends.put(FRIEND, random.nextDouble() * 1.2);
        if (secondFriend) friends.put(FRIEND_TWO, random.nextDouble() * 0.8);
        return RiskTestSupport.exposure(selfUpper, friends);
    }

    private static EntityKey entity(String name) {
        return new EntityKey(new ConnectionKey(41), name.hashCode() & 0x7fff_ffff,
            UUID.nameUUIDFromBytes(name.getBytes()));
    }

    private record Callback(int dueTick, long serial, boolean slot, boolean rotation,
                            boolean compatible) {
    }
}

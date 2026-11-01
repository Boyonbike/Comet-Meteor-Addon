/*
 * Original Comet test written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Phase 6.4 stage 1: a trap plan is exposed to the choice as an Opportunity for ranking only. Every field
 * comes from the plan; a field that cannot be established makes the opportunity absent instead of guessed.
 */
final class PistonOpportunityTest {
    private static final ConnectionKey CONN = new ConnectionKey(1L);
    private static final EntityKey TARGET = new EntityKey(CONN, 1, UUID.randomUUID());

    @Test
    void planFieldsBecomeTheRankingOpportunity() {
        PistonPlanner.Plan plan = plan();
        MitigationEnvelope envelope = new MitigationEnvelope(3L);

        Opportunity opportunity = PistonOpportunity.of(plan, envelope).orElseThrow();

        assertEquals(Strategy.PISTON, opportunity.strategy());
        assertEquals(plan.target(), opportunity.target());
        assertEquals(plan.expectedPushed(), opportunity.explosion());
        assertEquals(plan.targetDamage(), opportunity.targetDamage().guaranteedLower(), 1.0e-9);
        assertEquals(plan.targetDamage(), opportunity.targetDamage().expected(), 1.0e-9);
        assertEquals(plan.targetDamage(), opportunity.expectedDamage20(), 1.0e-9);
        assertEquals(plan.selfDamage(), opportunity.exposure().selfUpper().beforeAbsorption(), 1.0e-9);
        assertEquals(envelope, opportunity.exposure().envelope());
        assertEquals(plan.intents(), opportunity.plan());
        assertEquals(plan.intents().size(), opportunity.actionCost());
        assertEquals(plan.intents().size(), opportunity.etaTicks(),
            "eta is the serialised lower bound: at most one send per tick");
        assertEquals(plan.footprint(), opportunity.footprint());
        assertEquals(Confidence.WEAK.factor(), opportunity.confidence(), 1.0e-9,
            "a pushed-position estimate is predicted, not observed");
        assertTrue(opportunity.id() >= PistonOpportunity.ID_BASE,
            "trap ranking ids are namespaced away from direct intent ids");
    }

    @Test
    void anUnconfirmedTrapNeverClaimsLethality() {
        PistonPlanner.Plan plan = plan();
        Opportunity opportunity = PistonOpportunity.of(plan, new MitigationEnvelope(1L)).orElseThrow();

        assertFalse(CombatChoice.credibleLethal(opportunity, plan.targetDamage()),
            "lethality needs confidence 0.7; an unconfirmed trap estimate has 0.5");
    }

    @Test
    void missingInputsMakeTheOpportunityAbsentInsteadOfInventingThem() {
        PistonPlanner.Plan plan = plan();

        assertTrue(PistonOpportunity.of(null, new MitigationEnvelope(1L)).isEmpty());
        assertTrue(PistonOpportunity.of(plan, null).isEmpty());
    }

    private static PistonPlanner.Plan plan() {
        PistonPlannerTest.Fake world = new PistonPlannerTest.Fake();
        world.positions.put(TARGET, new Vec(0.5, -60.0, 0.5));
        world.floor(-61);
        world.put(new Cell(-1, -61, 0), "minecraft:obsidian");
        world.put(new Cell(-1, -62, 0), "minecraft:stone");
        world.put(new Cell(1, -60, 0), "minecraft:stone");
        world.put(new Cell(1, -59, 0), "minecraft:stone");
        world.put(new Cell(0, -60, 1), "minecraft:stone");
        world.put(new Cell(0, -59, 1), "minecraft:stone");
        world.put(new Cell(0, -60, -1), "minecraft:stone");
        world.put(new Cell(0, -59, -1), "minecraft:stone");
        world.put(new Cell(-1, -59, 0), "minecraft:stone");
        world.put(new Cell(-2, -59, 0), "minecraft:stone");
        world.put(new Cell(-3, -59, 0), "minecraft:stone");

        PistonPlanner.Result result = new PistonPlanner().tick(world, List.of(TARGET), 64);
        return result.plan().orElseThrow(
            () -> new AssertionError("cradle produced no plan, last reject " + result.lastReject()));
    }
}

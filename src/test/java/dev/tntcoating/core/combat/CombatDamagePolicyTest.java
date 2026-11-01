/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatDamagePolicyTest {
    private static final Safety SAFETY = CombatDamagePolicy.conservativeSafety();
    private static final MitigationEnvelope ENVELOPE = new MitigationEnvelope(1);
    private static final EntityKey FRIEND = new EntityKey(new ConnectionKey(1), 2,
        UUID.nameUUIDFromBytes("friend".getBytes(StandardCharsets.UTF_8)));

    private static EffectiveHealth health(double health, double absorption, Capability cap) {
        return new EffectiveHealth(health, absorption, cap, 1);
    }

    private static EffectiveHealth knownHealth(double health) {
        return health(health, 0.0, Capability.KNOWN);
    }

    private static CombatDamagePolicy policy(EffectiveHealth self, EffectiveHealth target) {
        return new CombatDamagePolicy(self, target);
    }

    private static ExposureBound noExposure(double selfUpper) {
        return new ExposureBound(new MitigatedDamage(selfUpper), Map.of(), ENVELOPE);
    }

    private static TargetEstimate target(double lower, double expected) {
        return new TargetEstimate(lower, expected, Math.max(expected, lower));
    }

    @Test
    void acceptsWhenEveryConservativeCheckPasses() {
        CombatDamagePolicy policy = policy(knownHealth(20.0), knownHealth(20.0));
        assertTrue(policy.allows(target(2.0, 6.0), noExposure(3.0), SAFETY));
    }

    @Test
    void rejectsExpectedTargetDamageBelowTheMinimum() {
        CombatDamagePolicy policy = policy(knownHealth(20.0), knownHealth(20.0));
        assertFalse(policy.allows(target(2.0, 3.999), noExposure(0.0), SAFETY));
        assertTrue(policy.allows(target(2.0, 4.0), noExposure(0.0), SAFETY));
    }

    @Test
    void rejectsSelfUpperBoundAboveTheMaximum() {
        CombatDamagePolicy policy = policy(knownHealth(20.0), knownHealth(20.0));
        assertFalse(policy.allows(target(2.0, 6.0), noExposure(6.001), SAFETY));
        assertTrue(policy.allows(target(2.0, 6.0), noExposure(6.0), SAFETY));
    }

    @Test
    void rejectsAFriendUpperBoundAboveTheMaximum() {
        CombatDamagePolicy policy = policy(knownHealth(20.0), knownHealth(20.0));
        ExposureBound exposure = new ExposureBound(new MitigatedDamage(1.0),
            Map.of(FRIEND, new MitigatedDamage(2.001)), ENVELOPE);
        assertFalse(policy.allows(target(2.0, 6.0), exposure, SAFETY));
        ExposureBound tolerable = new ExposureBound(new MitigatedDamage(1.0),
            Map.of(FRIEND, new MitigatedDamage(2.0)), ENVELOPE);
        assertTrue(policy.allows(target(2.0, 6.0), tolerable, SAFETY));
    }

    @Test
    void remainderOfExactlyTheMarginIsRejected() {
        // 5 known health - 3 self damage = 2, exactly the margin: strict comparison rejects it.
        CombatDamagePolicy exactly = policy(knownHealth(5.0), knownHealth(20.0));
        assertFalse(exactly.allows(target(2.0, 6.0), noExposure(3.0), SAFETY));

        CombatDamagePolicy justAbove = policy(knownHealth(5.001), knownHealth(20.0));
        assertTrue(justAbove.allows(target(2.0, 6.0), noExposure(3.0), SAFETY));
    }

    @Test
    void unknownSelfHealthUsesAZeroLowerBoundAndCannotQualify() {
        CombatDamagePolicy policy = policy(health(20.0, 4.0, Capability.UNKNOWN), knownHealth(20.0));
        assertFalse(policy.allows(target(20.0, 20.0), noExposure(0.0), SAFETY));
    }

    @Test
    void lethalOverrideRelaxesOnlyTheTargetMinimum() {
        CombatDamagePolicy policy = policy(knownHealth(20.0), knownHealth(10.0));
        // Target damage is below the minimum, but the guaranteed lower bound reaches the target's known
        // effective-health upper bound.
        assertTrue(policy.allows(target(10.0, 1.0), noExposure(0.0), SAFETY, true));
    }

    @Test
    void lethalOverrideRequiresTheLowerBoundToReachTheHealthUpperBound() {
        CombatDamagePolicy policy = policy(knownHealth(20.0), knownHealth(10.0));
        assertFalse(policy.allows(target(9.999, 10.0), noExposure(0.0), SAFETY, true));
    }

    @Test
    void expectedNeverQualifiesForTheLethalOverride() {
        CombatDamagePolicy policy = policy(knownHealth(20.0), knownHealth(10.0));
        // expected = 100 is huge, but guaranteedLower is below the known upper bound.
        assertFalse(policy.allows(target(1.0, 100.0), noExposure(0.0), SAFETY, true));
    }

    @Test
    void lethalOverrideNeedsAKnownTargetHealthCapability() {
        CombatDamagePolicy unknown = policy(knownHealth(20.0), health(10.0, 0.0, Capability.UNKNOWN));
        assertFalse(unknown.allows(target(10.0, 1.0), noExposure(0.0), SAFETY, true));
        CombatDamagePolicy censored = policy(knownHealth(20.0), health(10.0, 0.0, Capability.CENSORED));
        assertFalse(censored.allows(target(10.0, 1.0), noExposure(0.0), SAFETY, true));
    }

    @Test
    void lethalOverrideStillRespectsSelfAndFriendLimits() {
        CombatDamagePolicy policy = policy(knownHealth(20.0), knownHealth(10.0));
        assertFalse(policy.allows(target(10.0, 1.0), noExposure(7.0), SAFETY, true));
        ExposureBound friendAtRisk = new ExposureBound(new MitigatedDamage(0.0),
            Map.of(FRIEND, new MitigatedDamage(3.0)), ENVELOPE);
        assertFalse(policy.allows(target(10.0, 1.0), friendAtRisk, SAFETY, true));
    }

    @Test
    void rejectsNonFiniteAndNegativeInputs() {
        CombatDamagePolicy policy = policy(knownHealth(20.0), knownHealth(20.0));
        assertFalse(policy.allows(new TargetEstimate(Double.NaN, 6.0, 6.0), noExposure(0.0), SAFETY));
        assertFalse(policy.allows(new TargetEstimate(-1.0, 6.0, 6.0), noExposure(0.0), SAFETY));
        assertFalse(policy.allows(new TargetEstimate(2.0, 6.0, Double.POSITIVE_INFINITY),
            noExposure(0.0), SAFETY));
        assertFalse(policy.allows(target(2.0, 6.0),
            new ExposureBound(new MitigatedDamage(Double.NaN), Map.of(), ENVELOPE), SAFETY));
        assertFalse(policy.allows(target(2.0, 6.0), noExposure(-1.0), SAFETY));
        assertFalse(policy.allows(target(2.0, 6.0), noExposure(0.0),
            new Safety(Double.NaN, 6.0, 2.0, 2.0)));
        assertFalse(policy.allows(target(2.0, 6.0), noExposure(0.0),
            new Safety(4.0, 6.0, 2.0, -2.0)));
        assertFalse(policy.allows(null, noExposure(0.0), SAFETY));
        assertFalse(policy.allows(target(2.0, 6.0), null, SAFETY));
        assertFalse(policy.allows(target(2.0, 6.0), noExposure(0.0), null));
    }

    @Test
    void conservativeSafetyIsThePlanDefault() {
        assertTrue(SAFETY.minTarget() == 4.0 && SAFETY.maxSelf() == 6.0
            && SAFETY.minRemaining() == 2.0 && SAFETY.maxFriend() == 2.0);
    }
}

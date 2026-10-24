/*
 * Part of Comet's Auto Wasp port; see AutoWasp.java and THIRD_PARTY_NOTICES.md for DEVILS provenance.
 * Written for Comet by Boyonabike on 2026-10-24, independent of Adaptive Elytra Fly's classes.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.modules.autowasp;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class AutoWaspStandoffTest {
    private static final double EPSILON = 1.0E-9;

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertEquals(0.0, expected.distanceTo(actual), EPSILON);
    }

    @Test
    void disabledOrOutsideTheStandoffKeepsTheSameAim() {
        Vec3 aim = new Vec3(0.0, 65.0, 0.0);
        Vec3 target = Vec3.ZERO;

        assertSame(aim, AutoWaspStandoff.holdPoint(aim, target, new Vec3(10.0, 64.0, 0.0), 6.0));
        assertSame(aim, AutoWaspStandoff.holdPoint(aim, target, new Vec3(6.0, 64.0, 0.0), 6.0));
        assertSame(aim, AutoWaspStandoff.holdPoint(aim, target, new Vec3(2.0, 64.0, 0.0), 0.0));
        assertSame(aim, AutoWaspStandoff.holdPoint(aim, target, new Vec3(2.0, 64.0, 0.0), -1.0));
        assertSame(aim, AutoWaspStandoff.holdPoint(aim, target, new Vec3(2.0, 64.0, 0.0), Double.NaN));
        assertSame(aim, AutoWaspStandoff.holdPoint(aim, target, new Vec3(0.0, 64.0, 0.0), 6.0));
        assertSame(aim, AutoWaspStandoff.holdPoint(aim, null, new Vec3(2.0, 64.0, 0.0), 6.0));
        assertSame(aim, AutoWaspStandoff.holdPoint(aim, target, null, 6.0));
    }

    @Test
    void insideTheStandoffHoldsTheRingAtTheAimHeight() {
        Vec3 aim = new Vec3(0.0, 65.0, 0.0);
        Vec3 target = new Vec3(0.0, 64.0, 0.0);

        assertVec(new Vec3(6.0, 65.0, 0.0),
            AutoWaspStandoff.holdPoint(aim, target, new Vec3(2.0, 64.0, 0.0), 6.0));
    }

    @Test
    void diagonalBearingIsPreserved() {
        Vec3 aim = new Vec3(1.0, 70.0, 1.0);
        Vec3 target = new Vec3(0.0, 70.0, 0.0);

        assertVec(new Vec3(6.0, 70.0, 8.0),
            AutoWaspStandoff.holdPoint(aim, target, new Vec3(3.0, 70.0, 4.0), 10.0));
    }

    @Test
    void negativeBearingIsPreserved() {
        Vec3 aim = new Vec3(0.0, 64.0, 0.0);
        Vec3 target = new Vec3(0.0, 64.0, 0.0);

        assertVec(new Vec3(0.0, 64.0, -4.5),
            AutoWaspStandoff.holdPoint(aim, target, new Vec3(0.0, 64.0, -3.0), 4.5));
    }

    @Test
    void ringIsCentredOnTheTargetNotTheAim() {
        Vec3 aim = new Vec3(5.0, 65.0, 0.0);
        Vec3 target = new Vec3(0.0, 64.0, 0.0);

        assertVec(new Vec3(6.0, 65.0, 0.0),
            AutoWaspStandoff.holdPoint(aim, target, new Vec3(2.0, 64.0, 0.0), 6.0));
    }

    @Test
    void ringCenterFollowsTheActualTarget() {
        Vec3 aim = new Vec3(20.0, 65.0, 20.0);
        Vec3 target = new Vec3(20.0, 64.0, 20.0);

        assertVec(new Vec3(26.0, 65.0, 20.0),
            AutoWaspStandoff.holdPoint(aim, target, new Vec3(22.0, 64.0, 20.0), 6.0));
    }
}

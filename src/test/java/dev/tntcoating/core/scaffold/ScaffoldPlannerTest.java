package dev.tntcoating.core.scaffold;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static dev.tntcoating.core.scaffold.ScaffoldPlanner.*;
import static org.junit.jupiter.api.Assertions.*;

class ScaffoldPlannerTest {
    private static final Predicate<Cell> EMPTY = c -> false;

    private static Snapshot at(double x, double z, double vx, double vz, double dx, double dz,
                               double reach, long now, long lookahead) {
        return new Snapshot(x, z, 1.6, -1, vx, vz, dx, dz, .3, reach, now, lookahead);
    }

    private static Set<Cell> cells(Plan plan) {
        return plan.candidates().stream().map(Candidate::cell).collect(Collectors.toSet());
    }

    @Test void wideIncludesEveryReachableSquareInThreeForwardRowsAtFractionalNegativePositions() {
        for (double x : new double[]{-2.8, -.5, .1, .9}) {
            for (double z : new double[]{-2.8, -.5, .1, .9}) {
                var snapshot = at(x, z, 0, 0, 0, 1, 4.5, 100, 200_000_000);
                Set<Cell> actual = cells(new ScaffoldPlanner().plan(Preset.Wide, snapshot, EMPTY));
                Set<Cell> expected = new HashSet<>();
                // Independent rectangle/circle intersection oracle for north/south rows.
                for (int bx = -9; bx <= 9; bx++) for (int bz = -9; bz <= 9; bz++) {
                    double nearestX = Math.max(bx, Math.min(x, bx + 1));
                    double nearestZ = Math.max(bz, Math.min(z, bz + 1));
                    if (Math.hypot(Math.hypot(x - nearestX, z - nearestZ), 1.6) <= 4.5
                        && bz + 1 >= z - .3 && bz <= z + 2.3) expected.add(new Cell(bx, -1, bz));
                }
                assertEquals(expected, actual, "at " + x + "," + z);
            }
        }
    }

    @Test void wideKeepsReachMarginTargetsEvenWhenTheirCentresAreOutOfRange() {
        Plan plan = new ScaffoldPlanner().plan(Preset.Wide, at(.5,.5,0,0,0,1,6,0,0), EMPTY);
        assertTrue(cells(plan).contains(new Cell(6,-1,0)));
        assertTrue(plan.desiredWidth() > 9);
    }

    @Test void wideDoesNotTruncateCandidateEnvelopeAtSixtyFour() {
        Plan plan = new ScaffoldPlanner().plan(Preset.Wide, at(.5,.5,0,0,0,1,16,0,0), EMPTY);
        assertTrue(plan.candidates().size() > 64);
        assertTrue(cells(plan).contains(new Cell(15,-1,1)));
    }

    @Test void diagonalWideIsAContinuousTransverseBandAndSymmetricUnderAxisExchange() {
        var a = new ScaffoldPlanner().plan(Preset.Wide, at(.2,-.7,1,2,1,2,5,0,0), EMPTY);
        var b = new ScaffoldPlanner().plan(Preset.Wide, at(-.7,.2,2,1,2,1,5,0,0), EMPTY);
        assertEquals(cells(a).stream().map(c -> new Cell(c.z(), c.y(), c.x())).collect(Collectors.toSet()), cells(b));
        assertTrue(cells(a).contains(new Cell(-3,-1,1)));
        assertTrue(cells(a).contains(new Cell(3,-1,-1)));
    }

    @Test void missingFootpathIsUrgentAndConfirmedCellsAreNeverCandidates() {
        Cell support = new Cell(0,-1,0);
        var plan = new ScaffoldPlanner().plan(Preset.Wide, at(.5,.5,0,0,0,1,6,0,0), support::equals);
        assertFalse(cells(plan).contains(support));
        assertEquals(0, plan.candidates().getFirst().tier());
        assertEquals(0, plan.candidates().getFirst().cell().x());
    }

    @Test void interiorHolesAreRepairedBeforeOuterFrontiersAndDoNotCountAsConfirmedWidth() {
        Predicate<Cell> support = c -> c.z() >= 0 && c.z() <= 2 && c.x() != 1 && Math.abs(c.x()) <= 3;
        Plan plan = new ScaffoldPlanner().plan(Preset.Wide, at(.5,.5,0,0,0,1,6,0,0), support);
        Candidate hole = plan.candidates().stream().filter(c -> c.cell().equals(new Cell(1,-1,1))).findFirst().orElseThrow();
        assertEquals(1, hole.tier());
        assertTrue(plan.holes() >= 3);
        assertTrue(plan.confirmedWidth() < plan.desiredWidth());
        assertTrue(plan.candidates().indexOf(hole) < plan.candidates().size() / 2);
    }

    @Test void placedAdvancesFairSidePreferenceOnlyAfterActualSend() {
        ScaffoldPlanner planner = new ScaffoldPlanner();
        Predicate<Cell> centre = c -> c.x() == 0;
        Snapshot snapshot = at(.5,.5,0,0,0,1,6,100,0);
        var first = planner.plan(Preset.Wide, snapshot, centre).candidates().getFirst();
        assertEquals(first, planner.plan(Preset.Wide, snapshot, centre).candidates().getFirst());
        planner.placed(first.cell());
        var second = planner.plan(Preset.Wide, snapshot, centre).candidates().getFirst();
        assertTrue(first.lateral() * second.lateral() < 0, "opposite frontier gets the next send");
    }

    @Test void reachableUnfinishedRowsAndTheirFirstSeenTimeSurviveForwardMovement() {
        ScaffoldPlanner planner = new ScaffoldPlanner();
        Cell old = new Cell(2,-1,0);
        var first = planner.plan(Preset.Wide, at(.5,.5,0,1,0,1,6,100,0), EMPTY);
        assertTrue(cells(first).contains(old));
        var next = planner.plan(Preset.Wide, at(.5,2.5,0,1,0,1,6,200,0), EMPTY);
        var retained = next.candidates().stream().filter(c -> c.cell().equals(old)).findFirst().orElseThrow();
        assertEquals(100, retained.firstSeenNanos());
        assertEquals(2, retained.tier());
    }

    @Test void stationaryHeadingPersistsAndTinyInputNoiseDoesNotTurnTheStrip() {
        ScaffoldPlanner planner = new ScaffoldPlanner();
        Snapshot first = at(.5,.5,0,1,0,1,6,0,0);
        Set<Cell> expected = cells(planner.plan(Preset.Wide, first, EMPTY));
        assertEquals(expected, cells(planner.plan(Preset.Wide, at(.5,.5,0,0,0,0,6,1,0), EMPTY)));
        assertEquals(expected, cells(planner.plan(Preset.Wide, at(.5,.5,.00001,0,1,0,6,2,0), EMPTY)));
    }

    @Test void resetDropsPriorRowsHeadingAndFairness() {
        ScaffoldPlanner planner = new ScaffoldPlanner();
        planner.plan(Preset.Wide, at(.5,.5,1,0,1,0,6,1,0), EMPTY);
        planner.plan(Preset.Wide, at(2.5,.5,1,0,1,0,6,2,0), EMPTY);
        planner.reset();
        Snapshot stopped = at(2.5,.5,0,0,0,0,6,3,0);
        assertEquals(new ScaffoldPlanner().plan(Preset.Wide, stopped, EMPTY), planner.plan(Preset.Wide, stopped, EMPTY));
    }

    @Test void narrowIncludesAllAndOnlyTheSweptFootprintAtDiagonalCorners() {
        var plan = new ScaffoldPlanner().plan(Preset.Narrow, at(.5,.5,3,3,1,1,10,100,1_000_000_000), EMPTY);
        Set<Cell> expected = Set.of(new Cell(0,-1,0),new Cell(0,-1,1),new Cell(1,-1,0),new Cell(1,-1,1),
            new Cell(1,-1,2),new Cell(2,-1,1),new Cell(2,-1,2),new Cell(2,-1,3),new Cell(3,-1,2),new Cell(3,-1,3));
        assertEquals(expected, cells(plan));
    }

    @Test void narrowDenseSamplesNeverLoseAnOccupiedFootprintCellAcrossFractionalStartsAndHeadings() {
        for (double start : new double[]{-.95,-.5,-.05,.05,.5,.95}) for (int heading = 0; heading < 16; heading++) {
            double angle = heading * Math.PI / 8;
            double vx = Math.cos(angle) * 4, vz = Math.sin(angle) * 4;
            Plan plan = new ScaffoldPlanner().plan(Preset.Narrow, at(start,-start,vx,vz,vx,vz,10,0,500_000_000), EMPTY);
            Set<Cell> planned = cells(plan);
            for (int sample = 0; sample <= 200; sample++) {
                double px = start + vx * sample / 400, pz = -start + vz * sample / 400;
                for (int bx = (int)Math.floor(px-.3); bx <= Math.floor(px+.3); bx++)
                    for (int bz = (int)Math.floor(pz-.3); bz <= Math.floor(pz+.3); bz++)
                        assertTrue(planned.contains(new Cell(bx,-1,bz)), "missing footprint " + bx + "," + bz);
            }
        }
    }

    @Test void narrowUsesEarliestEntryDeadlinesAndOrdersThemMonotonically() {
        var plan = new ScaffoldPlanner().plan(Preset.Narrow, at(.5,.5,4,0,1,0,10,100,1_000_000_000), EMPTY);
        assertEquals(new Cell(0,-1,0), plan.candidates().getFirst().cell());
        assertEquals(100, plan.candidates().getFirst().deadlineNanos());
        Candidate next = plan.candidates().stream().filter(c -> c.cell().equals(new Cell(1,-1,0))).findFirst().orElseThrow();
        assertEquals(50_000_100, next.deadlineNanos());
        long previous = Long.MIN_VALUE;
        for (var candidate : plan.candidates()) {
            assertTrue(candidate.deadlineNanos() >= previous);
            previous = candidate.deadlineNanos();
        }
    }

    @Test void narrowSteeringRecomputesWithoutKeepingUnrelatedOldPath() {
        ScaffoldPlanner planner = new ScaffoldPlanner();
        planner.plan(Preset.Narrow, at(.5,.5,4,0,1,0,10,1,1_000_000_000), EMPTY);
        var turned = planner.plan(Preset.Narrow, at(.5,.5,0,4,0,1,10,2,1_000_000_000), EMPTY);
        assertTrue(cells(turned).contains(new Cell(0,-1,4)));
        assertFalse(cells(turned).contains(new Cell(4,-1,0)));
    }

    @Test void perpendicularSteeringKeepsTheObservedFirstTickFootprintAndItsEarliestDeadline() {
        var plan = new ScaffoldPlanner().plan(Preset.Narrow, at(.5,.5,4,0,0,1,10,100,50_000_000), EMPTY);
        Candidate momentum = plan.candidates().stream().filter(c -> c.cell().equals(new Cell(1,-1,0)))
            .findFirst().orElseThrow();
        assertEquals(50_000_100, momentum.deadlineNanos());
        assertFalse(cells(plan).contains(new Cell(0,-1,1)), "no instantaneous northward velocity");
    }

    @Test void opposingSprintJumpInputCannotEraseImminentMomentumAtTheLandingPlane() {
        var snapshot = new Snapshot(.5,.5,4,-1,8,0,-1,0,.3,10,100,50_000_000);
        var plan = new ScaffoldPlanner().plan(Preset.Narrow, snapshot, EMPTY);
        Candidate momentum = plan.candidates().stream().filter(c -> c.cell().equals(new Cell(1,-1,0)))
            .findFirst().orElseThrow();
        assertEquals(25_000_100, momentum.deadlineNanos());
        assertFalse(cells(plan).contains(new Cell(-1,-1,0)), "turning input needs time to reverse momentum");
    }

    @Test void longerSteeringPredictionBendsThePathWithoutRetainingTheOldStraightEndpoint() {
        var plan = new ScaffoldPlanner().plan(Preset.Narrow, at(.5,.5,4,0,0,1,10,100,1_000_000_000), EMPTY);
        Candidate firstMomentum = plan.candidates().stream().filter(c -> c.cell().equals(new Cell(1,-1,0)))
            .findFirst().orElseThrow();
        assertEquals(50_000_100, firstMomentum.deadlineNanos(), "later segments cannot overwrite first contact");
        assertTrue(plan.candidates().stream().anyMatch(c -> c.cell().z() >= 2), "later support follows the turn");
        assertFalse(cells(plan).contains(new Cell(4,-1,0)), "full stale east sweep is not retained");
        assertFalse(cells(plan).contains(new Cell(0,-1,4)), "desired heading is not an instantaneous velocity");
        long deadline = Long.MIN_VALUE;
        for (Candidate candidate : plan.candidates()) {
            assertTrue(candidate.deadlineNanos() >= deadline);
            assertTrue(candidate.deadlineNanos() <= 1_000_000_100);
            deadline = candidate.deadlineNanos();
        }
    }

    @Test void narrowHasNoSidePlatformWhenStandingStillAndProjectsToSelectedLandingPlane() {
        var snapshot = new Snapshot(-.1,-.1,7,3,0,0,1,0,.3,6,100,1_000_000_000);
        var plan = new ScaffoldPlanner().plan(Preset.Narrow, snapshot, EMPTY);
        assertEquals(Set.of(new Cell(-1,3,-1),new Cell(-1,3,0),new Cell(0,3,-1),new Cell(0,3,0)), cells(plan));
    }

    @Test void horizonIsCappedAtOneSecondAndCellsAreClippedByFaceReach() {
        var longHorizon = new ScaffoldPlanner().plan(Preset.Narrow, at(.5,.5,4,0,1,0,4.5,0,Long.MAX_VALUE), EMPTY);
        var oneSecond = new ScaffoldPlanner().plan(Preset.Narrow, at(.5,.5,4,0,1,0,4.5,0,1_000_000_000), EMPTY);
        assertEquals(cells(oneSecond), cells(longHorizon));
        assertFalse(cells(longHorizon).contains(new Cell(5,-1,0)));
    }

    @Test void wideContainsTheCurrentAxisAlignedFootprintAtDiagonalHeading() {
        var plan = new ScaffoldPlanner().plan(Preset.Wide, at(.25,.25,1,1,1,1,5,0,0), EMPTY);
        assertTrue(cells(plan).contains(new Cell(-1,-1,-1)), "player corner overlaps this square");
    }

    @Test void unfinishedRowsLeavingReachAreCountedAndUnreachableHistoryIsDropped() {
        ScaffoldPlanner planner = new ScaffoldPlanner();
        planner.plan(Preset.Wide, at(.5,.5,0,1,0,1,4.5,0,1_000_000_000), EMPTY);
        var moving = planner.plan(Preset.Wide, at(.5,3.5,0,2,0,1,4.5,100,1_000_000_000), EMPTY);
        assertTrue(moving.unfinishedRowsLeavingReach() > 0);
        var away = planner.plan(Preset.Wide, at(.5,20.5,0,2,0,1,4.5,200,1_000_000_000), EMPTY);
        assertTrue(away.candidates().stream().allMatch(c -> c.cell().z() >= 16));
        var returned = planner.plan(Preset.Wide, at(.5,.5,0,1,0,1,4.5,300,1_000_000_000), EMPTY);
        assertEquals(300, returned.candidates().stream().filter(c -> c.cell().equals(new Cell(0,-1,0)))
            .findFirst().orElseThrow().firstSeenNanos());
    }

    @Test void sendNotificationNeverConfirmsSupportAndAuthoritativePredicateRemovesIt() {
        ScaffoldPlanner planner = new ScaffoldPlanner();
        Snapshot snapshot = at(.5,.5,0,0,0,1,4.5,10,0);
        Cell target = new Cell(0,-1,0);
        planner.plan(Preset.Wide, snapshot, EMPTY);
        planner.placed(target);
        assertTrue(cells(planner.plan(Preset.Wide, snapshot, EMPTY)).contains(target));
        assertFalse(cells(planner.plan(Preset.Wide, snapshot, target::equals)).contains(target));
    }

    @Test void narrowDeadlineArithmeticHandlesNegativeNanoTimeAndSaturatesWithoutOverflow() {
        var negative = new ScaffoldPlanner().plan(Preset.Narrow, at(.5,.5,4,0,1,0,10,-100,1_000_000_000), EMPTY);
        assertEquals(-100, negative.candidates().getFirst().deadlineNanos());
        var late = new ScaffoldPlanner().plan(Preset.Narrow, at(.5,.5,4,0,1,0,10,Long.MAX_VALUE - 10,1_000_000_000), EMPTY);
        assertTrue(late.candidates().stream().allMatch(c -> c.deadlineNanos() >= Long.MAX_VALUE - 10));
    }

    @Test void fullSupportedRowsHaveConfirmedWidthAndNoMissingCandidatesOrHoles() {
        var plan = new ScaffoldPlanner().plan(Preset.Wide, at(.5,.5,0,0,0,1,6,0,0), c -> true);
        assertTrue(plan.desiredWidth() > 9);
        assertEquals(plan.desiredWidth(), plan.confirmedWidth());
        assertEquals(0, plan.holes());
        assertTrue(plan.candidates().isEmpty());
    }

    @Test void narrowUsesObservedVelocityWithoutInputAndNegativeDirectionsRemainSymmetric() {
        var east = new ScaffoldPlanner().plan(Preset.Narrow, at(.5,.5,4,0,0,0,10,0,500_000_000), EMPTY);
        var west = new ScaffoldPlanner().plan(Preset.Narrow, at(-.5,-.5,-4,0,0,0,10,0,500_000_000), EMPTY);
        assertTrue(cells(east).contains(new Cell(2,-1,0)));
        assertEquals(cells(east).stream().map(c -> new Cell(-c.x()-1,c.y(),-c.z()-1)).collect(Collectors.toSet()), cells(west));
    }

    @Test void stationaryInputSelectsInitialWideHeadingWithoutConsultingViewOrInventingMotion() {
        var east = new ScaffoldPlanner().plan(Preset.Wide, at(.5,.5,0,0,1,0,5,0,0), EMPTY);
        var south = new ScaffoldPlanner().plan(Preset.Wide, at(.5,.5,0,0,0,1,5,0,0), EMPTY);
        assertEquals(cells(east).stream().map(c -> new Cell(c.z(),c.y(),c.x())).collect(Collectors.toSet()), cells(south));
        assertFalse(cells(east).contains(new Cell(-3,-1,0)));
    }

    @Test void maximumAcceptedEnvelopeRemainsCompleteAndRepeatedSteeringDoesNotAccumulateUnreachableHistory() {
        ScaffoldPlanner planner = new ScaffoldPlanner();
        for (int step = 0; step < 20; step++) {
            var plan = planner.plan(Preset.Wide, at(step * 200 + .5,.5,0,1,0,1,64,step,0), EMPTY);
            assertTrue(plan.candidates().size() > 300);
            assertTrue(plan.candidates().size() < 20_000);
            long timestamp = step;
            assertTrue(plan.candidates().stream().allMatch(c -> c.firstSeenNanos() == timestamp));
        }
    }

    @Test void invalidAndUnreasonableInputCannotEnumerateAnUnboundedWorld() {
        ScaffoldPlanner planner = new ScaffoldPlanner();
        assertTrue(planner.plan(Preset.Wide, at(Double.NaN,0,0,0,0,1,4.5,0,0), EMPTY).candidates().isEmpty());
        assertTrue(planner.plan(Preset.Wide, at(0,0,0,0,0,1,Double.POSITIVE_INFINITY,0,0), EMPTY).candidates().isEmpty());
        assertTrue(planner.plan(Preset.Wide, at(0,0,0,0,0,1,1e8,0,0), EMPTY).candidates().isEmpty());
        assertTrue(planner.plan(Preset.Wide, at(1e20,0,0,0,0,1,4.5,0,0), EMPTY).candidates().isEmpty());
        assertTrue(planner.plan(null, at(0,0,0,0,0,1,4.5,0,0), EMPTY).candidates().isEmpty());
    }

    @Test void planCandidateListIsImmutableAndCannotCorruptPlannerHistory() {
        var plan = new ScaffoldPlanner().plan(Preset.Wide, at(.5,.5,0,0,0,1,4.5,0,0), EMPTY);
        assertThrows(UnsupportedOperationException.class, () -> plan.candidates().clear());
    }
}

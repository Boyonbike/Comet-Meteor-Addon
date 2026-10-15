package dev.tntcoating.core;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SpongePlannerTest {
    private final SpongePlanner.Cell origin = new SpongePlanner.Cell(0,0,0);
    @Test void emptyRegionDoesNotWasteSponge() { assertEquals(0,SpongePlanner.score(origin,p->false)); }
    @Test void stopsAtSixConnectedSteps() {
        assertEquals(6,SpongePlanner.score(origin,p->p.y()==0 && p.z()==0 && p.x()>0 && p.x()<20));
    }
    @Test void capsDenseWaterAt64() { assertEquals(64,SpongePlanner.score(origin,p->true)); }
    @Test void cannotAbsorbThroughDryOrSolidBarrier() {
        assertEquals(0,SpongePlanner.score(origin,p->p.x()==2 && p.y()==0 && p.z()==0));
    }
    @Test void connectedDrainableCellsCountOnce() {
        Set<SpongePlanner.Cell> water = Set.of(new SpongePlanner.Cell(1,0,0),new SpongePlanner.Cell(1,0,1),new SpongePlanner.Cell(0,0,1));
        assertEquals(3,SpongePlanner.score(origin,water::contains));
    }
    @Test void scoresBothSidesAndFindsBetterCoverage() {
        Set<SpongePlanner.Cell> water = new HashSet<>();
        for(int i=1;i<=10;i++) water.add(new SpongePlanner.Cell(i,0,0));
        assertEquals(6,SpongePlanner.score(origin,water::contains));
        assertEquals(9,SpongePlanner.score(new SpongePlanner.Cell(5,0,0),water::contains));
    }
}

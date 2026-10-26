package dev.tntcoating.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AutoTpyPacerTest {
    @Test void fixedBoundaryZeroAndLagNeverBurst() {
        var p=new AutoTpyPacer(); p.configure(500,false,50,5000); assertTrue(p.ready(0,0)); p.sent(0,0);
        assertFalse(p.ready(499,1)); assertTrue(p.ready(500,1)); p.sent(500,1);
        assertTrue(p.ready(99999,2)); p.sent(99999,2); assertFalse(p.ready(99999,2));
        p.configure(0,false,50,5000); assertFalse(p.ready(99999,2)); assertTrue(p.ready(99999,3));
    }
    @Test void distinctSuccessesAccelerateAndRateLimitPausesBeyondBounds() {
        var p=new AutoTpyPacer(); p.configure(500,true,50,5000);
        for(int i=0;i<9;i++) p.confirmed(i,i); p.confirmed(8,10); assertEquals(500,p.intervalMs());
        p.confirmed(9,11); assertEquals(450,p.intervalMs());
        p.rateLimited("a",30000L,20); assertEquals(900,p.intervalMs()); assertFalse(p.ready(30019,1)); assertTrue(p.ready(30020,1));
        p.rateLimited("a",30000L,21); assertEquals(900,p.intervalMs());
        p.configure(500,false,50,5000); assertEquals(500,p.intervalMs()); assertFalse(p.ready(1000,2));
    }
    @Test void invalidBoundsAreAtomicAndResetRestoresStart() {
        var p=new AutoTpyPacer(); p.configure(500,true,50,5000); p.rateLimited("a",null,0); assertEquals(1000,p.intervalMs());
        assertThrows(IllegalArgumentException.class,()->p.configure(0,true,500,50)); assertEquals(1000,p.intervalMs());
        p.resetLearning(); assertEquals(500,p.intervalMs());
    }
}

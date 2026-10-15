package dev.tntcoating.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RetryScheduleTest {
    @Test void repeatedFailuresBackOffInsteadOfSpamming() {
        assertEquals(3,RetrySchedule.delay(3,1));
        assertEquals(6,RetrySchedule.delay(3,2));
        assertEquals(12,RetrySchedule.delay(3,3));
    }
    @Test void longFailureStreakRemainsBounded() {
        assertEquals(40,RetrySchedule.delay(3,1000));
        assertEquals(40,RetrySchedule.delay(40,3));
    }
    @Test void resetStreakReturnsToBaseDelay() {
        assertEquals(24,RetrySchedule.delay(3,4));
        assertEquals(3,RetrySchedule.delay(3,1));
    }
}

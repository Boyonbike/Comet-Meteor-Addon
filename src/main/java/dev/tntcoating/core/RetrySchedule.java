package dev.tntcoating.core;
public final class RetrySchedule {
    private RetrySchedule() {}
    public static int delay(int base, int failures) {
        return Math.min(40, base * (1 << Math.min(4, Math.max(0, failures - 1))));
    }
}

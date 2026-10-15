package dev.tntcoating.core;
public final class FlightPolicy {
    private FlightPolicy() {}
    public static boolean allowPlacement(boolean enabled, boolean detectedFlight, boolean grounded, boolean jumping) {
        return enabled || (!detectedFlight && (grounded || jumping));
    }
}

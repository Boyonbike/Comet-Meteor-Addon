package dev.tntcoating.core;

/**
 * Rewrites the flight-feel goldens from the current pipeline. Run from the project directory, only for a
 * deliberate change of feel, and say why in the commit:
 * {@code java -cp build/classes/java/test:build/classes/java/main:<minecraft and libraries> dev.tntcoating.core.FlightFeelRecorder}
 */
public final class FlightFeelRecorder {
    private FlightFeelRecorder() {}

    public static void main(String[] args) throws Exception {
        FlightFeelScenarios.record(new LegacyFeelDriver());
        System.out.println("Recorded flight feel into " + FlightFeelScenarios.GOLDEN_DIRECTORY.toAbsolutePath());
    }
}

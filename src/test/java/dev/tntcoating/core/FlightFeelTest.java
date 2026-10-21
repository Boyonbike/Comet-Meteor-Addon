package dev.tntcoating.core;

import dev.tntcoating.core.FlightFeelScenarios.Frame;
import dev.tntcoating.core.FlightFeelScenarios.Profile;
import dev.tntcoating.core.FlightFeelScenarios.Scenario;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The recorded flight feel: every scripted flight must reproduce its golden per-tick movement and boost ramps.
 * A difference here is a change of feel; regenerate the goldens with {@link FlightFeelRecorder} only on purpose.
 */
class FlightFeelTest {
    private static final double TOLERANCE = 1e-12;
    private static final Supplier<FlightFeelScenarios.Driver> DRIVER = EngineFeelDriver::new;

    @TestFactory List<DynamicTest> everyScenarioMatchesItsRecordedFeel() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Profile profile : FlightFeelScenarios.PROFILES) for (Scenario scenario : FlightFeelScenarios.scenarios()) {
            tests.add(DynamicTest.dynamicTest(profile.name() + " " + scenario.name(), () -> {
                List<Frame> expected = FlightFeelScenarios.golden(profile, scenario);
                List<Frame> actual = FlightFeelScenarios.run(DRIVER.get(), profile, scenario);
                assertEquals(expected.size(), actual.size(), "ticks");
                for (int tick = 0; tick < expected.size(); tick++) {
                    Frame want = expected.get(tick), got = actual.get(tick);
                    String where = scenario.name() + " tick " + tick;
                    assertEquals(want.motion().x(), got.motion().x(), TOLERANCE, where + " x");
                    assertEquals(want.motion().y(), got.motion().y(), TOLERANCE, where + " y");
                    assertEquals(want.motion().z(), got.motion().z(), TOLERANCE, where + " z");
                    assertEquals(want.horizontalRamp(), got.horizontalRamp(), TOLERANCE, where + " horizontal ramp");
                    assertEquals(want.verticalRamp(), got.verticalRamp(), TOLERANCE, where + " vertical ramp");
                }
            }));
        }
        return tests;
    }

    /** The scripted flights must actually exercise what they are named after, or the goldens prove little. */
    @TestFactory List<DynamicTest> scenariosExerciseTheirFeatures() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Profile profile : FlightFeelScenarios.PROFILES) {
            tests.add(DynamicTest.dynamicTest(profile.name(), () -> {
                for (Scenario scenario : FlightFeelScenarios.scenarios()) {
                    List<Frame> frames = FlightFeelScenarios.run(DRIVER.get(), profile, scenario);
                    double top = frames.stream().mapToDouble(f -> f.motion().horizontalLength()).max().orElse(0);
                    double ramp = frames.stream().mapToDouble(Frame::horizontalRamp).max().orElse(0);
                    double climb = frames.stream().mapToDouble(f -> f.motion().y()).max().orElse(0);
                    double verticalRamp = frames.stream().mapToDouble(Frame::verticalRamp).max().orElse(0);
                    Frame last = frames.get(frames.size() - 1);
                    switch (scenario.name()) {
                        case "boost-release", "turns-boost" -> {
                            assertTrue(top > 14, scenario.name() + " reaches boost speed");
                            assertTrue(last.motion().horizontalLength() < 3, scenario.name() + " eases back to cruise");
                        }
                        // Only Meteor's fall-multiplier sink remains once W is released.
                        case "cruise-release" -> assertEquals(0, last.motion().horizontalLength(), 1e-9);
                        case "climb-boost-only" -> assertTrue(verticalRamp > 2, "vertical boost ramps");
                        case "vertical-cruise", "vertical-boost" -> assertTrue(climb > .5, scenario.name() + " climbs");
                        case "vertical-reversal" -> {
                            for (int tick : new int[] {50, 90, 140}) {
                                double before = frames.get(tick - 1).motion().y(), after = frames.get(tick).motion().y();
                                assertTrue(Math.abs(before) > .5 && before * after < 0, "turns around at once at tick " + tick);
                            }
                        }
                        case "wall-cruise-crawl", "wall-boost", "wall-glide-guard", "chunk-edge-stop" ->
                            assertTrue(last.motion().horizontalLength() < .7, scenario.name() + " stops or crawls at the edge");
                        case "chunk-edge-fly-into" -> assertTrue(last.motion().horizontalLength() > 14, "flies on into missing chunks");
                        case "landing" -> {
                            double dive = frames.stream().mapToDouble(f -> f.motion().y()).min().orElse(0);
                            assertTrue(dive < -.5, "dives before the floor");
                            assertEquals(-AdaptiveFlightStep.LANDING_SPEED, last.motion().y(), 1e-9,
                                "touches down at the soft-landing speed");
                        }
                        default -> assertTrue(top > .5 || ramp > 0, scenario.name() + " moves");
                    }
                }
            }));
        }
        return tests;
    }
}

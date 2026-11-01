/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * client and server classes; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest;

/** Small lambda-backed scenario used for the short Phase 0.4 scripts. */
public final class ScriptedScenario implements Scenario {
    @FunctionalInterface
    public interface Step {
        void run(ScenarioContext context) throws Exception;
    }

    private final String name;
    private final Step setup;
    private final Step run;

    public ScriptedScenario(String name, Step setup, Step run) {
        this.name = name;
        this.setup = setup;
        this.run = run;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void setup(ScenarioContext context) throws Exception {
        setup.run(context);
    }

    @Override
    public void run(ScenarioContext context) throws Exception {
        run.run(context);
    }
}

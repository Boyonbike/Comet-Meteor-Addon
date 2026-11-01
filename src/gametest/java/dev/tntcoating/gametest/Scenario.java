/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * client and server classes; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest;

/** One scripted Phase 0.4 scenario. Scenarios never touch product combat code. */
public interface Scenario {
    String name();

    /** Terrain, items and target setup. Runs before the client trace is started. */
    default void setup(ScenarioContext context) throws Exception {
    }

    void run(ScenarioContext context) throws Exception;
}

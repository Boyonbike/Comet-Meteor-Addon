/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.CombatDamagePolicy;
import dev.tntcoating.core.combat.DirectGateway;
import dev.tntcoating.core.combat.DirectSettings;
import dev.tntcoating.core.combat.Safety;
import dev.tntcoating.core.combat.Vec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The production stack is built once per client session and outlives disable, so every component that
 * derives anything from {@link DirectSettings} must follow a live config change. A raised
 * {@code max-self-damage} has to reach the world reader's safety stamp, the gateway and the piston trap,
 * not only the planner.
 */
final class LiveSettingsReconfigureTest {
    private static final DirectSettings CONSERVATIVE =
        DirectSettings.conservative(CombatDamagePolicy.conservativeSafety());
    private static final DirectSettings RAISED =
        DirectSettings.conservative(new Safety(4.0, 14.0, 2.0, 2.0));

    @BeforeAll
    static void bootstrap() {
        AdapterTestSupport.bootstrap();
    }

    @Test
    void engineHostForwardsTheChangeToReaderAndGateway() {
        EngineHarness harness = EngineHarness.create(CONSERVATIVE, new Vec(0.5, 64.0, 0.5));
        AtomicReference<DirectSettings> readerSaw = new AtomicReference<>();
        AtomicReference<DirectSettings> gatewaySaw = new AtomicReference<>();
        DirectWorldSource reader = new DirectWorldSource() {
            @Override
            public dev.tntcoating.core.combat.DirectWorld read() {
                return null;
            }

            @Override
            public void reconfigure(DirectSettings settings) {
                readerSaw.set(settings);
            }
        };
        DirectGateway gateway = new DirectGateway() {
            @Override
            public DirectSendResult send(dev.tntcoating.core.combat.DirectDispatch dispatch) {
                return DirectSendResult.FAILED;
            }

            @Override
            public void reconfigure(DirectSettings settings) {
                gatewaySaw.set(settings);
            }
        };
        DirectEngineHost host = new DirectEngineHost(harness.runtime, reader, harness.engine, gateway);

        host.reconfigure(RAISED);

        assertSame(RAISED, readerSaw.get(), "the reader stamps the safety envelope into every world view");
        assertSame(RAISED, gatewaySaw.get(), "the gateway reads rotations from the live settings");
    }

    @Test
    void pistonTrapWorldUsesTheNewLimits() {
        PistonTrapWorld world = new PistonTrapWorld(() -> null, new CombatDamageAdapter(), CONSERVATIVE,
            CONSERVATIVE.safety());
        assertEquals(6.0, world.selfDamageCeiling(), 1e-9);

        world.reconfigure(RAISED);

        assertEquals(14.0, world.selfDamageCeiling(), 1e-9);
        assertEquals(RAISED.safety(), world.safety());
    }

    @Test
    void controllerReconfigureReachesThePistonTrap() throws IOException {
        String source = Files.readString(
            Path.of("src/main/java/dev/tntcoating/core/combat/mc/CrystalCombatController.java"));
        int start = source.indexOf("public void reconfigure(");
        int end = source.indexOf("\n    }\n", start);
        String body = source.substring(start, end);
        assertTrue(body.contains("stack.engineHost().reconfigure(settings)"));
        assertTrue(body.contains("stack.pistonTrap().reconfigure(settings)"),
            "the trap keeps its own world view and must see the same config change");
    }
}

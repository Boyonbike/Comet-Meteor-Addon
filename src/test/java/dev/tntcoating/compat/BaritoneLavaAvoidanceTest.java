package dev.tntcoating.compat;

import dev.tntcoating.core.SettingOverride;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BaritoneLavaAvoidanceTest {
    @Test void walkingLeaseForcesAllThreeSafetySettingsAndRestoresTheirPreferences() {
        AtomicReference<Boolean> walkOnLava = new AtomicReference<>(true);
        AtomicReference<Boolean> liquidCheck = new AtomicReference<>(false);
        AtomicReference<Boolean> parkour = new AtomicReference<>(true);
        var lease = lease(walkOnLava, liquidCheck, parkour);

        lease.set(true);

        assertFalse(walkOnLava.get());
        assertTrue(liquidCheck.get());
        assertFalse(parkour.get());
        lease.set(false);
        assertTrue(walkOnLava.get());
        assertFalse(liquidCheck.get());
        assertTrue(parkour.get());
    }

    @Test void repeatedActivationDoesNotReplaceSavedPreferencesWithForcedValues() {
        AtomicReference<Boolean> walkOnLava = new AtomicReference<>(true);
        AtomicReference<Boolean> liquidCheck = new AtomicReference<>(false);
        AtomicReference<Boolean> parkour = new AtomicReference<>(true);
        var lease = lease(walkOnLava, liquidCheck, parkour);

        lease.set(true);
        lease.set(true);
        lease.set(false);
        lease.set(false);

        assertTrue(walkOnLava.get());
        assertFalse(liquidCheck.get());
        assertTrue(parkour.get());
    }

    @Test void thirdSettingFailureRollsBackTheWholePartiallyAcquiredLease() {
        AtomicReference<Boolean> walkOnLava = new AtomicReference<>(true);
        AtomicReference<Boolean> liquidCheck = new AtomicReference<>(false);
        AtomicReference<Boolean> parkour = new AtomicReference<>(true);
        var lease = new BaritoneElytraBridge.LavaAvoidance(
            override(walkOnLava, false), override(liquidCheck, true),
            new SettingOverride<>(parkour::get, value -> {
                parkour.set(value);
                if (!value) throw new IllegalStateException("Parkour override rejected");
            }, false));

        assertThrows(IllegalStateException.class, () -> lease.set(true));

        assertTrue(walkOnLava.get());
        assertFalse(liquidCheck.get());
        assertTrue(parkour.get());
    }

    @Test void releaseStillRestoresOtherSettingsWhenOneSettingRejectsRestoration() {
        AtomicReference<Boolean> walkOnLava = new AtomicReference<>(true);
        AtomicReference<Boolean> liquidCheck = new AtomicReference<>(false);
        AtomicReference<Boolean> parkour = new AtomicReference<>(true);
        var lease = new BaritoneElytraBridge.LavaAvoidance(
            override(walkOnLava, false), override(liquidCheck, true),
            new SettingOverride<>(parkour::get, value -> {
                if (value) throw new IllegalStateException("Parkour restore rejected");
                parkour.set(value);
            }, false));
        lease.set(true);

        assertThrows(IllegalStateException.class, () -> lease.set(false));

        assertTrue(walkOnLava.get());
        assertFalse(liquidCheck.get());
    }

    @Test void releasePreservesPreferencesEditedByTheUserWhileWalking() {
        AtomicReference<Boolean> walkOnLava = new AtomicReference<>(false);
        AtomicReference<Boolean> liquidCheck = new AtomicReference<>(true);
        AtomicReference<Boolean> parkour = new AtomicReference<>(false);
        var lease = lease(walkOnLava, liquidCheck, parkour);
        lease.set(true);
        walkOnLava.set(true);
        liquidCheck.set(false);
        parkour.set(true);

        lease.set(false);

        assertTrue(walkOnLava.get());
        assertFalse(liquidCheck.get());
        assertTrue(parkour.get());
    }

    private static BaritoneElytraBridge.LavaAvoidance lease(AtomicReference<Boolean> walkOnLava,
                                                           AtomicReference<Boolean> liquidCheck,
                                                           AtomicReference<Boolean> parkour) {
        return new BaritoneElytraBridge.LavaAvoidance(
            override(walkOnLava, false), override(liquidCheck, true), override(parkour, false));
    }

    private static SettingOverride<Boolean> override(AtomicReference<Boolean> value, boolean forced) {
        return new SettingOverride<>(value::get, value::set, forced);
    }
}

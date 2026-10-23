package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettingOverrideTest {
    @Test void acquireForcesTheValueAndReleaseRestoresTheOriginal() {
        AtomicReference<Integer> setting = new AtomicReference<>(5);
        SettingOverride<Integer> override = new SettingOverride<>(setting::get, setting::set, -1);

        override.acquire();
        assertEquals(-1, setting.get());

        override.release();
        assertEquals(5, setting.get());
    }

    @Test void refreshingDoesNotSaveTheForcedValueAsTheOriginal() {
        AtomicReference<Double> setting = new AtomicReference<>(1.2);
        SettingOverride<Double> override = new SettingOverride<>(setting::get, setting::set, 0.0);

        override.acquire();
        override.acquire();
        assertEquals(0.0, setting.get());

        override.release();
        assertEquals(1.2, setting.get());
    }

    @Test void refreshingReassertsTheOverrideAndSavesTheLatestExternalEdit() {
        AtomicReference<Integer> setting = new AtomicReference<>(5);
        SettingOverride<Integer> override = new SettingOverride<>(setting::get, setting::set, -1);

        override.acquire();
        setting.set(8);
        override.acquire();
        assertEquals(-1, setting.get());

        setting.set(12);
        override.acquire();
        override.release();
        assertEquals(12, setting.get());
    }

    @Test void releasePreservesAnExternalEditMadeSinceTheLastRefresh() {
        AtomicReference<Integer> setting = new AtomicReference<>(5);
        SettingOverride<Integer> override = new SettingOverride<>(setting::get, setting::set, -1);

        override.acquire();
        setting.set(9);
        override.release();

        assertEquals(9, setting.get());
    }

    @Test void repeatedReleaseDoesNotRestoreOverASubsequentEdit() {
        AtomicReference<Integer> setting = new AtomicReference<>(5);
        SettingOverride<Integer> override = new SettingOverride<>(setting::get, setting::set, -1);

        override.release();
        override.acquire();
        override.release();
        setting.set(15);
        override.release();

        assertEquals(15, setting.get());
    }

    @Test void aNewLeaseRestoresTheValueFromItsOwnAcquisition() {
        AtomicReference<Integer> setting = new AtomicReference<>(5);
        SettingOverride<Integer> override = new SettingOverride<>(setting::get, setting::set, -1);

        override.acquire();
        override.release();
        setting.set(11);
        override.acquire();
        assertEquals(-1, setting.get());
        override.release();

        assertEquals(11, setting.get());
    }
}

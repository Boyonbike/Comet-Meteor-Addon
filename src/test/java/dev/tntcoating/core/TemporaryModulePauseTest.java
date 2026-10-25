package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TemporaryModulePauseTest {
    @Test void pausesOnlyActiveModulesAndRestoresOnlyThoseModules() {
        TestModule active = new TestModule("active", true);
        TestModule inactive = new TestModule("inactive", false);
        TemporaryModulePause<TestModule> pause = pause();

        pause.acquire(List.of(active, inactive));

        assertFalse(active.active);
        assertFalse(inactive.active);
        assertTrue(pause.wasActive(active));
        assertFalse(pause.wasActive(inactive));
        assertEquals(Set.of(active), pause.snapshot());

        pause.release();

        assertTrue(active.active);
        assertFalse(inactive.active);
        assertEquals(2, active.toggles);
        assertEquals(0, inactive.toggles);
        assertTrue(pause.snapshot().isEmpty());
    }

    @Test void repeatedAcquisitionAndReleasePreserveTheOriginalState() {
        TestModule module = new TestModule("flight", true);
        TemporaryModulePause<TestModule> pause = pause();

        pause.acquire(List.of(module, module));
        pause.acquire(List.of(module));
        pause.release();
        pause.release();

        assertTrue(module.active);
        assertEquals(2, module.toggles);
    }

    @Test void refreshingRepausesAModuleEnabledDuringTheLease() {
        TestModule module = new TestModule("flight", true);
        TemporaryModulePause<TestModule> pause = pause();
        pause.acquire(List.of(module));

        module.active = true;
        pause.acquire(List.of(module));
        assertFalse(module.active);

        pause.release();
        assertTrue(module.active);
        assertEquals(3, module.toggles);
    }

    @Test void releaseDoesNotToggleAnOwnedModuleThatIsAlreadyActive() {
        TestModule module = new TestModule("flight", true);
        TemporaryModulePause<TestModule> pause = pause();
        pause.acquire(List.of(module));
        module.active = true;

        pause.release();

        assertTrue(module.active);
        assertEquals(1, module.toggles);
        assertFalse(pause.wasActive(module));
    }

    @Test void restoresInReverseAcquisitionOrder() {
        List<String> restored = new ArrayList<>();
        TestModule first = new TestModule("first", true);
        TestModule second = new TestModule("second", true);
        TemporaryModulePause<TestModule> pause = new TemporaryModulePause<>(new TemporaryModulePause.Access<>() {
            public boolean active(TestModule module) { return module.active; }
            public void toggle(TestModule module) {
                module.toggle();
                if (module.active) restored.add(module.name);
            }
        });
        pause.acquire(List.of(first, second));

        pause.release();

        assertEquals(List.of("second", "first"), restored);
    }

    @Test void failedAcquisitionRollsBackNewPausesWithoutReleasingTheExistingLease() {
        TestModule existing = new TestModule("existing", true);
        TestModule first = new TestModule("first", true);
        TestModule failing = new TestModule("failing", true);
        TemporaryModulePause<TestModule> pause = pause();
        pause.acquire(List.of(existing));
        failing.failureBeforeToggle = new IllegalStateException("cannot deactivate");

        assertSame(failing.failureBeforeToggle,
            assertThrows(IllegalStateException.class, () -> pause.acquire(List.of(first, failing))));

        assertFalse(existing.active);
        assertTrue(first.active);
        assertTrue(failing.active);
        assertEquals(Set.of(existing), pause.snapshot());
        pause.release();
        assertTrue(existing.active);
    }

    @Test void failedDeactivationThatAlreadyChangedStateIsAlsoRolledBack() {
        TestModule failing = new TestModule("failing", true);
        TemporaryModulePause<TestModule> pause = pause();
        failing.failureAfterToggle = new IllegalStateException("failed after deactivation");

        assertThrows(IllegalStateException.class, () -> pause.acquire(List.of(failing)));

        assertTrue(failing.active);
        assertTrue(pause.snapshot().isEmpty());
    }

    @Test void releaseAttemptsEveryRestoreAndRetainsFailedRestoresForRetry() {
        TestModule first = new TestModule("first", true);
        TestModule failing = new TestModule("failing", true);
        TestModule last = new TestModule("last", true);
        TemporaryModulePause<TestModule> pause = pause();
        pause.acquire(List.of(first, failing, last));
        failing.failureBeforeToggle = new IllegalStateException("cannot restore");

        assertSame(failing.failureBeforeToggle, assertThrows(IllegalStateException.class, pause::release));

        assertTrue(first.active);
        assertFalse(failing.active);
        assertTrue(last.active);
        assertEquals(Set.of(failing), pause.snapshot());
        failing.failureBeforeToggle = null;
        pause.release();
        assertTrue(failing.active);
        assertTrue(pause.snapshot().isEmpty());
    }

    @Test void snapshotsKeepTheSavedPreferenceWhenTheLeaseChanges() {
        TestModule first = new TestModule("first", true);
        TestModule second = new TestModule("second", true);
        TemporaryModulePause<TestModule> pause = pause();
        pause.acquire(List.of(first));
        Set<TestModule> saved = pause.snapshot();

        pause.acquire(List.of(second));
        pause.release();

        assertEquals(Set.of(first), saved);
        assertThrows(UnsupportedOperationException.class, saved::clear);
    }

    @Test void clearDiscardsOwnershipWithoutChangingModulesAndAllowsAFreshLease() {
        TestModule module = new TestModule("flight", true);
        TemporaryModulePause<TestModule> pause = pause();
        pause.acquire(List.of(module));

        pause.clear();

        assertTrue(pause.snapshot().isEmpty());
        assertFalse(pause.wasActive(module));
        assertFalse(module.active);
        assertEquals(1, module.toggles);

        pause.release();
        assertFalse(module.active);
        assertEquals(1, module.toggles);

        module.active = true;
        pause.acquire(List.of(module));
        assertFalse(module.active);
        assertTrue(pause.wasActive(module));
        pause.release();
        assertTrue(module.active);
        assertEquals(3, module.toggles);
    }

    private static TemporaryModulePause<TestModule> pause() {
        return new TemporaryModulePause<>(new TemporaryModulePause.Access<>() {
            public boolean active(TestModule module) { return module.active; }
            public void toggle(TestModule module) { module.toggle(); }
        });
    }

    private static final class TestModule {
        final String name;
        boolean active;
        int toggles;
        RuntimeException failureBeforeToggle;
        RuntimeException failureAfterToggle;

        TestModule(String name, boolean active) {
            this.name = name;
            this.active = active;
        }

        void toggle() {
            if (failureBeforeToggle != null) throw failureBeforeToggle;
            active = !active;
            toggles++;
            RuntimeException failure = failureAfterToggle;
            failureAfterToggle = null;
            if (failure != null) throw failure;
        }
    }
}

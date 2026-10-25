package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Temporarily pauses active modules without changing their saved enabled preference. */
public final class TemporaryModulePause<M> {
    public interface Access<M> {
        boolean active(M module);
        void toggle(M module);
    }

    private final Access<M> access;
    private final Set<M> paused = new LinkedHashSet<>();

    public TemporaryModulePause(Access<M> access) {
        this.access = Objects.requireNonNull(access);
    }

    /** Pauses active candidates, retaining earlier acquisitions when refreshing the lease. */
    public void acquire(Iterable<? extends M> modules) {
        List<M> acquired = new ArrayList<>();
        try {
            for (M module : modules) {
                if (!access.active(module)) continue;
                // Record ownership first: a deactivation callback can throw after toggling.
                if (paused.add(module)) acquired.add(module);
                access.toggle(module);
            }
        } catch (RuntimeException | Error failure) {
            Throwable rollbackFailure = restore(acquired);
            if (rollbackFailure != null && rollbackFailure != failure) failure.addSuppressed(rollbackFailure);
            throw failure;
        }
    }

    /** Restores every owned module; failed restores stay owned so a later release can retry. */
    public void release() {
        Throwable failure = restore(new ArrayList<>(paused));
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }

    /** Whether this lease owns the module's enabled preference, even while it is paused. */
    public boolean wasActive(M module) {
        return paused.contains(module);
    }

    /** An immutable copy suitable for preserving enabled preferences in saved configuration. */
    public Set<M> snapshot() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(paused));
    }

    /** Drops ownership without touching the modules, for a caller that is abandoning the lease. */
    public void clear() {
        paused.clear();
    }

    private Throwable restore(List<M> modules) {
        Throwable failure = null;
        for (int i = modules.size() - 1; i >= 0; i--) {
            M module = modules.get(i);
            try {
                if (!access.active(module)) access.toggle(module);
                paused.remove(module);
            } catch (RuntimeException | Error restoreFailure) {
                if (failure == null) failure = restoreFailure;
                else if (failure != restoreFailure) failure.addSuppressed(restoreFailure);
            }
        }
        return failure;
    }
}

/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * The single switch that keeps Meteor's own {@code CrystalAura} from sending while CrystalCombat owns combat.
 *
 * <p>Ordinarily only {@link dev.tntcoating.core.combat.mc.CombatRuntime} may send combat packets, so while
 * CrystalCombat is enabled Meteor's CrystalAura is suppressed at every audited send path. This class is the
 * state those paths consult; the mixin never mutates Meteor's enabled flag, settings or bookkeeping.</p>
 *
 * <p>Suppression is generation guarded. A delayed Meteor callback (the rotation queue runs callbacks after
 * the movement packet, on the client thread, in a later tick) captures the generation under which it was
 * created and re-checks both {@code active} and that generation when it executes. A callback created while
 * the gate was active never runs, including after a deactivate or a deactivate-then-reactivate; a callback
 * created while the gate was inactive runs only while the gate has stayed inactive. That way disabling
 * CrystalCombat cannot leave a queued attack or placement alive, while a gate that was never activated never
 * changes Meteor's behaviour.</p>
 *
 * <p>{@link #protects(UUID)} is a separate, read-only hazard exclusion. It is deliberately independent of
 * {@link #activate()}: a ledger-protected crystal must not be attacked by Meteor even while CrystalCombat is
 * disabled. The protector is supplied by the ledger owner; this class never changes ledger or entity state.</p>
 *
 * <p>All methods are safe from any thread. Activation and guarded execution share one lock, so a callback
 * cannot observe an inactive gate and then run a send after activation has begun; activation waits for an
 * in-flight guarded callback instead. The guarded callback runs while holding the lock, which is reentrant
 * for the gate methods it may call.</p>
 */
public final class CrystalCombatGate {
    private static final Object LOCK = new Object();

    /** Volatile so the per-send check never needs the lock. */
    private static volatile boolean active;
    /** Only touched under {@link #LOCK}; every transition that changes the meaning of a queued callback bumps it. */
    private static long generation;
    /** Read-only hazard exclusion; null means no protector is bound and nothing is protected. */
    private static volatile Predicate<UUID> protector;

    private CrystalCombatGate() {}

    /** Starts suppressing Meteor send paths. Idempotent: a second call while active changes nothing. */
    public static void activate() {
        synchronized (LOCK) {
            if (active) return;
            active = true;
            generation++;
        }
    }

    /** Stops suppressing Meteor send paths and invalidates every callback queued while the gate was active. */
    public static void deactivate() {
        synchronized (LOCK) {
            if (!active) return;
            active = false;
            generation++;
        }
    }

    /** True while CrystalCombat is enabled and Meteor's own crystal paths must not send. */
    public static boolean suppressMeteor() {
        return active;
    }

    /**
     * True when the crystal is ledger protected and must not be attacked by Meteor, even while CrystalCombat
     * is disabled. Read-only: no ledger, entity or module state is touched here.
     */
    public static boolean protects(UUID crystal) {
        Predicate<UUID> current = protector;
        return crystal != null && current != null && current.test(crystal);
    }

    /** Binds the read-only protection predicate; {@code null} unbinds it. */
    public static void setProtector(Predicate<UUID> newProtector) {
        protector = newProtector;
    }

    /**
     * Wraps a delayed Meteor callback so it can only send if the gate was inactive and stayed inactive since
     * the callback was created. A null callback stays null, matching the audited {@code Rotations.rotate}
     * calls that pass no callback.
     */
    public static Runnable guard(Runnable callback) {
        if (callback == null) return null;
        long createdAt;
        synchronized (LOCK) {
            createdAt = generation;
        }
        return () -> {
            synchronized (LOCK) {
                if (active || generation != createdAt) return;
                callback.run();
            }
        };
    }
}

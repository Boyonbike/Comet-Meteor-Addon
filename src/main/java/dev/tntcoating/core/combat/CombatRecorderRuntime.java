/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * Read-only runtime state sampled by {@link CombatRecorder}.
 *
 * <p>The interface deliberately exposes no send path: Phase 0's recorder must never dispatch. The Minecraft
 * host implements this from the client thread; tests pass a fake that fails if any send method is reached.</p>
 */
public interface CombatRecorderRuntime {
    /** Connection epoch, incremented for every new server connection. */
    long connectionId();

    /** Client tick counter, sampled for receipt records. */
    long clientTick();
}

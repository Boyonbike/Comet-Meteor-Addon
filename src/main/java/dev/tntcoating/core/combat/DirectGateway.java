/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * The engine's only route to packets. Production implements this by registering the plan context and calling
 * {@code CombatRuntime.dispatch}; the pure engine never calls a packet API itself.
 */
public interface DirectGateway {
    DirectSendResult send(DirectDispatch dispatch);

    /** Live settings change; a gateway that derives nothing from the settings ignores it. */
    default void reconfigure(DirectSettings settings) {
    }

    enum DirectSendResult {
        SENT,
        BLOCKED,
        FAILED
    }
}

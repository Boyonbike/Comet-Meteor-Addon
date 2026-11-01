/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/** One already-reserved action handed to the single dispatch path. */
public record DirectDispatch(Intent intent, DirectContext context, Ticket ticket, long plan,
                            SafetyState safetyState) {
    public DirectDispatch {
        if (intent == null) throw new IllegalArgumentException("intent");
        if (context == null) throw new IllegalArgumentException("context");
        if (ticket == null) throw new IllegalArgumentException("ticket");
        if (safetyState == null) throw new IllegalArgumentException("safetyState");
    }
}

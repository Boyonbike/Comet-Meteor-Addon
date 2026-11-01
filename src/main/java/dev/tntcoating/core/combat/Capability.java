/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/** Whether a health or absorption reading is usable, missing or hidden by the server. */
public enum Capability {
    KNOWN,
    UNKNOWN,
    CENSORED
}

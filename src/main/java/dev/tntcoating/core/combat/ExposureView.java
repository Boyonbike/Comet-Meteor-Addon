/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/** The pure raycast seam the exposure sampler uses; the adapter backs it with the pinned world. */
public interface ExposureView {
    RaycastHit raycast(Vec from, Vec to, ExposurePolicy policy);

    /** A world with nothing between any two points. */
    static ExposureView open() {
        return (from, to, policy) -> RaycastHit.clear(to);
    }
}

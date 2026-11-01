/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * How much of the world between an explosion and a sampled point counts as blocking.
 *
 * <p>{@link #FAITHFUL} is the exact collision-shape raycast of the current world plus any planned overlay
 * blocks; it is used for the expected value. {@link #TARGET_LOWER_BOUND} deliberately over-counts cover (any
 * non-air block, including soft cover Meteor's default factory ignores) so soft cover can never inflate
 * lethal eligibility. {@link #SELF_FRIEND_UPPER_BOUND} under-counts cover (only blast-resistant blocks, the
 * audited Meteor default) so it can never lower a self or friend upper bound.</p>
 */
public enum ExposurePolicy {
    FAITHFUL,
    TARGET_LOWER_BOUND,
    SELF_FRIEND_UPPER_BOUND
}

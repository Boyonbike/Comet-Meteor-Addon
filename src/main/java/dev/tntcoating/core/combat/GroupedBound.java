/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.List;
import java.util.Objects;

/**
 * Disabled-by-default grouped exposure bound. Grouping may only replace the plain sum when every runtime
 * proof obligation holds:
 *
 * <ul>
 *   <li>all member arrival intervals fit inside one window of {@code windowTicks};</li>
 *   <li>all members were priced against the same envelope revision;</li>
 *   <li>the caller proves no bypass damage, no armour break, no effect expiry and no cooldown reset can
 *       occur inside that window, and the grouping ratio property holds.</li>
 * </ul>
 *
 * <p>Any failure falls back to the plain sum. The class is deliberately never wired into a profile or the
 * default budget path; {@link CombatRiskBudget} supplies no proof data, so its grouped path always falls
 * back.
 */
public final class GroupedBound {
    public static final boolean DEFAULT_ENABLED = false;
    /** Immunity window W from the audit (C4): 10 ticks. */
    public static final long DEFAULT_WINDOW_TICKS = 10L;

    /**
     * One candidate component. The booleans are positive proofs: {@code true} means the corresponding
     * bypass/armour/effect/cooldown assumption was formally excluded, not merely assumed.
     */
    public record Member(double bound, ServerTickInterval arrival, long envelopeRevision,
                         boolean bypassExcluded, boolean armourStable, boolean effectsStable,
                         boolean cooldownStable, boolean ratioPropertyHolds) {
        public Member {
            Objects.requireNonNull(arrival, "arrival");
            if (!Double.isFinite(bound) || bound < 0.0) {
                throw new IllegalArgumentException("bound must be finite and non-negative");
            }
        }
    }

    private final boolean enabled;
    private final long windowTicks;

    public GroupedBound(boolean enabled) {
        this(enabled, DEFAULT_WINDOW_TICKS);
    }

    public GroupedBound(boolean enabled, long windowTicks) {
        if (windowTicks <= 0) throw new IllegalArgumentException("windowTicks must be positive");
        this.enabled = enabled;
        this.windowTicks = windowTicks;
    }

    public boolean enabled() {
        return enabled;
    }

    public long windowTicks() {
        return windowTicks;
    }

    /** Returns the grouped bound when every obligation holds, otherwise the plain sum. */
    public Bound bound(List<Member> members) {
        Objects.requireNonNull(members, "members");
        double sum = 0.0;
        for (Member member : members) {
            sum += member.bound();
        }
        if (!enabled || members.isEmpty()) return new Bound(sum, members.size());

        long minEarliest = Long.MAX_VALUE;
        long maxLatest = Long.MIN_VALUE;
        long revision = members.get(0).envelopeRevision();
        for (Member member : members) {
            if (member.arrival().earliest() < minEarliest) minEarliest = member.arrival().earliest();
            if (member.arrival().latest() > maxLatest) maxLatest = member.arrival().latest();
            if (member.envelopeRevision() != revision) return new Bound(sum, members.size());
            if (!member.bypassExcluded() || !member.armourStable() || !member.effectsStable()
                || !member.cooldownStable() || !member.ratioPropertyHolds()) {
                return new Bound(sum, members.size());
            }
        }
        if (maxLatest - minEarliest >= windowTicks) return new Bound(sum, members.size());

        double largest = 0.0;
        double second = 0.0;
        for (Member member : members) {
            double value = member.bound();
            if (value >= largest) {
                second = largest;
                largest = value;
            } else if (value > second) {
                second = value;
            }
        }
        return new Bound(largest + second, 1);
    }
}

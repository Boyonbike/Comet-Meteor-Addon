/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact behaviour and the project's own Phase 5 traces;
 * no third-party code. SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The exact sixteen measured piston layouts: four horizontal directions x feet/eye x torch/redstone.
 *
 * <p>The tester build exposes one beta toggle per combination. This type is the pure half of that
 * filter: the planner enumerates only enabled combinations and an empty selection leaves the PistonOnly
 * mode inert instead of silently falling back to a wider layout set. Ordering is deterministic so the
 * settings, docs and tests all agree on the same sixteen ids.</p>
 */
public final class PistonLayoutSelection {
    /** One measured layout plus its power source kind. The record is the stable set key. */
    public record Combination(PistonGeometry.Layout layout, PistonGeometry.PowerKind kind) {
        public Combination {
            Objects.requireNonNull(layout, "layout");
            Objects.requireNonNull(kind, "kind");
        }

        /** Stable lower-case id, for example {@code east-feet-torch}. */
        public String id() {
            return direction() + "-" + layout.heightName() + "-" + powerName();
        }

        public String direction() {
            PistonFacing facing = layout.facing();
            if (facing.dx() > 0) return "east";
            if (facing.dx() < 0) return "west";
            if (facing.dz() < 0) return "north";
            return "south";
        }

        public String powerName() {
            return kind == PistonGeometry.PowerKind.REDSTONE_TORCH ? "torch" : "redstone";
        }
    }

    private static final List<Combination> ALL = buildAll();

    private final Set<Combination> enabled;

    private PistonLayoutSelection(Set<Combination> enabled) {
        this.enabled = Set.copyOf(enabled);
    }

    /** Every combination, in direction/height/power order. */
    public static List<Combination> combinations() {
        return ALL;
    }

    public static PistonLayoutSelection all() {
        return new PistonLayoutSelection(new LinkedHashSet<>(ALL));
    }

    public static PistonLayoutSelection none() {
        return new PistonLayoutSelection(Set.of());
    }

    public static PistonLayoutSelection of(Collection<Combination> combinations) {
        Objects.requireNonNull(combinations, "combinations");
        return new PistonLayoutSelection(new LinkedHashSet<>(combinations));
    }

    /** Resolves a stable id (for example {@code north-eye-redstone}); empty when unknown. */
    public static Optional<Combination> byId(String id) {
        if (id == null) return Optional.empty();
        for (Combination combination : ALL) {
            if (combination.id().equals(id)) return Optional.of(combination);
        }
        return Optional.empty();
    }

    public boolean enabled(Combination combination) {
        return combination != null && enabled.contains(combination);
    }

    public boolean enabled(PistonGeometry.Layout layout, PistonGeometry.PowerKind kind) {
        return enabled.contains(new Combination(layout, kind));
    }

    /** Number of enabled combinations; {@code 0} means the mode is inert by configuration. */
    public int count() {
        return enabled.size();
    }

    public boolean empty() {
        return enabled.isEmpty();
    }

    public Set<Combination> enabledCombinations() {
        return enabled;
    }

    public List<String> enabledIds() {
        List<String> ids = new ArrayList<>();
        for (Combination combination : ALL) {
            if (enabled.contains(combination)) ids.add(combination.id());
        }
        return List.copyOf(ids);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PistonLayoutSelection selection && enabled.equals(selection.enabled);
    }

    @Override
    public int hashCode() {
        return enabled.hashCode();
    }

    @Override
    public String toString() {
        return "PistonLayoutSelection" + enabledIds();
    }

    private static List<Combination> buildAll() {
        List<Combination> all = new ArrayList<>(16);
        for (PistonGeometry.Layout layout : PistonGeometry.baseLayouts()) {
            for (PistonGeometry.PowerKind kind : PistonGeometry.PowerKind.values()) {
                all.add(new Combination(layout, kind));
            }
        }
        return List.copyOf(all);
    }
}

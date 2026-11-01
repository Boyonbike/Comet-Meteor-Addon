/*
 * Original Comet implementation by Boyonabike.
 * Independent: test doubles written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** P5.2 beta layout toggles: exactly the sixteen measured direction x height x power combinations. */
class PistonLayoutSelectionTest {
    private static final List<String> EXPECTED_IDS = List.of(
        "east-feet-torch", "east-feet-redstone", "north-feet-torch", "north-feet-redstone",
        "south-feet-torch", "south-feet-redstone", "west-feet-torch", "west-feet-redstone",
        "east-eye-torch", "east-eye-redstone", "north-eye-torch", "north-eye-redstone",
        "south-eye-torch", "south-eye-redstone", "west-eye-torch", "west-eye-redstone");

    @Test
    void exactlyTheSixteenMeasuredCombinationsExistWithStableIds() {
        List<PistonLayoutSelection.Combination> all = PistonLayoutSelection.combinations();
        assertEquals(16, all.size());
        Set<String> ids = new LinkedHashSet<>();
        for (PistonLayoutSelection.Combination combination : all) ids.add(combination.id());
        assertEquals(16, ids.size(), "every combination has a distinct id");
        assertEquals(new LinkedHashSet<>(EXPECTED_IDS), ids);
        assertEquals(PistonLayoutSelection.all().count(), 16);
        assertEquals(List.copyOf(EXPECTED_IDS), PistonLayoutSelection.all().enabledIds());
    }

    @Test
    void everyIdRoundTripsAndDirectionsHeightsAndPowersAreExact() {
        for (String id : EXPECTED_IDS) {
            Optional<PistonLayoutSelection.Combination> combination = PistonLayoutSelection.byId(id);
            assertTrue(combination.isPresent(), id);
            assertEquals(id, combination.orElseThrow().id());
        }
        assertTrue(PistonLayoutSelection.byId("up-feet-torch").isEmpty());
        assertTrue(PistonLayoutSelection.byId(null).isEmpty());

        Set<String> directions = new LinkedHashSet<>();
        Set<String> heights = new LinkedHashSet<>();
        Set<String> powers = new LinkedHashSet<>();
        for (PistonLayoutSelection.Combination combination
            : PistonLayoutSelection.combinations()) {
            directions.add(combination.direction());
            heights.add(combination.layout().heightName());
            powers.add(combination.powerName());
        }
        assertEquals(Set.of("east", "west", "north", "south"), directions);
        assertEquals(Set.of("feet", "eye"), heights);
        assertEquals(Set.of("torch", "redstone"), powers);
    }

    @Test
    void eachCombinationCanBeDisabledIndividually() {
        List<PistonLayoutSelection.Combination> enabled = new ArrayList<>();
        PistonLayoutSelection.Combination removed = null;
        for (PistonLayoutSelection.Combination combination
            : PistonLayoutSelection.combinations()) {
            if (removed == null && combination.id().equals("north-eye-redstone")) {
                removed = combination;
                continue;
            }
            enabled.add(combination);
        }
        PistonLayoutSelection selection = PistonLayoutSelection.of(enabled);
        assertEquals(15, selection.count());
        assertFalse(selection.enabled(removed));
        assertFalse(selection.enabled(removed.layout(), removed.kind()));
        assertTrue(selection.enabled(PistonGeometry.baseLayouts().get(0),
            PistonGeometry.PowerKind.REDSTONE_TORCH));
        assertThrows(NullPointerException.class, () -> PistonLayoutSelection.of(null));
    }

    @Test
    void noneIsEmptyAndAllIsTheDefault() {
        assertTrue(PistonLayoutSelection.none().empty());
        assertEquals(0, PistonLayoutSelection.none().count());
        assertFalse(PistonLayoutSelection.all().empty());
        assertEquals(PistonLayoutSelection.all(), PistonLayoutSelection.of(
            PistonLayoutSelection.combinations()));
    }
}

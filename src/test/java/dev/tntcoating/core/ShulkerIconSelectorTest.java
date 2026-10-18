package dev.tntcoating.core;

import dev.tntcoating.core.ShulkerIconSelector.Entry;
import dev.tntcoating.core.ShulkerIconSelector.Rule;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShulkerIconSelectorTest {
    @Test void selectedComboRetainsItsOwnStyleAndColourEvenWhenIconsAreTheSame() {
        var red = new Rule<>("Red", true, List.of("helmet"), "sword", ShulkerIconSelector.Style.SolidColour, 0xffc01234);
        var blue = new Rule<>("Blue", true, List.of("rockets"), "sword", ShulkerIconSelector.Style.SolidColour, 0xff1234c0);
        var contents = List.of(new Entry<>("helmet", 1), new Entry<>("rockets", 64));
        assertEquals(new ShulkerIconSelector.Icon<>("sword", ShulkerIconSelector.Style.SolidColour, 0xffc01234),
            ShulkerIconSelector.selectIcon(contents, List.of(red, blue)));
        assertEquals(0xff1234c0, ShulkerIconSelector.selectIcon(contents, List.of(blue, red)).colour());
    }

    @Test void legacyRulesAndAutomaticFallbackUseNormalItemIcons() {
        var legacy = new Rule<>("Old combo", true, List.of("helmet"), "sword");
        assertEquals(ShulkerIconSelector.Style.Normal, legacy.style());
        assertEquals(new ShulkerIconSelector.Icon<>("rockets", ShulkerIconSelector.Style.Normal, 0xffffffff),
            ShulkerIconSelector.selectIcon(List.of(new Entry<>("rockets", 64)), List.of(legacy)));
    }

    @Test void aggregatesQuantitiesAcrossSlotsRatherThanCountingStacks() {
        assertEquals("stone", select(List.of(new Entry<>("rockets", 64), new Entry<>("stone", 40),
            new Entry<>("stone", 40)), List.of()));
        assertEquals("rockets", select(List.of(new Entry<>("sword", 1), new Entry<>("sword", 1),
            new Entry<>("rockets", 64)), List.of()));
    }

    @Test void tiesUseFirstOccupiedSlotEvenIfAnotherItemReachesItsFinalCountFirst() {
        assertEquals("stone", select(List.of(new Entry<>("stone", 10), new Entry<>("rockets", 64),
            new Entry<>("stone", 54)), List.of()));
    }

    @Test void comboRequiresEverySelectedItemButAllowsExtraContents() {
        var rules = List.of(new Rule<>("Armour kit", true, List.of("helmet", "chestplate", "rockets"), "totem"));
        assertEquals("totem", select(List.of(new Entry<>("helmet", 1), new Entry<>("chestplate", 1),
            new Entry<>("rockets", 64), new Entry<>("food", 64)), rules));
        assertEquals("rockets", select(List.of(new Entry<>("helmet", 1), new Entry<>("rockets", 64)), rules));
    }

    @Test void firstMatchingEnabledRuleWins() {
        var contents = List.of(new Entry<>("helmet", 1), new Entry<>("rockets", 64));
        var broad = new Rule<>("Travel", true, List.of("rockets"), "elytra");
        var specific = new Rule<>("Kit", true, List.of("helmet", "rockets"), "totem");
        assertEquals("elytra", select(contents, List.of(broad, specific)));
        assertEquals("totem", select(contents, List.of(specific, broad)));
        assertEquals("totem", select(contents, List.of(new Rule<>("Disabled", false, List.of("rockets"), "stone"), specific)));
    }

    @Test void incompleteRulesAndEmptySlotsDoNotMatch() {
        var rules = List.of(new Rule<>("New combo", true, List.<String>of(), "totem"),
            new Rule<>("No icon", true, List.of("stone"), (String) null));
        assertEquals("stone", select(List.of(new Entry<>("stone", 3), new Entry<>("rockets", 0),
            new Entry<>(null, 64), new Entry<>("helmet", -1)), rules));
        assertNull(select(List.of(), rules));
        assertNull(select(List.of(new Entry<>("stone", 0)), rules));
    }

    @Test void ruleOwnsItsRequiredItems() {
        var required = new java.util.ArrayList<>(List.of("helmet", "rockets"));
        var rule = new Rule<>("Kit", true, required, "totem");
        required.clear();
        assertEquals(List.of("helmet", "rockets"), rule.required());
    }

    private static String select(List<Entry<String>> contents, List<Rule<String>> rules) {
        return ShulkerIconSelector.select(contents, rules);
    }
}

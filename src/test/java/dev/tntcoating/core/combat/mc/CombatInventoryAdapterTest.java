/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.CombatSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Hotbar-only ownership, switching and restore behaviour. */
final class CombatInventoryAdapterTest {
    private record Fixture(CombatTestFakes.FakeClient client, CombatTestFakes.FakeSink sink,
                           CombatInventoryAdapter adapter) {
    }

    private static CombatInventoryAdapter.Ownership ownership(boolean loan, boolean weaponAttack) {
        return new CombatInventoryAdapter.Ownership() {
            @Override
            public boolean loanActive() {
                return loan;
            }

            @Override
            public boolean weaponAttackActive() {
                return weaponAttack;
            }
        };
    }

    private static Fixture fixture(boolean loan, boolean weaponAttack) {
        CombatSession session = new CombatSession();
        session.open();
        CombatTestFakes.FakeClient client = new CombatTestFakes.FakeClient(session.connection());
        CombatTestFakes.FakeSink sink = new CombatTestFakes.FakeSink();
        return new Fixture(client, sink,
            new CombatInventoryAdapter(client, ownership(loan, weaponAttack)));
    }

    @Test
    void switchToMovesSelectionAndSendsOneSlotPacket() {
        Fixture fixture = fixture(false, false);
        CombatInventoryAdapter.SlotSwitch result = fixture.adapter().switchTo(3, 0, fixture.sink());
        assertTrue(result.attempted());
        assertTrue(result.enqueued());
        assertTrue(result.completed());
        assertFalse(result.failed());
        assertEquals(3, fixture.client().selectedSlot());
        assertEquals(1, fixture.sink().count(CombatTestFakes.FakeSink.Kind.SLOT));
        assertEquals(3, fixture.sink().last().number());
    }

    @Test
    void sameSlotNeedsNoPacket() {
        Fixture fixture = fixture(false, false);
        CombatInventoryAdapter.SlotSwitch result = fixture.adapter().switchTo(0, 0, fixture.sink());
        assertFalse(result.attempted());
        assertEquals(0, fixture.sink().attempts);
    }

    @Test
    void refusedSwitchRollsBackLocalSelectionWithoutRetry() {
        Fixture fixture = fixture(false, false);
        fixture.sink().failOnAttempt = 1;
        CombatInventoryAdapter.SlotSwitch result = fixture.adapter().switchTo(3, 0, fixture.sink());
        assertTrue(result.failed());
        assertEquals(0, fixture.client().selectedSlot());
        assertEquals(1, fixture.sink().attempts);
    }

    @Test
    void restoreOnlyWhenTheModuleStillOwnsTheSelection() {
        Fixture fixture = fixture(false, false);
        fixture.client().selectedSlot = 3;
        CombatInventoryAdapter.SlotSwitch result = fixture.adapter().restore(0, 3, fixture.sink());
        assertTrue(result.restored());
        assertEquals(0, fixture.client().selectedSlot());
        assertEquals(0, fixture.sink().last().number());
    }

    @Test
    void manualSelectionChangeAbandonsRestore() {
        Fixture fixture = fixture(false, false);
        fixture.client().selectedSlot = 5;
        CombatInventoryAdapter.SlotSwitch result = fixture.adapter().restore(0, 3, fixture.sink());
        assertFalse(result.attempted());
        assertEquals(5, fixture.client().selectedSlot());
        assertEquals(0, fixture.sink().attempts);
    }

    @Test
    void activeLoanOrPendingWeaponAttackOwnsTheInventory() {
        assertTrue(fixture(true, false).adapter().ownedElsewhere());
        assertTrue(fixture(false, true).adapter().ownedElsewhere());
        assertFalse(fixture(false, false).adapter().ownedElsewhere());
    }

    @Test
    void screenAndOffhandAreReadOnlyQueries() {
        Fixture fixture = fixture(false, false);
        fixture.client().screenOpen = true;
        fixture.client().offhand = "minecraft:totem_of_undying";
        assertTrue(fixture.adapter().screenOpen());
        assertEquals("minecraft:totem_of_undying", fixture.adapter().offhandItem());
        fixture.adapter().switchTo(4, 0, fixture.sink());
        fixture.adapter().restore(0, 4, fixture.sink());
        assertEquals("minecraft:totem_of_undying", fixture.adapter().offhandItem());
    }

    @Test
    void invalidSlotRefusedWithoutSending() {
        Fixture fixture = fixture(false, false);
        CombatInventoryAdapter.SlotSwitch result = fixture.adapter().switchTo(9, 0, fixture.sink());
        assertTrue(result.failed());
        assertEquals(0, fixture.sink().attempts);
    }

    @Test
    void adapterSourceNeverCallsLoanPrepareOrSpeedmine() throws IOException {
        String source = Files.readString(Path.of(
            "src/main/java/dev/tntcoating/core/combat/mc/CombatInventoryAdapter.java"));
        assertFalse(source.contains(".prepare("), "prepare() is not a pure availability query");
        assertFalse(source.contains("requestBreak"), "combat may not issue Speedmine requests");
        assertFalse(source.contains("OFF_HAND"), "combat must never move the offhand item");
    }
}

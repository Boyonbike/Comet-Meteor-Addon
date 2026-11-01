/*
 * Original Comet implementation by Boyonabike.
 * Independent: written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.ConnectionKey;
import dev.tntcoating.core.combat.mc.CombatTestFakes.FakeClient;
import dev.tntcoating.core.combat.mc.CombatTestFakes.FakeSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CombatInventoryAdapterTest {
    private FakeClient client;
    private FakeSink sink;
    private CombatInventoryAdapter adapter;
    private boolean loan;
    private boolean weapon;

    @BeforeEach
    void setUp() {
        client = new FakeClient(new ConnectionKey(1L));
        sink = new FakeSink();
        adapter = new CombatInventoryAdapter(client, new CombatInventoryAdapter.Ownership() {
            @Override
            public boolean loanActive() {
                return loan;
            }

            @Override
            public boolean weaponAttackActive() {
                return weapon;
            }
        });
    }

    @Test
    void switchToMovesSelectionAndSendsOneSlotPacket() {
        CombatInventoryAdapter.SlotSwitch result = adapter.switchTo(3, 0, sink);
        assertEquals(3, client.selectedSlot);
        assertEquals(1, sink.count(FakeSink.Kind.SLOT));
        assertTrue(result.attempted());
        assertTrue(result.enqueued());
        assertFalse(result.restored());
        assertFalse(result.failed());
    }

    @Test
    void sameSlotNeedsNoPacket() {
        client.selectedSlot = 2;
        CombatInventoryAdapter.SlotSwitch result = adapter.switchTo(2, 0, sink);
        assertFalse(result.attempted());
        assertEquals(0, sink.attempts);
    }

    @Test
    void refusedSwitchRollsBackLocalSelectionWithoutRetry() {
        client.selectedSlot = 0;
        sink.failOnAttempt = 1;
        CombatInventoryAdapter.SlotSwitch result = adapter.switchTo(3, 0, sink);
        assertEquals(0, client.selectedSlot);
        assertEquals(1, sink.attempts);
        assertTrue(result.failed());
        assertEquals("send-refused", result.failure());
    }

    @Test
    void restoreOnlyWhenTheModuleStillOwnsTheSelection() {
        client.selectedSlot = 2;
        CombatInventoryAdapter.SlotSwitch result = adapter.restore(0, 2, sink);
        assertEquals(0, client.selectedSlot);
        assertTrue(result.restored());
        assertEquals(1, sink.count(FakeSink.Kind.SLOT));
    }

    @Test
    void manualSelectionChangeAbandonsRestore() {
        client.selectedSlot = 5;
        CombatInventoryAdapter.SlotSwitch result = adapter.restore(0, 2, sink);
        assertEquals(5, client.selectedSlot);
        assertEquals(0, sink.attempts);
        assertEquals("user-changed-selection", result.failure());
    }

    @Test
    void activeLoanOrPendingWeaponAttackOwnsTheInventory() {
        assertFalse(adapter.ownedElsewhere());
        loan = true;
        assertTrue(adapter.ownedElsewhere());
        loan = false;
        weapon = true;
        assertTrue(adapter.ownedElsewhere());
    }

    @Test
    void screenAndOffhandAreReadOnlyQueries() {
        client.screenOpen = true;
        client.offhand = "minecraft:totem_of_undying";
        assertTrue(adapter.screenOpen());
        assertEquals("minecraft:totem_of_undying", adapter.offhandItem());
        assertEquals(0, sink.attempts);
    }

    @Test
    void invalidSlotRefusedWithoutSending() {
        CombatInventoryAdapter.SlotSwitch result = adapter.switchTo(9, 0, sink);
        assertEquals(0, sink.attempts);
        assertTrue(result.attempted());
        assertFalse(result.enqueued());
        assertEquals("invalid-slot", result.failure());
    }

    @Test
    void toolSlotPicksTheBestHotbarPickaxeAndNeverTheOffhand() {
        client.hotbar[1] = "minecraft:stone_pickaxe";
        client.hotbar[4] = "minecraft:diamond_pickaxe";
        client.hotbar[6] = "minecraft:netherite_pickaxe";
        client.offhand = "minecraft:netherite_pickaxe";
        assertEquals(6, adapter.toolSlot("minecraft:obsidian"));
        assertEquals(6, adapter.toolSlot("minecraft:sticky_piston"));
        client.hotbar[6] = null;
        assertEquals(4, adapter.toolSlot("minecraft:obsidian"));
    }

    @Test
    void toolSlotUsesTheCurrentSlotForAnInstantBreakAndRefusesWithoutAPickaxe() {
        client.selectedSlot = 3;
        assertEquals(3, adapter.toolSlot("minecraft:stone"));
        client.hotbar[3] = null;
        client.hotbar[5] = "minecraft:diamond_sword";
        assertEquals(-1, adapter.toolSlot("minecraft:obsidian"));
        assertEquals(-1, adapter.toolSlot(null));
        assertEquals(-1, adapter.toolSlot(""));
        assertTrue(CombatInventoryAdapter.requiresPickaxe("minecraft:piston"));
        assertFalse(CombatInventoryAdapter.requiresPickaxe("minecraft:dirt"));
    }

    @Test
    void adapterSourceNeverCallsLoanPrepareOrSpeedmine() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/dev/tntcoating/core/combat/mc/CombatInventoryAdapter.java"));
        assertFalse(source.contains(".prepare()"), "must not prepare a loan");
        assertFalse(source.contains("requestBreak"), "must not ask Speedmine to break");
        assertFalse(source.contains("InventoryWeaponAttack.INSTANCE.onTick"),
            "must not drive a weapon attack");
        assertFalse(source.contains("MAIN_HAND") || source.contains("OFF_HAND"), "no hand packets here");
        assertNotNull(new Cell(0, 64, 0));
    }
}

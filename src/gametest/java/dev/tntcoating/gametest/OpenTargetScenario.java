/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * client and server classes; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Plan 0.4: manual crystal place then hit, target in the open. */
public final class OpenTargetScenario implements Scenario {
    private static final BlockPos BASE = new BlockPos(2, -61, 0);

    @Override
    public String name() {
        return "open_target";
    }

    @Override
    public void setup(ScenarioContext context) {
        context.command("gamerule send_command_feedback false");
        context.command("gamerule spawn_mobs false");
        context.command("gamerule advance_time false");
        context.command("gamerule advance_weather false");
        context.command("gamerule keep_inventory true");
        CombatArena.flat(context, -61, -2, -2, 10, 2);
        CombatArena.obsidianPad(context, -61, 0, -1, 4, 1);
        context.command("clear HarnessClient");
        CombatArena.hotbar(context, "HarnessClient", 0, "minecraft:end_crystal", 16);
        CombatArena.hotbar(context, "HarnessClient", 1, "minecraft:obsidian", 16);
        context.teleportClient(0.5, -60.0, 0.5);
        context.setClientMaxHealth(200.0f, 200.0f);
        context.spawnTarget("HarnessTarget", 4.5, -60.0, 0.5);
        context.setTargetMaxHealth(200.0f, 200.0f);
        context.waitTicks(20);
        context.label("setup-complete");
    }

    @Override
    public void run(ScenarioContext context) {
        ClientActions.selectHotbar(context.client, 0);
        ClientActions.lookAt(context.client, BASE);
        context.label("place-crystal");
        ClientActions.placeOn(context.client, BASE, Direction.UP);
        int crystal = context.waitForCrystal(BASE, 3.0, 40);
        context.label("crystal-id=" + crystal);
        if (crystal == -1) {
            throw new AssertionError("crystal placement was not observed");
        }
        ClientActions.lookAt(context.client, new BlockPos(BASE.getX(), BASE.getY() + 1, BASE.getZ()));
        context.label("attack-crystal id=" + crystal);
        ClientActions.attack(context.client, crystal);
        context.waitTicks(30);
        context.label("attack-window-end");
    }
}

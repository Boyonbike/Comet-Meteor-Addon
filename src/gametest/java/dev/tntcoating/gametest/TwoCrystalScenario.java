/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * client and server classes; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Plan 0.4: two crystals inside one immunity window. The second crystal is placed
 * after the first detonation, so it cannot be destroyed by the first explosion.
 * {@code strongerSecond} chooses whether the second crystal is closer to the target.
 */
public final class TwoCrystalScenario implements Scenario {
    private static final BlockPos WEAK_BASE = new BlockPos(2, -61, 0);
    private static final BlockPos STRONG_BASE = new BlockPos(3, -61, 0);
    private final boolean strongerSecond;
    private final int gapTicks;

    public TwoCrystalScenario(boolean strongerSecond, int gapTicks) {
        this.strongerSecond = strongerSecond;
        this.gapTicks = gapTicks;
    }

    @Override
    public String name() {
        if (gapTicks > 10) return "window_boundary";
        return strongerSecond ? "two_crystals_stronger" : "two_crystals_weaker";
    }

    @Override
    public void setup(ScenarioContext context) {
        context.command("gamerule send_command_feedback false");
        context.command("gamerule spawn_mobs false");
        context.command("gamerule advance_time false");
        context.command("gamerule advance_weather false");
        CombatArena.flat(context, -61, 0, -1, 8, 1);
        CombatArena.obsidianPad(context, -61, 1, -1, 4, 1);
        context.command("clear HarnessClient");
        CombatArena.hotbar(context, "HarnessClient", 0, "minecraft:end_crystal", 16);
        CombatArena.hotbar(context, "HarnessClient", 1, "minecraft:obsidian", 16);
        context.teleportClient(1.5, -60.0, 0.5);
        context.setClientMaxHealth(200.0f, 200.0f);
        context.spawnTarget("HarnessTarget", 5.5, -60.0, 0.5);
        context.setTargetMaxHealth(200.0f, 200.0f);
        // Keep both players fixed so the second hit is comparable across the window.
        context.setTargetExplosionKnockbackResistance(1.0);
        context.setClientExplosionKnockbackResistance(1.0);
        context.waitTicks(20);
        context.label("setup-complete strongerSecond=" + strongerSecond + " gapTicks=" + gapTicks);
    }

    @Override
    public void run(ScenarioContext context) {
        BlockPos firstBase = strongerSecond ? WEAK_BASE : STRONG_BASE;
        BlockPos secondBase = strongerSecond ? STRONG_BASE : WEAK_BASE;
        int first = placeAndWait(context, firstBase);
        context.label("first-crystal id=" + first);
        ClientActions.lookAt(context.client,
            new BlockPos(firstBase.getX(), firstBase.getY() + 1, firstBase.getZ()));
        context.label("first-attack id=" + first);
        ClientActions.attack(context.client, first);
        context.waitTicks(gapTicks);
        if (gapTicks > 10) context.teleportClient(1.5, -60.0, 0.5);
        // From here to the second attack every client tick counts: stay inside W.
        context.client.getInput().lookAt(secondBase);
        ClientActions.placeOn(context.client, secondBase, Direction.UP);
        int second = context.waitForCrystal(secondBase, 1.0, 40);
        context.label("second-crystal id=" + second);
        context.client.getInput().lookAt(
            new BlockPos(secondBase.getX(), secondBase.getY() + 1, secondBase.getZ()));
        context.label("second-attack id=" + second);
        ClientActions.attack(context.client, second);
        context.waitTicks(40);
        context.label("second-window-end");
    }

    private int placeAndWait(ScenarioContext context, BlockPos base) {
        ClientActions.selectHotbar(context.client, 0);
        ClientActions.lookAt(context.client, base);
        ClientActions.placeOn(context.client, base, Direction.UP);
        int id = context.waitForCrystal(base, 1.0, 40);
        if (id == -1) throw new AssertionError("crystal placement was not observed at " + base);
        return id;
    }
}

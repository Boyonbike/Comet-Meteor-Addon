/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * server command set; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest;

/** Deterministic terrain helpers built through server commands. */
public final class CombatArena {
    private CombatArena() {
    }

    /** Flat floor whose top surface is {@code floorY}; clears six blocks above it. */
    public static void flat(ScenarioContext context, int floorY,
                            int minX, int minZ, int maxX, int maxZ) {
        context.command("fill " + minX + " " + floorY + " " + minZ
            + " " + maxX + " " + floorY + " " + maxZ + " minecraft:stone");
        context.command("fill " + minX + " " + (floorY + 1) + " " + minZ
            + " " + maxX + " " + (floorY + 6) + " " + maxZ + " minecraft:air");
    }

    /** Obsidian pad on top of the floor, used for crystal bases. */
    public static void obsidianPad(ScenarioContext context, int floorY,
                                   int minX, int minZ, int maxX, int maxZ) {
        context.command("fill " + minX + " " + floorY + " " + minZ
            + " " + maxX + " " + floorY + " " + maxZ + " minecraft:obsidian");
    }

    /** A two-block-high 1x1 hole with obsidian walls; target feet belong at floorY + 1. */
    public static void hole(ScenarioContext context, int floorY, int x, int z) {
        block(context, x, floorY, z, "minecraft:obsidian");
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                block(context, x + dx, floorY + 1, z + dz, "minecraft:obsidian");
                block(context, x + dx, floorY + 2, z + dz, "minecraft:obsidian");
            }
        }
        block(context, x, floorY + 1, z, "minecraft:air");
        block(context, x, floorY + 2, z, "minecraft:air");
    }

    /** A one-block-high 1x1 hole; the target's upper body remains exposed. */
    public static void holeOne(ScenarioContext context, int floorY, int x, int z) {
        block(context, x, floorY, z, "minecraft:obsidian");
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                block(context, x + dx, floorY + 1, z + dz, "minecraft:obsidian");
            }
        }
        block(context, x, floorY + 1, z, "minecraft:air");
    }

    public static void block(ScenarioContext context, int x, int y, int z, String block) {
        context.command("setblock " + x + " " + y + " " + z + " " + block + " replace");
    }

    public static void armor(ScenarioContext context, String player) {
        context.command("item replace entity " + player + " armor.head with minecraft:netherite_helmet");
        context.command("item replace entity " + player + " armor.chest with minecraft:netherite_chestplate");
        context.command("item replace entity " + player + " armor.legs with minecraft:netherite_leggings");
        context.command("item replace entity " + player + " armor.feet with minecraft:netherite_boots");
    }

    public static void hotbar(ScenarioContext context, String player, int slot, String item, int count) {
        context.command("item replace entity " + player + " hotbar." + slot
            + " with " + item + " " + count);
    }
}

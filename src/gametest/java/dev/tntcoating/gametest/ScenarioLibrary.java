/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * client and server classes; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;

/** The short Phase 0.4 scripts, kept separate from the harness driver. */
public final class ScenarioLibrary {
    private ScenarioLibrary() {
    }

    private static void gamerules(ScenarioContext context) {
        context.command("gamerule send_command_feedback false");
        context.command("gamerule spawn_mobs false");
        context.command("gamerule advance_time false");
        context.command("gamerule advance_weather false");
        context.command("gamerule keep_inventory true");
    }

    private static void baseArena(ScenarioContext context) {
        gamerules(context);
        CombatArena.flat(context, -61, -2, -2, 12, 2);
        context.teleportClient(0.5, -60.0, 0.5);
        context.setClientMaxHealth(200.0f, 200.0f);
    }

    private static void crystals(ScenarioContext context) {
        context.command("clear HarnessClient");
        CombatArena.hotbar(context, "HarnessClient", 0, "minecraft:end_crystal", 16);
        CombatArena.hotbar(context, "HarnessClient", 1, "minecraft:obsidian", 16);
    }

    private static void target(ScenarioContext context, double x, double z, float health) {
        context.spawnTarget("HarnessTarget", x, -60.0, z);
        context.setTargetMaxHealth(health, health);
    }

    private static int placeCrystal(ScenarioContext context, BlockPos base) {
        ClientActions.selectHotbar(context.client, 0);
        ClientActions.lookAt(context.client, base);
        ClientActions.placeOn(context.client, base, Direction.UP);
        int id = context.waitForCrystal(base, 1.0, 40);
        if (id == -1) throw new AssertionError("crystal placement was not observed at " + base);
        return id;
    }

    private static void attackCrystal(ScenarioContext context, int crystal, BlockPos base) {
        ClientActions.lookAt(context.client,
            new BlockPos(base.getX(), base.getY() + 1, base.getZ()));
        ClientActions.attack(context.client, crystal);
    }


    public static Scenario strongerInWindowDirect() {
        return new ScriptedScenario("c6_stronger_in_window",
            context -> {
                baseArena(context);
                target(context, 4.5, 0.5, 200.0f);
                context.waitTicks(20);
                context.label("setup-complete");
            },
            context -> {
                context.label("first-hit");
                context.damageTargetFromPlayer(6.0f);
                context.waitTicks(1);
                context.label("reset-before-second");
                context.resetTargetMotion();
                context.waitTicks(1);
                context.label("second-hit");
                context.damageTargetFromPlayer(10.0f);
                context.waitTicks(10);
                context.label("c6-window-end");
            });
    }


    public static Scenario holedVertical() {
        return new ScriptedScenario("holed_vertical",
            context -> {
                gamerules(context);
                CombatArena.flat(context, -61, -2, -2, 8, 2);
                CombatArena.holeOne(context, -61, 4, 0);
                context.teleportClient(0.5, -60.0, 0.5);
                context.setClientMaxHealth(200.0f, 200.0f);
                target(context, 4.5, 0.5, 200.0f);
                context.waitTicks(20);
                context.label("setup-complete");
            },
            context -> {
                int crystal = context.spawnCrystal(4.5, -58.0, 0.5);
                ClientActions.waitForClientEntity(context.client, crystal);
                context.label("above-hole-crystal id=" + crystal);
                context.detonateCrystal(crystal);
                context.waitTicks(40);
                context.label("holed-vertical-end");
            });
    }


    public static Scenario selfAbsorption() {
        return new ScriptedScenario("self_absorption",
            context -> {
                baseArena(context);
                context.setClientAbsorption(4.0f);
                context.waitTicks(20);
                context.label("setup-complete");
            },
            context -> {
                context.label("self-absorption-damage");
                context.damageClient(6.0f);
                context.waitTicks(20);
                context.label("self-absorption-end");
            });
    }


    public static Scenario ackZeroDamage() {
        return new ScriptedScenario("ack_zero_damage",
            context -> {
                baseArena(context);
                context.waitTicks(20);
                context.label("setup-complete");
            },
            context -> {
                int crystal = context.spawnCrystal(13.5, -60.0, 0.5);
                ClientActions.waitForClientEntity(context.client, crystal);
                BlockPos floor = new BlockPos(0, -61, 0);
                ClientActions.selectHotbar(context.client, 8);
                ClientActions.lookAt(context.client, floor);
                ExplosionInteractionProbe probe = new ExplosionInteractionProbe(context.trace, floor);
                ExplosionInteractionProbe.install(probe);
                try {
                    context.label("ack-zero-crystal id=" + crystal);
                    context.detonateCrystal(crystal);
                    context.waitTicks(30);
                } finally {
                    ExplosionInteractionProbe.uninstall(probe);
                }
                context.label("ack-zero-end fired=" + probe.fired());
            });
    }

    public static Scenario absorptionBundle() {
        return new ScriptedScenario("absorption_bundle",
            context -> {
                baseArena(context);
                target(context, 4.5, 0.5, 40.0f);
                context.setTargetAbsorption(4.0f);
                context.waitTicks(20);
                context.label("setup-complete");
            },
            context -> {
                context.label("damage-start");
                context.damageTarget(6.0f);
                context.waitTicks(20);
                context.label("damage-end");
            });
    }

    public static Scenario rejectedPlacement() {
        BlockPos base = new BlockPos(2, -61, 0);
        return new ScriptedScenario("rejected_placement",
            context -> {
                baseArena(context);
                crystals(context);
                context.waitTicks(20);
                context.label("setup-complete");
            },
            context -> {
                ClientActions.selectHotbar(context.client, 0);
                ClientActions.lookAt(context.client, base);
                context.label("rejected-place");
                ClientActions.placeOn(context.client, base, Direction.UP);
                context.waitTicks(20);
                context.label("rejected-place-end");
                if (context.findCrystalNear(2.5, -60.0, 0.5, 1.0) != -1) {
                    throw new AssertionError("server accepted a crystal on a stone base");
                }
            });
    }

    public static Scenario outOfReachAttack() {
        return new ScriptedScenario("out_of_reach_attack",
            context -> {
                baseArena(context);
                context.spawnCrystal(20.5, -60.0, 0.5);
                context.waitTicks(20);
                context.label("setup-complete");
            },
            context -> {
                int crystal = context.findCrystalNear(20.5, -60.0, 0.5, 1.0);
                if (crystal == -1) throw new AssertionError("far crystal was not spawned");
                ClientActions.waitForClientEntity(context.client, crystal);
                context.label("out-of-reach-attack id=" + crystal);
                ClientActions.attack(context.client, crystal);
                context.waitTicks(20);
                context.label("out-of-reach-end");
            });
    }

    public static Scenario crystalDestroyedByExplosion() {
        BlockPos a = new BlockPos(2, -61, 0);
        BlockPos b = new BlockPos(4, -61, 0);
        return new ScriptedScenario("crystal_destroyed_by_explosion",
            context -> {
                baseArena(context);
                CombatArena.obsidianPad(context, -61, 0, -1, 5, 1);
                target(context, 7.5, 0.5, 200.0f);
                context.spawnCrystal(2.5, -60.0, 0.5);
                context.spawnCrystal(4.5, -60.0, 0.5);
                context.waitTicks(20);
                context.label("setup-complete");
            },
            context -> {
                int first = context.findCrystalNear(2.5, -60.0, 0.5, 0.6);
                int second = context.findCrystalNear(4.5, -60.0, 0.5, 0.6);
                if (first == -1 || second == -1) {
                    throw new AssertionError("both crystals were not spawned");
                }
                ClientActions.waitForClientEntity(context.client, first);
                ClientActions.waitForClientEntity(context.client, second);
                context.label("attack-first id=" + first + " second=" + second);
                attackCrystal(context, first, a);
                context.waitTicks(30);
                context.label("destroy-window-end");
            });
    }

    public static Scenario ackBarrier() {
        BlockPos base = new BlockPos(2, -61, 0);
        BlockPos floor = new BlockPos(0, -61, 0);
        return new ScriptedScenario("ack_barrier",
            context -> {
                baseArena(context);
                CombatArena.obsidianPad(context, -61, 0, -1, 4, 1);
                crystals(context);
                target(context, 4.5, 0.5, 200.0f);
                context.waitTicks(20);
                context.label("setup-complete");
            },
            context -> {
                int crystal = placeCrystal(context, base);
                context.label("crystal-id=" + crystal);
                ClientActions.selectHotbar(context.client, 8);
                ClientActions.lookAt(context.client, floor);
                ExplosionInteractionProbe probe = new ExplosionInteractionProbe(context.trace, floor);
                ExplosionInteractionProbe.install(probe);
                try {
                    attackCrystal(context, crystal, base);
                    context.waitTicks(30);
                } finally {
                    ExplosionInteractionProbe.uninstall(probe);
                }
                context.label("ack-window-end fired=" + probe.fired());
            });
    }

    public static Scenario selfDamage(String name, double crystalX, boolean manual) {
        return new ScriptedScenario(name,
            context -> {
                baseArena(context);
                if (crystalX > 12.0) {
                    CombatArena.flat(context, -61, -2, -2, 16, 2);
                }
                CombatArena.obsidianPad(context, -61, 0, -1, 4, 1);
                crystals(context);
                context.waitTicks(20);
                context.label("setup-complete distance=" + crystalX + " manual=" + manual);
            },
            context -> {
                if (manual) {
                    BlockPos base = new BlockPos((int) crystalX, -61, 0);
                    int crystal = placeCrystal(context, base);
                    context.label("self-crystal id=" + crystal);
                    attackCrystal(context, crystal, base);
                } else {
                    int crystal = context.spawnCrystal(crystalX, -60.0, 0.5);
                    ClientActions.waitForClientEntity(context.client, crystal);
                    context.label("self-crystal id=" + crystal + " server-detonated");
                    context.detonateCrystal(crystal);
                }
                context.waitTicks(40);
                context.label("self-damage-end");
            });
    }

    public static Scenario totemFriend() {
        return new ScriptedScenario("totem_friend",
            context -> {
                baseArena(context);
                target(context, 4.5, 0.5, 20.0f);
                context.giveTotem(context.target());
                ServerPlayer friend = context.spawnPlayer("HarnessFriend", 7.5, -60.0, 0.5);
                context.setPlayerMaxHealth(friend, 40.0f, 30.0f);
                context.waitTicks(20);
                context.label("setup-complete friend=" + friend.getId());
            },
            context -> {
                context.label("totem-hit");
                context.damageTarget(30.0f);
                context.waitTicks(5);
                context.label("friend-hit");
                ServerPlayer friend = context.server.computeOnServer(minecraft ->
                    minecraft.getPlayerList().getPlayerByName("HarnessFriend"));
                if (friend != null) {
                    context.server.runOnServer(minecraft -> {
                        var level = (net.minecraft.server.level.ServerLevel) friend.level();
                        friend.hurtServer(level, level.damageSources().magic(), 2.0f);
                    });
                }
                context.waitTicks(20);
                context.label("totem-window-end");
                if (friend != null) context.removePlayer(friend);
            });
    }

    public static Scenario serverStall() {
        BlockPos base = new BlockPos(2, -61, 0);
        return new ScriptedScenario("server_stall",
            context -> {
                baseArena(context);
                CombatArena.obsidianPad(context, -61, 0, -1, 4, 1);
                crystals(context);
                target(context, 4.5, 0.5, 200.0f);
                context.waitTicks(20);
                context.label("setup-complete");
            },
            context -> {
                int crystal = placeCrystal(context, base);
                context.label("pre-stall crystal=" + crystal);
                context.server.runOnServer(minecraft -> minecraft.execute(() -> {
                    try {
                        Thread.sleep(3000L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }));
                context.waitTicks(60);
                context.label("post-stall");
                attackCrystal(context, crystal, base);
                context.waitTicks(30);
                context.label("stall-window-end");
            });
    }


    public static Scenario disconnectReconnect() {
        return new ScriptedScenario("disconnect_reconnect",
            context -> {
                baseArena(context);
                context.spawnCrystal(2.5, -60.0, 0.5);
                context.waitTicks(20);
                context.label("setup-complete");
            },
            context -> {
                context.label("disconnect-start");
                context.reconnect();
                context.waitTicks(20);
                context.label("reconnect-end crystal="
                    + context.findCrystalNear(2.5, -60.0, 0.5, 1.0));
            });
    }

    public static Scenario deathRespawn() {
        return new ScriptedScenario("death_respawn",
            context -> {
                baseArena(context);
                context.spawnCrystal(2.5, -60.0, 0.5);
                context.waitTicks(20);
                context.label("setup-complete");
            },
            context -> {
                context.label("death-start");
                context.killAndRespawnClient();
                context.waitTicks(30);
                context.label("respawn-end");
            });
    }

    public static Scenario dimensionChange() {
        return new ScriptedScenario("dimension_change",
            context -> {
                baseArena(context);
                context.spawnCrystal(2.5, -60.0, 0.5);
                context.waitTicks(20);
                context.label("setup-complete");
            },
            context -> {
                context.label("to-nether");
                context.command("execute in minecraft:the_nether run tp HarnessClient 0 64 0");
                context.waitTicks(30);
                context.label("to-overworld");
                context.command("execute in minecraft:overworld run tp HarnessClient 0.5 -60 0.5");
                context.waitTicks(30);
                context.label("dimension-window-end");
            });
    }
}

/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * client and server classes; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Plan 0.4: one piston push layout, parameterised by direction, height and power kind. */
public final class PistonPushScenario implements Scenario {
    private final String name;
    private final Direction direction;
    private final int crystalY;
    private final boolean torch;

    private PistonPushScenario(String name, Direction direction, int crystalY, boolean torch) {
        this.name = name;
        this.direction = direction;
        this.crystalY = crystalY;
        this.torch = torch;
    }

    public static PistonPushScenario parse(String name) {
        String[] parts = name.split("_");
        if (parts.length != 4 || !parts[0].equals("piston")) {
            throw new IllegalArgumentException("Unknown piston scenario: " + name);
        }
        Direction direction = switch (parts[1]) {
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            default -> throw new IllegalArgumentException("Unknown piston direction: " + name);
        };
        int y = switch (parts[2]) {
            case "feet" -> -60;
            case "eye" -> -59;
            default -> throw new IllegalArgumentException("Unknown piston height: " + name);
        };
        boolean torch = switch (parts[3]) {
            case "torch" -> true;
            case "redstone" -> false;
            default -> throw new IllegalArgumentException("Unknown piston power: " + name);
        };
        return new PistonPushScenario(name, direction, y, torch);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void setup(ScenarioContext context) {
        context.command("gamerule send_command_feedback false");
        context.command("gamerule spawn_mobs false");
        context.command("gamerule advance_time false");
        context.command("gamerule advance_weather false");
        CombatArena.flat(context, -61, -3, -3, 8, 8);
        context.teleportClient(6.5, -60.0, 6.5);
        context.setClientMaxHealth(200.0f, 200.0f);
        context.waitTicks(20);
        context.label("setup-complete " + name);
    }

    @Override
    public void run(ScenarioContext context) {
        BlockPos sourceBlock = new BlockPos(0, crystalY, 0);
        BlockPos piston = sourceBlock.relative(direction.getOpposite());
        BlockPos power = piston.relative(direction.getOpposite());
        int crystal = context.spawnCrystal(0.5, crystalY, 0.5);
        ClientActions.waitForClientEntity(context.client, crystal);
        context.label("piston-crystal id=" + crystal
            + " source=" + sourceBlock + " piston=" + piston + " power=" + power);
        context.command("setblock " + piston.getX() + " " + piston.getY() + " " + piston.getZ()
            + " minecraft:piston[facing=" + direction.getName() + "] replace");
        if (torch) {
            if (power.getY() > -61) {
                context.command("setblock " + power.getX() + " " + (power.getY() - 1) + " "
                    + power.getZ() + " minecraft:stone replace");
            }
            context.command("setblock " + power.getX() + " " + power.getY() + " " + power.getZ()
                + " minecraft:redstone_torch replace");
        } else {
            context.command("setblock " + power.getX() + " " + power.getY() + " " + power.getZ()
                + " minecraft:redstone_block replace");
        }
        context.label("piston-powered");
        context.waitTicks(20);
        context.label("piston-window-end");
    }
}

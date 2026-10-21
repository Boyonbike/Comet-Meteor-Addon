/*
 * This file is part of THM Addons — https://github.com/Leonn170709/THM-Addons
 * Copyright (c) THM Addons contributors. Credit the devs, keep the link.
 * By using this code you agree to the license terms and to keep your repo public.
 * Adapted for Comet / Minecraft 26.1.2.
 */

package dev.tntcoating.render;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class SpeedmineRender {
    private SpeedmineRender() {}
    public static void renderBlockShape(Render3DEvent event, BlockPos pos, BlockState state,
                                        Color sideColor, Color lineColor, ShapeMode shapeMode) {
        renderBlockShapeScaled(event, pos, state, 1, sideColor, lineColor, shapeMode);
    }

    /** Like {@link #renderBlockShape}, but each shape box is scaled toward its own center by {@code scale} (1.0 = full size, 0.0 = a point). Useful for mining-progress shrink effects on non-cube blocks. */
    public static void renderBlockShapeScaled(Render3DEvent event, BlockPos pos, BlockState state, double scale,
                                              Color sideColor, Color lineColor, ShapeMode shapeMode) {
        VoxelShape shape = state.getShape(mc.level, pos);
        if (shape.isEmpty()) {
            renderScaledBox(event, pos, 0, 0, 0, 1, 1, 1, scale, sideColor, lineColor, shapeMode);
            return;
        }

        shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) ->
            renderScaledBox(event, pos, minX, minY, minZ, maxX, maxY, maxZ, scale, sideColor, lineColor, shapeMode));
    }

    /** Block-local box, offset to world and scaled toward its own centre (1 = full size). */
    private static void renderScaledBox(Render3DEvent event, BlockPos pos,
                                        double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                        double scale, Color sideColor, Color lineColor, ShapeMode shapeMode) {
        double centerX = pos.getX() + (minX + maxX) / 2.0;
        double centerY = pos.getY() + (minY + maxY) / 2.0;
        double centerZ = pos.getZ() + (minZ + maxZ) / 2.0;
        double halfX = (maxX - minX) / 2.0 * scale;
        double halfY = (maxY - minY) / 2.0 * scale;
        double halfZ = (maxZ - minZ) / 2.0 * scale;

        event.renderer.box(centerX - halfX, centerY - halfY, centerZ - halfZ,
            centerX + halfX, centerY + halfY, centerZ + halfZ, sideColor, lineColor, shapeMode, 0);
    }

    public static Color withAlpha(Color base, int alpha) {
        return new Color(base.r, base.g, base.b, alpha);
    }
}

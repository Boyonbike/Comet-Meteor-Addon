/*
 * Original Comet implementation by Boyonabike. Independent of and not derived from any
 * cracked, leaked or decompiled client source; behaviour follows the public Meteor Client API.
 *
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.render;

import dev.tntcoating.core.combat.RiskDebtSnapshot;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

import java.util.List;

/**
 * Read-only HUD overlay for the audited risk model. It is handed one immutable
 * {@link RiskDebtSnapshot} that the client tick has already published, and never queries the world,
 * the risk budget, the ledger or the controller. No render callback progresses a sequence or sends.
 */
public final class CrystalCombatRender {
    private static final int MARGIN = 6;
    private static final int PADDING = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int BACKGROUND = 0x80000000;
    private static final int DEBT_COLOUR = 0xFFFFC857;
    private static final int SUSPENSION_COLOUR = 0xFFFF6B6B;

    private CrystalCombatRender() {
    }

    /**
     * Draws the published debt and suspension lines in the top-left corner. An empty list draws
     * nothing at all. The caller owns the snapshot read; this method only formats and paints it.
     */
    public static void render(Render2DEvent event, List<String> lines) {
        if (event == null || event.graphics == null || lines == null || lines.isEmpty()) return;

        Font font = Minecraft.getInstance().font;
        int textWidth = 0;
        for (String line : lines) textWidth = Math.max(textWidth, font.width(line));
        int x = MARGIN;
        int y = MARGIN;
        int width = textWidth + PADDING * 2;
        int height = lines.size() * LINE_HEIGHT + PADDING * 2;

        event.graphics.fill(x, y, x + width, y + height, BACKGROUND);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int colour = line.startsWith("paused:") ? SUSPENSION_COLOUR : DEBT_COLOUR;
            event.graphics.text(font, line, x + PADDING, y + PADDING + i * LINE_HEIGHT, colour, false);
        }
    }
}

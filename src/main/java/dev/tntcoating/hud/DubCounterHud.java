/*
 * Part of Comet's dub-counter adaptation from Majkey's DubCounter, with permission.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.hud;

import dev.tntcoating.modules.DubCounter;
import java.util.List;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;

/** Dub Counter's count; hidden while that module is off, with a reminder to enable it in the editor. */
public final class DubCounterHud extends HudElement {
    public static final HudElementInfo<DubCounterHud> INFO = new HudElementInfo<>(CometHud.GROUP, "dub-counter",
        "Loaded ordinary chest blocks divided by two, from the dub-counter module.", DubCounterHud::new);

    private static final List<String> PREVIEW = List.of("Dubs: 0", "Enable the dub-counter module");

    private final MeteorTextStyle style = new MeteorTextStyle(settings);

    private DubCounterHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        DubCounter module = Modules.get().get(DubCounter.class);
        List<String> lines = module == null ? null : module.hudLines();
        if (lines == null && isInEditor()) lines = PREVIEW;
        if (lines != null) style.render(this, renderer, null, lines);
        else setSize(0, 0);
    }
}

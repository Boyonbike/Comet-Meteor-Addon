/*
 * Comet highway travel helpers.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.HelperActivation;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.nbt.CompoundTag;

/** Meteor shows effective activation; profiles store only the independent preference. */
public abstract class HighwayHelper extends Module {
    private final HelperActivation activation = new HelperActivation();
    private boolean internalChange;
    private boolean toggling;
    private boolean profileLoading;

    protected HighwayHelper(String name, String description) {
        super(TntCoatingAddon.CATEGORY, name, description);
        runInMainMenu = true;
    }

    protected final HighwayFly highway() { return Modules.get().get(HighwayFly.class); }
    public final boolean independentlyEnabled() { return activation.independent(); }
    protected final boolean loadingProfile() { return profileLoading; }

    final void managed(boolean requested) {
        activation.managed(requested);
        reconcile();
    }

    private void reconcile() {
        if (!activation.needsTransition(isActive(), toggling)) return;
        internalChange = true;
        try { if (activation.effective()) enable(); else disable(); }
        finally { internalChange = false; }
    }

    @Override public final void toggle() {
        if (toggling) return;
        toggling = true;
        try { super.toggle(); }
        finally { toggling = false; }
        reconcile();
    }

    @Override public final void onActivate() {
        if (!internalChange) activation.independent(true);
        if (!profileLoading) started();
    }

    @Override public final void onDeactivate() {
        if (!internalChange) {
            boolean wasManaged = activation.managed();
            activation.disable();
            if (wasManaged && !profileLoading) highway().helperDisabled(this);
        }
        stopped();
    }

    protected void started() {}
    protected void stopped() {}

    public final void beginProfileLoad() {
        profileLoading = true;
        activation.disable();
        reconcile();
        stopped();
    }

    public final void endProfileLoad() {
        profileLoading = false;
        activation.independent(isActive());
        if (isActive()) started();
    }

    @Override public CompoundTag toTag() {
        CompoundTag tag = super.toTag();
        tag.putBoolean("active", activation.independent());
        return tag;
    }

    public final String activationLabel() {
        if (activation.managed()) return activation.independent()
            ? "Independently enabled; also used by Highway Fly" : "Managed by Highway Fly";
        return activation.independent() ? "Independently enabled" : "Off";
    }

    @Override public WWidget getWidget(GuiTheme theme) {
        var list = theme.verticalList();
        list.add(theme.label(activationLabel()));
        if (activation.managed() && !activation.independent()) {
            list.add(theme.button("Keep enabled independently")).widget().action = () -> activation.independent(true);
        }
        return list;
    }
}

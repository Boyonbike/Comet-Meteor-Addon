package dev.tntcoating.core.scaffold;

/** Event snapshot is committed before requesting rival disable, so its nested event cannot become a new enable. */
public final class ScaffoldOwnership {
    public enum Disable { NONE, COMET, LEGACY }
    private boolean comet, legacy;
    private long generation;
    public Disable changed(boolean comet, boolean legacy) {
        boolean enabledComet = comet && !this.comet;
        boolean enabledLegacy = legacy && !this.legacy;
        if (comet != this.comet || legacy != this.legacy) generation++;
        this.comet = comet; this.legacy = legacy;
        if (enabledComet && legacy) return Disable.LEGACY;
        if (enabledLegacy && comet) return Disable.COMET;
        return Disable.NONE;
    }
    public boolean buildersPaused() { return comet; }
    public long generation() { return generation; }
}

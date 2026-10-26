package dev.tntcoating.core;

/** Independent preference and temporary trip ownership are deliberately distinct. */
public final class HelperActivation {
    private boolean independent;
    private boolean managed;
    public boolean independent() { return independent; }
    public boolean managed() { return managed; }
    public boolean effective() { return independent || managed; }
    public boolean needsTransition(boolean runtimeActive, boolean changing) {
        return !changing && effective() != runtimeActive;
    }
    public void independent(boolean enabled) { independent = enabled; }
    public void managed(boolean requested) { managed = requested; }
    public void disable() { independent = false; managed = false; }
}

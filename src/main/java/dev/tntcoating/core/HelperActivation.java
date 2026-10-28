package dev.tntcoating.core;

/** Independent preference and temporary trip ownership are deliberately distinct. */
public final class HelperActivation {
    private boolean independent;
    private final java.util.Set<String> owners = new java.util.HashSet<>();
    public boolean independent() { return independent; }
    public boolean managed() { return !owners.isEmpty(); }
    public boolean managedBy(String owner) { return owners.contains(owner); }
    public boolean effective() { return independent || managed(); }
    public boolean needsTransition(boolean runtimeActive, boolean changing) {
        return !changing && effective() != runtimeActive;
    }
    public void independent(boolean enabled) { independent = enabled; }
    public void managed(boolean requested) { managed("highway-fly", requested); }
    public void managed(String owner, boolean requested) {
        if (requested) owners.add(owner); else owners.remove(owner);
    }
    public void disable() { independent = false; owners.clear(); }
}

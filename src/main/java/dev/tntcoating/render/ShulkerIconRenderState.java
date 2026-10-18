package dev.tntcoating.render;

/** Style travels with each submitted GUI item until Minecraft composites its texture. */
public interface ShulkerIconRenderState {
    Integer comet$iconColour();
    void comet$iconColour(Integer colour);
}

package dev.tntcoating.core;

import meteordevelopment.meteorclient.events.render.Render3DEvent;

/** Access to Meteor's private Path without copying its simulation or rendering implementation. */
public interface TrajectoryPathAccess {
    void comet$render(Render3DEvent event);
    void comet$clear();
}

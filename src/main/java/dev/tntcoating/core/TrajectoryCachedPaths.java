package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.List;

/** Kept outside the mixin package so transformed Meteor code can construct and access it normally. */
public final class TrajectoryCachedPaths {
    public final List<Object> paths = new ArrayList<>();
    public final TrajectoryPrediction<Object> prediction = new TrajectoryPrediction<>();
    public long seen;

    public void clear() {
        for (Object path : paths) ((TrajectoryPathAccess) path).comet$clear();
        paths.clear();
        prediction.clear();
    }
}

package dev.tntcoating.core;

import java.util.Objects;

/** A prediction is valid only for the same world, collision revision, tick and exact simulation input. */
public final class TrajectoryPrediction<K> {
    private Object world;
    private long tick, revision;
    private K input;
    private int allowance;
    private boolean limited, present;

    public boolean matches(Object world, long tick, long revision, K input, int allowance) {
        return present && this.world == world && this.tick == tick && this.revision == revision
            && Objects.equals(this.input, input) && (!limited || allowance <= this.allowance);
    }

    public void update(Object world, long tick, long revision, K input, int allowance, boolean limited) {
        this.world = world;
        this.tick = tick;
        this.revision = revision;
        this.input = input;
        this.allowance = allowance;
        this.limited = limited;
        present = true;
    }

    public void clear() {
        world = null;
        input = null;
        present = false;
    }
}

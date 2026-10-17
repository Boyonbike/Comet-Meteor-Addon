package dev.tntcoating.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chunk-based trail acquisition and steering, independent of Minecraft classes.
 * All times are caller-supplied monotonic milliseconds and all yaws use Minecraft's convention.
 */
public final class TrailTracker {
    private static final int MAX_REMEMBERED_CHUNKS = 4096;
    private static final double MIN_FORWARD_DISTANCE = 8;

    public enum State { SEARCHING, FOLLOWING, RECOVERING, EXHAUSTED }

    /** Give-up time is total silence since the last useful trail chunk, including the lost delay. */
    public record Config(int acquisitionCount, long acquisitionWindowMillis, int historySize,
                         double behindDistance, long lostAfterMillis, long giveUpMillis,
                         double recoveryDegreesPerSecond) {
        public Config {
            if (acquisitionCount < 1 || acquisitionCount > MAX_REMEMBERED_CHUNKS)
                throw new IllegalArgumentException("acquisitionCount must be between 1 and 4096");
            if (historySize < 1 || historySize > MAX_REMEMBERED_CHUNKS)
                throw new IllegalArgumentException("historySize must be between 1 and 4096");
            if (acquisitionWindowMillis <= 0 || lostAfterMillis <= 0 || giveUpMillis <= lostAfterMillis)
                throw new IllegalArgumentException("time windows must be positive, with give-up after recovery starts");
            if (!Double.isFinite(behindDistance) || behindDistance < 0)
                throw new IllegalArgumentException("behindDistance must be finite and nonnegative");
            if (!Double.isFinite(recoveryDegreesPerSecond) || recoveryDegreesPerSecond <= 0)
                throw new IllegalArgumentException("recovery turn rate must be finite and positive");
        }

        public static Config defaults() {
            return new Config(10, 5_000, 20, 32, 5_000, 30_000, 20);
        }
    }

    /** A forward look-ahead target along the fitted line, with bounded lateral correction. */
    public record Snapshot(State state, float yaw, double targetX, double targetZ,
                           int trailChunks, int acquisitionChunks) {}

    private record Chunk(int x, int z) {}
    private record Sample(Chunk chunk, long time, double x, double z) {}

    private final Config config;
    private final TrailLine line;
    private TrailLine.Estimate lineEstimate;
    private final Deque<Sample> history = new ArrayDeque<>();
    private final Deque<Sample> acquisition = new ArrayDeque<>();
    // Remember substantially more than the steering history: reloading one nearby chunk must
    // neither acquire a trail nor postpone losing it. Bound the set for long exploration sessions.
    private final Map<Chunk, Boolean> seen = new LinkedHashMap<>();
    private State state = State.SEARCHING;
    private long lastTime;
    private boolean hasTime;
    private long lastUseful;
    private boolean hasUseful;
    private float recoveryStartYaw;

    public TrailTracker(Config config) {
        this.config = java.util.Objects.requireNonNull(config);
        line = new TrailLine(new TrailLine.Config(Math.min(128, Math.max(32, config.historySize() * 4)),
            24, 96, 12, 64, 12));
    }

    public TrailTracker() {
        this(Config.defaults());
    }

    public void reset() {
        history.clear();
        acquisition.clear();
        seen.clear();
        state = State.SEARCHING;
        hasTime = false;
        lastTime = 0;
        lastUseful = 0;
        hasUseful = false;
        recoveryStartYaw = 0;
        line.reset();
        lineEstimate = null;
    }

    /**
     * Report a chunk already classified as trail by the compatibility bridge. Non-trail chunks
     * must not be reported. Returns whether this was a new, useful arrival. Duplicate suppression
     * covers the most recent 4096 useful chunks, rather than just the last few steering samples.
     */
    public boolean onTrailChunk(int chunkX, int chunkZ, long now,
                                double playerX, double playerZ, float travelYaw) {
        return onTrailChunk(chunkX, chunkZ, now, now, playerX, playerZ, travelYaw);
    }

    /**
     * Report a classification completed after its chunk arrived. Arrival time, rather than cache
     * lookup completion time, determines acquisition density and trail silence. Classification may
     * finish out of order, so an older result must never move the last-useful time backwards.
     */
    public boolean onTrailChunk(int chunkX, int chunkZ, long now, long receivedAtMillis,
                                double playerX, double playerZ, float travelYaw) {
        if (receivedAtMillis > now) throw new IllegalArgumentException("arrival cannot be in the future");
        // Receipt happens between client ticks, and classification can be delayed. Consider its
        // actual arrival before applying the current tick's silence timeout; otherwise a chunk
        // received just before the deadline would be discarded when drained just after it.
        advanceClock(now, playerX, playerZ, travelYaw);
        long age = now - receivedAtMillis;
        if (state == State.EXHAUSTED) return false;
        if ((state == State.FOLLOWING || state == State.RECOVERING)
            && receivedAtMillis - lastUseful >= config.giveUpMillis()) {
            state = State.EXHAUSTED;
            return false;
        }
        if (age >= config.giveUpMillis()) return false;

        Chunk key = new Chunk(chunkX, chunkZ);
        if (seen.containsKey(key)) return false;
        // Multiply as a double: large or negative chunk coordinates must not wrap or truncate.
        double x = chunkX * 16.0 + 8;
        double z = chunkZ * 16.0 + 8;
        if (isBehind(x, z, playerX, playerZ, travelYaw)) return false;

        seen.put(key, Boolean.TRUE);
        if (seen.size() > MAX_REMEMBERED_CHUNKS) {
            Iterator<Chunk> oldest = seen.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        Sample sample = new Sample(key, receivedAtMillis, x, z);
        if (age <= config.acquisitionWindowMillis() || state == State.FOLLOWING) {
            remember(history, sample, config.historySize());
        }
        if (age <= config.acquisitionWindowMillis()) {
            remember(acquisition, sample, config.acquisitionCount());
        }
        lastUseful = hasUseful ? Math.max(lastUseful, receivedAtMillis) : receivedAtMillis;
        hasUseful = true;
        State before = state;
        if (now - lastUseful < config.lostAfterMillis()
            && (state == State.RECOVERING || acquisition.size() >= config.acquisitionCount()))
            state = State.FOLLOWING;
        if (before == State.SEARCHING && state == State.FOLLOWING) {
            line.reset();
            for (Sample point : history) line.observePoint(point.x(), point.z());
        } else if (state == State.FOLLOWING && age <= config.acquisitionWindowMillis()) {
            line.observePoint(x, z);
        }
        return true;
    }

    /**
     * Advance timers and calculate the next heading. Pass the player's current direction of
     * travel, so chunks substantially behind cannot pull the steering around. Searching has no
     * timeout; recovery starts after lostAfterMillis of silence, and give-up is terminal until reset.
     */
    public Snapshot update(long now, double playerX, double playerZ, float travelYaw) {
        advanceClock(now, playerX, playerZ, travelYaw);
        if (state == State.FOLLOWING || state == State.RECOVERING) {
            long silence = now - lastUseful;
            if (silence >= config.giveUpMillis()) state = State.EXHAUSTED;
            else if (state == State.FOLLOWING && silence >= config.lostAfterMillis()) {
                state = State.RECOVERING;
                recoveryStartYaw = travelYaw;
            }
        }

        float yaw = wrapYaw(travelYaw);
        if (state == State.RECOVERING) {
            long recoveryElapsed = now - lastUseful - config.lostAfterMillis();
            yaw = wrapYaw(recoveryStartYaw + config.recoveryDegreesPerSecond() * recoveryElapsed / 1000.0);
        }
        double forwardX = -Math.sin(Math.toRadians(yaw));
        double forwardZ = Math.cos(Math.toRadians(yaw));
        double targetX = playerX + forwardX * MIN_FORWARD_DISTANCE;
        double targetZ = playerZ + forwardZ * MIN_FORWARD_DISTANCE;

        if (state == State.FOLLOWING) {
            lineEstimate = line.estimate(playerX, playerZ, travelYaw);
            targetX = lineEstimate.targetX();
            targetZ = lineEstimate.targetZ();
            yaw = lineEstimate.yaw();
        }
        return new Snapshot(state, yaw, targetX, targetZ, history.size(), acquisition.size());
    }

    /** The geometric trail direction, excluding the small steering correction back toward its centre. */
    public float axisYaw() { return lineEstimate == null ? Float.NaN : lineEstimate.axisYaw(); }

    public double lineConfidence() { return lineEstimate == null ? 0 : lineEstimate.confidence(); }

    /** Advance/prune without making a terminal decision before queued arrivals are considered. */
    private void advanceClock(long now, double playerX, double playerZ, float travelYaw) {
        if (!Double.isFinite(playerX) || !Double.isFinite(playerZ) || !Float.isFinite(travelYaw))
            throw new IllegalArgumentException("position and yaw must be finite");
        if (hasTime && now < lastTime) throw new IllegalArgumentException("time must be monotonic");
        hasTime = true;
        lastTime = now;
        acquisition.removeIf(sample -> now - sample.time() > config.acquisitionWindowMillis()
            || isBehind(sample.x(), sample.z(), playerX, playerZ, travelYaw));
        history.removeIf(sample -> isBehind(sample.x(), sample.z(), playerX, playerZ, travelYaw)
            || (state != State.FOLLOWING && now - sample.time() > config.acquisitionWindowMillis()));
    }

    private boolean isBehind(double x, double z, double playerX, double playerZ, float yaw) {
        double radians = Math.toRadians(yaw);
        double projection = (x - playerX) * -Math.sin(radians) + (z - playerZ) * Math.cos(radians);
        return projection < -config.behindDistance();
    }

    private static void remember(Deque<Sample> samples, Sample sample, int capacity) {
        if (samples.isEmpty() || samples.getLast().time() <= sample.time()) samples.addLast(sample);
        else {
            var sorted = new ArrayList<>(samples);
            sorted.add(sample);
            sorted.sort(Comparator.comparingLong(Sample::time));
            samples.clear();
            samples.addAll(sorted);
        }
        while (samples.size() > capacity) samples.removeFirst();
    }

    private static float wrapYaw(double yaw) {
        double wrapped = yaw % 360;
        if (wrapped <= -180) wrapped += 360;
        else if (wrapped > 180) wrapped -= 360;
        return (float) wrapped;
    }
}

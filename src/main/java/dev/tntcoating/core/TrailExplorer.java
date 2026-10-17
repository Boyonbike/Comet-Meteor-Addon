package dev.tntcoating.core;

import it.unimi.dsi.fastutil.longs.Long2ByteLinkedOpenHashMap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Hunts old-chunk trails from a local map of chunk classifications. It searches ahead for a band of unseen old
 * chunks, takes the band in its unflown direction whatever the crossing angle, follows the band's centre through
 * gentle curves and gaps, reports where it meets explicit new terrain, then leaves to search again. Unknown chunks
 * are never evidence of either. The caller owns the record of flights, which is checked before every chosen heading;
 * when every heading repeats some of it, the least repetitive one is flown instead of stopping.
 */
public final class TrailExplorer {
    private static final int MAX_CELLS = 16_384;
    private static final byte TRAIL = 1, FRONTIER = 2, FLOWN = 4;
    /**
     * Chunks this close to any recorded flight, about view distance, have been seen already: they still guide
     * following, but never attract a search or a new lock.
     */
    static final int SEEN_RADIUS_CHUNKS = 7;
    /**
     * This flight's own surroundings count as seen only once it has flown on this far, since new terrain loads
     * barely further ahead than SEEN_RADIUS_CHUNKS: a trail just coming into view must still attract the search.
     */
    private static final double SEEN_DELAY = 160;
    /** How far ahead on the search circle to aim, per block off it: 1 returns at 45 degrees. */
    private static final double RETURN_LEAD = 1;
    /** A trail-end lead needs this much trail followed first; crossing a band is not a trail. */
    private static final double MIN_LEAD_BLOCKS = 192;
    private static final double BIN = 16;
    private static final double FIRST_BIN = 32;
    /** Lateral samples across a corridor: three chunks wide, so one hole cannot hide a trail. */
    private static final double[] LANES = {-24, -8, 8, 24};
    private static final float FAN_STEP = 5;
    private static final float FAN_HALF = 35;
    private static final float SEARCH_STEP = 10;
    /** Searching turns towards unseen old chunks up to this far either side of the search heading, but no further. */
    private static final float SEARCH_HALF = 135;
    private static final float LOCK_HALF = 120;
    private static final float ESCAPE_STEP = 15;
    private static final float ESCAPE_HALF = 90;
    private static final float ESCAPE_WIDE_HALF = 135;
    /** Axis change per block travelled; a gentle curve is followed, a hairpin is not. */
    private static final double AXIS_DEGREES_PER_BLOCK = 0.75;
    private static final double MIN_AXIS_STEP = 0.5;
    private static final double MAX_CENTRING = 15;
    private static final double CENTRE_SCAN = 192;
    private static final int LOCK_BINS = 5;
    private static final int APPROACH_BINS = 3;
    private static final int CONTINUE_BINS = 2;
    private static final int FORK_BINS = 5;
    private static final double FORK_SPACING = 256;
    private static final double MAX_GAP = 384;
    /** Lock directions pointing within 60 degrees of 0,0 lead back to spawn, not to bases. */
    private static final double INWARD_LIMIT = -0.5;
    private static final double FOLLOW_OUTBOUND = 0.5;
    private static final double LOCK_OUTBOUND = 3;
    private static final double OUTBOUND_MIN_RADIUS = 1_000;
    private static final double MAX_STEP = 64;
    private static final double DEFAULT_HALF_WIDTH = 64;
    /** Forced exits allowed within FORCED_WINDOW blocks before the area counts as flown out. */
    private static final int MAX_FORCED = 4;
    private static final double FORCED_WINDOW = 3_000;
    /** A flown-out area is left by this much outward flight, after which the search starts afresh there. */
    private static final double RELOCATE_DISTANCE = 1_500;

    public enum State { SEARCHING, FOLLOWING, SEEKING, ESCAPING }

    /**
     * Straight holds the starting heading. Spiral circles 0,0 at the starting distance, which crosses trails
     * radiating from spawn instead of running alongside them, and widens by spacing blocks per turn. After following
     * a trail out to its end, the search heads back to that circle diagonally, crossing more trails on the way.
     */
    public enum Pattern { STRAIGHT, SPIRAL }

    /** Places worth a look for bases: a band meeting new terrain, or a second branch left behind at a fork. */
    public enum LeadKind { TRAIL_END, FORK }

    /** Queries must include all recorded flights in the current server and dimension. */
    public interface RouteAccess {
        /** Ignore the short initial exit from the present position, but not a later re-entry. */
        boolean wouldRevisit(double x, double z, float yaw, double lookAhead);

        /**
         * How much flown history lies along a heading, used only to pick the least repetitive way out when every
         * heading repeats some. Integer.MAX_VALUE means nothing can be certified, such as when memory is full.
         */
        default int overlap(double x, double z, float yaw, double lookAhead) {
            return wouldRevisit(x, z, yaw, lookAhead) ? 1 : 0;
        }

        /** Whether a flight from an earlier session passed within radiusChunks; the explorer tracks its own. */
        default boolean flownNear(int chunkX, int chunkZ, int radiusChunks) { return false; }
    }

    /**
     * @param lookAhead      distance checked against flight history for every chosen heading
     * @param scanRange      distance ahead read from the classification map
     * @param escapeDistance distance flown away from a trail end before searching again
     */
    public record Config(double lookAhead, double scanRange, double escapeDistance) {
        public Config {
            if (!Double.isFinite(lookAhead) || lookAhead < 64 || lookAhead > 1_024)
                throw new IllegalArgumentException("look-ahead must be between 64 and 1024 blocks");
            if (!Double.isFinite(scanRange) || scanRange < 64 || scanRange > 512)
                throw new IllegalArgumentException("scan range must be between 64 and 512 blocks");
            if (!Double.isFinite(escapeDistance) || escapeDistance < 64 || escapeDistance > 1_024)
                throw new IllegalArgumentException("escape distance must be between 64 and 1024 blocks");
        }

        public static Config defaults() { return new Config(256, 256, 96); }
    }

    /** heading is the trail's direction at the lead; for a fork, the branch not taken. */
    public record Lead(LeadKind kind, double x, double z, float heading, double trailBlocks) {}

    /** Blocked means no heading can be certified, as when route memory is full; land safely. Lead is usually null. */
    public record Decision(State state, float yaw, boolean blocked, Lead lead) {}

    private record Corridor(float yaw, int trailBins, double score) {}
    private record Band(double centre, Edge left, Edge right) {}
    private record Edge(double lane, boolean known) {}
    private record Fit(double offset, double slope) {}
    /** lateral = offset + slope * along, in blocks relative to the player and the present axis. */
    private record Centreline(double offset, double slope) {}

    private final RouteAccess routes;
    private final Config config;
    /** TRAIL or FRONTIER (explicit non-trail terrain), plus FLOWN once seen from a flight. Absent is unknown. */
    private final Long2ByteLinkedOpenHashMap cells = new Long2ByteLinkedOpenHashMap();
    private long seenChunk = Long.MIN_VALUE;
    /** Chunks entered by this flight with the distance travelled on entry, waiting for SEEN_DELAY. */
    private final ArrayDeque<double[]> pathSeen = new ArrayDeque<>();
    private boolean preferOutbound = true;
    private Pattern pattern = Pattern.STRAIGHT;
    private double spiralSpacing = 768;
    /** +1 counter-clockwise or -1 clockwise around 0,0, chosen from the first heading. */
    private int spiralSense;
    private double spiralStart = Double.NaN;
    /** Radians travelled around 0,0 in the spiral's sense; each full turn widens the circle by the spacing. */
    private double spiralTurned;
    private double lastBearing = Double.NaN;
    private State state = State.SEARCHING;
    private boolean started;
    private long lastTime;
    private double lastX;
    private double lastZ;
    private double travelled;
    private float command;
    private float searchHeading;
    private float axis;
    private double followStart;
    private double lastTrailAt;
    private double lastForkAt = Double.NEGATIVE_INFINITY;
    private float escapeAnchor;
    private float escapeHeading;
    private double escapeStart;
    /** Half the band's width, learned from sections with both edges known; used when only one edge is. */
    private double halfWidth = DEFAULT_HALF_WIDTH;
    private final ArrayDeque<Double> forcedAt = new ArrayDeque<>();
    /** A forced exit overlaps history by definition, so it is held for its distance without rechecking. */
    private boolean forcedExit;
    private boolean relocating;

    public TrailExplorer(RouteAccess routes) { this(routes, Config.defaults()); }

    public TrailExplorer(RouteAccess routes, Config config) {
        this.routes = Objects.requireNonNull(routes);
        this.config = Objects.requireNonNull(config);
    }

    /** Break ties between the two directions of a band, and choose exits, away from 0,0. */
    public void setPreferOutbound(boolean preferOutbound) { this.preferOutbound = preferOutbound; }

    public void setPattern(Pattern pattern, double spiralSpacing) {
        if (!Double.isFinite(spiralSpacing) || spiralSpacing < 128 || spiralSpacing > 16_384)
            throw new IllegalArgumentException("spiral spacing must be between 128 and 16384 blocks");
        this.pattern = Objects.requireNonNull(pattern);
        this.spiralSpacing = spiralSpacing;
    }

    public State state() { return state; }

    /** Whether the flight is leaving an area where every way out repeats earlier flights. */
    public boolean relocating() { return state == State.ESCAPING && relocating; }

    /** The trail's direction while following, excluding the centring correction. */
    float axis() { return axis; }

    /** Whether a trail axis is held, through a gap or while following. */
    public boolean hasTrailDirection() { return state == State.FOLLOWING || state == State.SEEKING; }

    /** Blocks followed along the present trail, or zero when not following one. */
    public double trailBlocks() { return hasTrailDirection() ? travelled - followStart : 0; }

    /** Records a classification; old=false means explicit non-trail evidence, never an unknown chunk. */
    public void observe(int chunkX, int chunkZ, boolean old) {
        long key = chunkKey(chunkX, chunkZ);
        byte previous = cells.get(key);
        boolean flown = (previous & FLOWN) != 0 || routes.flownNear(chunkX, chunkZ, SEEN_RADIUS_CHUNKS);
        cells.putAndMoveToLast(key, (byte) ((old ? TRAIL : FRONTIER) | (flown ? FLOWN : 0)));
        if (cells.size() > MAX_CELLS) cells.removeFirstByte();
    }

    /** Neutral/unknown classifications are neither trail evidence nor new terrain. */
    public void observe(int chunkX, int chunkZ, boolean old, boolean fresh) {
        if (old) observe(chunkX, chunkZ, true);
        else if (fresh) observe(chunkX, chunkZ, false);
        else forget(chunkX, chunkZ);
    }

    public void forget(int chunkX, int chunkZ) { cells.remove(chunkKey(chunkX, chunkZ)); }

    public Decision next(long now, double x, double z, float travelYaw) {
        if (!Double.isFinite(x) || !Double.isFinite(z) || !Float.isFinite(travelYaw))
            throw new IllegalArgumentException("position and yaw must be finite");
        if (started && now < lastTime) throw new IllegalArgumentException("time must be monotonic");
        if (!started) {
            started = true;
            searchHeading = command = wrap(travelYaw);
            spiralSense = 0;
            spiralStart = lastBearing = Double.NaN;
            spiralTurned = 0;
            lastX = x;
            lastZ = z;
        }
        lastTime = now;
        double step = Math.hypot(x - lastX, z - lastZ);
        // A teleport or setback is not progress along a trail.
        if (step <= MAX_STEP) travelled += step;
        lastX = x;
        lastZ = z;
        trackBearing(x, z);
        markSeen(x, z);
        return switch (state) {
            case SEARCHING -> search(x, z, step);
            case FOLLOWING, SEEKING -> follow(x, z, step);
            case ESCAPING -> escape(x, z, step);
        };
    }

    private Decision search(double x, double z, double step) {
        Float spiral = spiralHeading(x, z);
        if (spiral != null) searchHeading = spiral;
        if (onTrail(x, z)) {
            // Inside a band, its axis is visible: take whichever unflown direction along it is best.
            Corridor lock = best(x, z, searchHeading, LOCK_HALF, SEARCH_STEP, LOCK_BINS, command, 1, true);
            float heading = lock == null ? Float.NaN : bandAxis(x, z, lock.yaw());
            // A band whose own axis only leads back towards spawn is not taken, whichever heading first covered it.
            if (lock != null && !(preferOutbound && outbound(x, z, heading) < INWARD_LIMIT)) {
                state = State.FOLLOWING;
                axis = routes.wouldRevisit(x, z, heading, config.lookAhead()) ? lock.yaw() : heading;
                followStart = lastTrailAt = travelled;
                lastForkAt = Double.NEGATIVE_INFINITY;
                return follow(x, z, step);
            }
        }
        Corridor approach = best(x, z, searchHeading, SEARCH_HALF, SEARCH_STEP, APPROACH_BINS, command, 1, false);
        if (approach != null) return decide(State.SEARCHING, approach.yaw(), null);
        Float clear = clearHeading(x, z, searchHeading, SEARCH_HALF, SEARCH_STEP);
        if (clear == null) return startEscape(x, z, null);
        // A remembered route ahead moves the whole search heading, so it is not retried every tick.
        searchHeading = clear;
        return decide(State.SEARCHING, clear, null);
    }

    /**
     * Inside a wide band many headings cover it, including ones crossing it diagonally. When its edges are known,
     * turn the heading onto the band's own centreline at once instead of converging on it over hundreds of blocks.
     */
    private float bandAxis(double x, double z, float heading) {
        for (int refinement = 0; refinement < 3; refinement++) {
            Centreline line = centreline(x, z, heading);
            if (line == null) break;
            float fitted = wrap(heading - Math.toDegrees(Math.atan(line.slope())));
            float change = Math.abs(delta(heading, fitted));
            if (change > 75) break;
            heading = fitted;
            if (change < 1) break;
        }
        return heading;
    }

    private Decision follow(double x, double z, double step) {
        List<Corridor> fan = fan(x, z, axis, FAN_HALF, FAN_STEP, false);
        List<Corridor> continuing = sortedByScore(fan, axis, CONTINUE_BINS);
        boolean continuation = !continuing.isEmpty();
        Centreline line = continuation ? centreline(x, z, axis) : null;
        Float desired = null;
        if (line != null) {
            // The band's own centreline is the trail direction when both of its edges are known.
            float fitted = wrap(axis - Math.toDegrees(Math.atan(line.slope())));
            if (Math.abs(delta(axis, fitted)) <= FAN_HALF && !routes.wouldRevisit(x, z, fitted, config.lookAhead()))
                desired = fitted;
        }
        Corridor chosen = null;
        for (Corridor corridor : continuing) {
            if (!routes.wouldRevisit(x, z, corridor.yaw(), config.lookAhead())) {
                chosen = corridor;
                break;
            }
        }
        if (desired == null && chosen != null) desired = chosen.yaw();
        if (desired != null) {
            state = State.FOLLOWING;
            lastTrailAt = travelled;
            Lead fork = chosen == null ? null : fork(x, z, fan, chosen);
            double limit = Math.max(MIN_AXIS_STEP, step * AXIS_DEGREES_PER_BLOCK);
            float previous = axis;
            axis = wrap(axis + Math.clamp(delta(axis, desired), -limit, limit));
            if (line != null) {
                // Re-express the fitted line relative to the turned axis, then pursue a point on it 96 blocks ahead.
                double turn = Math.toRadians(delta(previous, axis));
                double lateral = line.offset() + line.slope() * 96;
                double along = 96;
                double rotated = lateral * Math.cos(turn) + along * Math.sin(turn);
                double ahead = along * Math.cos(turn) - lateral * Math.sin(turn);
                double correction = Math.clamp(Math.toDegrees(Math.atan2(rotated, Math.max(32, ahead))), -MAX_CENTRING, MAX_CENTRING);
                return decide(State.FOLLOWING, wrap(axis - correction), fork);
            }
            return decide(State.FOLLOWING, centred(x, z, axis), fork);
        }
        if (continuation) {
            // The band continues only into remembered flights: a junction with known history, not a lead.
            return startEscape(x, z, null);
        }
        double wall = frontierAhead(x, z, axis);
        if (!Double.isNaN(wall)) {
            double length = travelled - followStart;
            // Old chunks stop about one view radius past where their maker turned back or settled, which is also
            // about the band's half-width: report that point rather than where the new terrain became visible.
            double radians = Math.toRadians(axis), stop = Math.max(0, wall - halfWidth);
            Lead end = new Lead(LeadKind.TRAIL_END, x - Math.sin(radians) * stop, z + Math.cos(radians) * stop, axis, length + stop);
            return startEscape(x, z, length < MIN_LEAD_BLOCKS ? null : end);
        }
        if (travelled - lastTrailAt > MAX_GAP || routes.wouldRevisit(x, z, axis, config.lookAhead())) {
            // A long unknown gap is not an end, but the axis has stopped being evidence. Checking for an end back where
            // the trail stopped would be unreliable: a curving trail leaves the held bearing during the gap.
            return startEscape(x, z, null);
        }
        state = State.SEEKING;
        return decide(State.SEEKING, axis, null);
    }

    private Decision startEscape(double x, double z, Lead lead) {
        float from = state == State.SEARCHING ? searchHeading : axis;
        Float heading = escapeHeading(x, z, from, ESCAPE_HALF);
        if (heading == null) heading = escapeHeading(x, z, from, ESCAPE_WIDE_HALF);
        forcedExit = false;
        relocating = false;
        if (heading == null) {
            heading = forcedHeading(x, z, from);
            forcedExit = heading != null;
        }
        if (heading == null) {
            state = State.ESCAPING;
            escapeAnchor = escapeHeading = from;
            escapeStart = travelled;
            return new Decision(State.ESCAPING, command, true, lead);
        }
        state = State.ESCAPING;
        escapeAnchor = escapeHeading = heading;
        escapeStart = travelled;
        return decide(State.ESCAPING, heading, lead);
    }

    private Decision escape(double x, double z, double step) {
        double distance = relocating ? RELOCATE_DISTANCE : forcedExit ? config.escapeDistance() * 2 : config.escapeDistance();
        if (travelled - escapeStart >= distance) {
            state = State.SEARCHING;
            searchHeading = escapeHeading;
            forcedExit = false;
            if (relocating) {
                // Start the circle afresh out here, rather than heading back to the flown-out one.
                relocating = false;
                spiralSense = 0;
            }
            return search(x, z, step);
        }
        if (forcedExit || !routes.wouldRevisit(x, z, escapeHeading, config.lookAhead()))
            return decide(State.ESCAPING, escapeHeading, null);
        // Corrections stay within 90 degrees of the first escape bearing, so they cannot add up to a return.
        Float clear = clearHeading(x, z, escapeAnchor, ESCAPE_HALF, ESCAPE_STEP);
        if (clear == null) {
            // The way out is closed too: take the least repetitive heading, then search again past it.
            Float forced = forcedHeading(x, z, escapeAnchor);
            if (forced == null) return blocked(State.ESCAPING);
            escapeAnchor = escapeHeading = forced;
            escapeStart = travelled;
            forcedExit = true;
            return decide(State.ESCAPING, forced, null);
        }
        escapeHeading = clear;
        return decide(State.ESCAPING, clear, null);
    }

    /**
     * Every heading repeats some history. Rather than land, fly the one with the least remembered route alongside
     * it, preferring a small turn and leading away from 0,0. When that keeps happening the area has been flown out,
     * so leave it outward for RELOCATE_DISTANCE. Null only when nothing can be certified, as with full memory.
     */
    private Float forcedHeading(double x, double z, float from) {
        while (!forcedAt.isEmpty() && travelled - forcedAt.peekFirst() > FORCED_WINDOW) forcedAt.removeFirst();
        boolean relocate = forcedAt.size() >= MAX_FORCED;
        double radius = Math.hypot(x, z);
        float anchor = relocate && radius >= OUTBOUND_MIN_RADIUS ? wrap(Math.toDegrees(Math.atan2(z, x)) - 90) : from;
        float half = relocate ? 60 : 180 - ESCAPE_STEP;
        Float best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (float offset = -half; offset <= half; offset += ESCAPE_STEP) {
            float yaw = wrap(anchor + offset);
            int overlap = routes.overlap(x, z, yaw, config.lookAhead());
            if (overlap == Integer.MAX_VALUE) continue;
            double cost = overlap + Math.abs(offset) * 0.01 - outbound(x, z, yaw);
            if (cost < bestCost) {
                bestCost = cost;
                best = yaw;
            }
        }
        if (best == null) return null;
        if (relocate) {
            forcedAt.clear();
            relocating = true;
        } else forcedAt.addLast(travelled);
        return best;
    }

    /** Exits prefer new or unknown terrain, a small turn and, when enabled, leading away from 0,0. */
    private Float escapeHeading(double x, double z, float from, float half) {
        List<Corridor> options = new ArrayList<>();
        for (float offset = 0; offset <= half; offset += ESCAPE_STEP) {
            for (int side = offset == 0 ? 1 : -1; side <= 1; side += 2) {
                float yaw = wrap(from + side * offset);
                Corridor corridor = corridor(x, z, yaw, false);
                double score = -corridor.trailBins() - Math.abs(offset) * 0.02 + outbound(x, z, yaw) * 2;
                options.add(new Corridor(yaw, corridor.trailBins(), score));
            }
        }
        options.sort((a, b) -> Double.compare(b.score(), a.score()));
        for (Corridor option : options)
            if (!routes.wouldRevisit(x, z, option.yaw(), config.lookAhead())) return option.yaw();
        return null;
    }

    private Float clearHeading(double x, double z, float anchor, float half, float step) {
        for (float offset = 0; offset <= half; offset += step) {
            for (int side = offset == 0 ? 1 : -1; side <= 1; side += 2) {
                // Try the side nearer the present command first, so a correction does not flip-flop.
                float yaw = wrap(anchor + side * offset * (delta(anchor, command) < 0 ? -1 : 1));
                if (!routes.wouldRevisit(x, z, yaw, config.lookAhead())) return yaw;
            }
        }
        return null;
    }

    /**
     * The best unflown heading within half degrees of the anchor with at least minBins of trail ahead. Ties go to
     * the smaller turn from the present command; a band's two directions are decided by trail length ahead and,
     * when enabled, by leading away from 0,0.
     */
    private Corridor best(double x, double z, float anchor, float half, float step, int minBins,
                          float reference, double turnWeight, boolean outboundTieBreak) {
        List<Corridor> options = new ArrayList<>();
        // Searching and acquiring look only at old chunks no flight has seen yet.
        for (Corridor corridor : fan(x, z, anchor, half, step, true)) {
            if (corridor.trailBins() < minBins) continue;
            double score = corridor.score() - Math.abs(delta(reference, corridor.yaw())) * 0.01 * turnWeight;
            if (outboundTieBreak) {
                double outward = outbound(x, z, corridor.yaw());
                // Trails lead out from spawn to bases; following one back towards spawn finds neither.
                if (outward < INWARD_LIMIT) continue;
                score += outward * LOCK_OUTBOUND;
            }
            options.add(new Corridor(corridor.yaw(), corridor.trailBins(), score));
        }
        options.sort((a, b) -> Double.compare(b.score(), a.score()));
        for (Corridor option : options)
            if (!routes.wouldRevisit(x, z, option.yaw(), config.lookAhead())) return option;
        return null;
    }

    /** Fan candidates with enough continuation, best first, favouring the present axis and a straight line. */
    private List<Corridor> sortedByScore(List<Corridor> fan, float reference, int minBins) {
        List<Corridor> options = new ArrayList<>();
        for (Corridor corridor : fan) {
            if (corridor.trailBins() < minBins) continue;
            double turn = Math.abs(delta(reference, corridor.yaw()));
            double score = corridor.score() - turn * 0.03 + (turn < FAN_STEP / 2 ? 1 : 0)
                + outbound(lastX, lastZ, corridor.yaw()) * FOLLOW_OUTBOUND;
            options.add(new Corridor(corridor.yaw(), corridor.trailBins(), score));
        }
        options.sort((a, b) -> Double.compare(b.score(), a.score()));
        return options;
    }

    /** A second strong branch, separated from the chosen one by a weaker heading, is reported once. */
    private Lead fork(double x, double z, List<Corridor> fan, Corridor chosen) {
        if (travelled - lastForkAt < FORK_SPACING || travelled - followStart < FORK_SPACING / 2) return null;
        int chosenIndex = -1;
        for (int i = 0; i < fan.size(); i++) if (fan.get(i).yaw() == chosen.yaw()) chosenIndex = i;
        if (chosenIndex < 0) return null;
        Corridor branch = null;
        for (int i = 0; i < fan.size(); i++) {
            Corridor candidate = fan.get(i);
            if (candidate.trailBins() < FORK_BINS || Math.abs(delta(chosen.yaw(), candidate.yaw())) < 20) continue;
            boolean peak = (i == 0 || fan.get(i - 1).score() <= candidate.score())
                && (i == fan.size() - 1 || fan.get(i + 1).score() <= candidate.score());
            if (!peak) continue;
            double valley = Double.POSITIVE_INFINITY;
            for (int j = Math.min(i, chosenIndex) + 1; j < Math.max(i, chosenIndex); j++)
                valley = Math.min(valley, fan.get(j).score());
            if (valley < 0.5 * Math.min(candidate.score(), chosen.score())
                && (branch == null || candidate.score() > branch.score())) branch = candidate;
        }
        if (branch == null || routes.wouldRevisit(x, z, branch.yaw(), config.lookAhead())) return null;
        lastForkAt = travelled;
        return new Lead(LeadKind.FORK, x, z, branch.yaw(), travelled - followStart);
    }

    private List<Corridor> fan(double x, double z, float anchor, float half, float step, boolean unflownOnly) {
        List<Corridor> fan = new ArrayList<>();
        for (float offset = -half; offset <= half + 1e-3f; offset += step)
            fan.add(corridor(x, z, wrap(anchor + offset), unflownOnly));
        return fan;
    }

    /**
     * Longitudinal coverage, not the number of chunks: each 16-block step ahead counts once if any of four lanes
     * across it is trail. A dense patch beside the line therefore cannot outvote a long straight continuation.
     */
    private Corridor corridor(double x, double z, float yaw, boolean unflownOnly) {
        double radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians), forwardZ = Math.cos(radians);
        double sideX = Math.cos(radians), sideZ = Math.sin(radians);
        int trailBins = 0;
        double score = 0;
        for (double ahead = FIRST_BIN; ahead <= config.scanRange(); ahead += BIN) {
            for (double lane : LANES) {
                byte cell = cells.get(cellAt(x + forwardX * ahead + sideX * lane, z + forwardZ * ahead + sideZ * lane));
                if ((cell & TRAIL) != 0 && (!unflownOnly || (cell & FLOWN) == 0)) {
                    trailBins++;
                    score += 1 / (1 + ahead / 192);
                    break;
                }
            }
        }
        return new Corridor(yaw, trailBins, score);
    }

    /**
     * Explicit new terrain across the band ahead: three 16-block rows of at least three non-trail lanes each, with
     * no trail at all, within the next 144 blocks. Unknown holes break the wall; a single fresh chunk cannot.
     * Returns the distance to the first wall row, or NaN when there is no wall.
     */
    private double frontierAhead(double x, double z, float yaw) {
        double radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians), forwardZ = Math.cos(radians);
        double sideX = Math.cos(radians), sideZ = Math.sin(radians);
        int walls = 0;
        double first = Double.NaN;
        for (double ahead = FIRST_BIN; ahead <= 144; ahead += BIN) {
            int frontier = 0;
            for (double lane : LANES) {
                byte cell = cells.get(cellAt(x + forwardX * ahead + sideX * lane, z + forwardZ * ahead + sideZ * lane));
                if ((cell & TRAIL) != 0) return Double.NaN;
                if ((cell & FRONTIER) != 0) frontier++;
            }
            if (frontier >= 3) {
                walls++;
                if (Double.isNaN(first)) first = ahead;
            }
        }
        return walls >= 3 ? first : Double.NaN;
    }

    /** Aim at the middle of the contiguous band ahead, so a wide trail is not followed along one edge. */
    private float centred(double x, double z, float yaw) {
        double radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians), forwardZ = Math.cos(radians);
        double sideX = Math.cos(radians), sideZ = Math.sin(radians);
        double[] centres = new double[3];
        int count = 0;
        for (double ahead : new double[] {64, 96, 128}) {
            Band band = band(x + forwardX * ahead, z + forwardZ * ahead, sideX, sideZ);
            if (band != null) centres[count++] = band.centre();
        }
        if (count == 0) return yaw;
        Arrays.sort(centres, 0, count);
        double centre = centres[count / 2];
        double correction = Math.toDegrees(Math.atan2(centre, 96));
        correction = Math.clamp(correction, -MAX_CENTRING, MAX_CENTRING);
        // Positive lateral offsets lie along (sideX, sideZ), which is a negative yaw change.
        return wrap(yaw - correction);
    }

    /**
     * The band's centreline from cross-sections behind and ahead of the player. Each edge that meets explicit
     * non-trail terrain is fitted as a line, since a trail's edges run parallel to it; unknown chunks never place
     * an edge. With one edge known, which happens on a band wider than view distance, the centre is a learned
     * half-width inside it.
     */
    private Centreline centreline(double x, double z, float yaw) {
        double radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians), forwardZ = Math.cos(radians);
        double sideX = Math.cos(radians), sideZ = Math.sin(radians);
        List<double[]> lefts = new ArrayList<>(), rights = new ArrayList<>();
        for (double along = -96; along <= 128; along += 32) {
            Band band = band(x + forwardX * along, z + forwardZ * along, sideX, sideZ);
            if (band == null) continue;
            // Lanes are measured from the section's own point on the axis line through the player.
            if (band.left().known()) lefts.add(new double[] {along, band.left().lane()});
            if (band.right().known()) rights.add(new double[] {along, band.right().lane()});
        }
        Fit left = fit(lefts), right = fit(rights);
        if (left != null && right != null) {
            double width = (right.offset() - left.offset()) / 2;
            if (width > 0) halfWidth = Math.clamp(halfWidth * 0.8 + width * 0.2, 24, 192);
            return new Centreline((left.offset() + right.offset()) / 2, (left.slope() + right.slope()) / 2);
        }
        if (left != null) return new Centreline(left.offset() + halfWidth, left.slope());
        if (right != null) return new Centreline(right.offset() - halfWidth, right.slope());
        return null;
    }

    /** Least-squares lateral = offset + slope * along, over at least three sections spanning 64 blocks. */
    private static Fit fit(List<double[]> points) {
        if (points.size() < 3 || points.getLast()[0] - points.getFirst()[0] < 64) return null;
        double n = points.size(), sumAlong = 0, sumLateral = 0, sumAlong2 = 0, sumProduct = 0;
        for (double[] point : points) {
            sumAlong += point[0];
            sumLateral += point[1];
            sumAlong2 += point[0] * point[0];
            sumProduct += point[0] * point[1];
        }
        double slope = (n * sumProduct - sumAlong * sumLateral) / (n * sumAlong2 - sumAlong * sumAlong);
        return new Fit((sumLateral - slope * sumAlong) / n, slope);
    }

    /** The contiguous run of trail across the line through (x, z), allowing single-chunk holes. */
    private Band band(double x, double z, double sideX, double sideZ) {
        double start = Double.NaN;
        for (double lane = 0; lane <= 64; lane += 16) {
            if (isTrail(x + sideX * lane, z + sideZ * lane)) { start = lane; break; }
            if (isTrail(x - sideX * lane, z - sideZ * lane)) { start = -lane; break; }
        }
        if (Double.isNaN(start)) return null;
        Edge left = edge(x, z, sideX, sideZ, start, -1);
        Edge right = edge(x, z, sideX, sideZ, start, 1);
        return new Band((left.lane() + right.lane()) / 2, left, right);
    }

    /** The last trail lane before two non-trail lanes; known when explicit non-trail terrain follows it. */
    private Edge edge(double x, double z, double sideX, double sideZ, double start, int direction) {
        double last = start;
        for (double lane = start + direction * 16; Math.abs(lane) <= CENTRE_SCAN; lane += direction * 16) {
            byte cell = cells.get(cellAt(x + sideX * lane, z + sideZ * lane));
            if ((cell & TRAIL) != 0) {
                last = lane;
                continue;
            }
            if (isTrail(x + sideX * (lane + direction * 16), z + sideZ * (lane + direction * 16))) continue;
            return new Edge(last, cell != 0);
        }
        return new Edge(last, false);
    }

    /** Standing inside a band rather than beside an isolated chunk: four of the surrounding nine are trail. */
    private boolean onTrail(double x, double z) {
        int cx = Math.floorDiv((int) Math.floor(x), 16), cz = Math.floorDiv((int) Math.floor(z), 16);
        int trail = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if ((cells.get(chunkKey(cx + dx, cz + dz)) & TRAIL) != 0) trail++;
        return trail >= 4;
    }

    private boolean isTrail(double x, double z) { return (cells.get(cellAt(x, z)) & TRAIL) != 0; }

    /**
     * Towards a point ahead on the spiral's circle, which starts at the player's first distance from 0,0 and widens
     * by spiralSpacing per turn. Far off the circle, the point is as far ahead as the circle is away, so the way back
     * is a 45-degree diagonal. Null when straight, or too close to 0,0 for a useful circle.
     */
    private Float spiralHeading(double x, double z) {
        double radius = Math.hypot(x, z);
        if (pattern != Pattern.SPIRAL || radius < OUTBOUND_MIN_RADIUS) return null;
        if (spiralSense == 0) {
            // Circle in whichever sense is nearer the heading the player chose.
            double radians = Math.toRadians(searchHeading);
            double cross = x / radius * Math.cos(radians) + z / radius * Math.sin(radians);
            spiralSense = cross >= 0 ? 1 : -1;
            spiralStart = radius;
            spiralTurned = 0;
        }
        double circle = spiralStart + spiralSpacing * spiralTurned / (2 * Math.PI);
        double ahead = Math.max(384, RETURN_LEAD * Math.abs(radius - circle)) / circle;
        double bearing = Math.atan2(z, x) + spiralSense * ahead;
        double dx = Math.cos(bearing) * circle - x, dz = Math.sin(bearing) * circle - z;
        return wrap(Math.toDegrees(Math.atan2(dz, dx)) - 90);
    }

    /** Once the flight has moved SEEN_DELAY past a chunk, the chunks around it have been seen. */
    private void markSeen(double x, double z) {
        int cx = Math.floorDiv((int) Math.floor(x), 16), cz = Math.floorDiv((int) Math.floor(z), 16);
        long here = chunkKey(cx, cz);
        if (here != seenChunk) {
            seenChunk = here;
            pathSeen.addLast(new double[] {cx, cz, travelled});
        }
        while (!pathSeen.isEmpty() && travelled - pathSeen.peekFirst()[2] >= SEEN_DELAY) {
            double[] passed = pathSeen.removeFirst();
            markAround((int) passed[0], (int) passed[1]);
        }
    }

    private void markAround(int cx, int cz) {
        int radius = SEEN_RADIUS_CHUNKS;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                long key = chunkKey(cx + dx, cz + dz);
                byte cell = cells.get(key);
                if (cell != 0 && (cell & FLOWN) == 0) cells.put(key, (byte) (cell | FLOWN));
            }
    }

    /** Counts progress around 0,0 in the spiral's sense, wherever the flight is, so the circle widens over time. */
    private void trackBearing(double x, double z) {
        if (spiralSense == 0 || Math.hypot(x, z) < OUTBOUND_MIN_RADIUS) {
            lastBearing = Double.NaN;
            return;
        }
        double bearing = Math.atan2(z, x);
        if (!Double.isNaN(lastBearing)) {
            double turned = Math.IEEEremainder(bearing - lastBearing, 2 * Math.PI) * spiralSense;
            if (turned > 0) spiralTurned += turned;
        }
        lastBearing = bearing;
    }

    /** +1 heading straight away from 0,0, -1 straight towards it; 0 near spawn or when disabled. */
    private double outbound(double x, double z, float yaw) {
        double radius = Math.hypot(x, z);
        if (!preferOutbound || radius < OUTBOUND_MIN_RADIUS) return 0;
        double radians = Math.toRadians(yaw);
        return (-Math.sin(radians) * x + Math.cos(radians) * z) / radius;
    }

    private Decision blocked(State state) {
        this.state = state;
        return new Decision(state, command, true, null);
    }

    private Decision decide(State state, float yaw, Lead lead) {
        this.state = state;
        command = wrap(yaw);
        return new Decision(state, command, false, lead);
    }

    /** The chunk containing a block position. */
    private static long cellAt(double x, double z) {
        return chunkKey(Math.floorDiv((long) Math.floor(x), 16), Math.floorDiv((long) Math.floor(z), 16));
    }

    private static long chunkKey(long chunkX, long chunkZ) { return chunkX << 32 | chunkZ & 0xffffffffL; }

    private static float delta(float from, float to) { return wrap(to - from); }

    static float wrap(double yaw) {
        double result = yaw % 360;
        if (result <= -180) result += 360;
        else if (result > 180) result -= 360;
        return (float) result;
    }
}

package dev.tntcoating.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A deterministic flight simulator for Trail Hunter's navigation. It models what the client can know: chunks
 * arrive nearest-first as they enter view distance (capped like Paper's send rate), XaeroPlus classifies them
 * once and keeps the result, and the cache is sampled once a second. The player turns at the pilot's rate.
 */
final class TrailSim {
    enum Mode { OLD_112, NON_FRESH, EITHER }

    /** Old-chunk geometry: wide trail segments, base blobs at their ends, and recent modern traffic. */
    static final class World {
        record Segment(double x1, double z1, double x2, double z2, double halfWidth) {
            double distance(double x, double z) {
                double dx = x2 - x1, dz = z2 - z1;
                double length = dx * dx + dz * dz;
                double t = length == 0 ? 0 : Math.clamp(((x - x1) * dx + (z - z1) * dz) / length, 0, 1);
                return Math.hypot(x - (x1 + dx * t), z - (z1 + dz * t));
            }
        }
        record Disc(double x, double z, double radius) {}

        final List<Segment> trails = new ArrayList<>();
        final List<Disc> blobs = new ArrayList<>();
        final List<Segment> modernTraffic = new ArrayList<>();
        final List<Disc> modernAreas = new ArrayList<>();
        final List<double[]> bases = new ArrayList<>();
        double oldNoise;
        double holeNoise;
        long seed = 1;
        private final Map<Long, Byte> kinds = new HashMap<>();

        World trail(double halfWidth, double... points) {
            for (int i = 0; i + 3 < points.length; i += 2)
                trails.add(new Segment(points[i], points[i + 1], points[i + 2], points[i + 3], halfWidth));
            return this;
        }

        /** A base: a blob of old chunks with a point of interest at its centre. */
        World base(double x, double z, double radius) {
            blobs.add(new Disc(x, z, radius));
            bases.add(new double[] {x, z});
            return this;
        }

        World modern(double halfWidth, double x1, double z1, double x2, double z2) {
            modernTraffic.add(new Segment(x1, z1, x2, z2, halfWidth));
            return this;
        }

        World modernArea(double x, double z, double radius) {
            modernAreas.add(new Disc(x, z, radius));
            return this;
        }

        World noise(double oldNoise, double holeNoise) {
            this.oldNoise = oldNoise;
            this.holeNoise = holeNoise;
            return this;
        }

        /** 0 ungenerated, 1 modern but generated before, 2 old. */
        byte kind(int cx, int cz) {
            return kinds.computeIfAbsent(key(cx, cz), k -> computeKind(cx, cz));
        }

        private byte computeKind(int cx, int cz) {
            double x = cx * 16 + 8, z = cz * 16 + 8;
            boolean old = false;
            for (Segment trail : trails) if (trail.distance(x, z) <= trail.halfWidth()) { old = true; break; }
            if (!old) for (Disc blob : blobs) if (Math.hypot(x - blob.x(), z - blob.z()) <= blob.radius()) { old = true; break; }
            double roll = hash(cx, cz);
            if (old && roll < holeNoise) old = false;
            else if (!old && roll > 1 - oldNoise) old = true;
            if (old) return 2;
            for (Segment traffic : modernTraffic) if (traffic.distance(x, z) <= traffic.halfWidth()) return 1;
            for (Disc area : modernAreas) if (Math.hypot(x - area.x(), z - area.z()) <= area.radius()) return 1;
            return 0;
        }

        private double hash(int cx, int cz) {
            long h = seed * 0x9E3779B97F4A7C15L ^ cx * 0xC2B2AE3D27D4EB4FL ^ cz * 0x165667B19E3779F9L;
            h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L; h ^= h >>> 33;
            return (h >>> 11) * 0x1.0p-53;
        }
    }

    record Classified(boolean old112, boolean modern, boolean nonFresh, boolean fresh) {}

    /** Bot and simulated client state. */
    final World world;
    final Mode mode;
    final int viewDistance;
    int sendPerTick;
    final double speed;
    final float turnSpeed;
    final TrailRouteMemory memory = new TrailRouteMemory();
    boolean avoidHighways;
    TrailNavigator navigator;
    double x, z;
    float yaw;
    long tick;
    private long nextSample;
    private final Map<Long, Classified> cache = new HashMap<>();
    private final Set<Long> received = new HashSet<>();
    private final ArrayDeque<long[]> pendingArrivals = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();

    // Metrics
    final Set<Long> oldSeen = new HashSet<>();
    final Set<Integer> basesFound = new HashSet<>();
    final Map<TrailExplorer.State, Integer> stateTicks = new HashMap<>();
    int ticksOverOld;
    int blockedTicks;
    double distance;
    boolean stoppedBlocked;
    final List<String> log = new ArrayList<>();
    final List<TrailExplorer.Lead> leads = new ArrayList<>();
    TrailExplorer.State lastState;
    long navNanos;

    TrailSim(World world, Mode mode, int viewDistance, double speed, double x, double z, float yaw) {
        this(world, mode, viewDistance, speed, x, z, yaw, TrailExplorer.Config.defaults());
    }

    TrailSim(World world, Mode mode, int viewDistance, double speed, double x, double z, float yaw,
             TrailExplorer.Config config) {
        this.world = world;
        this.mode = mode;
        this.viewDistance = viewDistance;
        this.sendPerTick = 4;
        this.speed = speed;
        this.turnSpeed = 10;
        this.x = x;
        this.z = z;
        this.yaw = yaw;
        navigator = new TrailNavigator(memory, new TrailTracker.Config(10, 5_000, 20, 32, 5_000, 6_000, 20),
            config, true, (px, pz, heading) -> false);
    }

    /** Pre-seeds XaeroPlus's database, as after earlier flights over the area. */
    TrailSim knownAround(double cx, double cz, double radius) {
        for (int ix = (int) Math.floor((cx - radius) / 16); ix <= (int) Math.floor((cx + radius) / 16); ix++)
            for (int iz = (int) Math.floor((cz - radius) / 16); iz <= (int) Math.floor((cz + radius) / 16); iz++)
                if (Math.hypot(ix * 16 + 8 - cx, iz * 16 + 8 - cz) <= radius) cache.put(key(ix, iz), classify(ix, iz, true));
        return this;
    }

    private Classified classify(int cx, int cz, boolean previouslyGenerated) {
        byte kind = world.kind(cx, cz);
        boolean fresh = kind == 0 && !previouslyGenerated;
        return new Classified(kind == 2, kind != 2, !fresh, fresh);
    }

    boolean trail(Classified c) {
        return trailIgnoringHighways(c);
    }

    private boolean trailIgnoringHighways(Classified c) {
        return switch (mode) {
            case OLD_112 -> c.old112();
            case NON_FRESH -> c.nonFresh();
            case EITHER -> c.old112() || c.nonFresh();
        };
    }

    boolean frontier(Classified c) {
        return !trail(c) && c.fresh();
    }

    void run(int ticks) {
        for (int i = 0; i < ticks && !stoppedBlocked; i++) step();
    }

    void step() {
        long now = tick * 50;
        loadChunks(now);
        long start = System.nanoTime();
        if (now >= nextSample) {
            int pcx = Math.floorDiv((int) Math.floor(x), 16), pcz = Math.floorDiv((int) Math.floor(z), 16);
            for (int dx = -24; dx <= 24; dx++)
                for (int dz = -24; dz <= 24; dz++) {
                    Classified c = cache.get(key(pcx + dx, pcz + dz));
                    if (c == null) continue;
                    if (avoidHighways && TrailHighways.near(pcx + dx, pcz + dz)) navigator.observe(pcx + dx, pcz + dz, false, false);
                    else navigator.observe(pcx + dx, pcz + dz, trail(c), frontier(c));
                }
            nextSample = now + 1_000;
        }
        // Arrivals queued on the previous tick are drained now, as the bridge does.
        while (!pendingArrivals.isEmpty()) {
            long[] arrival = pendingArrivals.poll();
            int cx = (int) arrival[0], cz = (int) arrival[1];
            Classified c = cache.get(key(cx, cz));
            boolean highway = avoidHighways && TrailHighways.near(cx, cz);
            navigator.arrive(cx, cz, !highway && trail(c), !highway && frontier(c), now, arrival[2], x, z, yaw);
        }
        var decision = navigator.explore(now, x, z, yaw);
        if (decision.lead() != null) leads.add(decision.lead());
        navNanos += System.nanoTime() - start;
        stateTicks.merge(decision.state(), 1, Integer::sum);
        if (decision.state() != lastState) {
            log.add(String.format("t=%5.0fs %-9s at %6.0f,%6.0f yaw %4.0f", now / 1000.0, decision.state(), x, z, decision.yaw()));
            lastState = decision.state();
        }
        if (decision.blocked()) {
            blockedTicks++;
            stoppedBlocked = true;
            log.add(String.format("t=%5.0fs BLOCKED at %6.0f,%6.0f", now / 1000.0, x, z));
            return;
        }
        yaw = ElytraSteer.turn(yaw, decision.yaw(), turnSpeed);
        yaw = ElytraSteer.wrap(yaw);
        double nx = x - Math.sin(Math.toRadians(yaw)) * speed;
        double nz = z + Math.cos(Math.toRadians(yaw)) * speed;
        distance += Math.hypot(nx - x, nz - z);
        x = nx;
        z = nz;
        memory.record(x, z);
        if (world.kind(Math.floorDiv((int) Math.floor(x), 16), Math.floorDiv((int) Math.floor(z), 16)) == 2) ticksOverOld++;
        tick++;
    }

    private void loadChunks(long now) {
        int pcx = Math.floorDiv((int) Math.floor(x), 16), pcz = Math.floorDiv((int) Math.floor(z), 16);
        List<long[]> candidates = new ArrayList<>();
        for (int dx = -viewDistance - 1; dx <= viewDistance + 1; dx++)
            for (int dz = -viewDistance - 1; dz <= viewDistance + 1; dz++) {
                double ex = Math.max(0, Math.abs(dx) - 1), ez = Math.max(0, Math.abs(dz) - 1);
                if (ex * ex + ez * ez >= viewDistance * viewDistance) continue;
                long k = key(pcx + dx, pcz + dz);
                if (received.contains(k) || queued.contains(k)) continue;
                candidates.add(new long[] {pcx + dx, pcz + dz, dx * dx + dz * dz});
            }
        candidates.sort(Comparator.comparingLong(c -> c[2]));
        int sent = 0;
        for (long[] c : candidates) {
            if (sent++ >= sendPerTick) break;
            int cx = (int) c[0], cz = (int) c[1];
            long k = key(cx, cz);
            received.add(k);
            byte kind = world.kind(cx, cz);
            if (kind == 2) oldSeen.add(k);
            // XaeroPlus preserves an earlier fresh result on a revisit.
            cache.computeIfAbsent(k, ignored -> classify(cx, cz, false));
            pendingArrivals.add(new long[] {cx, cz, now});
        }
        double reach = viewDistance * 16;
        for (int i = 0; i < world.bases.size(); i++) {
            double[] base = world.bases.get(i);
            if (Math.hypot(base[0] - x, base[1] - z) <= reach) basesFound.add(i);
        }
    }

    String summary() {
        long ends = leads.stream().filter(lead -> lead.kind() == TrailExplorer.LeadKind.TRAIL_END).count();
        long endsNearBases = leads.stream().filter(lead -> lead.kind() == TrailExplorer.LeadKind.TRAIL_END
            && world.bases.stream().anyMatch(base -> Math.hypot(base[0] - lead.x(), base[1] - lead.z()) < 300)).count();
        return String.format("bases %d/%d, ends %d (%d at bases), old seen %d, over-old %.0f%%, %.0f blocks, states %s%s, nav %.2f ms/tick",
            basesFound.size(), world.bases.size(), ends, endsNearBases, oldSeen.size(), 100.0 * ticksOverOld / Math.max(1, tick), distance,
            stateTicks, stoppedBlocked ? " BLOCKED" : "", navNanos / 1e6 / Math.max(1, tick));
    }

    static long key(int cx, int cz) { return (long) cx << 32 | cz & 0xffffffffL; }

    /**
     * A denser seeded world: mostly radial trails from spawn with bends and branches, some cross-country trails,
     * recent modern traffic, and a base at every trail end.
     */
    static World realistic(long seed) {
        Random random = new Random(seed);
        World world = new World();
        world.seed = seed;
        world.modernArea(0, 0, 1_500);
        for (int i = 0; i < 48; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double r = 500 + random.nextDouble() * 5_000;
            spoke(world, random, Math.cos(angle) * r, Math.sin(angle) * r, angle + (random.nextDouble() - 0.5) * 0.7,
                1_500 + random.nextDouble() * 9_000);
        }
        for (int i = 0; i < 12; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double r = 2_000 + random.nextDouble() * 12_000;
            spoke(world, random, Math.cos(angle) * r, Math.sin(angle) * r, random.nextDouble() * Math.PI * 2,
                1_000 + random.nextDouble() * 3_000);
        }
        for (int i = 0; i < 24; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double r = random.nextDouble() * 14_000;
            double heading = random.nextDouble() * Math.PI * 2;
            double length = 2_000 + random.nextDouble() * 6_000;
            double x = Math.cos(angle) * r, z = Math.sin(angle) * r;
            world.modern(80 + random.nextDouble() * 60, x, z, x + Math.cos(heading) * length, z + Math.sin(heading) * length);
        }
        return world.noise(0.003, 0.02);
    }

    /** Adds the eight old highways from spawn, with trails branching off them to bases. */
    static World withHighways(World world, long seed) {
        Random random = new Random(seed * 31 + 7);
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            world.trail(150, Math.cos(angle) * 500, Math.sin(angle) * 500, Math.cos(angle) * 40_000, Math.sin(angle) * 40_000);
            for (int branch = 0; branch < 4; branch++) {
                double along = 2_000 + random.nextDouble() * 12_000;
                double side = random.nextBoolean() ? 1 : -1;
                double heading = angle + side * Math.toRadians(30 + random.nextDouble() * 60);
                spoke(world, random, Math.cos(angle) * along, Math.sin(angle) * along, heading, 1_000 + random.nextDouble() * 3_000);
            }
        }
        return world;
    }

    private static void spoke(World world, Random random, double x, double z, double heading, double total) {
        double halfWidth = 50 + random.nextDouble() * 70;
        List<Double> points = new ArrayList<>(List.of(x, z));
        double remaining = total;
        while (remaining > 0) {
            double length = Math.min(remaining, 700 + random.nextDouble() * 2_500);
            remaining -= length;
            heading += (random.nextDouble() - 0.5) * Math.toRadians(50);
            x += Math.cos(heading) * length;
            z += Math.sin(heading) * length;
            points.add(x);
            points.add(z);
            if (remaining > 0 && random.nextDouble() < 0.25) {
                double branch = heading + (random.nextBoolean() ? 1 : -1) * Math.toRadians(25 + random.nextDouble() * 45);
                double branchLength = 500 + random.nextDouble() * 2_000;
                double bx = x + Math.cos(branch) * branchLength, bz = z + Math.sin(branch) * branchLength;
                world.trail(halfWidth * 0.8, x, z, bx, bz);
                world.base(bx, bz, 50 + random.nextDouble() * 80);
            }
        }
        world.trail(halfWidth, points.stream().mapToDouble(Double::doubleValue).toArray());
        world.base(x, z, 50 + random.nextDouble() * 80);
    }

    /** A seeded random network: spokes from spawn with bends, forks and a base at each end. */
    static World network(long seed, int spokes) {
        Random random = new Random(seed);
        World world = new World();
        world.seed = seed;
        world.modernArea(0, 0, 1_200);
        for (int i = 0; i < spokes; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double r = 800 + random.nextDouble() * 4_000;
            double x = Math.cos(angle) * r, z = Math.sin(angle) * r;
            double heading = angle + (random.nextDouble() - 0.5) * 0.6;
            double halfWidth = 60 + random.nextDouble() * 70;
            int legs = 1 + random.nextInt(3);
            double[] points = new double[(legs + 1) * 2];
            points[0] = x;
            points[1] = z;
            for (int leg = 1; leg <= legs; leg++) {
                double length = 800 + random.nextDouble() * 2_500;
                heading += (random.nextDouble() - 0.5) * Math.toRadians(50);
                x += Math.cos(heading) * length;
                z += Math.sin(heading) * length;
                points[leg * 2] = x;
                points[leg * 2 + 1] = z;
                if (random.nextDouble() < 0.3) {
                    double branch = heading + (random.nextBoolean() ? 1 : -1) * Math.toRadians(25 + random.nextDouble() * 40);
                    double branchLength = 600 + random.nextDouble() * 1_800;
                    double bx = x + Math.cos(branch) * branchLength, bz = z + Math.sin(branch) * branchLength;
                    world.trail(halfWidth * 0.8, x, z, bx, bz);
                    world.base(bx, bz, 60 + random.nextDouble() * 80);
                }
            }
            world.trail(halfWidth, points);
            world.base(x, z, 60 + random.nextDouble() * 80);
        }
        for (int i = 0; i < spokes / 2; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double r = random.nextDouble() * 6_000;
            double heading = random.nextDouble() * Math.PI * 2;
            double length = 2_000 + random.nextDouble() * 6_000;
            double x = Math.cos(angle) * r, z = Math.sin(angle) * r;
            world.modern(80 + random.nextDouble() * 60, x, z, x + Math.cos(heading) * length, z + Math.sin(heading) * length);
        }
        return world;
    }
}

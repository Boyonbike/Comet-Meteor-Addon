/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 1.21.11 artifact behaviour, the CrystalCombat v2.1 plan and the
 * project's own Phase 5 contracts; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.Capability;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CollisionView;
import dev.tntcoating.core.combat.CombatDamage;
import dev.tntcoating.core.combat.CombatMotionModel;
import dev.tntcoating.core.combat.Confidence;
import dev.tntcoating.core.combat.DirectCrystal;
import dev.tntcoating.core.combat.DirectItem;
import dev.tntcoating.core.combat.DirectSettings;
import dev.tntcoating.core.combat.DirectTarget;
import dev.tntcoating.core.combat.DirectWorld;
import dev.tntcoating.core.combat.EffectiveHealth;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.MitigatedDamage;
import dev.tntcoating.core.combat.MitigationView;
import dev.tntcoating.core.combat.MotionSample;
import dev.tntcoating.core.combat.PistonFacing;
import dev.tntcoating.core.combat.PistonPlanner;
import dev.tntcoating.core.combat.PredictedBox;
import dev.tntcoating.core.combat.RawDamage;
import dev.tntcoating.core.combat.Safety;
import dev.tntcoating.core.combat.TargetEstimate;
import dev.tntcoating.core.combat.Vec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 5.3 read-only production world for the piston trap. Every method reads the current
 * {@link DirectWorld} snapshot produced by {@link DirectWorldSource}; nothing here sends or mutates the
 * world. Unknown motion returns {@code null} from {@link #feasiblePositions(EntityKey)} (the planner then
 * rejects the candidate) instead of inventing a point.
 */
public final class PistonTrapWorld implements PistonTrapController.World {
    private static final double STATIONARY_EPSILON = 1.0e-6;
    private static final double REFERENCE_DAMAGE = 10.0;

    private final DirectWorldSource source;
    private final CombatDamageAdapter damage;
    /** Both replaced together on every config change; the stack outlives disable. */
    private volatile DirectSettings settings;
    private volatile Safety safety;
    private final CombatMotionModel motionModel = new CombatMotionModel();
    private final Map<EntityKey, Track> tracks = new HashMap<>();
    private final AtomicLong planIds = new AtomicLong();

    private DirectWorld world;
    private int currentTick = -1;
    private int lastSampleTick = Integer.MIN_VALUE;

    public PistonTrapWorld(DirectWorldSource source, CombatDamageAdapter damage, DirectSettings settings,
                           Safety safety) {
        this.source = Objects.requireNonNull(source, "source");
        this.damage = Objects.requireNonNull(damage, "damage");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.safety = Objects.requireNonNull(safety, "safety");
    }

    /** Live settings change: ranges and the self, friend and target limits apply from the next check. */
    @Override
    public void reconfigure(DirectSettings next) {
        Objects.requireNonNull(next, "next");
        settings = next;
        safety = next.safety();
    }

    /**
     * Read-side refresh of the live world view at {@code serverTick}. The stationary trackers are only
     * advanced by {@link #onServerTick(int)}; production's sample dedupe lives there.
     */
    @Override
    public void refresh(int serverTick) {
        currentTick = serverTick;
        world = source.read();
        if (world == null) tracks.clear();
    }

    /**
     * Observational sample point: refresh the view and advance the stationary counters. Deduplicated per
     * {@code serverTick} so a Hybrid preview and the trap tick for the same tick count the world once.
     */
    @Override
    public void onServerTick(int serverTick) {
        if (serverTick == lastSampleTick) return;
        lastSampleTick = serverTick;
        refresh(serverTick);
        if (world == null) return;
        Map<EntityKey, Vec> positions = new LinkedHashMap<>();
        positions.put(world.self(), world.selfPosition());
        for (DirectTarget target : world.targets()) {
            positions.put(target.entity(), target.position());
        }
        for (Map.Entry<EntityKey, Vec> entry : positions.entrySet()) {
            Track track = tracks.computeIfAbsent(entry.getKey(), ignored -> new Track());
            Vec position = entry.getValue();
            boolean still = track.last != null && position.distance(track.last) <= STATIONARY_EPSILON
                && speed(entry.getKey()) < PistonPlanner.STATIONARY_SPEED;
            track.stationaryTicks = still ? track.stationaryTicks + 1 : 0;
            track.last = position;
        }
        tracks.keySet().retainAll(positions.keySet());
    }

    // ------------------------------------------------------------------ trap observation surface

    @Override
    public List<EntityKey> targets() {
        if (world == null) return List.of();
        Vec self = world.selfPosition();
        List<EntityKey> result = new ArrayList<>();
        for (DirectTarget target : world.targets()) {
            if (target.dead() || target.creative() || target.spectator()) continue;
            if (target.entity().equals(world.self())) continue;
            if (target.position().distance(self) > settings.targetRange()) continue;
            result.add(target.entity());
        }
        return result;
    }

    @Override
    public boolean alive(EntityKey entity) {
        if (world == null || entity == null) return false;
        if (entity.equals(world.self())) return true;
        DirectTarget target = target(entity);
        return target != null && !target.dead();
    }

    @Override
    public EffectiveHealth health(EntityKey entity) {
        if (world == null || entity == null) return unknownHealth();
        if (entity.equals(world.self())) return world.safetyState().selfHealth();
        DirectTarget target = target(entity);
        if (target != null) return target.health();
        EffectiveHealth friend = world.safetyState().friends().get(entity);
        return friend == null ? unknownHealth() : friend;
    }

    @Override
    public Map<EntityKey, EffectiveHealth> friendHealth() {
        return world == null ? Map.of() : world.safetyState().friends();
    }

    @Override
    public Safety safety() {
        return safety;
    }

    @Override
    public long mitigationRevision() {
        return world == null ? 0L : world.damage().envelope().revision();
    }

    @Override
    public boolean withinAttackEnvelope(EntityKey target, Vec pushed) {
        if (world == null || pushed == null) return false;
        return world.crystalReachable(pushed, Box.around(pushed, 0.5), settings.attackRange(),
            settings.wallsRange());
    }

    @Override
    public UUID uuid(EntityKey entity) {
        return entity == null ? null : entity.uuid();
    }

    @Override
    public Set<EntityKey> crystalEntities() {
        if (world == null) return Set.of();
        Set<EntityKey> result = new LinkedHashSet<>();
        for (DirectCrystal crystal : world.crystals()) result.add(crystal.entity());
        return result;
    }

    @Override
    public Set<UUID> crystalUuids() {
        if (world == null) return Set.of();
        Set<UUID> result = new LinkedHashSet<>();
        for (DirectCrystal crystal : world.crystals()) {
            if (crystal.entity().uuid() != null) result.add(crystal.entity().uuid());
        }
        return result;
    }

    @Override
    public Optional<Vec> crystalClientPosition(EntityKey entity) {
        // DirectWorld is the client's own observation; a present crystal carries the last position the
        // client knows. C21 means that position may stay at the spawn cell, which callers treat as stale.
        if (world == null || entity == null) return Optional.empty();
        DirectCrystal crystal = crystal(entity);
        return crystal == null ? Optional.empty() : Optional.of(crystal.position());
    }

    // ------------------------------------------------------------------ PistonPlanner.World

    @Override
    public EntityKey self() {
        return world == null ? null : world.self();
    }

    @Override
    public boolean hasItem(String itemId) {
        if (itemId == null || world == null) return false;
        for (DirectItem item : world.hotbar()) {
            if (item.slot() >= 0 && item.slot() <= 8 && itemId.equals(item.id())) return true;
        }
        return false;
    }

    @Override
    public Vec position(EntityKey entity) {
        if (world == null || entity == null) throw new IllegalArgumentException("no world snapshot");
        if (entity.equals(world.self())) return world.selfPosition();
        DirectTarget target = target(entity);
        if (target != null) return target.position();
        DirectCrystal crystal = crystal(entity);
        if (crystal != null) return crystal.position();
        Track track = tracks.get(entity);
        if (track != null && track.last != null) return track.last;
        throw new IllegalArgumentException("unknown entity position: " + entity);
    }

    @Override
    public double speed(EntityKey entity) {
        if (world == null || entity == null) return Double.POSITIVE_INFINITY;
        MotionSample sample = world.motion(entity);
        if (sample == null || sample.teleportOrSetback()) return Double.POSITIVE_INFINITY;
        Vec velocity = sample.velocity();
        return Math.sqrt(velocity.x() * velocity.x() + velocity.y() * velocity.y()
            + velocity.z() * velocity.z());
    }

    @Override
    public int stationaryTicks(EntityKey entity) {
        Track track = tracks.get(entity);
        return track == null ? 0 : track.stationaryTicks;
    }

    @Override
    public double crystalDamage(Vec crystalCentre, Vec victimFeet) {
        if (crystalCentre == null || victimFeet == null) return 0.0;
        RawDamage raw = CombatDamage.rawExplosionDamage(crystalCentre.distance(victimFeet), 1.0,
            CombatDamage.CRYSTAL_EXPLOSION_POWER);
        return raw.value();
    }

    /**
     * Body-box upper bound at a predicted feet position. Self and friends price the translated body box
     * with the audited weakest-mitigation upper bound, so head/eye exposure is included; this method
     * returns an already-mitigated raw damage value and the planner does not scale it again.
     */
    @Override
    public double crystalDamageUpper(EntityKey entity, Vec crystalCentre, Vec victimFeet) {
        if (world == null || entity == null || crystalCentre == null || victimFeet == null) return 0.0;
        MitigatedDamage upper;
        if (entity.equals(world.self())) {
            upper = world.damage().selfUpperWorst(victimFeet, crystalCentre, Set.of());
        } else {
            upper = world.damage().friendUpperWorst(entity, victimFeet, crystalCentre, Set.of());
        }
        return upper == null ? 0.0 : Math.max(0.0, upper.beforeAbsorption());
    }

    @Override
    public List<EntityKey> nearbyFriends() {
        return world == null ? List.of() : List.copyOf(world.safetyState().friends().keySet());
    }

    @Override
    public boolean friendly(EntityKey entity) {
        return world != null && entity != null && world.safetyState().friends().containsKey(entity);
    }

    /**
     * Damage multiplier for the planner: 1.0 means the full crystal damage lands. Target mitigation uses
     * the current view (guaranteed damage must not be inflated); self and friends use the weakest envelope
     * (risk must not be understated). Unknown views use 1.0.
     */
    @Override
    public double mitigation(EntityKey entity) {
        if (world == null || entity == null) return 1.0;
        MitigationView view = world.mitigation(entity);
        if (view == null) return 1.0;
        RawDamage reference = new RawDamage(REFERENCE_DAMAGE);
        boolean selfOrFriend = entity.equals(world.self())
            || world.safetyState().friends().containsKey(entity);
        double after = selfOrFriend
            ? damage.upperBound(reference, view).beforeAbsorption()
            : damage.mitigated(reference, view).beforeAbsorption();
        double multiplier = after / REFERENCE_DAMAGE;
        if (!Double.isFinite(multiplier)) return 1.0;
        return Math.max(0.0, Math.min(1.0, multiplier));
    }

    @Override
    public double selfDamageCeiling() {
        return safety.maxSelf();
    }

    @Override
    public double friendDamageCeiling() {
        return safety.maxFriend();
    }

    @Override
    public double minimumTargetDamage() {
        return safety.minTarget();
    }

    @Override
    public long planId() {
        return planIds.incrementAndGet();
    }

    @Override
    public int currentTick() {
        return currentTick;
    }

    @Override
    public List<Vec> feasiblePositions(EntityKey entity) {
        if (world == null || entity == null) return null;
        MotionSample motion = world.motion(entity);
        if (motion == null || motion.teleportOrSetback()) return null;
        Vec base = position(entity);
        Cell feet = cell(base);
        CollisionView collision = world.collision();
        List<Vec> points = new ArrayList<>();
        points.add(base);
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] direction : directions) {
            Cell side = new Cell(feet.x() + direction[0], feet.y(), feet.z() + direction[1]);
            Cell head = side.above();
            if (collision.blocks(side) || collision.blocks(head)) continue;
            points.add(new Vec(base.x() + direction[0], base.y(), base.z() + direction[1]));
        }
        return points;
    }

    /**
     * Continuous three-dimensional reachable feet hull from the motion model. Unknown motion, a teleport or
     * a lost prediction returns {@code null}; the planner then falls back to the discrete list or rejects.
     */
    @Override
    public Box feasibleHull(EntityKey entity) {
        if (world == null || entity == null) return null;
        MotionSample motion = world.motion(entity);
        if (motion == null || motion.teleportOrSetback()) return null;
        PredictedBox predicted = motionModel.predict(motion, 2, world.collision());
        if (predicted.confidence() == Confidence.LOST) return null;
        return predicted.hull();
    }

    /**
     * Audited lower-bound target damage for a victim point: the synthetic target keeps the real identity,
     * effective health and mitigation, translated so the damage adapter prices the actual victim box at
     * the pushed feet position (cover and lower-bound exposure included).
     */
    @Override
    public double targetDamageLower(EntityKey target, Vec crystalCentre, Vec victimFeet) {
        if (world == null || target == null || crystalCentre == null || victimFeet == null) return 0.0;
        DirectTarget actual = target(target);
        if (actual == null) return 0.0;
        double dx = victimFeet.x() - actual.position().x();
        double dy = victimFeet.y() - actual.position().y();
        double dz = victimFeet.z() - actual.position().z();
        Box shifted = new Box(actual.box().min().add(dx, dy, dz), actual.box().max().add(dx, dy, dz));
        DirectTarget synthetic = new DirectTarget(actual.entity(), victimFeet, shifted, actual.health(),
            actual.mitigation(), actual.dead(), actual.creative(), actual.spectator(), actual.friend(),
            actual.armourPressure());
        TargetEstimate estimate = world.damage().targetEstimate(synthetic, crystalCentre, Set.of());
        double lower = estimate.guaranteedLower();
        return Double.isFinite(lower) && lower > 0.0 ? lower : 0.0;
    }

    // ------------------------------------------------------------------ PistonGeometry.World

    @Override
    public String blockId(Cell cell) {
        return world == null || cell == null ? null : world.blocks().blockId(cell);
    }

    @Override
    public boolean replaceable(Cell cell) {
        return world != null && cell != null && world.blocks().isReplaceable(cell);
    }

    @Override
    public boolean fullSupport(Cell cell) {
        return world != null && cell != null && world.blocks().fullSupport(cell);
    }

    @Override
    public boolean entityFree(Cell cell) {
        return world != null && cell != null && world.blocks().entityFree(Box.fromCell(cell));
    }

    @Override
    public boolean crystalPresent(Cell cell) {
        return world != null && cell != null && world.blocks().crystalPresent(cell);
    }

    @Override
    public boolean pistonPowered(Cell cell) {
        return world != null && cell != null && world.blocks().pistonPowered(cell);
    }

    @Override
    public boolean pistonExtended(Cell cell) {
        return world != null && cell != null && world.blocks().pistonExtended(cell);
    }

    @Override
    public PistonFacing pistonFacing(Cell cell) {
        return world == null || cell == null ? null : world.blocks().pistonFacing(cell);
    }

    /**
     * Conservative redstone-neighbour check. This is broader than the exact component set on purpose: a
     * false positive only rejects a candidate, while a false negative could power the trap early.
     */
    @Override
    public boolean redstoneComponent(Cell cell) {
        String id = blockId(cell);
        if (id == null) return false;
        if (id.equals(PistonTrapController.PISTON_ITEM) || id.equals("minecraft:end_crystal")) return false;
        return id.contains("redstone") || id.contains("lever") || id.contains("torch")
            || id.contains("repeater") || id.contains("comparator") || id.contains("observer")
            || id.endsWith("_button") || id.endsWith("_pressure_plate");
    }

    // ------------------------------------------------------------------ helpers

    private DirectTarget target(EntityKey entity) {
        if (world == null || entity == null) return null;
        for (DirectTarget target : world.targets()) {
            if (target.entity().equals(entity)) return target;
        }
        return null;
    }

    private DirectCrystal crystal(EntityKey entity) {
        if (world == null || entity == null) return null;
        for (DirectCrystal crystal : world.crystals()) {
            if (crystal.entity().equals(entity)) return crystal;
        }
        return null;
    }

    private EffectiveHealth unknownHealth() {
        return new EffectiveHealth(0.0, 0.0, Capability.UNKNOWN, 0L);
    }

    private static Cell cell(Vec position) {
        return new Cell((int) Math.floor(position.x()), (int) Math.floor(position.y()),
            (int) Math.floor(position.z()));
    }

    private static final class Track {
        private Vec last;
        private int stationaryTicks;
    }
}

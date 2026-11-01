/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Scripted, Minecraft-free {@link DirectWorld} for the Phase 4.2 motion-prediction tests. Motion samples,
 * collision geometry and damage prices are all injected; the planner itself always runs unmodified.
 */
final class MotionFakeWorld implements DirectWorld {
    static final Safety SAFETY = CombatDamagePolicy.conservativeSafety();
    static final ConnectionKey CONN = new ConnectionKey(11L);
    static final ActionGeneration GEN = new ActionGeneration(5L);
    static final EntityKey SELF = new EntityKey(CONN, 1, new UUID(0, 1));
    static final Vec SELF_POSITION = new Vec(0.5, 64.5, 0.5);
    static final Vec EYE = new Vec(0.5, 65.62, 0.5);
    static final Vec TARGET_POSITION = new Vec(2.5, 64.5, 0.5);
    static final Cell BASE = new Cell(0, 63, 0);
    static final Cell CRYSTAL_CELL = new Cell(0, 64, 0);

    final FakeDamage damage = new FakeDamage();
    final FakeBlocks blocks = new FakeBlocks();
    final List<DirectTarget> targets = new ArrayList<>();
    final List<DirectCrystal> crystals = new ArrayList<>();
    final List<DirectItem> hotbar = new ArrayList<>();
    final Map<EntityKey, MotionSample> motion = new LinkedHashMap<>();
    final Map<EntityKey, EffectiveHealth> friendHealth = new LinkedHashMap<>();
    CollisionView collisions = CollisionView.open();
    int motionCalls;
    boolean reachable = true;
    boolean placeReachable = true;
    boolean visible = true;

    MotionFakeWorld() {
        hotbar.add(new DirectItem(0, "minecraft:end_crystal", false));
        hotbar.add(new DirectItem(1, "minecraft:obsidian", false));
        hotbar.add(new DirectItem(2, "minecraft:diamond_sword", true));
        blocks.base = BASE;
    }

    DirectTarget addTarget(Vec position) {
        return addTarget(position, new EffectiveHealth(20.0, 0.0, Capability.KNOWN, 0L));
    }

    DirectTarget addTarget(Vec position, EffectiveHealth health) {
        EntityKey key = new EntityKey(CONN, 20 + targets.size(),
            UUID.nameUUIDFromBytes(("target-" + targets.size()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        DirectTarget target = new DirectTarget(key, position,
            new Box(new Vec(position.x() - 1.0, position.y(), position.z() - 1.0),
                new Vec(position.x() + 1.0, position.y() + 2.0, position.z() + 1.0)),
            health, MitigationView.of(new MitigationState(CombatDifficulty.NORMAL, 0.0, 0.0, 0.0, 0,
                false, false, false)), false, false, false, false, false);
        targets.add(target);
        return target;
    }

    EntityKey addFriend(Vec position, double health) {
        EntityKey key = new EntityKey(CONN, 40 + friendHealth.size(),
            UUID.nameUUIDFromBytes(("friend-" + friendHealth.size()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        friendHealth.put(key, new EffectiveHealth(health, 0.0, Capability.KNOWN, 0L));
        return key;
    }

    void setMotion(EntityKey entity, MotionSample sample) {
        motion.put(entity, sample);
    }

    void setCollisions(CollisionView collisions) {
        this.collisions = collisions;
    }

    @Override public ConnectionKey connection() { return CONN; }
    @Override public SafetyState safetyState() {
        return new SafetyState(CONN, GEN, SELF, new EffectiveHealth(20.0, 0.0, Capability.KNOWN, 0L),
            Map.copyOf(friendHealth), SAFETY);
    }
    @Override public EntityKey self() { return SELF; }
    @Override public Vec eye() { return EYE; }
    @Override public Vec selfPosition() { return SELF_POSITION; }
    @Override public List<DirectTarget> targets() { return List.copyOf(targets); }
    @Override public List<DirectCrystal> crystals() { return List.copyOf(crystals); }
    @Override public List<DirectItem> hotbar() { return List.copyOf(hotbar); }
    @Override public int selectedSlot() { return 0; }
    @Override public boolean weakness() { return false; }
    @Override public DirectBlocks blocks() { return blocks; }
    @Override public DirectDamage damage() { return damage; }
    @Override public MotionSample motion(EntityKey entity) {
        motionCalls++;
        return motion.get(entity);
    }
    @Override public CollisionView collision() { return collisions; }
    @Override public boolean crystalReachable(Vec crystalPosition, Box crystalBox, double attackRange,
                                              double wallsRange) {
        return reachable;
    }
    @Override public boolean placementReachable(Cell crystalCell, double placeRange) {
        return placeReachable;
    }
    @Override public boolean visible(Vec eye, Box box) { return visible; }
    @Override public boolean eating() { return false; }
    @Override public boolean drinking() { return false; }
    @Override public boolean mining() { return false; }
    @Override public boolean screenOpen() { return false; }
    @Override public boolean baritoneActive() { return false; }
    @Override public double tps() { return 20.0; }
    @Override public long nanosSinceLastFeedback() { return 0L; }

    static final class FakeBlocks implements DirectBlocks {
        Cell base = BASE;

        @Override public String blockId(Cell cell) {
            return cell.equals(base) ? "minecraft:obsidian" : "minecraft:air";
        }
        @Override public boolean isAir(Cell cell) { return !cell.equals(base); }
        @Override public boolean isBaseBlock(Cell cell) { return cell.equals(base); }
        @Override public boolean isReplaceable(Cell cell) { return isAir(cell); }
        @Override public boolean hasSupportBelow(Cell cell) { return false; }
        @Override public boolean blocked(Cell cell) { return false; }
        @Override public boolean entityFree(Box box) { return true; }
        @Override public boolean crystalPresent(Cell cell) { return false; }
        @Override public boolean faceVisible(Vec eye, Cell base) { return true; }
    }

    static final class FakeDamage implements DirectDamage {
        TargetEstimate estimate = new TargetEstimate(4.0, 10.0, 12.0);
        Function<DirectTarget, TargetEstimate> price;
        MitigatedDamage selfUpper = new MitigatedDamage(1.0);
        Map<EntityKey, MitigatedDamage> friendUppers = new LinkedHashMap<>();
        Function<Vec, MitigatedDamage> selfUpperAt;
        final Map<EntityKey, Function<Vec, MitigatedDamage>> friendUpperAt = new LinkedHashMap<>();
        MitigationEnvelope envelope = new MitigationEnvelope(1L);

        @Override public MitigationEnvelope envelope() { return envelope; }

        @Override public TargetEstimate targetEstimate(DirectTarget target, Vec crystalCentre,
                                                       Collection<Cell> overlays) {
            if (price != null) return price.apply(target);
            return estimate;
        }

        @Override public MitigatedDamage selfUpper(Vec crystalCentre, Collection<Cell> overlays) {
            return selfUpper;
        }

        @Override public MitigatedDamage selfUpperAt(Vec selfFeet, Vec crystalCentre,
                                                     Collection<Cell> overlays) {
            return selfUpperAt == null ? selfUpper : selfUpperAt.apply(selfFeet);
        }

        @Override public Map<EntityKey, MitigatedDamage> friendUppers(Vec crystalCentre,
                                                                      Collection<Cell> overlays) {
            return Map.copyOf(friendUppers);
        }

        @Override public MitigatedDamage friendUpperAt(EntityKey friend, Vec friendFeet, Vec crystalCentre,
                                                       Collection<Cell> overlays) {
            Function<Vec, MitigatedDamage> upper = friendUpperAt.get(friend);
            return upper == null ? friendUppers.get(friend) : upper.apply(friendFeet);
        }
    }
}

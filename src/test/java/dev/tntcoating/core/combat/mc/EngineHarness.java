/*
 * Original Comet implementation by Boyonabike.
 * Independent: test harness written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.Arrival;
import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.Capability;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CollisionView;
import dev.tntcoating.core.combat.CombatDifficulty;
import dev.tntcoating.core.combat.CombatEngine;
import dev.tntcoating.core.combat.CombatDamagePolicy;
import dev.tntcoating.core.combat.CombatProfiles;
import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.CombatScheduler;
import dev.tntcoating.core.combat.CombatSession;
import dev.tntcoating.core.combat.ConnectionKey;
import dev.tntcoating.core.combat.CrystalLedger;
import dev.tntcoating.core.combat.CrystalState;
import dev.tntcoating.core.combat.DirectBlocks;
import dev.tntcoating.core.combat.DirectCrystal;
import dev.tntcoating.core.combat.DirectDamage;
import dev.tntcoating.core.combat.DirectItem;
import dev.tntcoating.core.combat.DirectPlanner;
import dev.tntcoating.core.combat.DirectSettings;
import dev.tntcoating.core.combat.DirectTarget;
import dev.tntcoating.core.combat.DirectWorld;
import dev.tntcoating.core.combat.EffectiveHealth;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.MitigatedDamage;
import dev.tntcoating.core.combat.MitigationEnvelope;
import dev.tntcoating.core.combat.MitigationState;
import dev.tntcoating.core.combat.MitigationView;
import dev.tntcoating.core.combat.MotionSample;
import dev.tntcoating.core.combat.Observation;
import dev.tntcoating.core.combat.PistonFacing;
import dev.tntcoating.core.combat.ObservationKind;
import dev.tntcoating.core.combat.ObservationPayload;
import dev.tntcoating.core.combat.PacketLedger;
import dev.tntcoating.core.combat.Safety;
import dev.tntcoating.core.combat.SafetyState;
import dev.tntcoating.core.combat.Snapshot;
import dev.tntcoating.core.combat.TargetEstimate;
import dev.tntcoating.core.combat.Ticket;
import dev.tntcoating.core.combat.Vec;
import dev.tntcoating.core.combat.mc.CombatTestFakes.FakeClient;
import dev.tntcoating.core.combat.mc.CombatTestFakes.FakeSink;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * End-to-end engine harness: a fake client/connection plus the real risk budget, ledger, scheduler,
 * inventory adapter, runtime and dispatch gateway. Observations are fed in the same receipt order the
 * Phase 0 traces record, while the engine plans against a fully controllable {@link DirectWorld}.
 *
 * <p>The harness never sends: the only path from a planned action to the fake connection is
 * {@link CombatRuntime#dispatch} through {@link RuntimeDirectGateway}, exactly like production.</p>
 */
final class EngineHarness {
    static final String END_CRYSTAL = "minecraft:end_crystal";
    static final String OBSIDIAN = "minecraft:obsidian";

    final DirectSettings settings;
    final Safety safety;
    final CombatSession session = new CombatSession();
    final ConnectionKey connection;
    final FakeClient client;
    final FakeSink sink = new FakeSink();
    final ObservationIntake intake;
    final PacketLedger packets = PacketLedger.conservative();
    final CrystalLedger crystals = new CrystalLedger();
    final CombatRiskBudget budget = new CombatRiskBudget((envelope, current) -> current);
    final CombatScheduler scheduler = new CombatScheduler(session, budget, packets);
    final CombatInventoryAdapter inventory;
    final CombatRuntime runtime;
    final Ticket ticket;
    final DirectPlanner planner;
    final CombatEngine engine;
    final HarnessWorld world;
    final RuntimeDirectGateway gateway;

    int serverTick;
    long receipt;
    long revision;
    private final Map<EntityKey, Integer> spawnTicks = new LinkedHashMap<>();
    private int nextCrystalId = 1000;

    private EngineHarness(DirectSettings settings, Vec selfPosition) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.safety = settings.safety();
        session.open();
        connection = session.connection();
        client = new FakeClient(connection);
        client.putSelf(1, UUID.fromString("00000000-0000-0000-0000-000000000001"));
        client.eye = new Vec(selfPosition.x(), selfPosition.y() + 1.62, selfPosition.z());
        client.blockRange = 4.5;
        client.entityRange = 3.0;
        client.hotbar[0] = END_CRYSTAL;
        client.hotbar[1] = OBSIDIAN;
        client.hotbar[2] = "minecraft:diamond_sword";
        client.offhand = "minecraft:totem_of_undying";
        ticket = session.ticket(CombatEngine.DIRECT_PLAN);
        intake = new ObservationIntake(session, client, new EntityDataIds(9, 10), 64);
        inventory = new CombatInventoryAdapter(client, new CombatInventoryAdapter.Ownership() {
            @Override
            public boolean loanActive() {
                return false;
            }

            @Override
            public boolean weaponAttackActive() {
                return false;
            }
        });
        runtime = new CombatRuntime(session, scheduler, budget, intake, inventory, client, sink, safety);
        world = new HarnessWorld(this, selfPosition);
        planner = new DirectPlanner(settings);
        engine = new CombatEngine(session, scheduler, budget, crystals, settings, planner);
        gateway = new RuntimeDirectGateway(runtime, client, settings);
        runtime.tick(0);
    }

    static EngineHarness create(DirectSettings settings, Vec selfPosition) {
        return new EngineHarness(settings, selfPosition);
    }

    static EngineHarness create(Vec selfPosition) {
        return new EngineHarness(DirectSettings.conservative(CombatDamagePolicy.conservativeSafety()),
            selfPosition);
    }

    Snapshot snapshot() {
        return new Snapshot(connection, revision, List.copyOf(client.crystals));
    }

    EntityKey self() {
        return client.self;
    }

    /** Advance one server tick: runtime first (it owns draining), then the engine. */
    void tick() {
        tick(1);
    }

    void tick(int count) {
        for (int i = 0; i < count; i++) {
            serverTick++;
            runtime.tick(serverTick);
            engine.tick(serverTick, world, snapshot(), gateway);
        }
    }

    int placeCount() {
        return sink.count(FakeSink.Kind.PLACE);
    }

    int attackCount() {
        return sink.count(FakeSink.Kind.ATTACK);
    }

    EntityKey spawnCrystal(Vec position) {
        return spawnCrystal(nextCrystalId++, UUID.randomUUID(), position);
    }

    EntityKey spawnCrystal(int id, UUID uuid, Vec position) {
        EntityKey key = new EntityKey(connection, id, uuid);
        client.putCrystal(id, uuid, position);
        spawnTicks.put(key, serverTick);
        observe(new Observation(connection, receipt++, revision++, System.nanoTime(), serverTick,
            ObservationKind.SPAWN, key, null, position, Box.fromCell(cellOf(position)),
            new ObservationPayload.Spawn(END_CRYSTAL)));
        engine.onSpawnObserved(key, world, snapshot(), gateway);
        return key;
    }

    void removeCrystal(EntityKey key) {
        Vec position = client.entityPosition(key);
        client.crystals.removeIf(crystal -> crystal.entity().equals(key));
        client.entities.remove(key.id());
        observe(new Observation(connection, receipt++, revision++, System.nanoTime(), serverTick,
            ObservationKind.REMOVED, key, null, position, null,
            new ObservationPayload.Removed("unknown", false)));
    }

    void explosion(EntityKey crystal, Vec position) {
        observe(new Observation(connection, receipt++, revision++, System.nanoTime(), serverTick,
            ObservationKind.EXPLOSION, crystal, cellOf(position), position, null,
            new ObservationPayload.Explosion(6.0)));
    }

    void selfHealth(double health, double absorption) {
        observe(new Observation(connection, receipt++, revision++, System.nanoTime(), serverTick,
            ObservationKind.HEALTH, self(), null, null, null,
            new ObservationPayload.Health(health, absorption, 20, 5.0)));
    }

    void observe(Observation observation) {
        crystals.observe(observation);
        if (observation.kind() == ObservationKind.SPAWN) {
            spawnTicks.putIfAbsent(observation.entity(), serverTick);
        }
        budget.observe(observation);
    }

    static Cell cellOf(Vec position) {
        return new Cell((int) Math.floor(position.x()), (int) Math.floor(position.y()),
            (int) Math.floor(position.z()));
    }

    void setBase(Cell cell) {
        world.bases.add(cell);
    }

    /** Solid non-base wall used to script a one-column hole around a target. */
    void setWall(Cell cell) {
        world.walls.add(cell);
    }

    /** Per-centre scripted estimate, e.g. to make a fresh placement outscore an owned-crystal break. */
    void setEstimateAt(java.util.function.Function<Vec, TargetEstimate> estimateAt) {
        world.estimateAt = estimateAt;
    }

    DirectTarget addTarget(Vec position) {
        return world.addTarget(position);
    }

    void setFriendUpper(EntityKey friend, double upper) {
        world.friendUppers.put(friend, new MitigatedDamage(upper));
    }

    EntityKey addFriend(Vec position, double health) {
        return world.addFriend(position, health);
    }

    /** Scripted Phase 4.2 motion sample for one entity (absent means motion unknown). */
    void setMotion(EntityKey entity, MotionSample sample) {
        world.motionSamples.put(entity, sample);
    }

    /** Collision geometry used by prediction; defaults to open air. */
    void setCollision(CollisionView collision) {
        world.collision = collision;
    }

    /** Position-priced target damage: the estimate is a function of the priced target box. */
    void setTargetEstimateByPosition(java.util.function.Function<DirectTarget, TargetEstimate> estimate) {
        world.estimateByPosition = estimate;
    }

    /** Position-priced self upper bound for motion prediction (feet position in, bound out). */
    void setSelfUpperAt(java.util.function.Function<Vec, MitigatedDamage> upper) {
        world.selfUpperAtPosition = upper;
    }

    /** Position-priced friend upper bound for motion prediction (feet position in, bound out). */
    void setFriendUpperAt(EntityKey friend, java.util.function.Function<Vec, MitigatedDamage> upper) {
        world.friendUpperAtPosition.put(friend, upper);
    }

    /** Number of prediction sample lookups made by the planner (zero when the tactic is off). */
    int motionCalls() {
        return world.motionCalls;
    }

    /** Phase 4.3 scripted hurt-adjusted estimate for one arrival interval; null delegates to Phase 3. */
    void setHurtEstimateAt(java.util.function.Function<Arrival, TargetEstimate> estimate) {
        world.hurtEstimateAt = estimate;
    }

    /** Phase 4.3 scripted wait decision; the default never defers. */
    void setHurtSyncDeferUntil(java.util.function.LongUnaryOperator deferUntil) {
        world.hurtSyncDeferUntil = deferUntil;
    }

    /** Phase 4.6: whole-hull self upper bound used by dangerous-crystal detection. */
    void setSelfUpperWorst(double upper) {
        world.selfUpperWorst = new MitigatedDamage(upper);
    }

    void setSelfUpper(double upper) {
        world.selfUpper = new MitigatedDamage(upper);
    }

    void setSelfHealth(double health) {
        world.selfHealth = health;
    }

    void setPlacementReachable(boolean reachable) {
        world.placementReachable = reachable;
    }

    void setFaceVisible(boolean visible) {
        world.faceVisible = visible;
    }

    void setClientSupportBelow(boolean supported) {
        client.supportBelow = supported;
    }

    /** Scripted piston: base cell, facing, and whether it looks powered. */
    void addPiston(Cell piston, PistonFacing facing, boolean powered) {
        world.pistons.add(piston);
        world.pistonFacings.put(piston, facing);
        if (powered) world.poweredPistons.add(piston);
    }

    int spawnTick(EntityKey crystal) {
        return spawnTicks.getOrDefault(crystal, serverTick);
    }

    /** Fake direct-world backed by the ledger and the fake client, with scripted pause/damage values. */
    static final class HarnessWorld implements DirectWorld {
        private static final MitigationState NO_MITIGATION = new MitigationState(
            CombatDifficulty.NORMAL, 0.0, 0.0, 0.0, 0, false, false, false);
        private static final MitigationView VIEW = MitigationView.of(NO_MITIGATION);

        private final EngineHarness harness;
        private final Vec selfPosition;
        final Set<Cell> bases = new java.util.HashSet<>();
        final Set<Cell> walls = new java.util.HashSet<>();
        final List<DirectTarget> targets = new ArrayList<>();
        final Map<EntityKey, EffectiveHealth> friendHealth = new LinkedHashMap<>();
        final Map<EntityKey, MitigatedDamage> friendUppers = new LinkedHashMap<>();
        final Map<EntityKey, MitigatedDamage> friendUppersWorst = new LinkedHashMap<>();
        final java.util.Set<Cell> pistons = new java.util.HashSet<>();
        final Map<Cell, PistonFacing> pistonFacings = new LinkedHashMap<>();
        final java.util.Set<Cell> poweredPistons = new java.util.HashSet<>();
        final java.util.Set<Cell> extendedPistons = new java.util.HashSet<>();
        MitigatedDamage selfUpperWorst;
        TargetEstimate estimate = new TargetEstimate(4.0, 10.0, 12.0);
        java.util.function.Function<Vec, TargetEstimate> estimateAt;
        java.util.function.Function<DirectTarget, TargetEstimate> estimateByPosition;
        final Map<EntityKey, MotionSample> motionSamples = new LinkedHashMap<>();
        java.util.function.Function<Arrival, TargetEstimate> hurtEstimateAt;
        java.util.function.LongUnaryOperator hurtSyncDeferUntil = tick -> -1L;
        CollisionView collision = CollisionView.open();
        int motionCalls;
        MitigatedDamage selfUpper = new MitigatedDamage(1.0);
        java.util.function.Function<Vec, MitigatedDamage> selfUpperAtPosition;
        final Map<EntityKey, java.util.function.Function<Vec, MitigatedDamage>> friendUpperAtPosition =
            new LinkedHashMap<>();
        boolean reachable = true;
        boolean placementReachable = true;
        boolean faceVisible = true;
        boolean visible = true;
        boolean eating;
        boolean drinking;
        boolean mining;
        boolean screenOpen;
        boolean baritone;
        double tps = 20.0;
        long nanosSinceFeedback;
        boolean weakness;
        double selfHealth = 20.0;

        HarnessWorld(EngineHarness harness, Vec selfPosition) {
            this.harness = harness;
            this.selfPosition = selfPosition;
        }

        DirectTarget addTarget(Vec position) {
            EntityKey key = new EntityKey(harness.connection, 200 + targets.size(),
                UUID.nameUUIDFromBytes(("target-" + targets.size()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            Box box = Box.around(position, 0.3);
            harness.client.putEntity(key.id(), key.uuid(), "minecraft:player", position, box);
            DirectTarget target = new DirectTarget(key, position, box,
                new EffectiveHealth(20.0, 0.0, Capability.KNOWN, 0L), VIEW,
                false, false, false, false, false);
            targets.add(target);
            return target;
        }

        EntityKey addFriend(Vec position, double health) {
            EntityKey key = new EntityKey(harness.connection, 300 + friendHealth.size(),
                UUID.nameUUIDFromBytes(("friend-" + friendHealth.size()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            Box box = Box.around(position, 0.3);
            harness.client.putEntity(key.id(), key.uuid(), "minecraft:player", position, box);
            friendHealth.put(key, new EffectiveHealth(health, 0.0, Capability.KNOWN, 0L));
            return key;
        }

        @Override
        public ConnectionKey connection() {
            return harness.connection;
        }

        @Override
        public SafetyState safetyState() {
            return new SafetyState(harness.connection, harness.session.actionGeneration(), harness.self(),
                new EffectiveHealth(selfHealth, 0.0, Capability.KNOWN, 0L), Map.copyOf(friendHealth),
                harness.safety);
        }

        @Override
        public EntityKey self() {
            return harness.self();
        }

        @Override
        public Vec eye() {
            return harness.client.eye;
        }

        @Override
        public Vec selfPosition() {
            return selfPosition;
        }

        @Override
        public List<DirectTarget> targets() {
            return List.copyOf(targets);
        }

        @Override
        public List<DirectCrystal> crystals() {
            List<DirectCrystal> result = new ArrayList<>();
            for (Snapshot.Crystal crystal : harness.client.crystals) {
                CrystalState state = harness.crystals.state(crystal.entity());
                result.add(new DirectCrystal(crystal.entity(), crystal.position(),
                    harness.client.entityBox(crystal.entity()), state,
                    harness.crystals.own(crystal.entity()), state == CrystalState.QUARANTINED,
                    harness.serverTick - harness.spawnTick(crystal.entity())));
            }
            return result;
        }

        @Override
        public List<DirectItem> hotbar() {
            List<DirectItem> items = new ArrayList<>();
            for (int slot = 0; slot < 9; slot++) {
                String id = harness.client.hotbar[slot];
                if (id == null || id.isEmpty()) continue;
                boolean breaks = id.contains("sword") || id.contains("axe") || id.contains("pickaxe");
                items.add(new DirectItem(slot, id, breaks));
            }
            return items;
        }

        @Override
        public int selectedSlot() {
            return harness.client.selectedSlot;
        }

        @Override
        public boolean weakness() {
            return weakness;
        }

        @Override
        public DirectBlocks blocks() {
            return new DirectBlocks() {
                @Override
                public String blockId(Cell cell) {
                    if (bases.contains(cell)) return OBSIDIAN;
                    return walls.contains(cell) ? "minecraft:stone" : "minecraft:air";
                }

                @Override
                public boolean isAir(Cell cell) {
                    return !bases.contains(cell) && !walls.contains(cell);
                }

                @Override
                public boolean isBaseBlock(Cell cell) {
                    return bases.contains(cell);
                }

                @Override
                public boolean isReplaceable(Cell cell) {
                    return isAir(cell);
                }

                @Override
                public boolean hasSupportBelow(Cell cell) {
                    return bases.contains(cell.below());
                }

                @Override
                public boolean blocked(Cell cell) {
                    return harness.crystals.blocksCell(cell);
                }

                @Override
                public boolean entityFree(Box box) {
                    for (Snapshot.Crystal crystal : harness.client.crystals) {
                        if (box.contains(crystal.position())) return false;
                    }
                    for (FakeClient.EntityState entity : harness.client.entities.values()) {
                        if (entity.key().equals(harness.self())) continue;
                        if (box.intersects(entity.box())) return false;
                    }
                    return true;
                }

                @Override
                public boolean crystalPresent(Cell cell) {
                    for (Snapshot.Crystal crystal : harness.client.crystals) {
                        if (cellOf(crystal.position()).equals(cell)) return true;
                    }
                    return false;
                }

                @Override
                public boolean faceVisible(Vec eye, Cell base) {
                    return faceVisible;
                }

                @Override
                public boolean isPiston(Cell cell) {
                    return pistons.contains(cell);
                }

                @Override
                public PistonFacing pistonFacing(Cell cell) {
                    return pistonFacings.get(cell);
                }

                @Override
                public boolean pistonPowered(Cell cell) {
                    return poweredPistons.contains(cell);
                }

                @Override
                public boolean pistonExtended(Cell cell) {
                    return extendedPistons.contains(cell);
                }
            };
        }

        @Override
        public DirectDamage damage() {
            return new DirectDamage() {
                @Override
                public MitigationEnvelope envelope() {
                    return new MitigationEnvelope(harness.revision);
                }

                @Override
                public TargetEstimate targetEstimate(DirectTarget target, Vec crystalCentre,
                                                     java.util.Collection<Cell> overlays) {
                    if (estimateByPosition != null) {
                        TargetEstimate byPosition = estimateByPosition.apply(target);
                        if (byPosition != null) return byPosition;
                    }
                    return estimateAt == null ? estimate : estimateAt.apply(crystalCentre);
                }

                @Override
                public TargetEstimate targetEstimate(DirectTarget target, Vec crystalCentre,
                                                     java.util.Collection<Cell> overlays,
                                                     Arrival arrival) {
                    if (hurtEstimateAt != null) {
                        TargetEstimate scripted = hurtEstimateAt.apply(arrival);
                        if (scripted != null) return scripted;
                    }
                    return targetEstimate(target, crystalCentre, overlays);
                }

                @Override
                public long hurtSyncDeferUntil(DirectTarget target, Vec crystalCentre,
                                               java.util.Collection<Cell> overlays,
                                               Arrival immediateArrival, int serverTick,
                                               TargetEstimate immediate) {
                    return hurtSyncDeferUntil.applyAsLong(serverTick);
                }

                @Override
                public MitigatedDamage selfUpper(Vec crystalCentre, java.util.Collection<Cell> overlays) {
                    return selfUpper;
                }

                @Override
                public MitigatedDamage selfUpperAt(Vec selfFeet, Vec crystalCentre,
                                                   java.util.Collection<Cell> overlays) {
                    return selfUpperAtPosition == null ? selfUpper : selfUpperAtPosition.apply(selfFeet);
                }

                @Override
                public Map<EntityKey, MitigatedDamage> friendUppers(Vec crystalCentre,
                                                                   java.util.Collection<Cell> overlays) {
                    return Map.copyOf(friendUppers);
                }

                @Override
                public MitigatedDamage selfUpperWorst(Vec selfFeet, Vec crystalCentre,
                                                      java.util.Collection<Cell> overlays) {
                    return selfUpperWorst == null
                        ? DirectDamage.super.selfUpperWorst(selfFeet, crystalCentre, overlays)
                        : selfUpperWorst;
                }

                @Override
                public MitigatedDamage friendUpperWorst(EntityKey friend, Vec friendFeet,
                                                        Vec crystalCentre,
                                                        java.util.Collection<Cell> overlays) {
                    MitigatedDamage worst = friendUppersWorst.get(friend);
                    return worst == null
                        ? DirectDamage.super.friendUpperWorst(friend, friendFeet, crystalCentre, overlays)
                        : worst;
                }

                @Override
                public MitigatedDamage friendUpperAt(EntityKey friend, Vec friendFeet,
                                                     Vec crystalCentre,
                                                     java.util.Collection<Cell> overlays) {
                    java.util.function.Function<Vec, MitigatedDamage> upper =
                        friendUpperAtPosition.get(friend);
                    return upper == null ? friendUppers.get(friend) : upper.apply(friendFeet);
                }
            };
        }

        @Override
        public boolean crystalReachable(Vec crystalPosition, Box crystalBox, double attackRange,
                                        double wallsRange) {
            if (!reachable) return false;
            double distance = eye().distance(crystalBox.center());
            return distance <= attackRange || distance <= wallsRange;
        }

        @Override
        public boolean placementReachable(Cell crystalCell, double placeRange) {
            if (!placementReachable) return false;
            Vec click = new Vec(crystalCell.x() + 0.5, crystalCell.y(), crystalCell.z() + 0.5);
            return eye().distance(click) <= Math.min(placeRange, harness.client.blockRange);
        }

        @Override
        public boolean visible(Vec eye, Box box) {
            return visible;
        }

        @Override
        public boolean eating() {
            return eating;
        }

        @Override
        public boolean drinking() {
            return drinking;
        }

        @Override
        public boolean mining() {
            return mining;
        }

        @Override
        public boolean screenOpen() {
            return screenOpen;
        }

        @Override
        public boolean baritoneActive() {
            return baritone;
        }

        @Override
        public double tps() {
            return tps;
        }

        @Override
        public long nanosSinceLastFeedback() {
            return nanosSinceFeedback;
        }

        @Override
        public MotionSample motion(EntityKey entity) {
            motionCalls++;
            return motionSamples.get(entity);
        }

        @Override
        public CollisionView collision() {
            return collision;
        }
    }
}

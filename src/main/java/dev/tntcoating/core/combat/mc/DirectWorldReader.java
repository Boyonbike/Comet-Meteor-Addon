/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.1.2 artifact and Meteor build 42 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import baritone.api.BaritoneAPI;
import dev.tntcoating.core.combat.ArmorPiece;
import dev.tntcoating.core.combat.Arrival;
import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.Capability;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CollisionView;
import dev.tntcoating.core.combat.CombatDamage;
import dev.tntcoating.core.combat.CombatDifficulty;
import dev.tntcoating.core.combat.CombatHurtModel;
import dev.tntcoating.core.combat.CombatSession;
import dev.tntcoating.core.combat.ConnectionKey;
import dev.tntcoating.core.combat.CrystalLedger;
import dev.tntcoating.core.combat.CrystalState;
import dev.tntcoating.core.combat.DirectBlocks;
import dev.tntcoating.core.combat.DirectCrystal;
import dev.tntcoating.core.combat.DirectDamage;
import dev.tntcoating.core.combat.DirectItem;
import dev.tntcoating.core.combat.DirectSettings;
import dev.tntcoating.core.combat.DirectTarget;
import dev.tntcoating.core.combat.DirectWorld;
import dev.tntcoating.core.combat.EffectiveHealth;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.ExposurePolicy;
import dev.tntcoating.core.combat.HurtSync;
import dev.tntcoating.core.combat.MitigatedDamage;
import dev.tntcoating.core.combat.MitigationEnvelope;
import dev.tntcoating.core.combat.MitigationState;
import dev.tntcoating.core.combat.MitigationView;
import dev.tntcoating.core.combat.MotionSample;
import dev.tntcoating.core.combat.PistonFacing;
import dev.tntcoating.core.combat.RawDamage;
import dev.tntcoating.core.combat.SafetyState;
import dev.tntcoating.core.combat.TargetEstimate;
import dev.tntcoating.core.combat.Vec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.world.TickRate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft-facing {@link DirectWorld} reader for the Verified direct aura baseline. Everything here is
 * read-only: no packets, no rotations, no world mutation. The planner consumes one instance per pass.
 */
public final class DirectWorldReader implements DirectWorldSource {
    /**
     * Phase 3 approximation of "an armour piece that could break before arrival": a piece at or below
     * this remaining-durability fraction is treated as breakable in the mitigation envelope. Full
     * prior-hit durability modelling belongs to Phase 4; the important safety property here is that
     * self and friend upper bounds always price the weakest envelope, never the current state.
     */
    static final double BREAKABLE_ARMOUR_FRACTION = 0.10;
    /**
     * Phase 4.2 motion sampling: an observed feet displacement larger than this many blocks in one tick is
     * treated as a teleport or setback, not velocity, and invalidates the prediction.
     */
    static final double TELEPORT_STEP_BLOCKS = 4.0;
    private static final EquipmentSlot[] ARMOUR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final Minecraft mc;
    private final CombatSession session;
    private final CombatClient client;
    private final ObservationIntake intake;
    private final CrystalLedger ledger;
    private final CombatDamageAdapter damage;
    private final CombatHurtModel hurtModel;
    /** Replaced on every config change; the stack outlives disable, so a captured copy would go stale. */
    private volatile DirectSettings settings;

    public DirectWorldReader(Minecraft mc, CombatSession session, CombatClient client,
                             ObservationIntake intake, CrystalLedger ledger,
                             CombatDamageAdapter damage, CombatHurtModel hurtModel,
                             DirectSettings settings) {
        this.mc = Objects.requireNonNull(mc, "mc");
        this.session = Objects.requireNonNull(session, "session");
        this.client = Objects.requireNonNull(client, "client");
        this.intake = Objects.requireNonNull(intake, "intake");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.damage = Objects.requireNonNull(damage, "damage");
        this.hurtModel = Objects.requireNonNull(hurtModel, "hurtModel");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Later reads stamp the new safety envelope into their {@link SafetyState}. */
    @Override
    public void reconfigure(DirectSettings next) {
        settings = Objects.requireNonNull(next, "next");
    }

    /** One immutable read of the world, or null when there is no local player or level yet. */
    @Override
    public DirectWorld read() {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !client.connectionOpen()) return null;
        ConnectionKey connection = session.connection();
        if (connection == null) return null;

        long revision = intake.appliedRevision();
        MitigationView selfMitigation = mitigation(player);
        Vec eye = toVec(player.getEyePosition());
        Vec selfPosition = toVec(player.position());
        Box selfBox = toBox(player.getBoundingBox());
        EffectiveHealth selfHealth = health(player);
        SafetyState safetyState = new SafetyState(connection, session.actionGeneration(),
            client.selfKey(), selfHealth, friends(connection), settings.safety());

        List<DirectTarget> targets = targets(connection, player, revision);
        List<DirectCrystal> crystals = crystals(connection, player);
        List<DirectItem> hotbar = hotbar(player);
        boolean weakness = player.hasEffect(MobEffects.WEAKNESS);
        return new Read(connection, safetyState, client.selfKey(), eye, selfPosition, selfBox,
            selfHealth, selfMitigation, targets, crystals, hotbar,
            player.getInventory().getSelectedSlot(), weakness, new ReadBlocks(eye, player, crystals),
            new ReadDamage(revision, selfBox, selfPosition, hurtModel), player, revision);
    }

    private List<DirectTarget> targets(ConnectionKey connection, LocalPlayer self, long revision) {
        List<DirectTarget> result = new ArrayList<>();
        for (Player player : mc.level.players()) {
            if (player == self) continue;
            EffectiveHealth health = health(player);
            boolean dead = health.cap() == Capability.KNOWN && health.value() <= 0.0
                || player.isDeadOrDying() || player.isRemoved();
            boolean friend = Friends.get().isFriend(player);
            result.add(new DirectTarget(
                new EntityKey(connection, player.getId(), player.getUUID()),
                toVec(player.position()),
                toBox(player.getBoundingBox()),
                health,
                mitigation(player),
                dead,
                player.isCreative(),
                player.isSpectator(),
                friend,
                armourPressure(player)));
        }
        return List.copyOf(result);
    }

    private List<DirectCrystal> crystals(ConnectionKey connection, LocalPlayer self) {
        List<DirectCrystal> result = new ArrayList<>();
        for (EndCrystal crystal : mc.level.getEntitiesOfClass(EndCrystal.class,
            self.getBoundingBox().inflate(32.0))) {
            if (crystal == null || crystal.isRemoved() || !crystal.isAlive()) continue;
            EntityKey key = new EntityKey(connection, crystal.getId(), crystal.getUUID());
            CrystalState state = crystalState(ledger, key);
            result.add(new DirectCrystal(key, toVec(crystal.position()),
                toBox(crystal.getBoundingBox()), state, ledger.own(key),
                state == CrystalState.QUARANTINED, Math.max(0, crystal.tickCount)));
        }
        return List.copyOf(result);
    }

    /**
     * The ledger state of a crystal present in the applied world. The ledger only learns a crystal from its
     * spawn observation or a rescan, so one that predates the session or whose spawn was lost is unknown
     * here; like a rescan, record it as an observed crystal that is not ours.
     */
    static CrystalState crystalState(CrystalLedger ledger, EntityKey key) {
        CrystalState state = ledger.state(key);
        return state != null ? state : CrystalState.OBSERVED;
    }

    private List<DirectItem> hotbar(LocalPlayer player) {
        List<DirectItem> result = new ArrayList<>(9);
        Entity damageTarget = damageTarget(player);
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            String id = stack.isEmpty() ? ""
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            boolean breaks = false;
            if (!stack.isEmpty() && damageTarget != null) {
                breaks = DamageUtils.getAttackDamage(player, damageTarget, stack) > 0.0f;
            }
            result.add(new DirectItem(slot, id, breaks));
        }
        return List.copyOf(result);
    }

    private Entity damageTarget(LocalPlayer player) {
        List<EndCrystal> crystals = mc.level.getEntitiesOfClass(EndCrystal.class,
            player.getBoundingBox().inflate(8.0));
        if (!crystals.isEmpty()) return crystals.getFirst();
        return player;
    }

    private Map<EntityKey, EffectiveHealth> friends(ConnectionKey connection) {
        Map<EntityKey, EffectiveHealth> result = new HashMap<>();
        for (Player player : mc.level.players()) {
            if (player == mc.player) continue;
            if (!Friends.get().isFriend(player)) continue;
            result.put(new EntityKey(connection, player.getId(), player.getUUID()), health(player));
        }
        return Map.copyOf(result);
    }

    private EffectiveHealth health(Player player) {
        return new EffectiveHealth(player.getHealth(), player.getAbsorptionAmount(),
            Capability.KNOWN, intake.appliedRevision());
    }

    private boolean armourPressure(Player player) {
        for (ItemStack stack : armour(player)) {
            if (stack == null || stack.isEmpty() || !stack.isDamageableItem()) continue;
            int max = stack.getMaxDamage();
            if (max <= 0) continue;
            double remaining = (max - stack.getDamageValue()) / (double) max;
            if (remaining <= settings.faceplaceArmourFraction()) return true;
        }
        return false;
    }

    private MitigationView mitigation(Player player) {
        List<ArmorPiece> pieces = new ArrayList<>(ARMOUR_SLOTS.length);
        int protection = 0;
        for (EquipmentSlot slot : ARMOUR_SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack == null || stack.isEmpty()) continue;
            double[] attributes = new double[2];
            stack.forEachModifier(slot, (attribute, modifier) -> {
                if (attribute.equals(Attributes.ARMOR)) attributes[0] += modifier.amount();
                else if (attribute.equals(Attributes.ARMOR_TOUGHNESS)) attributes[1] += modifier.amount();
            });
            int points = Utils.getEnchantmentLevel(stack, Enchantments.PROTECTION)
                + 2 * Utils.getEnchantmentLevel(stack, Enchantments.BLAST_PROTECTION);
            protection += points;
            boolean breakable = stack.isDamageableItem()
                && couldBreakBeforeArrival(stack.getMaxDamage(), stack.getDamageValue());
            pieces.add(new ArmorPiece(attributes[0], attributes[1], points, breakable));
        }
        var resistance = player.getEffect(MobEffects.RESISTANCE);
        int amplifier = resistance == null ? 0 : resistance.getAmplifier();
        boolean expiring = resistance != null && resistance.getDuration() <= 40;
        MitigationState state = new MitigationState(combatDifficulty(mc.level.getDifficulty()),
            player.getArmorValue(), player.getAttributeValue(Attributes.ARMOR_TOUGHNESS),
            protection, amplifier, false, false, false);
        List<ArmorPiece> envelopePieces = List.copyOf(pieces);
        return new MitigationView() {
            @Override
            public MitigationState current() {
                return state;
            }

            @Override
            public List<ArmorPiece> pieces() {
                return envelopePieces;
            }

            @Override
            public boolean effectsMayExpire() {
                return expiring;
            }
        };
    }

    /** Break reach: the attack range with a clear line of sight to the crystal, the walls range without one. */
    static double breakRange(boolean clearSight, double attackRange, double wallsRange) {
        return clearSight ? attackRange : wallsRange;
    }

    /** A piece at or below the breakage fraction is assumed broken by the mitigation envelope. */
    static boolean couldBreakBeforeArrival(int maxDamage, int damageValue) {
        if (maxDamage <= 0) return false;
        double remaining = (maxDamage - damageValue) / (double) maxDamage;
        return remaining <= BREAKABLE_ARMOUR_FRACTION;
    }

    /**
     * Self and friend upper bounds always run through the adapter's envelope path, which derives the
     * weakest possible mitigation. Pricing them against {@code current()} would understate the bound
     * whenever armour could break or Resistance could expire before arrival.
     */
    static MitigatedDamage upperBound(CombatDamageAdapter adapter, RawDamage raw, MitigationView view) {
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(view, "view");
        return adapter.upperBound(raw, view);
    }

    /**
     * Phase 4.2: distance from the crystal to the true closest point of the body box, not to its minimum
     * corner. Clamping the crystal into the box is exact for an axis-aligned box and keeps the whole-hull
     * worst bound honest on diagonal approaches.
     */
    static double closestDistance(Box body, Vec point) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(point, "point");
        double x = Math.max(body.min().x(), Math.min(point.x(), body.max().x()));
        double y = Math.max(body.min().y(), Math.min(point.y(), body.max().y()));
        double z = Math.max(body.min().z(), Math.min(point.z(), body.max().z()));
        return new Vec(x, y, z).distance(point);
    }

    /**
     * Phase 4.2: whole-hull worst raw damage. Cover is ignored (exposure 1.0) and the distance is the
     * closest point on the body box, so the raw blast is at least the raw blast any reachable point inside
     * the hull can produce.
     */
    static RawDamage worstRaw(CombatDamageAdapter adapter, Box body, Vec crystalCentre) {
        Objects.requireNonNull(adapter, "adapter");
        return adapter.rawExplosionDamage(closestDistance(body, crystalCentre), 1.0);
    }

    private static List<ItemStack> armour(Player player) {
        List<ItemStack> stacks = new ArrayList<>(ARMOUR_SLOTS.length);
        for (EquipmentSlot slot : ARMOUR_SLOTS) {
            stacks.add(player.getItemBySlot(slot));
        }
        return List.copyOf(stacks);
    }

    private static CombatDifficulty combatDifficulty(Difficulty difficulty) {
        return switch (difficulty) {
            case PEACEFUL -> CombatDifficulty.PEACEFUL;
            case EASY -> CombatDifficulty.EASY;
            case HARD -> CombatDifficulty.HARD;
            default -> CombatDifficulty.NORMAL;
        };
    }

    // ------------------------------------------------------------------ read view

    private final class Read implements DirectWorld {
        private final ConnectionKey connection;
        private final SafetyState safetyState;
        private final EntityKey self;
        private final Vec eye;
        private final Vec selfPosition;
        private final Box selfBox;
        private final EffectiveHealth selfHealth;
        private final List<DirectTarget> targets;
        private final List<DirectCrystal> crystals;
        private final List<DirectItem> hotbar;
        private final int selectedSlot;
        private final boolean weakness;
        private final DirectBlocks blocks;
        private final DirectDamage damageView;
        private final LocalPlayer player;
        private final long revision;

        private Read(ConnectionKey connection, SafetyState safetyState, EntityKey self, Vec eye,
                     Vec selfPosition, Box selfBox, EffectiveHealth selfHealth,
                     MitigationView selfMitigation, List<DirectTarget> targets,
                     List<DirectCrystal> crystals, List<DirectItem> hotbar, int selectedSlot,
                     boolean weakness, DirectBlocks blocks, DirectDamage damageView,
                     LocalPlayer player, long revision) {
            this.connection = connection;
            this.safetyState = safetyState;
            this.self = self;
            this.eye = eye;
            this.selfPosition = selfPosition;
            this.selfBox = selfBox;
            this.selfHealth = selfHealth;
            this.targets = targets;
            this.crystals = crystals;
            this.hotbar = hotbar;
            this.selectedSlot = selectedSlot;
            this.weakness = weakness;
            this.blocks = blocks;
            this.damageView = damageView;
            this.player = player;
            this.revision = revision;
        }

        @Override
        public ConnectionKey connection() {
            return connection;
        }

        @Override
        public SafetyState safetyState() {
            return safetyState;
        }

        @Override
        public EntityKey self() {
            return self;
        }

        @Override
        public Vec eye() {
            return eye;
        }

        @Override
        public Vec selfPosition() {
            return selfPosition;
        }

        @Override
        public List<DirectTarget> targets() {
            return targets;
        }

        @Override
        public List<DirectCrystal> crystals() {
            return crystals;
        }

        @Override
        public List<DirectItem> hotbar() {
            return hotbar;
        }

        @Override
        public int selectedSlot() {
            return selectedSlot;
        }

        @Override
        public MitigationView mitigation(EntityKey key) {
            if (key == null) return null;
            if (key.equals(self)) return DirectWorldReader.this.mitigation(player);
            for (DirectTarget target : targets) {
                if (target.entity().equals(key)) return target.mitigation();
            }
            return null;
        }

        @Override
        public boolean weakness() {
            return weakness;
        }

        @Override
        public DirectBlocks blocks() {
            return blocks;
        }

        @Override
        public DirectDamage damage() {
            return damageView;
        }

        /**
         * Phase 4.2: motion sample from live entity state. The client does not simulate remote players, so
         * the observed tick displacement (position minus the previous tick position) is the best available
         * velocity; the tracked delta movement is used when the observation is still zero. A step too large
         * for one tick is a teleport or setback and is reported invalidated, which forces loss of confidence.
         */
        @Override
        public MotionSample motion(EntityKey key) {
            Entity entity = entityOf(key);
            if (entity == null) return null;
            Vec3 position = entity.position();
            Vec3 observed = position.subtract(new Vec3(entity.xOld, entity.yOld, entity.zOld));
            Vec3 delta = entity.getDeltaMovement();
            Vec3 velocity = observed.lengthSqr() > 1.0e-6 ? observed : delta;
            MotionSample sample = MotionSample.of(position.x, position.y, position.z, velocity.x,
                velocity.y, velocity.z, entity.onGround());
            return velocity.lengthSqr() > TELEPORT_STEP_BLOCKS * TELEPORT_STEP_BLOCKS
                ? sample.invalidated() : sample;
        }

        /** Phase 4.2: block collision geometry for the pure motion model. */
        @Override
        public CollisionView collision() {
            return cell -> {
                BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
                return !mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
            };
        }

        private Entity entityOf(EntityKey key) {
            if (key == null || mc.level == null || mc.player == null) return null;
            if (key.id() == mc.player.getId() && key.uuid().equals(mc.player.getUUID())) {
                return mc.player;
            }
            Entity entity = mc.level.getEntity(key.id());
            return entity != null && entity.getUUID().equals(key.uuid()) ? entity : null;
        }

        @Override
        public boolean crystalReachable(Vec crystalPosition, Box crystalBox, double attackRange,
                                        double wallsRange) {
            Vec3 start = toVec3(eye);
            Vec3 end = toVec3(crystalPosition);
            // Sight is judged to the middle of the crystal: its position is its feet, on the base block's
            // top face, where the ray would end on the base block itself.
            Vec3 sight = crystalBox != null ? toVec3(crystalBox.center()) : end;
            HitResult hit = mc.level.clip(new ClipContext(start, sight, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
            double range = breakRange(hit.getType() == HitResult.Type.MISS, attackRange, wallsRange);
            return start.distanceToSqr(end) <= range * range;
        }

        @Override
        public boolean placementReachable(Cell crystalCell, double placeRange) {
            Vec click = new Vec(crystalCell.x() + 0.5, crystalCell.y(), crystalCell.z() + 0.5);
            return eye.distance(click) <= placeRange;
        }

        @Override
        public boolean visible(Vec eye, Box box) {
            Vec3 start = toVec3(eye);
            Vec3 end = toVec3(box.center());
            HitResult hit = mc.level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.MISS) return true;
            return hit.getLocation().distanceToSqr(end) <= 0.01;
        }

        @Override
        public boolean eating() {
            return PlayerUtils.shouldPause(false, true, false);
        }

        @Override
        public boolean drinking() {
            return PlayerUtils.shouldPause(false, false, true);
        }

        @Override
        public boolean mining() {
            return mc.gameMode != null && mc.gameMode.isDestroying();
        }

        @Override
        public boolean screenOpen() {
            return client.screenOpen();
        }

        @Override
        public boolean baritoneActive() {
            try {
                return BaritoneAPI.getProvider().getPrimaryBaritone() != null
                    && BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing();
            } catch (Throwable ignored) {
                return false;
            }
        }

        @Override
        public double tps() {
            return TickRate.INSTANCE.getTickRate();
        }

        @Override
        public long nanosSinceLastFeedback() {
            return (long) (TickRate.INSTANCE.getTimeSinceLastTick() * 1_000_000_000.0f);
        }
    }

    private final class ReadBlocks implements DirectBlocks {
        private final Vec eye;
        private final LocalPlayer player;
        private final List<DirectCrystal> crystals;

        private ReadBlocks(Vec eye, LocalPlayer player, List<DirectCrystal> crystals) {
            this.eye = eye;
            this.player = player;
            this.crystals = crystals;
        }

        @Override
        public String blockId(Cell cell) {
            return BuiltInRegistries.BLOCK.getKey(block(cell).getBlock()).toString();
        }

        @Override
        public boolean isAir(Cell cell) {
            return block(cell).isAir();
        }

        @Override
        public boolean isBaseBlock(Cell cell) {
            return block(cell).getBlock() == Blocks.OBSIDIAN || block(cell).getBlock() == Blocks.BEDROCK;
        }

        @Override
        public boolean isReplaceable(Cell cell) {
            return block(cell).canBeReplaced();
        }

        @Override
        public boolean hasSupportBelow(Cell cell) {
            return !block(cell.below()).isAir();
        }

        @Override
        public boolean blocked(Cell cell) {
            return ledger.blocksCell(cell);
        }

        @Override
        public boolean entityFree(Box box) {
            AABB aabb = toAabb(box);
            for (Entity entity : mc.level.getEntitiesOfClass(Entity.class, aabb)) {
                if (entity == null || entity.isRemoved() || entity.isSpectator()) continue;
                if (entity.getBoundingBox().intersects(aabb)) return false;
            }
            return true;
        }

        @Override
        public boolean fullSupport(Cell cell) {
            return client.supportBelow(cell);
        }

        @Override
        public boolean crystalPresent(Cell cell) {
            for (DirectCrystal crystal : crystals) {
                if (cellOf(crystal.position()).equals(cell)) return true;
            }
            return false;
        }

        @Override
        public boolean isPiston(Cell cell) {
            return block(cell).getBlock() instanceof PistonBaseBlock;
        }

        @Override
        public PistonFacing pistonFacing(Cell cell) {
            BlockState state = block(cell);
            if (!(state.getBlock() instanceof PistonBaseBlock)) return null;
            Direction direction = state.getValue(DirectionalBlock.FACING);
            return new PistonFacing(direction.getStepX(), direction.getStepY(), direction.getStepZ());
        }

        @Override
        public boolean pistonPowered(Cell cell) {
            // Arming estimate only: a redstone neighbour signal on the piston base cell. Dispatch safety never
            // depends on this value; it only affects the opportunity's arrival ordering.
            return mc.level.hasNeighborSignal(new BlockPos(cell.x(), cell.y(), cell.z()));
        }

        @Override
        public boolean pistonExtended(Cell cell) {
            BlockState state = block(cell);
            return state.getBlock() instanceof PistonBaseBlock
                && state.getValue(PistonBaseBlock.EXTENDED);
        }

        @Override
        public boolean faceVisible(Vec eye, Cell base) {
            Vec3 start = toVec3(eye);
            Vec3 face = new Vec3(base.x() + 0.5, base.y() + 1.0, base.z() + 0.5);
            HitResult hit = mc.level.clip(new ClipContext(start, face, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.MISS) return true;
            if (!(hit instanceof BlockHitResult block)) return false;
            return block.getBlockPos().equals(new BlockPos(base.x(), base.y(), base.z()))
                || hit.getLocation().distanceToSqr(face) <= 0.01;
        }

        private BlockState block(Cell cell) {
            return mc.level.getBlockState(new BlockPos(cell.x(), cell.y(), cell.z()));
        }
    }

    private final class ReadDamage implements DirectDamage {
        private final long revision;
        private final Box selfBox;
        private final Vec selfPosition;
        private final List<DirectCrystal> crystals;

        private ReadDamage(long revision, Box selfBox, Vec selfPosition, CombatHurtModel hurtModel) {
            this.revision = revision;
            this.selfBox = selfBox;
            this.selfPosition = selfPosition;
            this.crystals = List.of();
        }

        @Override
        public MitigationEnvelope envelope() {
            return new MitigationEnvelope(revision);
        }

        @Override
        public TargetEstimate targetEstimate(DirectTarget target, Vec crystalCentre,
                                             Collection<Cell> overlays) {
            RawPricing p = pricing(target, crystalCentre, overlays);
            return CombatDamage.targetEstimate(p.lower(), p.expected(), p.optimistic(), p.current(),
                p.weakest());
        }

        @Override
        public TargetEstimate targetEstimate(DirectTarget target, Vec crystalCentre,
                                             Collection<Cell> overlays, Arrival arrival) {
            RawPricing p = pricing(target, crystalCentre, overlays);
            return HurtSync.estimate(hurtModel, target.entity(), p.lower(), p.expected(),
                p.optimistic(), p.current(), p.weakest(), arrival);
        }

        @Override
        public long hurtSyncDeferUntil(DirectTarget target, Vec crystalCentre,
                                       Collection<Cell> overlays, Arrival immediateArrival,
                                       int serverTick, TargetEstimate immediate) {
            RawPricing p = pricing(target, crystalCentre, overlays);
            return HurtSync.deferUntil(hurtModel, target.entity(), p.lower(), p.expected(),
                p.optimistic(), p.current(), p.weakest(), immediateArrival, serverTick, immediate,
                target.health());
        }

        private RawPricing pricing(DirectTarget target, Vec crystalCentre, Collection<Cell> overlays) {
            double distance = target.position().distance(crystalCentre);
            double lowerExposure = exposure(crystalCentre, target.box(),
                ExposurePolicy.TARGET_LOWER_BOUND, overlays);
            double expectedExposure = exposure(crystalCentre, target.box(),
                ExposurePolicy.FAITHFUL, overlays);
            RawDamage lower = CombatDamage.rawExplosionDamage(distance, lowerExposure,
                CombatDamage.CRYSTAL_EXPLOSION_POWER);
            RawDamage expected = CombatDamage.rawExplosionDamage(distance, expectedExposure,
                CombatDamage.CRYSTAL_EXPLOSION_POWER);
            MitigationState current = target.mitigation().current();
            MitigationState weakest = CombatDamage.weakestEnvelope(current,
                target.mitigation().pieces(), target.mitigation().effectsMayExpire());
            RawDamage optimistic = CombatDamage.rawExplosionDamage(distance, 1.0,
                CombatDamage.CRYSTAL_EXPLOSION_POWER);
            return new RawPricing(lower, expected, optimistic, current, weakest);
        }

        private record RawPricing(RawDamage lower, RawDamage expected, RawDamage optimistic,
                                  MitigationState current, MitigationState weakest) {
        }

        @Override
        public MitigatedDamage selfUpper(Vec crystalCentre, Collection<Cell> overlays) {
            double exposure = exposure(crystalCentre, selfBox,
                ExposurePolicy.SELF_FRIEND_UPPER_BOUND, overlays);
            RawDamage raw = CombatDamage.rawExplosionDamage(selfPosition.distance(crystalCentre),
                exposure, CombatDamage.CRYSTAL_EXPLOSION_POWER);
            return upperBound(damage, raw, mitigation(mc.player));
        }

        @Override
        public Map<EntityKey, MitigatedDamage> friendUppers(Vec crystalCentre,
                                                            Collection<Cell> overlays) {
            Map<EntityKey, MitigatedDamage> result = new HashMap<>();
            ConnectionKey connection = session.connection();
            for (Player player : mc.level.players()) {
                if (player == mc.player || !Friends.get().isFriend(player)) continue;
                Box box = toBox(player.getBoundingBox());
                double exposure = exposure(crystalCentre, box,
                    ExposurePolicy.SELF_FRIEND_UPPER_BOUND, overlays);
                RawDamage raw = CombatDamage.rawExplosionDamage(
                    toVec(player.position()).distance(crystalCentre), exposure,
                    CombatDamage.CRYSTAL_EXPLOSION_POWER);
                result.put(new EntityKey(connection, player.getId(), player.getUUID()),
                    upperBound(damage, raw, mitigation(player)));
            }
            return Map.copyOf(result);
        }

        private double exposure(Vec centre, Box box, ExposurePolicy policy,
                                Collection<Cell> overlays) {
            return damage.exposure(mc.level, centre, box, policy, overlays);
        }

        /**
         * Phase 4.2: self upper bound at a predicted feet position. The body box is translated with the
         * prediction; the baseline call is unchanged when motion prediction is off.
         */
        @Override
        public MitigatedDamage selfUpperAt(Vec selfFeet, Vec crystalCentre,
                                           Collection<Cell> overlays) {
            Box box = translated(selfBox, selfPosition, selfFeet);
            double exposure = exposure(crystalCentre, box,
                ExposurePolicy.SELF_FRIEND_UPPER_BOUND, overlays);
            RawDamage raw = CombatDamage.rawExplosionDamage(selfFeet.distance(crystalCentre),
                exposure, CombatDamage.CRYSTAL_EXPLOSION_POWER);
            return upperBound(damage, raw, mitigation(mc.player));
        }

        /** Phase 4.2: one friend's upper bound at a predicted feet position, or null when it is gone. */
        @Override
        public MitigatedDamage friendUpperAt(EntityKey friend, Vec friendFeet, Vec crystalCentre,
                                             Collection<Cell> overlays) {
            Player player = friendPlayer(friend);
            if (player == null) return null;
            Box box = translated(toBox(player.getBoundingBox()), toVec(player.position()), friendFeet);
            double exposure = exposure(crystalCentre, box,
                ExposurePolicy.SELF_FRIEND_UPPER_BOUND, overlays);
            RawDamage raw = CombatDamage.rawExplosionDamage(friendFeet.distance(crystalCentre),
                exposure, CombatDamage.CRYSTAL_EXPLOSION_POWER);
            return upperBound(damage, raw, mitigation(player));
        }

        private Player friendPlayer(EntityKey key) {
            if (key == null || mc.level == null) return null;
            Entity entity = mc.level.getEntity(key.id());
            return entity instanceof Player player && player.getUUID().equals(key.uuid()) ? player : null;
        }

        /**
         * Phase 4.2 whole-hull self bound: the true closest point of the translated body box to the
         * crystal at full exposure, pushed through the weakest mitigation envelope. Cover is deliberately
         * ignored so no reachable point inside the hull can deal more than this.
         */
        @Override
        public MitigatedDamage selfUpperWorst(Vec selfFeet, Vec crystalCentre,
                                              Collection<Cell> overlays) {
            Box box = translated(selfBox, selfPosition, selfFeet);
            return upperBound(damage, worstRaw(damage, box, crystalCentre), mitigation(mc.player));
        }

        /** Phase 4.2 whole-hull friend bound, or {@code null} when the friend cannot be priced. */
        @Override
        public MitigatedDamage friendUpperWorst(EntityKey friend, Vec friendFeet, Vec crystalCentre,
                                                Collection<Cell> overlays) {
            Player player = friendPlayer(friend);
            if (player == null) return null;
            Box box = translated(toBox(player.getBoundingBox()), toVec(player.position()), friendFeet);
            return upperBound(damage, worstRaw(damage, box, crystalCentre), mitigation(player));
        }
    }

    private static Box translated(Box box, Vec from, Vec to) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double dz = to.z() - from.z();
        return new Box(box.min().add(dx, dy, dz), box.max().add(dx, dy, dz));
    }

    // ------------------------------------------------------------------ conversions

    static Cell cellOf(Vec position) {
        return new Cell((int) Math.floor(position.x()), (int) Math.floor(position.y()),
            (int) Math.floor(position.z()));
    }

    static Vec toVec(Vec3 vec) {
        return new Vec(vec.x(), vec.y(), vec.z());
    }

    static Vec3 toVec3(Vec vec) {
        return new Vec3(vec.x(), vec.y(), vec.z());
    }

    static Box toBox(AABB box) {
        return new Box(new Vec(box.minX, box.minY, box.minZ),
            new Vec(box.maxX, box.maxY, box.maxZ));
    }

    static AABB toAabb(Box box) {
        return new AABB(box.min().x(), box.min().y(), box.min().z(),
            box.max().x(), box.max().y(), box.max().z());
    }
}

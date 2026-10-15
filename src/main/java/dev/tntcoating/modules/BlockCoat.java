package dev.tntcoating.modules;

import dev.tntcoating.core.ServerPacer;
import dev.tntcoating.core.RetrySchedule;
import dev.tntcoating.core.PlacementGeometry;
import dev.tntcoating.core.PlacementOrder;
import dev.tntcoating.core.CoatingRules;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.mixin.GameModeInvoker;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.Flight;
import dev.tntcoating.core.ModeGeometry;
import dev.tntcoating.core.FlightPolicy;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coating with optional container interactions; never ignites placed blocks. */
public final class BlockCoat extends Module {
    public enum SurfaceTypes { All, Floors, Walls, Ceilings }
    public enum AutoSwitch { Silent, Normal, Disabled }
    public enum Priority { Nearest, Farthest }
    public enum ServerMode { Adaptive, Conservative, Fast }

    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Block> coatingBlock = general.add(new BlockSetting.Builder()
        .name("coating-block").description("Block used for coating, scaffold and jump placement.")
        .defaultValue(Blocks.TNT)
        .filter(block -> block.asItem() instanceof BlockItem item && item.getBlock() == block)
        .build());
    private Block activeBlock = Blocks.TNT;
    private final SettingGroup server = settings.createGroup("Server Pacing");
    private final Setting<ServerMode> serverMode = server.add(new EnumSetting.Builder<ServerMode>().name("server-mode").description("Adaptive learns from confirmations; Conservative limits to one interaction per tick; Fast uses the configured batch limit.").defaultValue(ServerMode.Adaptive).build());
    private final Setting<Integer> serverCap = integer(server, "server-rate-cap", "Maximum adaptive interactions per tick.", 2, 1, 8);
    private final Setting<Integer> pendingCap = integer(server, "max-pending", "Maximum outstanding interactions in Adaptive mode.", 6, 1, 16);
    private final Setting<Boolean> containerSupports = bool(server, "container-supports", "Sneak-use chests and other interactive supports. Disable if the server rejects these placements.", true);
    private final Setting<Boolean> narrowGaps = bool(server, "narrow-gap-targeting", "Experimental: sample very close to face edges. Leave off for ordinary targeting.", false);
    private final SettingGroup jumping = settings.createGroup("Smart Jump Place");
    private final SettingGroup modes = settings.createGroup("Scaffold and Tunnel");
    private final Setting<Boolean> scaffold = bool(modes, "scaffold", "Build a strip below movement while continuing surface coating. Disabled during flight.", false);
    private final Setting<Integer> scaffoldWidth = integer(modes, "scaffold-width", "Strip width in blocks, centred with a one-block bias for even widths.", 3, 1, 9);
    private final Setting<Boolean> tunnel = bool(modes, "tunnel-backfill", "Coat only behind your horizontal facing. Smart Jump underfoot placement is exempt; scaffold remains behind-only.", false);
    private final Setting<Boolean> flightCoating = bool(modes, "coat-while-flying", "Allow coating during flight or airborne movement outside a jump. Turn off to pause it.", true);
    private final Setting<Boolean> externalFlight = bool(modes, "external-flight-detection", "Manually mark another addon's flight as active (including ground spoofing). Flight coating is controlled separately.", false);
    private final SettingGroup rendering = settings.createGroup("Preview");

    private final Setting<Integer> radius = integer(general, "radius", "Cube half-size: radius 5 scans an 11 x 11 x 11 block cube.", 5, 1, 8);
    private final Setting<Integer> perTick = integer(general, "blocks-per-tick", "Maximum surrounding placement attempts per tick.", 8, 1, 32);
    private final Setting<Double> reach = general.add(new DoubleSetting.Builder().name("placement-reach")
        .description("Maximum eye-to-face distance, also capped by the player's interaction range.")
        .defaultValue(6).range(1, 6).build());
    private final Setting<SurfaceTypes> surfaces = general.add(new EnumSetting.Builder<SurfaceTypes>()
        .name("surface-types").description("Exposed terrain faces to coat.").defaultValue(SurfaceTypes.All).build());
    private final Setting<AutoSwitch> autoSwitch = general.add(new EnumSetting.Builder<AutoSwitch>()
        .name("auto-switch").description("Silent restores the selected hotbar slot after interacting.")
        .defaultValue(AutoSwitch.Silent).build());
    private final Setting<Integer> retryDelay = integer(general, "retry-delay", "Ticks before retrying a rejected surrounding placement.", 3, 1, 40);
    private final Setting<Boolean> rotate = bool(general, "rotate", "Rotate toward the support face before interacting.", true);
    private final Setting<Boolean> preferNearby = bool(general, "prefer-nearby", "Prioritise nearby placements, including retries. Overrides the older placement-priority setting when on.", true);
    private final Setting<Priority> priority = general.add(new EnumSetting.Builder<Priority>()
        .name("placement-priority").description("Order reachable candidates by distance.").defaultValue(Priority.Nearest).build());
    private final Setting<Integer> confirmTimeout = integer(general, "confirmation-timeout", "Ticks to wait for a server block update before resolving a rejected attempt.", 20, 5, 100);

    private final Setting<Boolean> smartJump = bool(jumping, "smart-jump-place", "Prioritise a supported block beneath the moving player during a jump; retry until sent or landed.", true);
    private final Setting<Integer> correctionCooldown = integer(jumping, "correction-cooldown", "Ticks to pause after a server position correction.", 10, 1, 100);
    private final Setting<Boolean> preview = bool(rendering, "render-preview", "Render valid placements in green and pending confirmations in amber.", true);
    private final Setting<Boolean> rejectedPreview = bool(rendering, "render-rejected", "Also render a bounded sample of rejected candidates in red.", false);

    private final ServerPacer pacer = new ServerPacer();
    private final Set<BlockPos> changed = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> unconfirmed = ConcurrentHashMap.newKeySet();
    private final Deque<BlockPos> searchQueue = new ArrayDeque<>();
    private final Map<BlockPos, BlockHitResult> hitCache = new HashMap<>();
    private boolean reorder = true;
    private boolean searching;
    private int rays;
    private Priority queuedPriority;
    private final Set<BlockPos> candidates = new HashSet<>();
    private final Map<BlockPos, Pending> pending = new ConcurrentHashMap<>();
    private final Map<BlockPos, Integer> retryAfter = new HashMap<>();
    private final Map<BlockPos, Integer> watched = new ConcurrentHashMap<>();
    private final Map<BlockPos, Integer> failures = new HashMap<>();
    private final Map<BlockPos, Integer> visibilityRetry = new HashMap<>();
    private final Map<BlockPos, Integer> lastAttempt = new HashMap<>();
    private final List<BlockPos> validPreview = new ArrayList<>();
    private final List<BlockPos> invalidPreview = new ArrayList<>();
    private final ConcurrentLinkedQueue<Update> updates = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean corrected = new AtomicBoolean();
    private final AtomicBoolean dirty = new AtomicBoolean(true);
    private ClientLevel level;
    private volatile BlockPos scanOrigin;
    private SurfaceTypes scannedSurfaces;
    private volatile int scannedRadius;
    private Vec3 lastPosition;
    private boolean lastGround;
    private BlockPos takeoffCell;
    private BlockPos jumpPending;
    private int tick;
    private int generation;
    private boolean jumpActive, jumpPlaced;
    private int pauseUntil;
    private float facingYaw;
    private Vec3 travel = new Vec3(0, 0, 1);
    private final Set<BlockPos> strip = new LinkedHashSet<>();
    private final Set<BlockPos> criticalStrip = new LinkedHashSet<>();
    private long interactionTime = Long.MIN_VALUE;
    private int interactions;
    private int readyCount, protectedCount, reachCount;
    private String status = "Starting";

    private static final class Pending {
        final int started;
        final Block block;
        volatile boolean sent;
        int sentTick;
        Pending(int started, Block block) { this.started = started; this.block = block; }
    }
    private record Update(BlockPos pos, boolean matchesBlock, boolean replaceable, Pending attempt) {}
    private enum Rejection { None, Occupied, Retry, Powered, Entity, Protected, Height, NoTerrain, Reach }
    private record Validation(Rejection reason, BlockHitResult hit) {
        boolean valid() { return reason == Rejection.None; }
    }

    public BlockCoat() {
        super(TntCoatingAddon.CATEGORY, "block-coat", "Comet by Boyonabike: configurable block coating, priority jump placement, scaffold strips and tunnel coating.");
    }

    private Setting<Boolean> bool(SettingGroup group, String name, String description, boolean value) {
        return group.add(new BoolSetting.Builder().name(name).description(description).defaultValue(value).build());
    }

    private Setting<Integer> integer(SettingGroup group, String name, String description, int value, int min, int max) {
        return group.add(new IntSetting.Builder().name(name).description(description).defaultValue(value).range(min, max).sliderRange(min, max).build());
    }

    @Override public void onActivate() { reset(); }
    @Override public void onDeactivate() { reset(); }
    @Override public String getInfoString() { return status; }

    private void reset() {
        generation++;
        pacer.reset();
        jumpActive = jumpPlaced = false; pauseUntil = 0; strip.clear(); criticalStrip.clear(); travel = new Vec3(0, 0, 1);
        watched.clear(); failures.clear(); visibilityRetry.clear();
        changed.clear(); unconfirmed.clear(); searchQueue.clear(); hitCache.clear(); reorder = true;
        candidates.clear(); pending.clear(); retryAfter.clear(); lastAttempt.clear(); updates.clear();
        clearPreview();
        dirty.set(true); corrected.set(false);
        level = null; scanOrigin = null; lastPosition = null;
        takeoffCell = jumpPending = null;
        tick = 0; searching = false; rays = 0;
        interactionTime = Long.MIN_VALUE;
        interactions = readyCount = protectedCount = reachCount = 0;
        status = "Starting";
    }

    private void clearPreview() { validPreview.clear(); invalidPreview.clear(); }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        // Receive callbacks may be on the network thread. Only enqueue immutable data here.
        if (event.packet instanceof ClientboundPlayerPositionPacket) {
            corrected.set(true);
        } else if (event.packet instanceof ClientboundBlockUpdatePacket packet) {
            enqueueUpdate(packet.getPos(), packet.getBlockState());
        } else if (event.packet instanceof ClientboundSectionBlocksUpdatePacket packet) {
            packet.runUpdates(this::enqueueUpdate);
        }
    }

    private void enqueueUpdate(BlockPos pos, BlockState state) {
        Pending attempt = pending.get(pos);
        // Ignore unrelated updates and packets arriving before this interaction was sent.
        if ((attempt != null && attempt.sent) || unconfirmed.contains(pos) || watched.containsKey(pos))
            updates.add(new Update(pos.immutable(), state.is(attempt == null ? activeBlock : attempt.block), state.canBeReplaced(), attempt));
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        BlockPos origin = scanOrigin;
        if (origin != null && PlacementGeometry.inCube(event.pos.getX(), event.pos.getY(), event.pos.getZ(),
            origin.getX(), origin.getY(), origin.getZ(), scannedRadius + 1)) changed.add(event.pos.immutable());
    }

    // Capture the user's facing before Meteor temporarily aims at a placement support.
    @EventHandler(priority = 1000)
    private void onMovementPre(SendMovementPacketsEvent.Pre event) {
        if (mc.player != null) facingYaw = mc.player.getYRot();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) { reset(); return; }
        if (activeBlock != coatingBlock.get()) { reset(); activeBlock = coatingBlock.get(); }
        if (level != mc.level) {
            reset();
            level = mc.level;
            lastPosition = mc.player.position();
            lastGround = mc.player.onGround();
        }
        tick++;
        facingYaw = mc.player.getYRot();
        clearPreview();
        drainUpdates();
        expirePending();

        if (corrected.getAndSet(false)) {
            generation++;
            candidates.clear();
            // Keep sent attempts tracked, but discard every deferred interaction.
            pending.entrySet().removeIf(e -> !e.getValue().sent);
            pauseUntil = tick + correctionCooldown.get(); jumpActive = false;
            pacer.failed(tick, correctionCooldown.get());
            dirty.set(true);
            takeoffCell = null;
        }

        Vec3 position = mc.player.position();
        boolean grounded = mc.player.onGround();
        boolean flying = flying();
        Vec3 delta = lastPosition == null ? Vec3.ZERO : position.subtract(lastPosition);
        if (delta.horizontalDistanceSqr() > .0001) travel = new Vec3(delta.x, 0, delta.z).normalize();
        boolean started = smartJump.get() && lastGround && !grounded && mc.player.getDeltaMovement().y > .05
            && mc.player.input.keyPresses.jump() && !flying && !mc.player.isInWater();
        if (started) {
            generation++;
            pending.entrySet().removeIf(e -> !e.getValue().sent);
            jumpActive = true; jumpPlaced = false; jumpPending = null;
        }
        if (grounded || flying || !smartJump.get()) jumpActive = false;
        lastGround = grounded; lastPosition = position;
        if (tick < pauseUntil || mc.player.isInWater() || !mc.player.isAlive()) { status = "Paused"; return; }
        if (!flightPlacementAllowed()) { strip.clear(); criticalStrip.clear(); status = "Flight coating off"; return; }
        if (spongeHasPriority()) {
            generation++;
            pending.entrySet().removeIf(e -> !e.getValue().sent);
            status = "Waiting for Smart Sponge";
            return;
        }
        // Retry while clear, including the descending half of the jump. Follow the player horizontally.
        if (jumpActive && !jumpPlaced) {
            takeoffCell = BlockPos.containing(position.x, mc.player.getBoundingBox().minY - 1, position.z);
            Validation under = validate(takeoffCell, true, null);
            if (attempt(takeoffCell, true, under)) { status = "Jump priority"; return; }
            if (jumpPending != null && pending.containsKey(jumpPending)) { status = "Jump pending"; return; }
        }
        strip.clear(); criticalStrip.clear();
        if (scaffold.get() && !flying && (grounded || jumpActive)) {
            BlockPos base = BlockPos.containing(position.x, mc.player.getBoundingBox().minY - 1, position.z);
            for (ModeGeometry.Cell cell : ModeGeometry.path(position.x, base.getY(), position.z, travel.x, travel.z, 1))
                criticalStrip.add(new BlockPos(cell.x(), cell.y(), cell.z()));
            for (ModeGeometry.Cell cell : ModeGeometry.path(position.x, base.getY(), position.z, travel.x, travel.z, scaffoldWidth.get()))
                strip.add(new BlockPos(cell.x(), cell.y(), cell.z()));
        }
        FindItemResult material = findCoatingBlock();
        if (!material.found()) { status = "No selected block in hotbar"; return; }
        BlockPos origin = mc.player.blockPosition();
        boolean fullScan = dirty.getAndSet(false) || scannedRadius != radius.get()
            || scannedSurfaces != surfaces.get() || tick % 40 == 0;
        if (fullScan || !origin.equals(scanOrigin)) scan(origin, !fullScan && scanOrigin != null);
        refreshChanges();

        if (reorder || queuedPriority != priority.get() || tick % 5 == 0) {
            Comparator<BlockPos> sorter = Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(position));
            Comparator<BlockPos> coverageOrder = preferNearby.get() || priority.get() == Priority.Nearest ? sorter : sorter.reversed();
            if (tunnel.get()) coverageOrder = Comparator.<BlockPos, Boolean>comparing(pos -> !tunnelTarget(pos)).thenComparing(coverageOrder);
            searchQueue.clear();
            searchQueue.addAll(PlacementOrder.nearFirst(candidates, coverageOrder.thenComparingLong(BlockPos::asLong)));
            reorder = false; queuedPriority = priority.get();
        }
        int limit = perTick.get();
        if (paced()) limit = Math.min(limit, pacer.budget(tick, rateCap(), pending.size(), window()));
        int attempts = 0;
        // Footpath always gets first use of the budget, including at one interaction per tick.
        for (BlockPos target : criticalStrip) {
            if (attempts >= limit) break;
            if (attempt(target, false, validate(target, false, null))) attempts++;
        }
        // Widen only after preparing the path; leave remaining capacity for surrounding coating.
        int wideningLimit = attempts + Math.max(0, (limit - attempts) / 2);
        for (BlockPos target : strip) {
            if (attempts >= wideningLimit) break;
            if (!criticalStrip.contains(target) && attempt(target, false, validate(target, false, null))) attempts++;
        }
        readyCount = protectedCount = reachCount = 0;
        rays = 0; searching = true;
        int checks = Math.min(128, searchQueue.size());
        for (int i = 0; i < checks && attempts < limit && rays < 256; i++) {
            BlockPos target = searchQueue.removeFirst();
            searchQueue.addLast(target);
            if (pending.containsKey(target) || visibilityRetry.getOrDefault(target,0) > tick) continue;
            Validation validation = validate(target, false, null);
            if (!validation.valid()) {
                if (validation.reason == Rejection.Protected || validation.reason == Rejection.Height) protectedCount++;
                if (validation.reason == Rejection.Reach) { reachCount++; visibilityRetry.put(target,tick+3); }
                if (rejectedPreview.get() && invalidPreview.size() < 128) invalidPreview.add(target);
                continue;
            }
            readyCount++;
            if (validPreview.size() < 256) validPreview.add(target);
            if (attempts < limit && attempt(target, false, validation)) attempts++;
        }
        searching = false;
        status = attempts + " queued / " + pending.size() + " pending / " + candidates.size() + " surfaces";
    }

    private FindItemResult findCoatingBlock() {
        if (autoSwitch.get() == AutoSwitch.Disabled) {
            if (mc.player.getOffhandItem().is(activeBlock.asItem())) return new FindItemResult(SlotUtils.OFFHAND, mc.player.getOffhandItem().getCount());
            if (mc.player.getMainHandItem().is(activeBlock.asItem())) return new FindItemResult(mc.player.getInventory().getSelectedSlot(), mc.player.getMainHandItem().getCount());
            return new FindItemResult(-1, 0);
        }
        return InvUtils.findInHotbar(activeBlock.asItem());
    }

    private boolean spongeHasPriority() {
        SmartSponge sponge = Modules.get().get(SmartSponge.class);
        return sponge != null && sponge.blocksCoating();
    }

    private boolean flightPlacementAllowed() {
        return FlightPolicy.allowPlacement(flightCoating.get(), flying(), mc.player.onGround(), jumpActive);
    }

    private boolean flying() {
        return externalFlight.get() || mc.player.getAbilities().flying || mc.player.isFallFlying()
            || Modules.get().isActive(Flight.class);
    }

    private boolean scaffoldTarget(BlockPos target) {
        return scaffold.get() && !flying() && (mc.player.onGround() || jumpActive) && strip.contains(target);
    }

    private boolean tunnelTarget(BlockPos target) {
        Vec3 offset = Vec3.atCenterOf(target).subtract(mc.player.position());
        return tunnel.get() && ModeGeometry.behindFacing(offset.x, offset.z, facingYaw);
    }

    private boolean paced() { return serverMode.get() != ServerMode.Fast; }
    private int rateCap() { return Math.min(perTick.get(), serverMode.get() == ServerMode.Conservative ? 1 : serverCap.get()); }
    private int window() { return serverMode.get() == ServerMode.Conservative ? 2 : pendingCap.get(); }

    private void scheduleRetry(BlockPos pos) {
        int count = failures.merge(pos,1,Integer::sum);
        int delay = RetrySchedule.delay(retryDelay.get(),count);
        retryAfter.put(pos,tick+delay);
        changed.add(pos); hitCache.remove(pos); visibilityRetry.remove(pos); reorder = true;
        pacer.failed(tick,retryDelay.get());
        if (pos.equals(jumpPending) && jumpActive) { jumpPlaced = false; jumpPending = null; }
    }

    private void drainUpdates() {
        Update update;
        while ((update = updates.poll()) != null) {
            unconfirmed.remove(update.pos);
            changed.add(update.pos);
            visibilityRetry.remove(update.pos);
            Pending attempt = update.attempt;
            boolean resolved = attempt != null && attempt.sent && pending.remove(update.pos,attempt);
            if (update.matchesBlock) {
                failures.remove(update.pos); retryAfter.remove(update.pos);
                if (resolved) pacer.accepted(tick-attempt.sentTick,rateCap());
            } else if (update.replaceable && (resolved || watched.containsKey(update.pos))) {
                // Includes a later authoritative rollback after an earlier confirmation.
                watched.remove(update.pos);
                scheduleRetry(update.pos);
            } else if (!update.replaceable) {
                watched.remove(update.pos); failures.remove(update.pos); retryAfter.remove(update.pos);
            }
        }
    }

    private void expirePending() {
        pending.entrySet().removeIf(e -> {
            Pending attempt = e.getValue();
            if (!attempt.sent) return tick - attempt.started > 2;
            int timeout = paced() ? pacer.timeout(confirmTimeout.get()) : confirmTimeout.get();
            if (tick - attempt.sentTick < timeout) return false;
            scheduleRetry(e.getKey());
            return true;
        });
        retryAfter.entrySet().removeIf(e -> {
            if (tick < e.getValue()) return false;
            changed.add(e.getKey()); reorder = true; return true;
        });
        watched.entrySet().removeIf(e -> tick > e.getValue() || !inRadius(e.getKey()));
        failures.keySet().removeIf(pos -> !inRadius(pos));
        visibilityRetry.entrySet().removeIf(e -> tick >= e.getValue() || !inRadius(e.getKey()));
    }

    private void refreshChanges() {
        Set<BlockPos> affected = new HashSet<>();
        for (BlockPos pos : changed) {
            if (!changed.remove(pos)) continue;
            affected.add(pos);
            for (Direction side : Direction.values()) affected.add(pos.relative(side));
        }
        for (BlockPos pos : affected) {
            hitCache.remove(pos); visibilityRetry.remove(pos);
            boolean eligible = inRadius(pos) && replaceable(pos) && hasTerrainFace(pos);
            boolean modified = eligible ? candidates.add(pos) : candidates.remove(pos);
            if (modified) reorder = true;
        }
    }

    private boolean terrain(BlockPos pos) {
        if (!loaded(pos) || mc.level.isOutsideBuildHeight(pos)) return false;
        BlockState state = mc.level.getBlockState(pos);
        return CoatingRules.surface(state.is(Blocks.TNT) || state.is(activeBlock), !state.getFluidState().isEmpty(), state.canBeReplaced(),
            !state.getCollisionShape(mc.level, pos).isEmpty(), state.getBlock().getExplosionResistance(),
            state.getDestroySpeed(mc.level, pos));
    }

    private boolean enabledFace(Direction face) {
        return switch (surfaces.get()) {
            case All -> true;
            case Floors -> face == Direction.UP;
            case Ceilings -> face == Direction.DOWN;
            case Walls -> face.getAxis().isHorizontal();
        };
    }

    private boolean inRadius(BlockPos pos) {
        BlockPos origin = mc.player.blockPosition();
        return PlacementGeometry.inCube(pos.getX(), pos.getY(), pos.getZ(), origin.getX(), origin.getY(), origin.getZ(), radius.get());
    }

    private void scan(BlockPos origin, boolean incremental) {
        BlockPos previousOrigin = scanOrigin;
        if (!incremental) candidates.clear();
        else candidates.removeIf(pos -> !inRadius(pos));
        visibilityRetry.clear();
        reorder = true;
        scanOrigin = origin.immutable(); scannedRadius = radius.get(); scannedSurfaces = surfaces.get();
        int r = radius.get();
        // Scan targets rather than surfaces, so a terrain face just outside the cube still coats its boundary cell.
        for (BlockPos mutable : BlockPos.betweenClosed(origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
            if (incremental && PlacementGeometry.inCube(mutable.getX(),mutable.getY(),mutable.getZ(),
                previousOrigin.getX(),previousOrigin.getY(),previousOrigin.getZ(),r)) continue;
            if (!replaceable(mutable)) continue;
            BlockPos target = mutable.immutable();
            if (hasTerrainFace(target)) candidates.add(target);
        }
        lastAttempt.keySet().retainAll(candidates);
        hitCache.keySet().retainAll(candidates);
        unconfirmed.removeIf(pos -> !inRadius(pos) && !pending.containsKey(pos));
    }

    private boolean replaceable(BlockPos pos) {
        if (pos == null || mc.level.isOutsideBuildHeight(pos) || !loaded(pos)
            || !mc.level.getWorldBorder().isWithinBounds(pos)) return false;
        BlockState state = mc.level.getBlockState(pos);
        return !state.is(activeBlock) && state.canBeReplaced() && state.getFluidState().isEmpty();
    }

    private boolean hasTerrainFace(BlockPos target) {
        for (Direction side : Direction.values()) {
            if (enabledFace(side.getOpposite()) && terrain(target.relative(side))) return true;
        }
        return false;
    }

    private boolean touchesWall(BlockPos target) {
        for (Direction side : Direction.values()) {
            if (side.getAxis().isHorizontal() && terrain(target.relative(side))) return true;
        }
        return false;
    }

    /** Terrain creates targets; any eligible collidable block (including the selected coating block) may support one. */
    private BlockHitResult supportHit(BlockPos target) {
        Vec3 eye = mc.player.getEyePosition();
        List<Direction> sides = new ArrayList<>(List.of(Direction.values()));
        sides.sort(Comparator.comparingDouble(side -> Vec3.atCenterOf(target.relative(side)).distanceToSqr(eye)));
        for (Direction side : sides) {
            BlockPos support = target.relative(side);
            if (!loaded(support) || (paced() && unconfirmed.contains(support))) continue;
            BlockState state = mc.level.getBlockState(support);
            if (!terrain(support) && !state.is(activeBlock)) continue;
            if (state.canBeReplaced() || !state.getFluidState().isEmpty()
                || (!containerSupports.get() && BlockUtils.isClickable(state.getBlock()))) continue;
            Direction face = side.getOpposite();
            int axis = face.getAxis() == Direction.Axis.X ? 0 : face.getAxis() == Direction.Axis.Y ? 1 : 2;
            for (AABB localBox : state.getCollisionShape(mc.level, support).toAabbs()) {
                AABB box = localBox.move(support.getX(), support.getY(), support.getZ());
                var bounds = new PlacementGeometry.Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
                for (var sample : PlacementGeometry.faceSamples(bounds, axis, face.getAxisDirection().getStep(),
                    new PlacementGeometry.Point(eye.x, eye.y, eye.z), narrowGaps.get())) {
                    BlockHitResult hit = new BlockHitResult(new Vec3(sample.x(), sample.y(), sample.z()), face, support, false);
                    if (searching && rays >= 256) return null;
                    if (visibleHit(target, hit)) return hit;
                }
            }
        }
        return null;
    }

    private boolean visibleHit(BlockPos target, BlockHitResult hit) {
        if (paced() && unconfirmed.contains(hit.getBlockPos())) return false;
        if (!hit.getBlockPos().relative(hit.getDirection()).equals(target)) return false;
        BlockState support = mc.level.getBlockState(hit.getBlockPos());
        if (!terrain(hit.getBlockPos()) && !support.is(activeBlock)) return false;
        if (support.canBeReplaced() || !support.getFluidState().isEmpty()
            || (!containerSupports.get() && BlockUtils.isClickable(support.getBlock()))) return false;
        Vec3 eye = mc.player.getEyePosition();
        double maxReach = Math.min(reach.get(), mc.player.blockInteractionRange()) - (paced() ? .15 : 0);
        if (eye.distanceToSqr(hit.getLocation()) > maxReach * maxReach) return false;
        Direction face = hit.getDirection();
        Vec3 inside = hit.getLocation().add(-face.getStepX() * .001, -face.getStepY() * .001, -face.getStepZ() * .001);
        if (searching) { if (rays >= 256) return false; rays++; }
        BlockHitResult ray = mc.level.clip(new ClipContext(eye, inside, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(hit.getBlockPos())
            && ray.getDirection() == face && ray.getLocation().distanceToSqr(hit.getLocation()) < .0004;
    }

    private boolean loaded(BlockPos pos) { return mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4); }

    private Validation reject(Rejection reason) { return new Validation(reason, null); }

    private Validation validate(BlockPos target, boolean underfoot, BlockHitResult preferred) {
        if (!flightPlacementAllowed()) return reject(Rejection.Protected);
        if (!CoatingRules.tunnelAllows(tunnel.get(), underfoot, tunnelTarget(target))) return reject(Rejection.Protected);
        if (!replaceable(target) || !inRadius(target)) return reject(Rejection.Occupied);
        if (retryAfter.getOrDefault(target, 0) > tick) return reject(Rejection.Retry);
        if (mc.level.hasNeighborSignal(target)) return reject(Rejection.Powered);
        boolean platform = !underfoot && scaffoldTarget(target);
        if (!underfoot && !platform && !hasTerrainFace(target)) return reject(Rejection.NoTerrain);
        AABB block = new AABB(target);
        if (underfoot) {
            if (!jumpActive || jumpPlaced || flying() || mc.player.getBoundingBox().minY < target.getY() + 1.0
                || !mc.player.getBoundingBox().inflate(0.03, 0, 0.03).move(0, -1, 0).intersects(block)
                || mc.player.getBoundingBox().intersects(block)) return reject(Rejection.Protected);
        } else {
            int feetY = mc.player.blockPosition().getY();
            if (!platform && !tunnelTarget(target) && (target.getY() == feetY || target.getY() == feetY + 1)
                && !CoatingRules.heightAllowed(target.getY(), feetY, touchesWall(target))) return reject(Rejection.Height);
            if (mc.player.getBoundingBox().inflate(0.08, 0, 0.08).intersects(block)) return reject(Rejection.Protected);
        }
        if (!BlockUtils.canPlaceBlock(target, true, activeBlock)) return reject(Rejection.Entity);
        BlockHitResult hit;
        if (preferred != null) hit = visibleHit(target, preferred) ? preferred : null;
        else {
            BlockHitResult cached = hitCache.get(target);
            hit = cached != null && visibleHit(target, cached) ? cached : supportHit(target);
            if (hit != null) hitCache.put(target, hit); else hitCache.remove(target);
        }
        return hit == null ? reject(Rejection.Reach) : new Validation(Rejection.None, hit);
    }

    private boolean validateForSend(BlockPos target, boolean underfoot, BlockHitResult hit) {
        boolean previous = searching;
        searching = false;
        try { return validate(target, underfoot, hit).valid(); }
        finally { searching = previous; }
    }

    private boolean attempt(BlockPos target, boolean underfoot, Validation validation) {
        if (spongeHasPriority() || pending.containsKey(target) || !validation.valid() || !findCoatingBlock().found()) return false;
        // Keep one outstanding slot available for the footpath when coating alongside scaffold.
        int reservationWindow = scaffold.get() && !flying() && !underfoot && !scaffoldTarget(target)
            ? Math.max(1, window() - 1) : window();
        if (paced() && pacer.budget(tick, rateCap(), pending.size(), reservationWindow) <= 0) return false;
        BlockHitResult hit = validation.hit;
        lastAttempt.put(target, tick);
        Pending reservation = new Pending(tick, activeBlock);
        pending.put(target, reservation);
        if (underfoot) jumpPending = target;
        int token = generation;
        int scheduledTick = tick;
        Runnable interact = () -> {
            // Rotations can defer execution. Recheck everything against live player/world state.
            if (!isActive() || mc.player == null || mc.level != level || mc.gameMode == null
                || spongeHasPriority() || token != generation || coatingBlock.get() != reservation.block || corrected.get() || tick - scheduledTick > 1
                || pending.get(target) != reservation) {
                pending.remove(target, reservation);
                return;
            }
            if (tick < pauseUntil || !validateForSend(target, underfoot, hit)) { pending.remove(target); return; }
            if (!underfoot && jumpActive && !jumpPlaced) {
                pending.remove(target);
                return;
            }
            BlockHitResult liveHit = hit;
            FindItemResult material = findCoatingBlock();
            if (!material.found()) { pending.remove(target); return; }
            long now = mc.level.getGameTime();
            if (interactionTime != now) { interactionTime = now; interactions = 0; }
            int liveLimit = underfoot ? 1 : (perTick.get());
            if (interactions >= liveLimit) { pending.remove(target); return; }
            if (paced() && pacer.budget(tick, rateCap(), pending.size() - 1, window()) <= 0) { pending.remove(target); return; }
            interactions++;
            pacer.sent(tick);
            boolean offhand = material.isOffhand();
            boolean swapBack = autoSwitch.get() == AutoSwitch.Silent;
            if (!offhand && autoSwitch.get() != AutoSwitch.Disabled) InvUtils.swap(material.slot(), swapBack);
            try {
                if (underfoot) jumpPlaced = true;
                reservation.sentTick = tick;
                watched.put(target,tick+200);
                unconfirmed.add(target);
                reservation.sent = true;
                interactWithSupport(liveHit, offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
            } finally {
                if (!offhand && autoSwitch.get() != AutoSwitch.Disabled && swapBack) InvUtils.swapBack();
            }
        };
        if (rotate.get() || paced()) Rotations.rotate(Rotations.getYaw(hit.getLocation()), Rotations.getPitch(hit.getLocation()), underfoot ? 100 : criticalStrip.contains(target) ? 80 : 20, interact);
        else interact.run();
        return true;
    }

    /** Sneak-use containers so they act as support instead of opening their screen. */
    private void interactWithSupport(BlockHitResult hit, InteractionHand hand) {
        if (!BlockUtils.isClickable(mc.level.getBlockState(hit.getBlockPos()).getBlock())) {
            BlockUtils.interact(hit, hand, true);
            return;
        }
        if (!containerSupports.get()) return;
        Input original = mc.player.input.keyPresses;
        var connection = mc.getConnection();
        if (connection == null) return;
        // In 26.2 shift state is carried by the ordinary player input packet.
        Input sneaking = new Input(original.forward(), original.backward(), original.left(), original.right(),
            original.jump(), true, original.sprint());
        connection.send(new ServerboundPlayerInputPacket(sneaking));
        try {
            // Send the sequenced interaction without client-side container prediction or local crouching.
            ((GameModeInvoker) mc.gameMode).comet$startPrediction(mc.level, sequence -> new ServerboundUseItemOnPacket(hand, hit, sequence));
            mc.player.swing(hand);
        } finally {
            connection.send(new ServerboundPlayerInputPacket(original));
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!preview.get() || mc.player == null || mc.level != level || corrected.get()) return;
        Color green = new Color(70, 220, 110, 35), greenLine = new Color(70, 220, 110, 180);
        Color amber = new Color(255, 180, 50, 40), amberLine = new Color(255, 180, 50, 200);
        Color red = new Color(230, 70, 70, 20), redLine = new Color(230, 70, 70, 100);
        for (BlockPos pos : validPreview) event.renderer.box(pos, green, greenLine, ShapeMode.Both, 0);
        for (BlockPos pos : pending.keySet()) event.renderer.box(pos, amber, amberLine, ShapeMode.Both, 0);
        if (rejectedPreview.get()) for (BlockPos pos : invalidPreview) event.renderer.box(pos, red, redLine, ShapeMode.Both, 0);
    }
}

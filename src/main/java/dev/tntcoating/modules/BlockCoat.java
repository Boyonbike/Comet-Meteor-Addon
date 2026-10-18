package dev.tntcoating.modules;

import dev.tntcoating.core.ServerPacer;
import dev.tntcoating.core.PacketBudget;
import dev.tntcoating.core.PlacementTracker;
import dev.tntcoating.core.PlacementGeometry;
import dev.tntcoating.core.PlacementOrder;
import dev.tntcoating.core.CoatingRules;
import dev.tntcoating.core.TntBlastPlanner;
import dev.tntcoating.core.TntRedstoneSafety;
import dev.tntcoating.core.BlockCoatSettings;
import dev.tntcoating.core.LiquidFiller;
import dev.tntcoating.core.HeldItemPause;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.entity.player.InteractEntityEvent;
import meteordevelopment.meteorclient.events.entity.player.InteractItemEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
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
import java.util.concurrent.atomic.AtomicInteger;

/** Coating with optional container interactions; never ignites placed blocks. */
public final class BlockCoat extends Module {
    public enum SurfaceTypes { All, Floors, Walls, Ceilings }
    public enum AutoSwitch { Sticky, Silent, Normal, Disabled }
    public enum Priority { Nearest, Farthest }
    public enum ServerMode { Adaptive, Conservative, Fast }

    private final SettingGroup general = settings.getDefaultGroup();
    private final SettingGroup tntSaving = settings.createGroup("TNT Saving", false);
    private final Setting<Block> coatingBlock = general.add(new BlockSetting.Builder()
        .name("coating-block").description("Block used for coating, scaffold and jump placement.")
        .defaultValue(Blocks.TNT)
        .filter(block -> block.asItem() instanceof BlockItem item && item.getBlock() == block)
        .build());
    private Block activeBlock = Blocks.TNT;
    private final Setting<Boolean> conservativeTnt = bool(tntSaving, "conservative-tnt", "Save TNT by skipping surface placements whose terrain is already covered by an estimated blast. Jump, scaffold and isolated-source filling are unchanged.", false);
    private boolean activeConservativeTnt;
    private final SettingGroup liquids = settings.createGroup("Liquid Filler");
    private final Setting<Boolean> liquidFiller = bool(liquids, "liquid-filler", "Replace isolated water source blocks with TNT in normal and conservative TNT modes. Skips sources touching another source on any of their six faces; flowing neighbours are allowed. Requires coating-block TNT.", true);
    private boolean activeLiquidFiller;
    private final SettingGroup server = settings.createGroup("Server Pacing");
    private final Setting<ServerMode> serverMode = server.add(new EnumSetting.Builder<ServerMode>().name("server-mode").description("Adaptive learns from confirmations; Conservative limits to one interaction per tick; Fast uses the configured batch limit.").defaultValue(ServerMode.Adaptive).build());
    private final Setting<Integer> serverCap = integer(server, "server-rate-cap", "Maximum adaptive interactions per tick.", 2, 1, 8);
    private final Setting<Integer> pendingCap = integer(server, "max-pending", "Maximum outstanding interactions in Adaptive mode.", 6, 1, 16);
    private final Setting<Boolean> containerSupports = bool(server, "container-supports", "Sneak-use chests and other interactive supports. Disable if the server rejects these placements.", true);
    private final Setting<Boolean> narrowGaps = bool(server, "narrow-gap-targeting", "Experimental: sample very close to face edges. Leave off for ordinary targeting.", false);
    private final Setting<Integer> packetLimit = integer(server, "max-packets-per-second", "Skip placements that would push everything you sent in the last second above this. ViaVersion's default sustained limit is 200.", 150, 40, 800);
    private final Setting<Boolean> extraRotations = bool(server, "extra-rotation-packets", "Allow more than one rotated placement per tick. Each extra one sends its own rotation packet; off places at most one rotated block per tick.", false);
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
        .name("auto-switch").description("Sticky holds the block while placing and switches back after a second idle. Silent switches to it and back for every placement (two extra packets each). Normal leaves it selected.")
        .defaultValue(AutoSwitch.Sticky).build());
    private final Setting<List<Item>> pauseItems = general.add(new ItemListSetting.Builder()
        .name("pause-items").description("Pause all Block Coat placement and automatic switching while a listed item is held in either hand, until pause-items-timeout expires. Clear the list to disable.")
        .defaultValue(Items.FLINT_AND_STEEL).build());
    private final Setting<Integer> pauseItemsTimeout = integer(general, "pause-items-timeout", "Seconds before resuming while a pause item is still held. Zero pauses until you switch away. Selecting a pause item again starts a new timeout.", 5, 0, 60);
    private final Setting<Boolean> pauseItemsResetOnUse = bool(general, "pause-items-reset-on-use", "Restart the pause timeout when a listed item is used. Continuous item use keeps the timer refreshed.", true);
    private final Setting<Integer> retryDelay = integer(general, "retry-delay", "Ticks before retrying a rejected surrounding placement.", 3, 1, 40);
    private final Setting<Boolean> rotate = bool(general, "rotate", "Rotate toward the support face before interacting, in every server mode. Turn off only to test whether the server needs it.", true);
    private final Setting<Priority> order = general.add(new EnumSetting.Builder<Priority>()
        .name("placement-order").description("Order reachable candidates, including retries, by distance.").defaultValue(Priority.Nearest).build());
    private final Setting<Integer> confirmTimeout = integer(general, "confirmation-timeout", "Ticks to wait for a server block update before resolving a rejected attempt.", 20, 5, 100);

    private final Setting<Boolean> smartJump = bool(jumping, "smart-jump-place", "Prioritise a supported block beneath the moving player during a jump; retry until sent or landed.", true);
    private final Setting<Integer> correctionCooldown = integer(jumping, "correction-cooldown", "Ticks to pause after a server position correction.", 10, 1, 100);
    private final Setting<Boolean> preview = bool(rendering, "render-preview", "Render valid placements in green and pending confirmations in amber.", true);
    private final Setting<Boolean> rejectedPreview = bool(rendering, "render-rejected", "Also render a bounded sample of rejected candidates in red.", false);

    private final ServerPacer pacer = new ServerPacer();
    private final PacketBudget packetBudget = new PacketBudget();
    private final HeldItemPause heldItemPause = new HeldItemPause();
    private boolean coatingInteraction;
    private final AtomicInteger sentPackets = new AtomicInteger();
    private int plannedPackets, rotationsPlanned, corrections;
    private boolean packetLimited;
    private static final int STICKY_HOLD_TICKS = 20;
    private int stickyRestore = -1, stickySlot = -1, stickyUsed;
    private final Set<BlockPos> changed = ConcurrentHashMap.newKeySet();
    private final Deque<BlockPos> searchQueue = new ArrayDeque<>();
    private final Map<BlockPos, BlockHitResult> hitCache = new HashMap<>();
    private final AtomicBoolean blastDirty = new AtomicBoolean(true);
    private final Set<TntBlastPlanner.Cell> nearbyTnt = new HashSet<>();
    private final Map<TntBlastPlanner.Cell, Set<TntBlastPlanner.Cell>> blastCache = new HashMap<>();
    private final Map<TntBlastPlanner.Cell, TntBlastPlanner.Material> blastMaterials = new HashMap<>();
    private final Map<Long, Boolean> blastChunks = new HashMap<>();
    private BlockPos blastOrigin;
    private int blastRadius, blastTick;
    private static final int BLAST_PREDICTIONS_PER_TICK = 16;
    private int blastPredictions, blastPredictionTick;
    private boolean blastLimited;
    private boolean reorder = true;
    private boolean searching;
    private int rays;
    private Priority queuedPriority;
    private final Set<BlockPos> candidates = new HashSet<>();
    private final Map<BlockPos, Integer> visibilityRetry = new HashMap<>();
    private final PlacementTracker<BlockPos, Block> tracker = new PlacementTracker<>(new PlacementTracker.Listener<>() {
        @Override public void accepted(BlockPos pos, int latencyTicks) { pacer.accepted(latencyTicks, rateCap()); }

        @Override public void retryScheduled(BlockPos pos, boolean failure) {
            changed.add(pos); hitCache.remove(pos); visibilityRetry.remove(pos); reorder = true;
            // A correction already paused the pacer; placements it discarded are not rejections.
            if (failure) pacer.failed(tick, retryDelay.get());
            if (pos.equals(jumpPending) && jumpActive) { jumpPlaced = false; jumpPending = null; }
        }

        @Override public void retryReady(BlockPos pos) { changed.add(pos); reorder = true; }
    });
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
    private int readyCount, protectedCount, reachCount, coveredCount, planningCount;
    private String status = "Starting";

    private record Update(BlockPos pos, boolean matchesBlock, boolean replaceable, PlacementTracker.Attempt<Block> attempt) {}
    private enum Rejection { None, Occupied, Retry, Redstone, Entity, Protected, Height, NoTerrain, Reach, Covered, Planning }
    private record Validation(Rejection reason, BlockHitResult hit, boolean liquid) {
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
    @Override public BlockCoat fromTag(CompoundTag tag) {
        super.fromTag(BlockCoatSettings.migrate(tag));
        return this;
    }

    private void reset() {
        releaseSticky();
        heldItemPause.reset();
        generation++;
        pacer.reset(); packetBudget.reset(); sentPackets.set(0);
        plannedPackets = rotationsPlanned = corrections = 0; packetLimited = false;
        jumpActive = jumpPlaced = false; pauseUntil = 0; strip.clear(); criticalStrip.clear(); travel = new Vec3(0, 0, 1);
        tracker.clear(); visibilityRetry.clear();
        changed.clear(); searchQueue.clear(); hitCache.clear(); reorder = true;
        candidates.clear(); updates.clear();
        clearBlastCache();
        clearPreview();
        dirty.set(true); corrected.set(false);
        level = null; scanOrigin = null; lastPosition = null;
        takeoffCell = jumpPending = null;
        tick = 0; searching = false; rays = 0;
        interactionTime = Long.MIN_VALUE;
        interactions = readyCount = protectedCount = reachCount = coveredCount = planningCount = 0;
        status = "Starting";
    }

    private void clearPreview() { validPreview.clear(); invalidPreview.clear(); }

    private void clearBlastCache() {
        nearbyTnt.clear(); blastCache.clear(); blastMaterials.clear(); blastChunks.clear();
        blastOrigin = null; blastDirty.set(true);
        blastPredictions = 0; blastPredictionTick = -1; blastLimited = false;
    }

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
        PlacementTracker.Attempt<Block> attempt = tracker.attempt(pos);
        if (tracker.concerns(pos, attempt))
            updates.add(new Update(pos.immutable(), state.is(attempt == null ? activeBlock : attempt.block), state.canBeReplaced(), attempt));
    }

    // May run on the network thread.
    @EventHandler
    private void onSent(PacketEvent.Sent event) { sentPackets.incrementAndGet(); }

    @EventHandler(priority = -1000)
    private void onUseBlock(InteractBlockEvent event) {
        if (!event.isCancelled()) pauseItemUsed(event.hand);
    }

    @EventHandler(priority = -1000)
    private void onUseEntity(InteractEntityEvent event) {
        if (!event.isCancelled()) pauseItemUsed(event.hand);
    }

    @EventHandler(priority = -1000)
    private void onUseItem(InteractItemEvent event) {
        if (event.toReturn == null) pauseItemUsed(event.hand);
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        BlockPos origin = scanOrigin;
        if (origin != null && PlacementGeometry.inCube(event.pos.getX(), event.pos.getY(), event.pos.getZ(),
            origin.getX(), origin.getY(), origin.getZ(), scannedRadius + 1)) changed.add(event.pos.immutable());
        // Sources can sit outside the coating cube, with shielding another blast radius beyond them.
        if (origin != null && PlacementGeometry.inCube(event.pos.getX(), event.pos.getY(), event.pos.getZ(),
            origin.getX(), origin.getY(), origin.getZ(), scannedRadius + 2 * TntBlastPlanner.RADIUS + 1)) blastDirty.set(true);
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        // Full chunk replacement need not emit individual block updates. This event runs after it is applied.
        BlockPos origin = scanOrigin;
        if (origin == null) return;
        var chunk = event.chunk().getPos();
        int margin = scannedRadius + 2 * TntBlastPlanner.RADIUS + 1;
        if (chunk.x() < ((origin.getX() - margin) >> 4) || chunk.x() > ((origin.getX() + margin) >> 4)
            || chunk.z() < ((origin.getZ() - margin) >> 4) || chunk.z() > ((origin.getZ() + margin) >> 4)) return;
        blastDirty.set(true);
        if ((conservativeTnt.get() || liquidFiller.get()) && activeBlock == Blocks.TNT) dirty.set(true);
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
        if (activeConservativeTnt != conservativeTnt.get()) {
            activeConservativeTnt = conservativeTnt.get();
            generation++;
            tracker.discardUnsent();
            clearBlastCache();
        }
        if (activeLiquidFiller != liquidFiller.get()) {
            activeLiquidFiller = liquidFiller.get();
            generation++;
            tracker.discardUnsent();
            dirty.set(true);
        }
        if (level != mc.level) {
            reset();
            level = mc.level;
            lastPosition = mc.player.position();
            lastGround = mc.player.onGround();
        }
        tick++;
        packetBudget.record(tick, sentPackets.getAndSet(0));
        plannedPackets = rotationsPlanned = 0; packetLimited = false;
        if (stickyRestore >= 0 && (autoSwitch.get() != AutoSwitch.Sticky || tick - stickyUsed > STICKY_HOLD_TICKS)) releaseSticky();
        facingYaw = mc.player.getYRot();
        clearPreview();
        drainUpdates();
        expirePending();

        if (corrected.getAndSet(false)) {
            generation++;
            corrections++;
            candidates.clear();
            // Keep sent attempts tracked, but discard every deferred interaction.
            tracker.setback();
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
            tracker.discardUnsent();
            jumpActive = true; jumpPlaced = false; jumpPending = null;
        }
        if (grounded || flying || !smartJump.get()) jumpActive = false;
        lastGround = grounded; lastPosition = position;
        if (pauseForHeldItem()) {
            generation++;
            tracker.discardUnsent();
            releaseSticky();
            status = "Holding pause item";
            return;
        }
        if (tick < pauseUntil || mc.player.isInWater() || !mc.player.isAlive()) { status = "Paused"; return; }
        if (!flightPlacementAllowed()) { strip.clear(); criticalStrip.clear(); status = "Flight coating off"; return; }
        if (spongeHasPriority()) {
            generation++;
            tracker.discardUnsent();
            status = "Waiting for Smart Sponge";
            return;
        }
        // Retry while clear, including the descending half of the jump. Follow the player horizontally.
        if (jumpActive && !jumpPlaced) {
            takeoffCell = BlockPos.containing(position.x, mc.player.getBoundingBox().minY - 1, position.z);
            Validation under = validate(takeoffCell, true, null);
            if (attempt(takeoffCell, true, under)) { status = "Jump priority"; return; }
            if (jumpPending != null && tracker.isPending(jumpPending)) { status = "Jump pending"; return; }
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

        if (reorder || queuedPriority != order.get() || tick % 5 == 0) {
            Comparator<BlockPos> sorter = Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(position));
            Comparator<BlockPos> coverageOrder = order.get() == Priority.Nearest ? sorter : sorter.reversed();
            if (tunnel.get()) coverageOrder = Comparator.<BlockPos, Boolean>comparing(pos -> !tunnelTarget(pos)).thenComparing(coverageOrder);
            searchQueue.clear();
            searchQueue.addAll(PlacementOrder.nearFirst(candidates, coverageOrder.thenComparingLong(BlockPos::asLong)));
            reorder = false; queuedPriority = order.get();
        }
        int limit = perTick.get();
        if (paced()) limit = Math.min(limit, pacer.budget(tick, rateCap(), tracker.pendingCount(), window()));
        // Without extra rotation packets, the tick's one rotation rides the ordinary movement packet.
        if (rotating() && !extraRotations.get()) limit = Math.min(limit, 1);
        if (!packetBudget.allows(tick, PacketBudget.placementCost(0, false, false), packetLimit.get())) { limit = 0; packetLimited = true; }
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
        readyCount = protectedCount = reachCount = coveredCount = planningCount = 0;
        rays = 0; searching = true;
        int checks = Math.min(128, searchQueue.size());
        for (int i = 0; i < checks && attempts < limit && rays < 256; i++) {
            BlockPos target = searchQueue.removeFirst();
            searchQueue.addLast(target);
            if (tracker.isPending(target) || visibilityRetry.getOrDefault(target,0) > tick) continue;
            Validation validation = validate(target, false, null);
            if (!validation.valid()) {
                if (validation.reason == Rejection.Protected || validation.reason == Rejection.Height) protectedCount++;
                if (validation.reason == Rejection.Reach) { reachCount++; visibilityRetry.put(target,tick+3); }
                if (validation.reason == Rejection.Covered) coveredCount++;
                if (validation.reason == Rejection.Planning) planningCount++;
                if (rejectedPreview.get() && invalidPreview.size() < 128) invalidPreview.add(target);
                continue;
            }
            readyCount++;
            if (validPreview.size() < 256) validPreview.add(target);
            if (attempts < limit && attempt(target, false, validation)) attempts++;
        }
        searching = false;
        int pps = packetBudget.perSecond(tick);
        status = packetLimited && attempts == 0 ? "Packet limit " + pps + "/" + packetLimit.get() + " pps"
            : attempts + " queued / " + tracker.pendingCount() + " pending / " + candidates.size() + " targets / " + pps + " pps";
        if (corrections > 0) status += " / " + corrections + " corrections";
        if (conservativeTnt.get() && activeBlock == Blocks.TNT) {
            status += " / " + coveredCount + " covered";
            if (planningCount > 0) status += " / calculating blasts";
        }
    }

    private FindItemResult findCoatingBlock() {
        if (pauseForHeldItem()) return new FindItemResult(-1, 0);
        if (autoSwitch.get() == AutoSwitch.Disabled) {
            if (mc.player.getOffhandItem().is(activeBlock.asItem())) return new FindItemResult(SlotUtils.OFFHAND, mc.player.getOffhandItem().getCount());
            if (mc.player.getMainHandItem().is(activeBlock.asItem())) return new FindItemResult(mc.player.getInventory().getSelectedSlot(), mc.player.getMainHandItem().getCount());
            return new FindItemResult(-1, 0);
        }
        return InvUtils.findInHotbar(activeBlock.asItem());
    }

    private boolean holdingPauseItem() {
        return mc.player != null && ((!mc.player.getMainHandItem().isEmpty()
            && pauseItems.get().contains(mc.player.getMainHandItem().getItem()))
            || (!mc.player.getOffhandItem().isEmpty()
            && pauseItems.get().contains(mc.player.getOffhandItem().getItem())));
    }

    private boolean pauseForHeldItem() {
        if (mc.player != null && mc.player.isUsingItem()) pauseItemUsed(mc.player.getUsedItemHand());
        return heldItemPause.paused(holdingPauseItem(), System.nanoTime(), pauseItemsTimeout.get() * 1_000_000_000L);
    }

    private void pauseItemUsed(InteractionHand hand) {
        if (coatingInteraction || mc.player == null || mc.level == null) return;
        var stack = mc.player.getItemInHand(hand);
        heldItemPause.itemUsed(pauseItemsResetOnUse.get(), !stack.isEmpty() && pauseItems.get().contains(stack.getItem()), System.nanoTime());
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
    private boolean rotating() { return rotate.get(); }

    private boolean clickable(BlockHitResult hit) {
        return BlockUtils.isClickable(mc.level.getBlockState(hit.getBlockPos()).getBlock());
    }

    /** Hotbar-switch packets a placement adds; none when the block is already held or in the offhand. */
    private int switchPackets(FindItemResult material) {
        if (material.isOffhand() || material.slot() == mc.player.getInventory().getSelectedSlot()) return 0;
        return switch (autoSwitch.get()) {
            case Silent -> 2;
            case Sticky, Normal -> 1;
            case Disabled -> 0;
        };
    }

    private void holdSticky(int slot) {
        var inventory = mc.player.getInventory();
        int selected = inventory.getSelectedSlot();
        if (selected != slot) {
            // A slot the player picks while the block is held becomes the one to return to.
            if (stickyRestore < 0 || selected != stickySlot) stickyRestore = selected;
            inventory.setSelectedSlot(slot);
            ((GameModeInvoker) mc.gameMode).comet$ensureHasSentCarriedItem();
        }
        stickySlot = slot;
        stickyUsed = tick;
    }

    /** Return to the slot Sticky switched away from, unless the player has since picked another. */
    private void releaseSticky() {
        if (stickyRestore >= 0 && mc.player != null && mc.gameMode != null
            && !pauseForHeldItem() && mc.player.getInventory().getSelectedSlot() == stickySlot) {
            mc.player.getInventory().setSelectedSlot(stickyRestore);
            ((GameModeInvoker) mc.gameMode).comet$ensureHasSentCarriedItem();
        }
        stickyRestore = stickySlot = -1;
    }

    private void drainUpdates() {
        Update update;
        while ((update = updates.poll()) != null) {
            blastDirty.set(true);
            changed.add(update.pos);
            visibilityRetry.remove(update.pos);
            tracker.update(update.pos, update.matchesBlock, update.replaceable, update.attempt, tick, retryDelay.get());
        }
    }

    private void expirePending() {
        int timeout = paced() ? pacer.timeout(confirmTimeout.get()) : confirmTimeout.get();
        tracker.expire(tick, timeout, retryDelay.get(), this::inRadius);
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
            boolean eligible = inRadius(pos) && candidateTarget(pos);
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
            if (candidateTarget(mutable)) candidates.add(mutable.immutable());
        }
        hitCache.keySet().retainAll(candidates);
        // A predicted TNT ghost remains untrusted until an authoritative update or chunk unload,
        // even after leaving the coating cube or disabling the saving toggle.
        tracker.pruneUnconfirmed(pos -> inRadius(pos) || (activeBlock == Blocks.TNT && loaded(pos)));
    }

    private boolean replaceable(BlockPos pos) {
        if (pos == null || mc.level.isOutsideBuildHeight(pos) || !loaded(pos)
            || !mc.level.getWorldBorder().isWithinBounds(pos)) return false;
        BlockState state = mc.level.getBlockState(pos);
        return !state.is(activeBlock) && state.canBeReplaced() && state.getFluidState().isEmpty();
    }

    private boolean liquidTarget(BlockPos pos) {
        return liquidFiller.get() && activeBlock == Blocks.TNT && pos != null
            && mc.level.getWorldBorder().isWithinBounds(pos)
            && LiquidFiller.isolatedWaterSource(pos, mc.level::isOutsideBuildHeight,
                // A predicted TNT ghost can hide a source still present on the server.
                neighbour -> loaded(neighbour) && !tracker.unconfirmed(neighbour) ? mc.level.getBlockState(neighbour) : null);
    }

    private boolean candidateTarget(BlockPos pos) {
        return liquidTarget(pos) || (replaceable(pos) && hasTerrainFace(pos));
    }

    private boolean hasTerrainFace(BlockPos target) {
        for (Direction side : Direction.values()) {
            if (enabledFace(side.getOpposite()) && terrain(target.relative(side))) return true;
        }
        return false;
    }

    /** Keep coverage separate from candidates: a failed or removed source must reopen its neighbours. */
    private boolean blastCovered(BlockPos target) {
        blastLimited = false;
        refreshBlastCache();
        Set<TntBlastPlanner.Cell> remaining = new HashSet<>();
        for (Direction side : Direction.values()) {
            BlockPos terrain = target.relative(side);
            if (enabledFace(side.getOpposite()) && terrain(terrain)) remaining.add(blastCell(terrain));
        }
        Set<TntBlastPlanner.Cell> uniqueSources = new HashSet<>(nearbyTnt);
        // Reservations prevent duplicate coverage within a batch, including deferred rotations.
        for (BlockPos source : tracker.pendingPositions()) uniqueSources.add(blastCell(source));
        List<TntBlastPlanner.Cell> sources = new ArrayList<>(uniqueSources);
        sources.removeIf(source -> Math.abs(source.x() - target.getX()) > TntBlastPlanner.RADIUS + 1
            || Math.abs(source.y() - target.getY()) > TntBlastPlanner.RADIUS + 1
            || Math.abs(source.z() - target.getZ()) > TntBlastPlanner.RADIUS + 1);
        sources.sort(Comparator.comparingDouble(source -> {
            double dx = source.x() - target.getX(), dy = source.y() - target.getY(), dz = source.z() - target.getZ();
            return dx * dx + dy * dy + dz * dz;
        }));
        return TntBlastPlanner.coveredByOthers(blastCell(target), remaining, sources, this::sourceCoverage);
    }

    private Set<TntBlastPlanner.Cell> sourceCoverage(TntBlastPlanner.Cell cell) {
        BlockPos source = new BlockPos(cell.x(), cell.y(), cell.z());
        PlacementTracker.Attempt<Block> pending = tracker.attempt(source);
        if (pending == null) {
            // Client prediction can leave a ghost after timeout; only trust confirmed world TNT.
            if (tracker.unconfirmed(source) || !loaded(source) || !mc.level.getBlockState(source).is(Blocks.TNT)
                || !mc.level.getBlockState(source).getFluidState().isEmpty()) return Set.of();
        } else if (pending.block != Blocks.TNT) return Set.of();
        Set<TntBlastPlanner.Cell> cached = blastCache.get(cell);
        if (cached != null) return cached;
        if (blastPredictionTick != tick) { blastPredictionTick = tick; blastPredictions = 0; }
        if (blastPredictions >= BLAST_PREDICTIONS_PER_TICK) { blastLimited = true; return Set.of(); }
        blastPredictions++;
        Set<TntBlastPlanner.Cell> coverage = TntBlastPlanner.coverage(cell,
            pos -> blastMaterials.computeIfAbsent(pos, this::blastMaterial));
        blastCache.put(cell, coverage);
        return coverage;
    }

    private static TntBlastPlanner.Cell blastCell(BlockPos pos) {
        return new TntBlastPlanner.Cell(pos.getX(), pos.getY(), pos.getZ());
    }

    private TntBlastPlanner.Material blastMaterial(TntBlastPlanner.Cell cell) {
        BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
        boolean chunkLoaded = loaded(pos);
        long chunk = ((long) (pos.getX() >> 4) << 32) | ((pos.getZ() >> 4) & 0xffffffffL);
        blastChunks.put(chunk, chunkLoaded);
        if (!chunkLoaded || mc.level.isOutsideBuildHeight(pos) || !mc.level.getWorldBorder().isWithinBounds(pos))
            return TntBlastPlanner.Material.BLOCKED;
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir() && state.getFluidState().isEmpty()) return TntBlastPlanner.Material.AIR;
        if (state.getDestroySpeed(mc.level, pos) < 0) return TntBlastPlanner.Material.BLOCKED;
        float resistance = Math.max(state.getBlock().getExplosionResistance(), state.getFluidState().getExplosionResistance());
        if (!Float.isFinite(resistance) || resistance < 0) return TntBlastPlanner.Material.BLOCKED;
        return new TntBlastPlanner.Material(resistance, terrain(pos));
    }

    private void refreshBlastCache() {
        BlockPos origin = mc.player.blockPosition();
        boolean invalidate = blastDirty.getAndSet(false) || !origin.equals(blastOrigin) || blastRadius != radius.get();
        if (!invalidate && tick - blastTick < 5) return;
        if (!invalidate) {
            for (var entry : blastChunks.entrySet()) {
                long chunk = entry.getKey();
                if (mc.level.hasChunk((int) (chunk >> 32), (int) chunk) != entry.getValue()) { invalidate = true; break; }
            }
        }
        // Periodic source discovery preserves completed predictions, so large jobs can span ticks.
        if (invalidate) { blastCache.clear(); blastMaterials.clear(); blastChunks.clear(); }
        nearbyTnt.clear();
        blastOrigin = origin.immutable(); blastRadius = radius.get(); blastTick = tick;
        int r = radius.get() + TntBlastPlanner.RADIUS + 1;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
            if (loaded(pos) && !mc.level.isOutsideBuildHeight(pos) && !tracker.unconfirmed(pos)
                && mc.level.getBlockState(pos).is(Blocks.TNT) && mc.level.getBlockState(pos).getFluidState().isEmpty())
                nearbyTnt.add(blastCell(pos));
        }
    }

    private boolean touchesWall(BlockPos target) {
        for (Direction side : Direction.values()) {
            if (side.getAxis().isHorizontal() && terrain(target.relative(side))) return true;
        }
        return false;
    }

    /** Filler can also use blast-resistant supports; ordinary coating still needs eligible terrain. */
    private boolean usableSupport(BlockPos pos, BlockState state, boolean liquid) {
        return loaded(pos) && !mc.level.isOutsideBuildHeight(pos)
            && mc.level.getWorldBorder().isWithinBounds(pos)
            && !state.canBeReplaced() && state.getFluidState().isEmpty()
            && !state.getCollisionShape(mc.level, pos).isEmpty()
            && (containerSupports.get() || !BlockUtils.isClickable(state.getBlock()))
            && (liquid || terrain(pos) || state.is(activeBlock));
    }

    private BlockHitResult supportHit(BlockPos target, boolean liquid) {
        Vec3 eye = mc.player.getEyePosition();
        List<Direction> sides = new ArrayList<>(List.of(Direction.values()));
        // Ordinary faces first: a container face costs two extra sneak-input packets.
        sides.sort(Comparator.comparing((Direction side) -> BlockUtils.isClickable(mc.level.getBlockState(target.relative(side)).getBlock()))
            .thenComparingDouble(side -> Vec3.atCenterOf(target.relative(side)).distanceToSqr(eye)));
        for (Direction side : sides) {
            BlockPos support = target.relative(side);
            if (!loaded(support) || (paced() && tracker.unconfirmed(support))) continue;
            BlockState state = mc.level.getBlockState(support);
            if (!usableSupport(support, state, liquid)) continue;
            Direction face = side.getOpposite();
            int axis = face.getAxis() == Direction.Axis.X ? 0 : face.getAxis() == Direction.Axis.Y ? 1 : 2;
            for (AABB localBox : state.getCollisionShape(mc.level, support).toAabbs()) {
                AABB box = localBox.move(support.getX(), support.getY(), support.getZ());
                var bounds = new PlacementGeometry.Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
                for (var sample : PlacementGeometry.faceSamples(bounds, axis, face.getAxisDirection().getStep(),
                    new PlacementGeometry.Point(eye.x, eye.y, eye.z), narrowGaps.get())) {
                    BlockHitResult hit = new BlockHitResult(new Vec3(sample.x(), sample.y(), sample.z()), face, support, false);
                    if (searching && rays >= 256) return null;
                    if (visibleHit(target, hit, liquid)) return hit;
                }
            }
        }
        return null;
    }

    private boolean visibleHit(BlockPos target, BlockHitResult hit, boolean liquid) {
        if (paced() && tracker.unconfirmed(hit.getBlockPos())) return false;
        if (!hit.getBlockPos().relative(hit.getDirection()).equals(target)) return false;
        BlockState support = mc.level.getBlockState(hit.getBlockPos());
        if (!usableSupport(hit.getBlockPos(), support, liquid)) return false;
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

    private Validation reject(Rejection reason) { return new Validation(reason, null, false); }

    private Validation validate(BlockPos target, boolean underfoot, BlockHitResult preferred) {
        if (pauseForHeldItem() || !flightPlacementAllowed()) return reject(Rejection.Protected);
        if (!CoatingRules.tunnelAllows(tunnel.get(), underfoot, tunnelTarget(target))) return reject(Rejection.Protected);
        boolean liquid = !underfoot && liquidTarget(target);
        if ((!liquid && !replaceable(target)) || !inRadius(target)) return reject(Rejection.Occupied);
        if (tracker.retryBlocked(target, tick)) return reject(Rejection.Retry);
        // Unpowered circuits can react to placement; re-read the whole buffer on deferred sends too.
        if (!TntRedstoneSafety.canPlace(activeBlock == Blocks.TNT, target, mc.level::isOutsideBuildHeight,
            pos -> loaded(pos) ? mc.level.getBlockState(pos) : null, mc.level::hasNeighborSignal))
            return reject(Rejection.Redstone);
        boolean platform = !underfoot && scaffoldTarget(target);
        if (!underfoot && !platform && !liquid && !hasTerrainFace(target)) return reject(Rejection.NoTerrain);
        AABB block = new AABB(target);
        if (underfoot) {
            if (!jumpActive || jumpPlaced || flying() || mc.player.getBoundingBox().minY < target.getY() + 1.0
                || !mc.player.getBoundingBox().inflate(0.03, 0, 0.03).move(0, -1, 0).intersects(block)
                || mc.player.getBoundingBox().intersects(block)) return reject(Rejection.Protected);
        } else {
            int feetY = mc.player.blockPosition().getY();
            if (!platform && !liquid && !tunnelTarget(target) && (target.getY() == feetY || target.getY() == feetY + 1)
                && !CoatingRules.heightAllowed(target.getY(), feetY, touchesWall(target))) return reject(Rejection.Height);
            if (mc.player.getBoundingBox().inflate(0.08, 0, 0.08).intersects(block)) return reject(Rejection.Protected);
        }
        if (!BlockUtils.canPlaceBlock(target, true, activeBlock)) return reject(Rejection.Entity);
        if (conservativeTnt.get() && activeBlock == Blocks.TNT && !underfoot && !platform && !liquid) {
            if (blastCovered(target)) return reject(Rejection.Covered);
            // Incomplete coverage must defer placement rather than spend TNT on an unknown result.
            if (blastLimited) return reject(Rejection.Planning);
        }
        BlockHitResult hit;
        if (preferred != null) hit = visibleHit(target, preferred, liquid) ? preferred : null;
        else {
            BlockHitResult cached = hitCache.get(target);
            // Search again past a cached container face in case an ordinary face is now usable.
            hit = cached != null && !clickable(cached) && visibleHit(target, cached, liquid) ? cached : supportHit(target, liquid);
            if (hit != null) hitCache.put(target, hit); else hitCache.remove(target);
        }
        return hit == null ? reject(Rejection.Reach) : new Validation(Rejection.None, hit, liquid);
    }

    private boolean validateForSend(BlockPos target, boolean underfoot, BlockHitResult hit, boolean liquid) {
        boolean previous = searching;
        searching = false;
        try {
            Validation validation = validate(target, underfoot, hit);
            return validation.valid() && validation.liquid == liquid;
        }
        finally { searching = previous; }
    }

    private boolean attempt(BlockPos target, boolean underfoot, Validation validation) {
        if (pauseForHeldItem() || spongeHasPriority() || tracker.isPending(target) || !validation.valid()) return false;
        FindItemResult planned = findCoatingBlock();
        if (!planned.found()) return false;
        // Keep one outstanding slot available for the footpath when coating alongside scaffold.
        int reservationWindow = scaffold.get() && !flying() && !underfoot && !scaffoldTarget(target)
            ? Math.max(1, window() - 1) : window();
        if (paced() && pacer.budget(tick, rateCap(), tracker.pendingCount(), reservationWindow) <= 0) return false;
        boolean extraRotation = rotating() && rotationsPlanned > 0;
        if (extraRotation && !extraRotations.get()) return false;
        BlockHitResult hit = validation.hit;
        int cost = PacketBudget.placementCost(switchPackets(planned), extraRotation, clickable(hit));
        if (!packetBudget.allows(tick, plannedPackets + cost, packetLimit.get())) { packetLimited = true; return false; }
        plannedPackets += cost;
        if (rotating()) rotationsPlanned++;
        PlacementTracker.Attempt<Block> reservation = tracker.reserve(target, tick, activeBlock);
        if (underfoot) jumpPending = target;
        int token = generation;
        int scheduledTick = tick;
        boolean scheduledConservativeTnt = conservativeTnt.get();
        boolean scheduledLiquidFiller = liquidFiller.get();
        Runnable interact = () -> {
            // Rotations can defer execution. Recheck everything against live player/world state.
            if (!isActive() || mc.player == null || mc.level != level || mc.gameMode == null
                || pauseForHeldItem() || spongeHasPriority() || token != generation || coatingBlock.get() != reservation.block
                || conservativeTnt.get() != scheduledConservativeTnt || corrected.get() || tick - scheduledTick > 1
                || liquidFiller.get() != scheduledLiquidFiller
                || !tracker.current(target, reservation)) {
                tracker.cancel(target, reservation);
                return;
            }
            if (tick < pauseUntil || !validateForSend(target, underfoot, hit, validation.liquid)) { tracker.cancel(target, reservation); return; }
            if (!underfoot && jumpActive && !jumpPlaced) {
                tracker.cancel(target, reservation);
                return;
            }
            FindItemResult material = findCoatingBlock();
            if (!material.found()) { tracker.cancel(target, reservation); return; }
            long now = mc.level.getGameTime();
            if (interactionTime != now) { interactionTime = now; interactions = 0; }
            int liveLimit = underfoot ? 1 : (perTick.get());
            if (interactions >= liveLimit) { tracker.cancel(target, reservation); return; }
            if (paced() && pacer.budget(tick, rateCap(), tracker.pendingCount() - 1, window()) <= 0) { tracker.cancel(target, reservation); return; }
            interactions++;
            pacer.sent(tick);
            boolean offhand = material.isOffhand();
            AutoSwitch mode = autoSwitch.get();
            boolean swapBack = mode == AutoSwitch.Silent;
            if (!offhand && mode == AutoSwitch.Sticky) holdSticky(material.slot());
            else if (!offhand && mode != AutoSwitch.Disabled) InvUtils.swap(material.slot(), swapBack);
            try {
                if (underfoot) jumpPlaced = true;
                tracker.markSent(target, reservation, tick);
                coatingInteraction = true;
                try {
                    interactWithSupport(hit, offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
                } finally {
                    coatingInteraction = false;
                }
            } finally {
                if (!offhand && swapBack) InvUtils.swapBack();
            }
        };
        if (rotating()) Rotations.rotate(Rotations.getYaw(hit.getLocation()), Rotations.getPitch(hit.getLocation()), underfoot ? 100 : criticalStrip.contains(target) ? 80 : 20, interact);
        else interact.run();
        return true;
    }

    /** Sneak-use containers so they act as support instead of opening their screen. */
    private void interactWithSupport(BlockHitResult hit, InteractionHand hand) {
        if (!clickable(hit)) {
            BlockUtils.interact(hit, hand, true);
            return;
        }
        if (!containerSupports.get()) return;
        Input original = mc.player.input.keyPresses;
        var connection = mc.getConnection();
        if (connection == null) return;
        // In 26.1.2 shift state is carried by the ordinary player input packet.
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
        for (BlockPos pos : tracker.pendingPositions()) event.renderer.box(pos, amber, amberLine, ShapeMode.Both, 0);
        if (rejectedPreview.get()) for (BlockPos pos : invalidPreview) event.renderer.box(pos, red, redLine, ShapeMode.Both, 0);
    }
}

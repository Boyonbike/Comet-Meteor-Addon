package dev.tntcoating.modules;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.PacketBudget;
import dev.tntcoating.core.scaffold.ScaffoldCoordinator;
import dev.tntcoating.core.scaffold.ScaffoldAdapter;
import dev.tntcoating.core.scaffold.ScaffoldDiagnostics;
import dev.tntcoating.core.scaffold.ScaffoldEdgeGuard;
import dev.tntcoating.core.scaffold.ScaffoldPlanner;
import dev.tntcoating.core.scaffold.ScaffoldRotationBridge;
import dev.tntcoating.core.scaffold.ScaffoldRuntime;
import dev.tntcoating.mixin.GameModeInvoker;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLongArray;

/** Independent scaffold: planning never sends packets and only applied server state confirms support. */
public final class CometScaffold extends Module {
    public enum Height { Preset, FollowFeet, Activation, Absolute, AboveHead }
    public enum Switching { Sticky, Normal, Silent, Disabled }

    private static final Gson JSON = new Gson();
    private final SettingGroup general = settings.getDefaultGroup();
    private final SettingGroup materials = settings.createGroup("Materials");
    private final SettingGroup movement = settings.createGroup("Movement");
    private final SettingGroup network = settings.createGroup("6b6t Timing");
    private final SettingGroup display = settings.createGroup("Preview and Diagnostics");
    private final Setting<ScaffoldPlanner.Preset> preset = general.add(new EnumSetting.Builder<ScaffoldPlanner.Preset>()
        .name("preset").description("Wide fills the reachable strip freely; Narrow spends capacity on sprinting and sprint-jump support.")
        .defaultValue(ScaffoldPlanner.Preset.Narrow).build());
    private final Profile wide = new Profile("Wide", 200, 0);
    private final Profile narrow = new Profile("Narrow", 1_000, .03);
    private final Setting<Switching> switching = materials.add(new EnumSetting.Builder<Switching>().name("switching")
        .description("Sticky saves slot packets; Silent restores after each interaction; Disabled only uses held blocks.")
        .defaultValue(Switching.Sticky).build());
    private final Setting<List<Block>> allowed = materials.add(new BlockListSetting.Builder().name("allowed-blocks")
        .description("Empty allows every eligible building block.").build());
    private final Setting<List<Block>> denied = materials.add(new BlockListSetting.Builder().name("denied-blocks")
        .description("Never automatically use these blocks.").build());
    private final Setting<Boolean> fullBlocks = bool(materials, "full-blocks-only", "Use full, non-falling building blocks.", true);
    private final Setting<Boolean> edgeProtection = bool(movement, "edge-protection", "Hold the immediate footpath when credible support is missing; Wide does not hold for outer coverage.", true);
    private final Setting<Boolean> tower = bool(movement, "tower", "Use ordinary jumps while holding Jump. Fixed planes suppress tower.", false);
    private final Setting<Boolean> autoJump = bool(movement, "auto-jump", "Jump at grounded horizontal obstructions when support is available.", false);
    private final Setting<Boolean> edgeSneak = bool(movement, "edge-sneak", "Sneak when edge protection has to hold movement.", false);
    private final Setting<Keybind> downKey = movement.add(new KeybindSetting.Builder().name("downward-key")
        .description("Hold to target one block below the selected floor plane.").defaultValue(Keybind.none()).build());
    private final Setting<Integer> packetCap = integer(network, "max-packets-per-second", "Real-time ceiling over all observed connection sends; Scaffold withholds its own traffic.", 150, 40, 800);
    private final Setting<Integer> interval = integer(network, "minimum-interval-ms", "Minimum elapsed time between interactions. No catch-up credit or extra rotation packets.", 50, 50, 1_000);
    private final Setting<Boolean> containers = bool(network, "container-supports", "Use sequenced sneak interactions on interactive supports; costs two additional input packets.", false);
    private final Setting<Boolean> airPlace = bool(network, "air-place", "Explicit synthetic target interaction; lacks support-face verification and still needs server confirmation.", false);
    private final Setting<Boolean> preview = bool(display, "preview", "Show the selected target and pending interactions.", true);
    private final Setting<Boolean> detailedPreview = bool(display, "detailed-preview", "Also show bounded corridor targets and chosen support face.", false);
    private final Setting<ScaffoldDiagnostics.Mode> logging = display.add(new EnumSetting.Builder<ScaffoldDiagnostics.Mode>()
        .name("logging").description("Summary records success and failures; Trace adds per-attempt and packet history.")
        .defaultValue(ScaffoldDiagnostics.Mode.Summary).build());

    private final ScaffoldPlanner planner = new ScaffoldPlanner();
    private final ScaffoldPlanner layerPlanner = new ScaffoldPlanner();
    private final ScaffoldAdapter.Layers layers = new ScaffoldAdapter.Layers();
    private final ScaffoldRuntime runtime = new ScaffoldRuntime(System::nanoTime);
    private final StickySlot sticky = new StickySlot();
    private final CometScaffoldPlacement placement = new CometScaffoldPlacement();
    private final ThreadLocal<ScaffoldRuntime.Attempt> dispatch = new ThreadLocal<>();
    private final AtomicLongArray packetCounts = new AtomicLongArray(6);
    private final AtomicLongArray ownPacketCounts = new AtomicLongArray(6);
    private final Map<Long, CometScaffoldPlacement.Hit> pendingHits = new LinkedHashMap<>();
    private volatile ScaffoldDiagnostics diagnostics;
    private ScaffoldDiagnostics.Mode activeLogging;
    private ScaffoldPlanner.Plan plan;
    private CometScaffoldPlacement.Hit selected;
    private ClientLevel world;
    private long generation, tick, lastInteractionTick = -1, holds, deadlineMisses;
    private int groundY, activationY, preferredSlot = -1;
    private final int[] scanCursors = new int[3];
    private boolean planeReady, quarantineOverflow;
    private String fingerprint = "", status = "Idle", loggerWarning;
    private Vec3 lastPosition;
    private double directionX, directionZ = 1;
    private ScaffoldPlanner.Preset activePreset = ScaffoldPlanner.Preset.Narrow;

    private final class Profile {
        final Setting<Height> height;
        final Setting<Integer> y, offset, layers, horizon;
        final Setting<Boolean> layersUp;
        final Setting<Double> reachMargin;
        Profile(String label, int horizonMs, double margin) {
            SettingGroup group = settings.createGroup(label + " Options", false);
            height = group.add(new EnumSetting.Builder<Height>().name(label.toLowerCase(Locale.ROOT) + "-height")
                .description("Preset locks Wide when selected; Narrow holds grounded height through normal jumps.")
                .defaultValue(Height.Preset).build());
            y = integer(group, "absolute-y", "Target block Y for Absolute height.", 63, -64, 319);
            offset = integer(group, "vertical-offset", "Explicit offset from the selected target plane.", 0, -3, 3);
            layers = integer(group, "layers", "Explicit additional vertical building layers; does not widen Narrow.", 1, 1, 3);
            layersUp = bool(group, "layers-upward", "Expand layers above the plane instead of below it.", false);
            horizon = integer(group, "lookahead-limit-ms", "Prediction limit; Narrow also accounts for measured confirmation latency.", horizonMs, 50, 1_000);
            reachMargin = group.add(new DoubleSetting.Builder().name("reach-margin")
                .description("Subtract from actual interaction reach. Wide defaults to the full reachable contact-face range.")
                .defaultValue(margin).range(0, .5).build());
        }
    }

    public CometScaffold() {
        super(TntCoatingAddon.CATEGORY, "comet-scaffold", "Independent Wide coverage and Narrow sprint scaffold with confirmed supports and real-time packet pacing.");
    }

    @Override public String getInfoString() { return preset.get() + " | " + status; }
    @Override public void onActivate() {
        invalidateUnsent("activated");
        planner.reset(); layerPlanner.reset(); layers.reset(); placement.reset(); planeReady = false; lastPosition = null;
        fingerprint = ""; loggerWarning = null;
        ensureDiagnostics();
    }
    @Override public void onDeactivate() {
        invalidateUnsent("disabled"); releaseSticky(); selected = null; plan = null;
        if (diagnostics != null) diagnostics.close(summary());
        diagnostics = null;
    }

    /** Called by the permanent observer even when this module is disabled. */
    public void maintainConnection() {
        tick++;
        if (world != mc.level) disconnected();
        runtime.maintain();
        runtime.setPacketCap(packetCap.get());
        runtime.setMinIntervalNanos(interval.get() * 1_000_000L);
        if (isActive()) {
            ensureDiagnostics();
            drainEvents();
            if (diagnostics != null) {
                diagnostics.summary(summary());
                String problem = diagnostics.problem();
                if (problem != null && !problem.equals(loggerWarning)) {
                    loggerWarning = problem;
                    warning("Scaffold diagnostics: %s", problem);
                }
            }
            if (sticky.holding() && (switching.get() != Switching.Sticky || sticky.idle((int) tick))) releaseSticky();
        } else runtime.drainEvents();
    }

    /** Connection Sent hook: this observes local sends, not delivery or server acceptance. */
    public void observePacket(Packet<?> packet) {
        int category = packetCategory(packet);
        packetCounts.incrementAndGet(category);
        ScaffoldRuntime.Attempt owner = dispatch.get();
        if (owner != null) ownPacketCounts.incrementAndGet(category);
        ScaffoldRuntime.PacketKind kind = switch (category) {
            case 0 -> ScaffoldRuntime.PacketKind.USE_ITEM;
            case 1 -> ScaffoldRuntime.PacketKind.SWING;
            case 2 -> ScaffoldRuntime.PacketKind.SLOT;
            case 3 -> ScaffoldRuntime.PacketKind.MOVEMENT;
            default -> ScaffoldRuntime.PacketKind.OTHER;
        };
        runtime.observePacket(owner, kind);
        if (owner != null && category == 0) lastInteractionTick = tick;
        ScaffoldDiagnostics log = diagnostics;
        if (log != null) {
            JsonObject sample = new JsonObject(); sample.addProperty("category", categoryName(category)); sample.addProperty("attributed", owner != null);
            if (owner != null) sample.addProperty("attempt", owner.id());
            if (packet instanceof ServerboundMovePlayerPacket move) {
                if (move.hasPosition()) { sample.addProperty("x", move.getX(0)); sample.addProperty("y", move.getY(0)); sample.addProperty("z", move.getZ(0)); }
                if (move.hasRotation()) { sample.addProperty("yaw", move.getYRot(0)); sample.addProperty("pitch", move.getXRot(0)); }
                sample.addProperty("ground", move.isOnGround());
                log.sample("movement", sample);
            } else log.sample("packet", sample);
        }
    }

    public void authoritative(BlockPos pos, BlockState state) {
        runtime.authoritative(pos.asLong(), mc.level != null && mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
            && CometScaffoldPlacement.usableSupport(pos, state), state.canBeReplaced());
    }
    public void setback() {
        invalidateUnsent("correction"); planner.reset(); layerPlanner.reset(); layers.reset(); placement.reset(); lastPosition = null;
        runtime.correction();
        if (diagnostics != null && mc.player != null) {
            JsonObject data = summary(); data.addProperty("x", mc.player.getX()); data.addProperty("y", mc.player.getY()); data.addProperty("z", mc.player.getZ());
            diagnostics.event("correction", data);
        }
    }
    public void disconnected() {
        invalidateUnsent("world changed"); sticky.release(() -> false);
        if (diagnostics != null) diagnostics.close(summary());
        diagnostics = null; world = mc.level;
        runtime.resetWorld(); planner.reset(); layerPlanner.reset(); layers.reset(); placement.reset(); pendingHits.clear();
        quarantineOverflow = false; planeReady = false; lastPosition = null; plan = null; selected = null;
        preferredSlot = -1; lastInteractionTick = -1;
        for (int i = 0; i < 6; i++) { packetCounts.set(i, 0); ownPacketCounts.set(i, 0); }
    }
    public void invalidateUnsent(String reason) {
        generation++; runtime.invalidateUnsent(reason); ScaffoldRotationBridge.INSTANCE.cancel(); selected = null;
    }
    public void quarantinePrediction(BlockPos pos) {
        if (!runtime.markQuarantined(pos.asLong())) quarantineOverflow = true;
    }
    public boolean knownUnconfirmed(BlockPos pos) { return quarantineOverflow || runtime.knownUnconfirmed(pos.asLong()); }

    @EventHandler private void tick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) { status = "Waiting for world"; return; }
        if (mc.screen != null || mc.player.isUsingItem()) {
            invalidateUnsent("screen or manual item use"); status = "Paused for screen/item use";
            lastPosition = mc.player.position(); return;
        }
        Profile profile = profile();
        String configured = fingerprint();
        if (!fingerprint.equals(configured)) {
            invalidateUnsent("settings or preset changed"); planner.reset(); layerPlanner.reset(); layers.reset(); placement.reset(); Arrays.fill(scanCursors, 0);
            if (activePreset != preset.get() || !planeReady) { activationY = floorY(); groundY = activationY; planeReady = true; }
            activePreset = preset.get(); fingerprint = configured;
            if (diagnostics != null) diagnostics.event("settings", settingsSnapshot());
        }
        if (!planeReady) { activationY = groundY = floorY(); planeReady = true; }
        if (mc.player.onGround()) groundY = floorY();
        if (!mc.player.isAlive() || mc.player.isPassenger() || mc.player.isFallFlying() || mc.player.getAbilities().flying || mc.player.isInWater()) {
            invalidateUnsent("movement not supported"); status = "Paused movement state"; lastPosition = mc.player.position(); return;
        }
        if (quarantineOverflow) { status = "Prediction quarantine full; waiting for world refresh"; return; }
        if (!ScaffoldRotationBridge.INSTANCE.available()) { status = "Rotation compatibility unavailable"; return; }

        Vec3 velocity = mc.player.getDeltaMovement();
        Vec3 position = mc.player.position();
        Vec3 measured = lastPosition == null ? velocity : position.subtract(lastPosition);
        lastPosition = position;
        Vec3 actual = velocity.horizontalDistanceSqr() >= measured.horizontalDistanceSqr() ? velocity : measured;
        double speed = Math.min(1, actual.horizontalDistance());
        Input keys = mc.player.input.keyPresses;
        double f = (keys.forward() ? 1 : 0) - (keys.backward() ? 1 : 0);
        double s = (keys.left() ? 1 : 0) - (keys.right() ? 1 : 0);
        if (f != 0 || s != 0) {
            double yaw = Math.toRadians(mc.player.getYRot());
            double x = -Math.sin(yaw) * f + Math.cos(yaw) * s;
            double z = Math.cos(yaw) * f + Math.sin(yaw) * s;
            double length = Math.hypot(x, z); directionX = x / length; directionZ = z / length;
            speed = Math.max(.1, speed);
        } else if (speed > .01) {
            directionX = actual.x / Math.max(.00001, actual.horizontalDistance());
            directionZ = actual.z / Math.max(.00001, actual.horizontalDistance());
        }
        long lookahead = (long) Math.min(profile.horizon.get() * 1_000_000L,
            Math.max(150_000_000L, (runtime.stats().latencyMillis() + interval.get() + 50) * 1_000_000));
        double actualLength = actual.horizontalDistance();
        double vx = actualLength > .01 ? actual.x / actualLength * speed : speed * directionX;
        double vz = actualLength > .01 ? actual.z / actualLength * speed : speed * directionZ;
        lookahead = clippedHorizon(vx, vz, lookahead);
        int y = selectedY(profile);
        var snapshot = ScaffoldAdapter.snapshot(position.x, position.z, mc.player.getEyeY(), y,
            vx, vz, directionX, directionZ, mc.player.getBbWidth() / 2.0, mc.player.blockInteractionRange(), System.nanoTime(), lookahead);
        plan = planner.plan(preset.get(), snapshot, cell -> credible(new BlockPos(cell.x(), cell.y(), cell.z())));
        if (profile.layers.get() > 1) {
            var desiredLayers = layerPlanner.plan(preset.get(), snapshot, cell -> false).candidates();
            if (preset.get() == ScaffoldPlanner.Preset.Narrow) layers.replace(desiredLayers, y, this::inEnvelope);
            else layers.remember(desiredLayers, y, this::inEnvelope);
        }
        if (autoJump.get() && mc.player.onGround() && mc.player.horizontalCollision && !heightLocked(profile)) mc.player.jumpFromGround();
        if (tower.get() && mc.options.keyJump.isDown() && mc.player.onGround() && !heightLocked(profile)
            && mc.level.noCollision(mc.player, mc.player.getBoundingBox().move(0, 1, 0))) mc.player.jumpFromGround();
        if (lastInteractionTick == tick || System.nanoTime() < runtime.nextEligibleNanos()) { status = "Pacing / " + runtime.pendingCount() + " pending"; return; }
        var material = placement.material(allowed.get(), denied.get(), fullBlocks.get(), switching.get() != Switching.Disabled, preferredSlot);
        if (material == null) { status = "Waiting for eligible held/hotbar blocks"; return; }
        chooseAndPrepare(material, profile);
    }

    private void chooseAndPrepare(CometScaffoldPlacement.Material material, Profile profile) {
        List<ScaffoldPlanner.Candidate> candidates = layers.expand(plan.candidates(), selectedY(profile), profile.layers.get(), profile.layersUp.get(), this::inEnvelope);
        if (candidates.isEmpty()) { selected = null; status = coverageStatus("Complete"); return; }
        Set<BlockPos> visited = new HashSet<>();
        int usedRays = 0;
        for (int phase = 0; phase < 3; phase++) {
            final int tier = phase;
            List<ScaffoldPlanner.Candidate> current = candidates.stream()
                .filter(c -> tier == 2 ? c.tier() >= 2 : c.tier() == tier).toList();
            var rays = new CometScaffoldPlacement.Rays(ScaffoldAdapter.searchBudget(phase, usedRays));
            int start = scanCursors[phase];
            for (int index : ScaffoldAdapter.scanOrder(current.size(), start)) {
                var candidate = current.get(index);
                scanCursors[phase] = index + 1;
                {
                    var cell = candidate.cell();
                    BlockPos target = new BlockPos(cell.x(), cell.y(), cell.z());
                    if (!visited.add(target) || runtime.pending(target.asLong()) || !runtime.retryReady(target.asLong()) || credible(target)) continue;
                    var hit = placement.resolve(target, material, rays, airPlace.get(), containers.get(), profile.reachMargin.get(), ScaffoldCoordinator::knownUnconfirmed);
                    if (hit != null) {
                        boolean urgent = candidate.tier() <= 1;
                        int switches = material.hand() == InteractionHand.OFF_HAND || material.slot() == mc.player.getInventory().getSelectedSlot()
                            ? 0 : switching.get() == Switching.Silent ? 2 : 1;
                        var attempt = runtime.reserve(target.asLong(), urgent, generation, PacketBudget.placementCost(switches, false, hit.container()));
                        if (attempt == null) { status = coverageStatus("Packet/pending/retry pacing"); return; }
                        prepare(attempt, hit, candidate.deadlineNanos()); return;
                    }
                    if (rays.remaining() == 0) break;
                }
                if (rays.remaining() == 0) break;
            }
            usedRays += rays.used();
        }
        selected = null; status = coverageStatus("Waiting for confirmed visible support");
    }

    private void prepare(ScaffoldRuntime.Attempt attempt, CometScaffoldPlacement.Hit hit, long deadline) {
        var context = new ScaffoldAdapter.DispatchContext(generation, tick, mc.level, mc.player,
            mc.player.getInventory().getSelectedSlot(), fingerprint());
        selected = hit;
        pendingHits.put(attempt.id(), hit);
        while (pendingHits.size() > 256) pendingHits.remove(pendingHits.keySet().iterator().next());
        boolean prepared = ScaffoldRotationBridge.INSTANCE.prepare(hit.yaw(), hit.pitch(),
            () -> {
                boolean valid = contextCurrent(context);
                if (!valid) runtime.cancel(attempt, "settings, slot or movement context changed");
                return valid;
            }, () -> interact(attempt, hit, context, deadline));
        if (!prepared) { runtime.cancel(attempt, "rotation unavailable"); status = "Rotation busy"; }
        else status = coverageStatus("Prepared");
    }

    private boolean contextCurrent(ScaffoldAdapter.DispatchContext context) {
        return isActive() && mc.player != null && mc.gameMode != null && mc.screen == null && !mc.player.isUsingItem()
            && context.current(generation, tick, mc.level, mc.player, mc.player.getInventory().getSelectedSlot(), fingerprint());
    }

    private void interact(ScaffoldRuntime.Attempt attempt, CometScaffoldPlacement.Hit hit,
                          ScaffoldAdapter.DispatchContext context, long deadline) {
        if (!contextCurrent(context) || lastInteractionTick == tick || !ScaffoldRotationBridge.INSTANCE.orientationMatches(hit.yaw(), hit.pitch())) {
            runtime.cancel(attempt, "live context or observed orientation changed"); return;
        }
        var ownedPlayer = mc.player;
        float visualYaw = ownedPlayer.getYRot(), visualPitch = ownedPlayer.getXRot();
        try {
            // Context and vanilla local prediction must see the same aim the server received.
            ownedPlayer.setYRot(hit.yaw()); ownedPlayer.setXRot(hit.pitch());
            interactAimed(attempt, hit, context, deadline);
        } finally { ownedPlayer.setYRot(visualYaw); ownedPlayer.setXRot(visualPitch); }
    }

    private void interactAimed(ScaffoldRuntime.Attempt attempt, CometScaffoldPlacement.Hit hit,
                               ScaffoldAdapter.DispatchContext context, long deadline) {
        ClientLevel expectedWorld = (ClientLevel) context.world();
        Profile profile = profile();
        if (!placement.verify(hit, new CometScaffoldPlacement.Rays(2), containers.get(), profile.reachMargin.get(), ScaffoldCoordinator::knownUnconfirmed)) {
            runtime.cancel(attempt, "live context/face/material invalid"); return;
        }
        if (switching.get() == Switching.Disabled && hit.material().hand() == InteractionHand.MAIN_HAND
            && mc.player.getInventory().getSelectedSlot() != hit.material().slot()) {
            runtime.cancel(attempt, "manual slot changed"); return;
        }
        if (!runtime.beginDispatch(attempt)) { runtime.cancel(attempt, "live packet/pacing limit"); return; }
        if (!ScaffoldCoordinator.prediction(hit.target())) { runtime.cancel(attempt, "prediction quarantine capacity"); return; }
        if (System.nanoTime() > deadline && preset.get() == ScaffoldPlanner.Preset.Narrow) deadlineMisses++;
        int original = mc.player.getInventory().getSelectedSlot();
        boolean offhand = hit.material().hand() == InteractionHand.OFF_HAND;
        boolean swapped = !offhand && original != hit.material().slot();
        dispatch.set(attempt);
        try {
            if (!offhand) {
                if (switching.get() == Switching.Sticky) sticky.hold(hit.material().slot(), (int) tick);
                else if (switching.get() != Switching.Disabled && swapped) {
                    mc.player.getInventory().setSelectedSlot(hit.material().slot());
                    ((GameModeInvoker) mc.gameMode).comet$ensureHasSentCarriedItem();
                }
            }
            preferredSlot = hit.material().slot();
            ItemStack actualStack = mc.player.getItemInHand(hit.material().hand());
            if (actualStack.isEmpty() || actualStack.getItem() != hit.material().item()
                || !offhand && mc.player.getInventory().getSelectedSlot() != hit.material().slot()
                || generation != context.generation() || !context.settings().equals(fingerprint())) {
                runtime.cancel(attempt, "material changed during swap"); return;
            }
            if (hit.container()) BlockCoatInteraction.use(hit.hit(), hit.material().hand(), true);
            else {
                mc.gameMode.useItemOn(mc.player, hit.material().hand(), hit.hit());
                mc.player.swing(hit.material().hand());
            }
        } finally {
            try {
                if (swapped && switching.get() == Switching.Silent && mc.level == expectedWorld && mc.player != null && mc.gameMode != null
                    && mc.player.getInventory().getSelectedSlot() == hit.material().slot()) {
                    mc.player.getInventory().setSelectedSlot(original);
                    ((GameModeInvoker) mc.gameMode).comet$ensureHasSentCarriedItem();
                }
            } finally { runtime.finishDispatch(attempt); dispatch.remove(); }
        }
        if (lastInteractionTick == tick) planner.placed(new ScaffoldPlanner.Cell(hit.target().getX(), hit.target().getY(), hit.target().getZ()));
    }

    @EventHandler(priority = -10000) private void move(PlayerMoveEvent event) {
        if (!edgeProtection.get() || mc.player == null || mc.level == null || !mc.player.onGround()
            || mc.player.isPassenger() || mc.player.isFallFlying() || mc.player.getAbilities().flying || mc.player.isInWater()) return;
        Vec3 motion = event.movement;
        if (motion.horizontalDistanceSqr() < 1e-8) return;
        AABB body = mc.player.getBoundingBox();
        var clipped = ScaffoldEdgeGuard.clip(motion.x, motion.z,
            (x, z) -> CometScaffoldPlacement.supportBelow(body.move(x, 0, z), ScaffoldCoordinator::knownUnconfirmed)
                || downKey.get().isPressed() && CometScaffoldPlacement.supportBelow(body.move(x, -1, z), ScaffoldCoordinator::knownUnconfirmed));
        if (clipped.x() != motion.x || clipped.z() != motion.z) {
            event.movement = new Vec3(clipped.x(), motion.y, clipped.z()); holds++;
            if (edgeSneak.get()) {
                Input keys = mc.player.input.keyPresses;
                mc.player.input.keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), true, keys.sprint());
            }
        }
    }

    @EventHandler private void render(Render3DEvent event) {
        if (!preview.get()) return;
        if (selected != null) event.renderer.box(selected.target(), new Color(60, 220, 100, 35), new Color(60, 220, 100), ShapeMode.Both, 0);
        int shown = 0;
        for (var hit : pendingHits.values()) if (runtime.pending(hit.target().asLong()) && shown++ < 6)
            event.renderer.box(hit.target(), new Color(240, 180, 40, 35), new Color(240, 180, 40), ShapeMode.Both, 0);
        if (detailedPreview.get() && plan != null) {
            shown = 0;
            for (var candidate : plan.candidates()) {
                if (shown++ >= 64) break;
                var p = candidate.cell();
                event.renderer.box(new BlockPos(p.x(), p.y(), p.z()), new Color(70, 140, 255, 10), new Color(70, 140, 255, 70), ShapeMode.Lines, 0);
            }
            if (selected != null && !selected.air()) event.renderer.box(selected.hit().getBlockPos(), new Color(80, 160, 255, 10), new Color(80, 160, 255), ShapeMode.Lines, 0);
        }
    }

    private boolean credible(BlockPos pos) {
        return mc.level != null && mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4) && !ScaffoldCoordinator.knownUnconfirmed(pos)
            && CometScaffoldPlacement.usableSupport(pos, mc.level.getBlockState(pos));
    }
    private boolean inEnvelope(ScaffoldPlanner.Cell cell) {
        Vec3 eye = mc.player.getEyePosition();
        double x = Math.clamp(eye.x, cell.x(), cell.x() + 1.0), y = Math.clamp(eye.y, cell.y(), cell.y() + 1.0), z = Math.clamp(eye.z, cell.z(), cell.z() + 1.0);
        double bound = mc.player.blockInteractionRange() + 1;
        return eye.distanceToSqr(new Vec3(x, y, z)) <= bound * bound;
    }
    private Profile profile() { return preset.get() == ScaffoldPlanner.Preset.Wide ? wide : narrow; }
    private int floorY() { return (int) Math.floor(mc.player.getBoundingBox().minY - .001); }
    private boolean heightLocked(Profile p) { return p.height.get() == Height.Activation || p.height.get() == Height.Absolute || p.height.get() == Height.AboveHead || p.height.get() == Height.Preset && preset.get() == ScaffoldPlanner.Preset.Wide; }
    private int selectedY(Profile p) {
        int y = switch (p.height.get()) {
            case Activation -> activationY;
            case Absolute -> p.y.get();
            case AboveHead -> (int) Math.ceil(mc.player.getBoundingBox().maxY);
            case FollowFeet -> floorY();
            case Preset -> preset.get() == ScaffoldPlanner.Preset.Wide ? activationY : tower.get() && mc.options.keyJump.isDown() ? floorY() : groundY;
        };
        return y + p.offset.get() - (downKey.get().isPressed() ? 1 : 0);
    }
    private long clippedHorizon(double vx, double vz, long horizon) {
        int count = Math.max(1, (int) Math.ceil(horizon / 5_000_000.0));
        for (int i = 1; i <= count; i++) {
            double ticks = (double) i / count * horizon / 50_000_000.0;
            if (!mc.level.noCollision(mc.player, mc.player.getBoundingBox().move(vx * ticks, 0, vz * ticks)))
                return Math.max(0, (long) ((double) (i - 1) / count * horizon));
        }
        return horizon;
    }
    private void releaseSticky() { sticky.release(() -> true); }
    private String coverageStatus(String prefix) {
        return prefix + (preset.get() == ScaffoldPlanner.Preset.Wide && plan != null
            ? " | width " + plan.confirmedWidth() + "/" + plan.desiredWidth() + ", holes " + plan.holes()
            : " | " + runtime.pendingCount() + " pending") + " | " + runtime.actualPackets() + " pps";
    }
    private void ensureDiagnostics() {
        if (!isActive()) return;
        if (diagnostics != null && activeLogging == logging.get()) return;
        if (diagnostics != null) diagnostics.close(summary());
        activeLogging = logging.get();
        Path directory = meteordevelopment.meteorclient.MeteorClient.FOLDER.toPath().resolve("comet/comet-scaffold-logs");
        diagnostics = new ScaffoldDiagnostics(directory, System::nanoTime);
        JsonObject metadata = settingsSnapshot();
        metadata.addProperty("minecraft", "26.1.2");
        metadata.addProperty("version", FabricLoader.getInstance().getModContainer("comet").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("unknown"));
        Properties build = new Properties();
        try (InputStream source = getClass().getResourceAsStream("/comet-scaffold-build.properties")) { if (source != null) build.load(source); }
        catch (Exception error) { metadata.addProperty("buildMetadataError", error.getClass().getSimpleName()); }
        metadata.addProperty("meteorBuild", build.getProperty("meteor", "unknown"));
        metadata.addProperty("packetObservation", "Meteor Sent hook; not wire delivery or proof of placement causality");
        diagnostics.start(activeLogging, metadata);
    }
    private JsonObject settingsSnapshot() {
        JsonObject object = new JsonObject(); object.addProperty("preset", preset.get().name());
        JsonObject values = new JsonObject();
        settings.forEach(group -> group.forEach(setting -> values.addProperty(group.name + "/" + setting.name, String.valueOf(setting.get()))));
        object.add("settings", values); return object;
    }
    private String fingerprint() { return settingsSnapshot().toString(); }
    private JsonObject summary() {
        JsonObject data = JSON.toJsonTree(runtime.stats()).getAsJsonObject();
        data.addProperty("preset", preset.get().name()); data.addProperty("tick", tick); data.addProperty("generation", generation);
        data.addProperty("edgeHolds", holds); data.addProperty("deadlineMisses", deadlineMisses); data.addProperty("status", status);
        data.addProperty("extraRotationPackets", 0);
        JsonObject all = new JsonObject(), own = new JsonObject();
        for (int i = 0; i < 6; i++) { all.addProperty(categoryName(i), packetCounts.get(i)); own.addProperty(categoryName(i), ownPacketCounts.get(i)); }
        data.add("connectionPackets", all); data.add("attributedDispatchPackets", own);
        if (plan != null) { data.addProperty("desiredWidth", plan.desiredWidth()); data.addProperty("confirmedWidth", plan.confirmedWidth()); data.addProperty("holes", plan.holes()); data.addProperty("unfinishedRowsLeavingReach", plan.unfinishedRowsLeavingReach()); }
        return data;
    }
    private void drainEvents() {
        for (var event : runtime.drainEvents()) {
            if (diagnostics == null || event.type() == ScaffoldRuntime.EventType.PACKET) continue;
            JsonObject data = new JsonObject(); data.addProperty("attempt", event.attemptId()); data.addProperty("position", event.position());
            data.addProperty("generation", generation); data.addProperty("detail", event.detail()); data.addProperty("timeNanos", event.timeNanos());
            var hit = pendingHits.get(event.attemptId());
            if (hit != null) {
                data.addProperty("target", hit.target().toShortString()); data.addProperty("support", hit.hit().getBlockPos().toShortString());
                data.addProperty("face", hit.hit().getDirection().name()); data.addProperty("hit", hit.hit().getLocation().toString());
                data.addProperty("hand", hit.material().hand().name()); data.addProperty("slot", hit.material().slot());
            }
            String type = event.type().name().toLowerCase(Locale.ROOT);
            if (event.type() == ScaffoldRuntime.EventType.REJECTED) type = "rejection";
            diagnostics.event(type, data);
            diagnostics.sample("attempt", data);
        }
    }
    private static int packetCategory(Packet<?> packet) {
        if (packet instanceof ServerboundUseItemOnPacket) return 0;
        if (packet instanceof ServerboundSwingPacket) return 1;
        if (packet instanceof ServerboundSetCarriedItemPacket) return 2;
        if (packet instanceof ServerboundMovePlayerPacket) return 3;
        if (packet instanceof ServerboundPlayerInputPacket) return 4;
        return 5;
    }
    private static String categoryName(int category) { return switch (category) { case 0 -> "interaction"; case 1 -> "swing"; case 2 -> "slot"; case 3 -> "movement"; case 4 -> "input"; default -> "other"; }; }
    private static Setting<Boolean> bool(SettingGroup group, String name, String description, boolean value) { return group.add(new BoolSetting.Builder().name(name).description(description).defaultValue(value).build()); }
    private static Setting<Integer> integer(SettingGroup group, String name, String description, int value, int min, int max) { return group.add(new IntSetting.Builder().name(name).description(description).defaultValue(value).range(min, max).sliderRange(min, max).build()); }
}

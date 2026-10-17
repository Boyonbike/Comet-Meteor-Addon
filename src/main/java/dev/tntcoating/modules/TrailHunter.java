package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.compat.BaritoneBridge;
import dev.tntcoating.compat.XaeroPlusBridge;
import dev.tntcoating.compat.XaeroPlusBridge.Detection;
import dev.tntcoating.core.ElytraPilot;
import dev.tntcoating.core.ElytraSteer;
import dev.tntcoating.core.ElytraTakeoff;
import dev.tntcoating.core.TrailFlightPlan;
import dev.tntcoating.core.TrailLandingSite;
import dev.tntcoating.core.TrailLeadLog;
import dev.tntcoating.core.TrailHighways;
import dev.tntcoating.core.TrailTracker;
import dev.tntcoating.core.TrailExplorer;
import dev.tntcoating.core.TrailNavigator;
import dev.tntcoating.core.TrailRouteMemory;
import dev.tntcoating.core.TrailRouteStore;
import dev.tntcoating.core.TrailRoutePath;
import dev.tntcoating.core.TrailHeadingAvoidance;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.mixin.MinecraftServerAccessor;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.systems.waypoints.Waypoint;
import meteordevelopment.meteorclient.systems.waypoints.Waypoints;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Comet's trail flight, independent of Stash-Hunter's scanner and flight controller. */
public final class TrailHunter extends Module {
    public enum TrailEnd { Land, Heading, Disconnect }
    public enum StuckAction { Warn, Disconnect }
    private enum Phase { TRACKING, HEADING, LANDING }

    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Boolean> keepSearching = general.add(new BoolSetting.Builder().name("keep-searching").description("Hunt continuously: seek unflown old chunks, follow each trail to its end, log the end as a possible base, then find another trail.").defaultValue(true).build());
    private final Setting<Detection> detection = general.add(new EnumSetting.Builder<Detection>().name("trail-chunks").description("OLD_112 uses XaeroPlus OldChunks; NON_FRESH uses a positive Palette NewChunks non-fresh result; EITHER accepts either enabled detector.").defaultValue(Detection.EITHER).build());
    private final Setting<Integer> acquire = general.add(new IntSetting.Builder().name("lock-chunks").description("With keep-searching off: distinct trail chunks needed within lock-window to begin following.").defaultValue(10).range(1, 100).sliderRange(1, 30).visible(() -> !keepSearching.get()).build());
    private final Setting<Double> window = general.add(new DoubleSetting.Builder().name("lock-window").description("Seconds in which lock-chunks must arrive.").defaultValue(5).range(0.5, 30).sliderRange(1, 10).visible(() -> !keepSearching.get()).build());
    private final Setting<Integer> history = general.add(new IntSetting.Builder().name("trail-history").description("Recent acquisition history. Line fitting uses four times this count, bounded to 32–128 centres, for stable direction.").defaultValue(20).range(2, 100).sliderRange(5, 40).visible(() -> !keepSearching.get()).build());
    private final Setting<Double> lost = general.add(new DoubleSetting.Builder().name("lost-after").description("Seconds without a useful trail chunk before circling to recover.").defaultValue(5).range(1, 60).sliderRange(1, 15).visible(() -> !keepSearching.get()).build());
    private final Setting<Double> giveUp = general.add(new DoubleSetting.Builder().name("give-up-after").description("With keep-searching off, total silence before trail-end. Must exceed lost-after.").defaultValue(30).range(2, 300).sliderRange(10, 120).visible(() -> !keepSearching.get()).build());
    private final Setting<TrailEnd> trailEnd = general.add(new EnumSetting.Builder<TrailEnd>().name("trail-end").description("When keep-searching is off: land, keep flying on fallback-heading, or disconnect after losing the trail.").defaultValue(TrailEnd.Land).visible(() -> !keepSearching.get()).build());
    private final Setting<Double> fallbackHeading = general.add(new DoubleSetting.Builder().name("fallback-heading").description("Minecraft yaw for Heading: 0 south, 90 west, -90 east, 180 north.").defaultValue(0).range(-180, 180).sliderRange(-180, 180).visible(() -> trailEnd.get() == TrailEnd.Heading).build());
    private final SettingGroup search = settings.createGroup("Search");
    private final Setting<TrailExplorer.Pattern> pattern = search.add(new EnumSetting.Builder<TrailExplorer.Pattern>().name("search-pattern").description("Between trails. SPIRAL circles 0,0 at your starting distance, crossing trails that lead out from spawn, and widens each turn. STRAIGHT holds your starting heading.").defaultValue(TrailExplorer.Pattern.SPIRAL).visible(keepSearching::get).build());
    private final Setting<Integer> spiralSpacing = search.add(new IntSetting.Builder().name("spiral-spacing").description("Blocks the search circle widens per turn around 0,0.").defaultValue(768).range(128, 16384).sliderRange(256, 4096).visible(() -> keepSearching.get() && pattern.get() == TrailExplorer.Pattern.SPIRAL).build());
    private final Setting<Boolean> outbound = search.add(new BoolSetting.Builder().name("prefer-outbound").description("Take trails in the direction leading away from 0,0, where bases are, and skip trails that only lead back towards spawn.").defaultValue(true).visible(keepSearching::get).build());
    private final Setting<Boolean> avoidHighways = search.add(new BoolSetting.Builder().name("avoid-highways").description("Ignore old chunks within 192 blocks of the axis and diagonal highways, so a trail that joins one is crossed instead of followed along it.").defaultValue(true).visible(keepSearching::get).build());
    private final SettingGroup leads = settings.createGroup("Leads");
    private final Setting<Boolean> markEnds = leads.add(new BoolSetting.Builder().name("mark-trail-ends").description("Add a Meteor waypoint where a followed trail meets new terrain, which is often a base. xaero-waypoints shows them on Xaero's maps.").defaultValue(true).visible(keepSearching::get).build());
    private final Setting<Boolean> markForks = leads.add(new BoolSetting.Builder().name("mark-forks").description("Also add a waypoint where a trail splits, for the branch not taken.").defaultValue(false).visible(keepSearching::get).build());
    private final SettingGroup flight = settings.createGroup("Flight");
    private final Setting<Double> height = flight.add(new DoubleSetting.Builder().name("flight-height").description("Overworld target height in Vanilla and Packet. Pitch40 uses its own height bounds.").defaultValue(200).range(-50, 1000).sliderRange(64, 320).build());
    private final Setting<Integer> netherHeight = flight.add(new IntSetting.Builder().name("nether-height").description("Height of the point ahead given to Baritone. Baritone chooses its path through terrain.").defaultValue(80).range(32, 120).sliderRange(32, 120).build());
    private final Setting<Double> turnSpeed = flight.add(new DoubleSetting.Builder().name("turn-speed").description("Maximum degrees per tick when steering ElytraFly.").defaultValue(10).range(1, 45).sliderRange(1, 45).build());
    private final Setting<Boolean> takeOff = flight.add(new BoolSetting.Builder().name("take-off").description("Jump and open the elytra from flat ground. Pitch40 needs you already gliding above its upper bound.").defaultValue(true).build());
    private final Setting<StuckAction> ifStuck = flight.add(new EnumSetting.Builder<StuckAction>().name("if-stuck").description("After 90 seconds without 64 blocks of progress (a take-off retry comes first, at 30 seconds): warn, or disconnect with AutoReconnect off so an unattended player is not left standing.").defaultValue(StuckAction.Disconnect).build());

    private final ElytraPilot pilot = new ElytraPilot("Trail Hunter", message -> warning("%s", message));
    private final TrailFlightPlan routes = new TrailFlightPlan();
    private final TrailHeadingAvoidance rejectedRoutes = new TrailHeadingAvoidance();
    private final ElytraTakeoff netherTakeoff = new ElytraTakeoff();
    private XaeroPlusBridge xaero;
    private BaritoneBridge baritone;
    private TrailNavigator navigator;
    private TrailExplorer.Decision exploration;
    private TrailTracker.Config trackerConfig;
    private TrailTracker.Snapshot snapshot;
    private ClientLevel level;
    private Phase phase = Phase.TRACKING;
    private Detection selectedDetection;
    private float searchYaw;
    private float commandedYaw;
    private boolean nether, startedFlight, enabledElytraFly, resumeElytraFly, leaveBaritone;
    private boolean landingDone, warnedLanding, warnedTakeoff, glided;
    private Vec3 landingSite;
    private TrailFlightPlan.Goal commandedGoal;
    private long nextLandingSearch, pathStarted;
    private long nextKnowledgeSample, nextRouteSave, queuedRouteRevision = -1;
    private long nextPathCheck;
    private int aheadChecks, aheadUnloaded;
    private boolean warnedOutrunning;
    private long nextAheadCheck;
    private double progressX, progressZ;
    private long progressSince;
    private boolean retriedStuck, warnedStuck;
    private boolean routesOpened, previousKeepSearching;
    // Keep the live map across toggles. Immutable captures are written in order away from the client tick.
    private TrailRouteMemory routeMemory;
    private Path routeFile;
    private TrailLeadLog leadLog;
    private Path leadFile;
    private int endsThisRun;
    private Future<?> routeSave;
    private volatile IOException routeSaveFailure;
    private final ExecutorService routeWriter = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "comet-trail-routes");
        thread.setDaemon(true);
        return thread;
    });

    public TrailHunter() {
        // Meteor drops an earlier module with the same name, so avoid other addons' trail, trail-maker and base-finder.
        super(TntCoatingAddon.CATEGORY, "trail-hunter", "Hunts bases: finds unflown XaeroPlus old-chunk trails, follows each to its end and logs it, with ElytraFly or Nether Baritone flight.");
    }

    @Override public String getInfoString() {
        if (phase == Phase.LANDING) return "Landing";
        if (phase == Phase.HEADING) return "Heading";
        if (keepSearching.get() && exploration != null) {
            String state = switch (exploration.state()) {
                case SEARCHING -> "Seeking old chunks";
                case FOLLOWING -> "Following " + (navigator == null ? 0 : (int) navigator.trailBlocks()) + " blocks";
                case SEEKING -> "Crossing a gap";
                case ESCAPING -> navigator != null && navigator.relocating() ? "Leaving flown-out area" : "Leaving trail";
            };
            return endsThisRun == 0 ? state : state + " · " + endsThisRun + (endsThisRun == 1 ? " end" : " ends");
        }
        if (snapshot == null) return "Searching";
        return switch (snapshot.state()) {
            case SEARCHING -> "Searching " + snapshot.acquisitionChunks() + "/" + acquire.get();
            case FOLLOWING -> "Following " + snapshot.trailChunks();
            case RECOVERING -> "Recovering";
            case EXHAUSTED -> "Trail ended";
        };
    }

    @Override public void onActivate() {
        phase = Phase.TRACKING;
        snapshot = null;
        landingSite = null;
        commandedGoal = null;
        exploration = null;
        routesOpened = false;
        previousKeepSearching = keepSearching.get();
        nextKnowledgeSample = nextRouteSave = 0;
        nextPathCheck = 0;
        endsThisRun = 0;
        aheadChecks = aheadUnloaded = 0;
        warnedOutrunning = false;
        nextAheadCheck = 0;
        progressSince = 0;
        retriedStuck = warnedStuck = false;
        rejectedRoutes.clear();
        startedFlight = enabledElytraFly = resumeElytraFly = leaveBaritone = landingDone = false;
        warnedLanding = warnedTakeoff = glided = false;
        nextLandingSearch = 0;
        routes.reset();
        pilot.begin();
        if (mc.player == null || mc.level == null) { refuse("Join a world before starting Trail Hunter."); return; }
        level = mc.level;
        if (!level.dimension().equals(Level.OVERWORLD) && !level.dimension().equals(Level.NETHER)) {
            refuse("Trail Hunter supports the Overworld and Nether."); return;
        }
        if (stashFlying()) { refuse("Stop Stash-Hunter's flight with .sh stop before starting Trail Hunter. Its scanner can stay enabled."); return; }
        if (!mc.player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER)) {
            refuse("Put on an elytra before starting Trail Hunter."); return;
        }
        nether = level.dimension().equals(Level.NETHER);
        selectedDetection = detection.get();
        searchYaw = mc.player.getYRot();
        trackerConfig = trackerConfig();
        try {
            xaero = XaeroPlusBridge.create();
            if (xaero == null) { refuse("XaeroPlus isn't installed. Trail Hunter does not need Stash-Hunter."); return; }
            String unavailable = xaero.unavailableReason(selectedDetection);
            if (unavailable != null) { refuse(unavailable); return; }
            baritone = BaritoneBridge.create();
            if (nether && (baritone == null || !baritone.elytraSupported())) {
                refuse("Nether Trail Hunter needs Baritone with a supported elytra pathfinder."); return;
            }
            if (baritone != null && (baritone.flightActive() || baritone.walking() != null)) {
                refuse("Stop the current Baritone trip before starting Trail Hunter."); return;
            }
            openRoutes();
            resetNavigator();
            ElytraFly fly = elytraFly();
            if (nether) {
                if (fly.isActive()) { fly.toggle(); resumeElytraFly = true; }
                netherTakeoff.reset(mc.player.isFallFlying());
            } else {
                if (fly.flightMode.get() == ElytraFlightModes.Bounce) {
                    refuse("Choose ElytraFly Vanilla, Packet or Pitch40 before starting Trail Hunter."); return;
                }
                if (!fly.isActive()) { fly.toggle(); enabledElytraFly = fly.isActive(); }
                if (!fly.isActive()) { refuse("ElytraFly could not start. Check its mode and take-off height."); return; }
            }
            xaero.start(selectedDetection);
            rememberFlight(System.nanoTime() / 1_000_000);
            if (keepSearching.get()) {
                info("Hunting old-chunk trails (%s, %s search); %d flown chunks remembered, %d trail ends logged.",
                    selectedDetection, pattern.get().name().toLowerCase(Locale.ROOT), routeMemory.size(), leadLog.count(TRAIL_END_LEAD));
            } else {
                info("Searching for %d trail chunks in %.1f seconds (%s); remembering %d flown chunks.", acquire.get(), window.get(), selectedDetection, routeMemory.size());
            }
        } catch (IOException e) {
            MeteorClient.LOG.error("Opening Comet's trail route memory failed", e);
            refuse("Could not read the saved route memory. Trail Hunter stopped rather than forgetting previous flights. See the log.");
        } catch (LinkageError | RuntimeException e) { incompatible(e); }
    }

    @Override public void onDeactivate() {
        if (routesOpened) {
            if (mc.player != null && mc.level == level) routeMemory.finishPosition(mc.player.getX(), mc.player.getZ());
            saveRoutes(true);
            routeMemory.breakContinuity();
            routesOpened = false;
        }
        if (xaero != null) {
            try { xaero.stop(); }
            catch (LinkageError | RuntimeException e) { MeteorClient.LOG.error("Removing Trail Hunter's XaeroPlus listener failed", e); }
        }
        pilot.release();
        if (baritone != null && startedFlight && !leaveBaritone) {
            try { baritone.stop(); }
            catch (LinkageError | RuntimeException e) { MeteorClient.LOG.error("Stopping Trail Hunter's Baritone route failed", e); }
        }
        ElytraFly fly = elytraFly();
        // A cancelled landing must not resume Pitch40's climb loop while still low above terrain.
        if ((enabledElytraFly || landingDone || phase == Phase.LANDING && !leaveBaritone) && fly.isActive()) fly.toggle();
        pilot.restoreLandingSettings();
        if (resumeElytraFly && !landingDone && mc.player != null) {
            if (!startedFlight && !fly.isActive()) fly.toggle();
            else info("ElytraFly remains off after Nether flight; enable it again when ready.");
        }
        xaero = null;
        baritone = null;
        navigator = null;
        level = null;
    }

    @EventHandler(priority = EventPriority.HIGHEST) private void onGameLeft(GameLeftEvent event) {
        // Never carry a remembered trail, held keys or a deferred landing into another connection.
        // Run before Meteor unsubscribes ordinary module handlers during its own GameLeft listener.
        if (isActive()) toggle();
    }

    @EventHandler private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        if (mc.level != level) { refuse("World or dimension changed. Start Trail Hunter again to search here."); return; }
        if (stashFlying()) {
            // Stash-Hunter now owns flight; releasing its ElytraFly or Baritone would fight that hand-off.
            enabledElytraFly = resumeElytraFly = false;
            leaveBaritone = true;
            refuse("Stash-Hunter started flying. Trail Hunter has released control."); return;
        }
        long now = System.nanoTime() / 1_000_000;
        try {
            if (!ownsFlight()) return;
            rememberFlight(now);
            if (routesOpened && routeSaveFailure != null && phase != Phase.LANDING) {
                beginLanding("Could not save the route memory; landing before exploration continues.", now);
                return;
            }
            if (phase == Phase.LANDING) { land(now); return; }
            if (!watchProgress(now)) return;
            checkChunksAhead(now);
            if (previousKeepSearching != keepSearching.get()) {
                previousKeepSearching = keepSearching.get();
                snapshot = null;
                resetNavigator();
                if (phase == Phase.HEADING && keepSearching.get()) {
                    phase = Phase.TRACKING;
                    xaero.start(selectedDetection);
                }
            }
            if (phase == Phase.HEADING) { fly(fallbackHeading.get().floatValue(), now); return; }
            TrailTracker.Config config = trackerConfig();
            if (selectedDetection != detection.get() || !trackerConfig.equals(config)) {
                selectedDetection = detection.get();
                trackerConfig = config;
                snapshot = null;
                searchYaw = travelYaw();
                xaero.stop();
                xaero.start(selectedDetection);
                resetNavigator();
                info("Trail settings changed; searching for a new trail.");
            }
            String unavailable = xaero.unavailableReason(selectedDetection);
            if (unavailable != null) { refuse(unavailable); return; }
            float travelYaw = travelYaw();
            if (keepSearching.get() && now >= nextKnowledgeSample) {
                for (var chunk : xaero.sample((int) Math.floor(mc.player.getX() / 16), (int) Math.floor(mc.player.getZ() / 16), nether ? 32 : 24)) {
                    if (chunk.dimension().equals(level.dimension())) navigator.observe(chunk.x(), chunk.z(), isTrail(chunk), isFrontier(chunk));
                }
                nextKnowledgeSample = now + 1_000;
            }
            for (var chunk : xaero.drain()) {
                if (!chunk.dimension().equals(level.dimension())) continue;
                navigator.arrive(chunk.x(), chunk.z(), isTrail(chunk), isFrontier(chunk), now, chunk.receivedAtMillis(),
                    mc.player.getX(), mc.player.getZ(), travelYaw);
            }
            if (keepSearching.get()) {
                if (routeMemory.isFull()) { beginLanding("Route memory is full; landing instead of forgetting flown routes.", now); return; }
                if (nether && startedFlight && now >= nextPathCheck) {
                    nextPathCheck = now + 500;
                    if (TrailRoutePath.wouldRevisit(routeMemory, baritone.flightPath(), mc.player.getX(), mc.player.getZ(), 512)) {
                        rejectedRoutes.reject(mc.player.getX(), mc.player.getZ(), commandedYaw);
                        // The calculated terrain detour can be unsafe even when its straight goal ray was clear.
                        // Permit one immediate replacement, then reject that heading for this location.
                        routes.reset();
                    }
                }
                var previousExploration = exploration;
                navigator.setPreferOutbound(outbound.get());
                navigator.setPattern(pattern.get(), spiralSpacing.get());
                exploration = navigator.explore(now, mc.player.getX(), mc.player.getZ(), travelYaw);
                if (exploration.lead() != null) reportLead(exploration.lead());
                if (exploration.blocked()) {
                    landBeforeRepeating(now);
                    return;
                }
                if (nether && exploration.state() == TrailExplorer.State.ESCAPING
                    && (previousExploration == null || previousExploration.state() != TrailExplorer.State.ESCAPING)) {
                    // A nearby frontier must replace a distant straight goal immediately, once per escape.
                    routes.reset();
                }
                if (previousExploration != null && previousExploration.state() != exploration.state()) {
                    // Routine search/escape changes are frequent on a long hunt; report what the player would act on.
                    var was = previousExploration.state();
                    switch (exploration.state()) {
                        case ESCAPING -> {
                            if (navigator.relocating()) info("Every way out of this area repeats earlier flights; heading outward to new ground.");
                            else if (exploration.lead() == null && (was == TrailExplorer.State.FOLLOWING || was == TrailExplorer.State.SEEKING))
                                info("Left a short or already-flown trail; looking for another.");
                        }
                        case FOLLOWING -> {
                            if (was == TrailExplorer.State.SEARCHING)
                                info("Following an unflown trail heading %s.", compass(exploration.yaw()));
                        }
                        default -> {}
                    }
                }
                fly(exploration.yaw(), now);
                return;
            }
            var previous = snapshot == null ? TrailTracker.State.SEARCHING : snapshot.state();
            snapshot = navigator.track(now, mc.player.getX(), mc.player.getZ(), travelYaw);
            if (snapshot.state() != previous) reportTransition(previous);
            if (snapshot.state() == TrailTracker.State.EXHAUSTED) { endTrail(now); return; }
            fly(snapshot.state() == TrailTracker.State.SEARCHING ? searchYaw : snapshot.yaw(), now);
        } catch (LinkageError | RuntimeException e) { incompatible(e); }
    }

    private void fly(float yaw, long now) {
        if (!nether) {
            if (!elytraFly().isActive()) { refuse("ElytraFly was turned off. Trail Hunter has stopped."); return; }
            double radians = Math.toRadians(yaw);
            pilot.steer(new Vec3(mc.player.getX() - Math.sin(radians) * 100, Math.max(height.get(), terrainAhead(radians) + TERRAIN_CLEARANCE),
                mc.player.getZ() + Math.cos(radians) * 100), turnSpeed.get(), takeOff.get());
            return;
        }
        if (elytraFly().isActive()) { refuse("ElytraFly was enabled during Baritone flight. Trail Hunter has stopped."); return; }
        var goal = routes.next(now, mc.player.getX(), mc.player.getZ(), netherHeight.get(), yaw);
        if (goal != null) {
            baritone.fly(new BaritoneBridge.Trip(null, goal.x(), goal.y(), goal.z()));
            commandedGoal = goal;
            commandedYaw = yaw;
            if (!startedFlight) { pathStarted = now; startedFlight = true; }
        }
        netherTakeoff(now);
    }

    private void netherTakeoff(long now) {
        glided |= mc.player.isFallFlying();
        if (!takeOff.get() || warnedTakeoff || baritone.autoJump()) return;
        if (!baritone.readyForTakeoff()) {
            if (!glided && now - pathStarted > 10_000) refuse("Baritone could not find a flight path. Trail Hunter stopped.");
            return;
        }
        boolean open = !mc.player.isInWater() && !mc.player.isInLava()
            && level.noCollision(mc.player, mc.player.getBoundingBox().expandTowards(0, 3, 0));
        switch (netherTakeoff.next(mc.player.onGround(), mc.player.isFallFlying(), mc.player.getDeltaMovement().y, open)) {
            case JUMP -> mc.player.jumpFromGround();
            case OPEN -> {
                if (mc.player.tryToStartFallFlying()) mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            }
            case COVERED, GAVE_UP -> {
                warnedTakeoff = true;
                warning("Baritone needs you to take off by hand here. Trail Hunter keeps the route.");
            }
            case WAIT -> {}
        }
    }

    private void endTrail(long now) {
        xaero.stop();
        switch (trailEnd.get()) {
            case Land -> {
                beginLanding("Trail ended at " + mc.player.blockPosition().toShortString() + "; landing.", now);
            }
            case Heading -> {
                phase = Phase.HEADING;
                info("Trail ended; flying on heading %.0f until Trail Hunter is turned off.", fallbackHeading.get());
                fly(fallbackHeading.get().floatValue(), now);
            }
            case Disconnect -> disconnect("Trail ended at " + mc.player.blockPosition().toShortString());
        }
    }

    private static final double STUCK_DISTANCE = 64;
    private static final long STUCK_RETRY_MILLIS = 30_000, STUCK_ACTION_MILLIS = 90_000;

    /**
     * A working flight covers thousands of blocks in 90 seconds. Without 64 blocks of progress, retry take-off once,
     * then warn or disconnect. Returns false once the module has stopped.
     */
    private boolean watchProgress(long now) {
        double x = mc.player.getX(), z = mc.player.getZ();
        if (progressSince == 0 || phase != Phase.TRACKING || Math.hypot(x - progressX, z - progressZ) >= STUCK_DISTANCE) {
            progressX = x;
            progressZ = z;
            progressSince = now;
            retriedStuck = warnedStuck = false;
            return true;
        }
        long still = now - progressSince;
        if (!retriedStuck && still >= STUCK_RETRY_MILLIS) {
            retriedStuck = true;
            // The pilot gives up after three jumps; one more round costs nothing while standing still.
            if (!nether) pilot.begin();
            warning("No progress for 30 seconds at %s; trying to take off again.", mc.player.blockPosition().toShortString());
        }
        if (still < STUCK_ACTION_MILLIS) return true;
        if (ifStuck.get() == StuckAction.Disconnect) {
            disconnect("Stuck without progress at " + mc.player.blockPosition().toShortString());
            return false;
        }
        if (!warnedStuck) {
            warnedStuck = true;
            warning("Still no progress after 90 seconds. Trail Hunter keeps trying; take over if needed.");
        }
        return true;
    }

    /**
     * Trail ends need the terrain ahead classified before the flight reaches it. When the server sends chunks slower
     * than the flight crosses them, the chunk 48 blocks ahead is repeatedly missing: say so once.
     */
    private void checkChunksAhead(long now) {
        if (warnedOutrunning || now < nextAheadCheck || phase != Phase.TRACKING) return;
        nextAheadCheck = now + 1_000;
        var velocity = mc.player.getDeltaMovement();
        double speed = Math.hypot(velocity.x, velocity.z);
        if (speed < 0.5) return;
        int x = (int) Math.floor(mc.player.getX() + velocity.x / speed * 48);
        int z = (int) Math.floor(mc.player.getZ() + velocity.z / speed * 48);
        aheadChecks++;
        if (!level.hasChunk(x >> 4, z >> 4)) aheadUnloaded++;
        if (aheadChecks < 30) return;
        if (aheadUnloaded * 3 > aheadChecks) {
            warnedOutrunning = true;
            warning("Chunks are arriving slower than you fly, so trail ends can be missed. Lower ElytraFly's speed.");
        }
        aheadChecks = aheadUnloaded = 0;
    }

    /** Stops the module and leaves the server, turning AutoReconnect off so it does not put the player back. */
    private void disconnect(String why) {
        var reason = Component.literal("[Trail Hunter] " + why);
        AutoReconnect reconnect = Modules.get().get(AutoReconnect.class);
        if (reconnect.isActive()) { reconnect.toggle(); reason.append("\nAutoReconnect was turned off."); }
        toggle();
        mc.player.connection.handleDisconnect(new ClientboundDisconnectPacket(reason));
    }

    private void beginLanding(String reason, long now) {
        phase = Phase.LANDING;
        // Once landing is requested, Baritone owns the final route through touchdown.
        if (nether) leaveBaritone = true;
        if (xaero != null) xaero.stop();
        warning("%s", reason);
        land(now);
    }

    private void landBeforeRepeating(long now) {
        if (nether && startedFlight && !mc.player.onGround()) {
            // An unsafe calculated path must not continue to its distant endpoint while landing.
            // A goal at the present position asks Baritone to finish here and choose a nearby landing spot.
            int x = (int) Math.floor(mc.player.getX()), z = (int) Math.floor(mc.player.getZ());
            int y = (int) Math.floor(mc.player.getY());
            baritone.fly(new BaritoneBridge.Trip(null, x, y, z));
            commandedGoal = new TrailFlightPlan.Goal(x, y, z);
        }
        beginLanding("Route memory can no longer clear any heading; landing instead of retracing earlier flights.", now);
    }

    /** Highway chunks are reported as unknown: neither trail to follow nor new terrain ending one. */
    private boolean onHighway(XaeroPlusBridge.Chunk chunk) {
        return keepSearching.get() && avoidHighways.get() && TrailHighways.near(chunk.x(), chunk.z());
    }

    private boolean isTrail(XaeroPlusBridge.Chunk chunk) {
        if (onHighway(chunk)) return false;
        return switch (selectedDetection) {
            case OLD_112 -> chunk.old112();
            case NON_FRESH -> chunk.nonFresh();
            case EITHER -> chunk.old112() || chunk.nonFresh();
        };
    }

    /**
     * Explicit evidence that a chunk is not trail, as opposed to an unknown classification: a fresh palette result,
     * or, when trails are 1.12 chunks only, OldChunks' modern result. Under EITHER with palette detection on, a
     * modern chunk can still be a non-fresh trail chunk, so only a fresh result counts there.
     */
    private boolean isFrontier(XaeroPlusBridge.Chunk chunk) {
        if (onHighway(chunk) || isTrail(chunk)) return false;
        return chunk.fresh() || chunk.modern()
            && (selectedDetection == Detection.OLD_112 || !xaero.freshDetectionAvailable());
    }

    private void resetNavigator() {
        navigator = new TrailNavigator(routeMemory, trackerConfig,
            nether ? new TrailExplorer.Config(512, 384, 128) : TrailExplorer.Config.defaults(), keepSearching.get(),
            (x, z, yaw) -> nether && rejectedRoutes.blocked(x, z, yaw));
        navigator.setPreferOutbound(outbound.get());
        navigator.setPattern(pattern.get(), spiralSpacing.get());
        exploration = null;
        nextKnowledgeSample = 0;
    }

    /** Blocks kept above the highest loaded terrain ahead, so a fixed flight-height cannot fly into a mountain. */
    private static final int TERRAIN_CLEARANCE = 24;
    private static final int TERRAIN_LOOK_AHEAD = 128;

    /** The highest loaded surface along the next 128 blocks of this heading; unloaded chunks are skipped. */
    private int terrainAhead(double radians) {
        int highest = level.getMinY();
        for (int ahead = 0; ahead <= TERRAIN_LOOK_AHEAD; ahead += 8) {
            int x = (int) Math.floor(mc.player.getX() - Math.sin(radians) * ahead);
            int z = (int) Math.floor(mc.player.getZ() + Math.cos(radians) * ahead);
            if (level.hasChunk(x >> 4, z >> 4)) highest = Math.max(highest, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
        }
        return highest;
    }

    private static String compass(float yaw) {
        String[] points = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
        return points[Math.floorMod(Math.round(yaw / 45f), 8)];
    }

    private static final String TRAIL_END_LEAD = "trail-end";
    private static final String FORK_LEAD = "fork";

    /** Chat, the per-world lead log and, if enabled, a waypoint. A lead near an earlier one is the same place. */
    private void reportLead(TrailExplorer.Lead lead) {
        boolean end = lead.kind() == TrailExplorer.LeadKind.TRAIL_END;
        int x = (int) Math.floor(lead.x()), z = (int) Math.floor(lead.z());
        var entry = new TrailLeadLog.Entry(System.currentTimeMillis(), end ? TRAIL_END_LEAD : FORK_LEAD, x, z,
            Math.round(lead.heading()), (int) lead.trailBlocks());
        if (leadLog == null || !leadLog.add(entry)) return;
        Path file = leadFile;
        routeWriter.submit(() -> {
            try { TrailLeadLog.append(file, entry); }
            catch (IOException | RuntimeException e) { MeteorClient.LOG.error("Writing Comet's trail lead log failed", e); }
        });
        int number = leadLog.count(entry.kind());
        if (end) {
            info("Trail end %d at %d, %d after %d blocks of trail: possible base.", number, x, z, entry.trailBlocks());
            if (endsThisRun++ == 0) info("Leads are logged in meteor-client/comet/trail-leads/.");
        } else info("Fork at %d, %d: an untaken branch heads %s.", x, z, compass(lead.heading()));
        if (!(end ? markEnds.get() : markForks.get())) return;
        try {
            int y = nether || !level.hasChunk(x >> 4, z >> 4) ? mc.player.getBlockY()
                : level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            Waypoints.get().add(new Waypoint.Builder().name((end ? "Trail end " : "Fork ") + number)
                .icon(end ? "star" : "triangle").pos(new BlockPos(x, y, z)).dimension(PlayerUtils.getDimension()).build());
        } catch (LinkageError | RuntimeException e) {
            MeteorClient.LOG.error("Adding a trail lead waypoint failed", e);
        }
    }

    /** Scope by the actual world folder/server address and dimension; paths never contain server names. */
    private void openRoutes() throws IOException {
        String world;
        if (mc.getSingleplayerServer() != null) {
            world = "singleplayer:" + ((MinecraftServerAccessor) (Object) mc.getSingleplayerServer()).meteor$getStorageSource()
                .getDimensionPath(Level.OVERWORLD).toAbsolutePath().normalize();
        } else if (mc.getCurrentServer() != null) {
            world = "server:" + mc.getCurrentServer().ip.trim().toLowerCase(Locale.ROOT);
        } else {
            throw new IOException("No world identity available for persistent route memory");
        }
        String identity = world + "\n" + level.dimension().identifier();
        String file;
        try {
            file = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        Path target = MeteorClient.FOLDER.toPath().resolve("comet/trail-routes").resolve(file + ".routes");
        Path leads = MeteorClient.FOLDER.toPath().resolve("comet/trail-leads").resolve(file + ".csv");
        if (!leads.equals(leadFile) || leadLog == null) {
            try {
                leadLog = TrailLeadLog.load(leads);
            } catch (IOException e) {
                // The lead log is a convenience; its failure must not stop a flight that remembers routes correctly.
                MeteorClient.LOG.error("Reading Comet's trail lead log failed", e);
                warning("Could not read the trail lead log; earlier leads may be reported again.");
                leadLog = new TrailLeadLog();
            }
            leadFile = leads;
        }
        // A different world may be opened immediately after disconnecting. Finish its queued
        // writes before loading any file, including a world just evicted from the live slot.
        if (routeSave != null) {
            try { routeSave.get(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted while saving route history", e); }
            catch (ExecutionException e) { throw new IOException("Saving route history failed", e.getCause()); }
        }
        // Do not replace unsaved history with another world's map after a failed background write.
        // Retrying here also lets the player fix the disk/permissions and resume without losing RAM history.
        if (routeSaveFailure != null && routeMemory != null && routeFile != null) {
            TrailRouteStore.save(routeFile, routeMemory.snapshot());
            queuedRouteRevision = routeMemory.revision();
            routeSaveFailure = null;
        }
        if (!target.equals(routeFile) || routeMemory == null) {
            TrailRouteMemory loaded = TrailRouteStore.load(target);
            routeMemory = loaded;
            routeFile = target;
            queuedRouteRevision = loaded.revision();
        }
        routeMemory.breakContinuity();
        // Flights before this activation, even while the memory stayed loaded, have been seen already.
        routeMemory.startSession();
        routeSaveFailure = null;
        routesOpened = true;
    }

    private void rememberFlight(long now) {
        if (!routesOpened) return;
        boolean airborne = mc.player.isFallFlying() || !nether && elytraFly().isActive()
            && elytraFly().flightMode.get() == ElytraFlightModes.Packet && !mc.player.onGround();
        if (airborne) routeMemory.record(mc.player.getX(), mc.player.getZ());
        else routeMemory.finishPosition(mc.player.getX(), mc.player.getZ());
        if (now >= nextRouteSave) {
            saveRoutes(false);
            nextRouteSave = now + 30_000;
        }
    }

    private void saveRoutes(boolean force) {
        if (!routesOpened || routeMemory == null || routeFile == null) return;
        if (routeMemory.revision() == queuedRouteRevision && routeSaveFailure == null) return;
        if (!force && routeSave != null && !routeSave.isDone()) return;
        var capture = routeMemory.snapshot();
        Path target = routeFile;
        queuedRouteRevision = capture.revision();
        routeSave = routeWriter.submit(() -> {
            try {
                TrailRouteStore.save(target, capture);
                routeSaveFailure = null;
            } catch (IOException | RuntimeException e) {
                routeSaveFailure = e instanceof IOException failure ? failure : new IOException(e);
                MeteorClient.LOG.error("Saving Comet's trail route memory failed", e);
            }
        });
    }

    private void land(long now) {
        if (mc.player.onGround()) {
            landingDone = true;
            info("Trail ended; landed at %s.", mc.player.blockPosition().toShortString());
            toggle();
            return;
        }
        if (nether) {
            // Do not cancel or replace the final route: Baritone picks its landing near that goal.
            if (!baritone.flightActive()) {
                warning("Baritone's route ended before touching down. Take over the glide.");
                leaveBaritone = true;
                toggle();
            }
            return;
        }
        if (!elytraFly().isActive()) { refuse("ElytraFly was turned off during landing. Take over the glide."); return; }
        if (landingSite != null && !TrailLandingSite.valid(mc, landingSite)) landingSite = null;
        if (landingSite == null && now >= nextLandingSearch) {
            landingSite = TrailLandingSite.find(mc);
            nextLandingSearch = now + 1_000;
        }
        if (landingSite == null) {
            if (!warnedLanding) { warning("No loaded dry landing area nearby; holding position and checking again."); warnedLanding = true; }
            pilot.descend(new Vec3(mc.player.getX(), mc.player.getY() + 10, mc.player.getZ()), turnSpeed.get());
        } else {
            pilot.descend(landingSite, turnSpeed.get());
        }
    }

    private void reportTransition(TrailTracker.State previous) {
        switch (snapshot.state()) {
            case FOLLOWING -> info(previous == TrailTracker.State.RECOVERING ? "Trail found again." : "Locked onto the trail.");
            case RECOVERING -> warning("Trail lost; circling to find it again.");
            default -> {}
        }
    }

    private float travelYaw() {
        Vec3 velocity = mc.player.getDeltaMovement();
        return Math.hypot(velocity.x, velocity.z) > 0.05 ? ElytraSteer.yawTo(velocity.x, velocity.z) : mc.player.getYRot();
    }

    private boolean stashFlying() {
        StashHunterFly stash = Modules.get().get(StashHunterFly.class);
        return stash != null && stash.isStashHunterFlying();
    }

    private ElytraFly elytraFly() { return Modules.get().get(ElytraFly.class); }
    private TrailTracker.Config trackerConfig() {
        return new TrailTracker.Config(acquire.get(), millis(window.get()), history.get(), 32,
            millis(lost.get()), Math.max(millis(giveUp.get()), millis(lost.get()) + 1_000), 20);
    }

    /** Respect trips the player starts during trail flight, including #goto and #goal redirection. */
    private boolean ownsFlight() {
        if (baritone == null) return true;
        var trip = baritone.flight();
        boolean foreign = baritone.walking() != null || (!nether && baritone.flightActive())
            || (nether && startedFlight && trip != null && trip.goal() != null && commandedGoal != null
                && (trip.x() != commandedGoal.x() || trip.y() != commandedGoal.y() || trip.z() != commandedGoal.z()));
        if (foreign) {
            leaveBaritone = true;
            resumeElytraFly = false;
            if (elytraFly().isActive()) elytraFly().toggle();
            refuse("Another Baritone trip took over. Trail Hunter has released control.");
            return false;
        }
        boolean movedDestination = trip != null && commandedGoal != null
            && (trip.x() != commandedGoal.x() || trip.y() != commandedGoal.y() || trip.z() != commandedGoal.z());
        if (nether && startedFlight && glided && (movedDestination || trip == null && baritone.flightActive())) {
            leaveBaritone = true;
        }
        if (nether && startedFlight && glided && phase != Phase.LANDING && (trip == null || movedDestination)) {
            // Baritone replaces the requested destination with its own landing spot. A bare #elytra can also
            // change it without a custom goal; in either case, leave that flight to finish rather than overwrite it.
            phase = Phase.LANDING;
            leaveBaritone = true;
            if (xaero != null) xaero.stop();
            warning("Baritone is finishing its flight; waiting for touchdown.");
        }
        return true;
    }
    private static long millis(double seconds) { return (long) (seconds * 1_000); }
    private void refuse(String reason) { error("%s", reason); if (isActive()) toggle(); }
    private void incompatible(Throwable e) {
        MeteorClient.LOG.error("Trail Hunter integration failed", e);
        refuse("Trail Hunter could not use this XaeroPlus/Baritone installation (" + e.getClass().getSimpleName() + "). See the log.");
    }
}
